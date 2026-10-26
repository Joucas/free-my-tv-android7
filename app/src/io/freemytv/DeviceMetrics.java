package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Lightweight, on-device performance readings while the home screen is visible. */
final class DeviceMetrics {
    interface Listener { void onMetrics(Snapshot snapshot); }

    static final class Snapshot {
        final float cpu;
        final long usedRam, totalRam;
        final int fps;
        long freeStorage, totalStorage;

        Snapshot(float cpu, long usedRam, long totalRam, int fps) {
            this.cpu = cpu;
            this.usedRam = usedRam;
            this.totalRam = totalRam;
            this.fps = fps;
        }
    }

    private static final long SAMPLE_MS = 2000;
    private static final int FRAME_HISTORY = 120;

    private final Context context;
    private final Window window;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final long[] frameTimes = new long[FRAME_HISTORY];
    private final Object frameLock = new Object();
    private int frameNext, frameCount;
    private ScheduledExecutorService worker;
    private volatile boolean running;
    private boolean cpuReadFailed;
    private long previousTotal, previousIdle;

    private final Window.OnFrameMetricsAvailableListener frameListener = (source, metrics, dropped) -> {
        long now = SystemClock.elapsedRealtime();
        synchronized (frameLock) {
            frameTimes[frameNext] = now;
            frameNext = (frameNext + 1) % frameTimes.length;
            if (frameCount < frameTimes.length) frameCount++;
        }
    };

    DeviceMetrics(Context context, Window window, Listener listener) {
        this.context = context.getApplicationContext();
        this.window = window;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        window.addOnFrameMetricsAvailableListener(frameListener, main);
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::sample, 0, SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (!running) return;
        running = false;
        window.removeOnFrameMetricsAvailableListener(frameListener);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private void sample() {
        double usage = readCpu();
        float cpu = Double.isNaN(usage) ? Float.NaN : (float) usage;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        long usedRam = -1, totalRam = -1;
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            totalRam = memory.totalMem;
            usedRam = Math.max(0, totalRam - memory.availMem);
        } else {
            AppLog.w("performance: activity manager unavailable");
        }
        Snapshot snapshot = new Snapshot(cpu, usedRam, totalRam, fps());
        try {
            android.os.StatFs disk = new android.os.StatFs(context.getFilesDir().getAbsolutePath());
            snapshot.freeStorage = disk.getAvailableBytes();
            snapshot.totalStorage = disk.getTotalBytes();
        } catch (IllegalArgumentException ignored) { }
        main.post(() -> {
            if (running) listener.onMetrics(snapshot);
        });
    }

    private double readCpu() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) throw new IOException("system CPU counters unavailable");
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 5) throw new IOException("incomplete system CPU counters");
            long user = Long.parseLong(fields[1]);
            long nice = Long.parseLong(fields[2]);
            long system = Long.parseLong(fields[3]);
            long idle = Long.parseLong(fields[4]);
            long iowait = fields.length > 5 ? Long.parseLong(fields[5]) : 0;
            long irq = fields.length > 6 ? Long.parseLong(fields[6]) : 0;
            long softirq = fields.length > 7 ? Long.parseLong(fields[7]) : 0;
            long steal = fields.length > 8 ? Long.parseLong(fields[8]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            long idleAll = idle + iowait;
            if (previousTotal == 0) {
                previousTotal = total;
                previousIdle = idleAll;
                return Double.NaN;
            }
            long elapsed = total - previousTotal;
            long idleElapsed = idleAll - previousIdle;
            previousTotal = total;
            previousIdle = idleAll;
            return elapsed > 0 ? Math.max(0, Math.min(100, 100.0 * (elapsed - idleElapsed) / elapsed)) : Double.NaN;
        } catch (IOException | NumberFormatException e) {
            if (!cpuReadFailed) AppLog.w("performance CPU: " + e.getMessage());
            cpuReadFailed = true;
            return Double.NaN;
        }
    }

    private int fps() {
        long now = SystemClock.elapsedRealtime(), cutoff = now - 1000;
        int recent = 0;
        synchronized (frameLock) {
            for (int i = 0; i < frameCount; i++) {
                int at = (frameNext - 1 - i + frameTimes.length) % frameTimes.length;
                long frameAt = frameTimes[at];
                if (frameAt < cutoff) break;
                recent++;
            }
        }
        return recent >= 10 ? recent : -1;
    }
}
