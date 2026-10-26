package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - the first screen puts weather and live device-performance readings above the app shelf and All Apps;
 *  - ↓ keeps the shelf at the top and reveals home/TV options, then Explore tabs and recommendation cards;
 *  - the background is the first card's photo, heavily blurred and almost white (Backdrop); without picks, one of your
 *    own photos (Photos) the same way, or else a light gradient;
 *  - the input drawer remains available from the shelf; the background lightens (darkens at night);
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 32 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 178 × 100, 133 × 75 with ten apps
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 24, TILE_GAP = 34;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.06f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, DASHBOARD = 2, STATUS = 3, PLATTER = 4, TILES = 5, DRAWER = 6, SHEET = 7, TOAST = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3, Z_SHORTCUTS = 6;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, dashboard, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 500, L_TABBAR_TOP = 548, L_TABBAR_H = 68, L_CARDS_TOP = 650, L_CARD_H = 280;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_TILE_H = 120;
    private static final float L_SHORTCUTS_TOP = 320, L_SHORTCUTS_H = 72;
    private static final float DASHBOARD_TOP = 194, DASHBOARD_H = 620;
    private static final float EXPLORE_SHELF_GAP = 64;
    private static final float L_MAIN_TITLE_BASE = 130;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final DeviceMetrics performance;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, dashboardLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex, shortcutIndex;
    private int widgetIndex;    // with TalkBack on, the weather (0) or performance (1) dashboard can take focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private List<HomeApps.Item> shortcuts = new ArrayList<>();
    private Spring[] shortcutFocus = new Spring[0];
    private RectF[] shortcutRects = new RectF[0];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lSmall, lSleep, lTip, lDate, lClock;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop wallpaperBackdrop;
    private String wallpaperKey = "";
    private boolean destroyed;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF dashboardWeather = new RectF(), dashboardPerformance = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private final Spring explore = new Spring(0.38f, 0.9f, 0);
    private boolean drawerOpen;
    private boolean exploreOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, exploreWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim;
    private Paint sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metricTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metricUsage = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metricDivider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private final Paint dashboardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String feelsText = "", humidityText = "—", windText = "—", storageText = "—";
    private String cpuState = "", ramPercent = "", displayText = "";
    private String maxText = "", minText = "", ramText = "", diskText = "", androidText = "";
    private float cpuFraction;
    private String networkText = "", resolutionText = "";
    private final RectF headerRect = new RectF();
    private final String[] forecastTime = new String[4], forecastTemp = new String[4], forecastCondition = new String[4];
    private int textPrefs = -1;
    private String textLanguage, weatherTitle, nowTemp, highLow;
    private String performanceCpu = "—", performanceRam = "—", performanceFps = "—";
    private float performanceRamFraction = -1;

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case DASHBOARD: drawDashboard(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        performance = new DeviceMetrics(a, a.getWindow(), this::onPerformance);
        recs = Recs.get(a);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a) + 1;
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        dashboardLayer = new Layer(a, DASHBOARD);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 7 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, dashboardLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, dashboardLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        setShortcutItems(exploreShortcuts(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        performance.start();
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        performance.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        destroyed = true;
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        exploreOpen = false;
        explore.target = 0;
        if (!animated) explore.snap(0);
        layoutLight();
        requestLayout();
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= 3 && topIndex >= 3 + lightButtons.size()) {
            if (lightButtons.isEmpty()) {
                zone = Z_SHELF;
            } else {
                topIndex = 2 + lightButtons.size();
            }
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        layoutDashboard();
        refreshDashboardText();
        heroDirty = statusDirty = drawerDirty = true;
        dashboardLayer.invalidate();
        platter.invalidate();
    }

    private void onPerformance(DeviceMetrics.Snapshot metrics) {
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo network = connectivity == null ? null : connectivity.getActiveNetworkInfo();
        networkText = network == null || !network.isConnected() ? Ui.tr("Offline")
                : network.getType() == android.net.ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : "Ethernet";
        resolutionText = w + " \u00d7 " + h;
        statusDirty = true;
        cpuFraction = Float.isNaN(metrics.cpu) ? -1 : metrics.cpu / 100;
        cpuState = Float.isNaN(metrics.cpu) ? Ui.tr("Unavailable") : metrics.cpu < 15 ? Ui.tr("Idle") : Ui.tr("Active");
        ramPercent = metrics.totalRam <= 0 ? "" : " (" + Math.round(100f * metrics.usedRam / metrics.totalRam) + "%)";
        storageText = metrics.totalStorage <= 0 ? "—" : String.format(java.util.Locale.getDefault(), "%.0f / %.0f GB",
                metrics.freeStorage / 1073741824.0, metrics.totalStorage / 1073741824.0);
        displayText = Math.round(activity.getWindowManager().getDefaultDisplay().getRefreshRate()) + " Hz";
        performanceCpu = Float.isNaN(metrics.cpu) ? "—" : Math.round(metrics.cpu) + "%";
        performanceRam = metrics.totalRam <= 0 ? "—" : String.format(java.util.Locale.getDefault(), "%.1f/%.1f GB",
                metrics.usedRam / 1073741824.0, metrics.totalRam / 1073741824.0);
        performanceRamFraction = metrics.totalRam > 0 && metrics.usedRam >= 0
                ? Ui.clamp((float) metrics.usedRam / metrics.totalRam) : -1;
        performanceFps = metrics.fps < 0 ? "—" : Integer.toString(metrics.fps);
        ramText = performanceRam + ramPercent;
        diskText = Ui.tr("Storage free") + ": " + storageText;
        androidText = "Android " + android.os.Build.VERSION.RELEASE;
        dashboardLayer.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, dashboardLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, exploreOpen ? height : Math.round(180 * kk));
        measure(dashboardLayer, width, Math.round((DASHBOARD_TOP + DASHBOARD_H + 40) * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(530 * kk));
        measure(tiles, width, Math.round(530 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(dashboardLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(500 * kk));
        place(tiles, 0, Math.round(500 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 25 * k);   // the sleep timer's "Off in"
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutDashboard();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lSmall = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lSleep = Ui.text(Ui.medium, 25 * k, L_DIM);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt), shortcutsMoving = step(shortcutFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving | shortcutsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt), exploreMoving = explore.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || exploreMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving) || (exploreWasMoving && !exploreMoving)) {
            platter.invalidate();
        }
        if (exploreMoving || (exploreWasMoving && !exploreMoving)) {
            heroLayer.invalidate();
            dashboardLayer.invalidate();
            status.invalidate();
        }
        drawerWasMoving = drawerMoving;
        exploreWasMoving = exploreMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving || exploreMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        float e = Ui.clamp(explore.value);
        float exploreLift = Math.max(0, platterRect.bottom - (L_SHORTCUTS_TOP - EXPLORE_SHELF_GAP) * k);
        float optionsGap = L_SHORTCUTS_TOP * k - (platterRect.bottom - exploreLift * e);
        // Reveal the options once the shelf has cleared their row.
        show(heroLayer, top * smoothstep(0, EXPLORE_SHELF_GAP * k, optionsGap), 0);
        show(dashboardLayer, top * (1 - e), -e * h);
        show(status, top * (1 - e), lift);
        show(platter, home, lift - exploreLift * e);
        show(tiles, home, lift - exploreLift * e);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (!Prefs.background().equals("auto") && b != wallpaperBackdrop) return;
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 7 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lSmall.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lSleep.setColor(tone(L_DIM, 0xFF98989D));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, connected inputs and Settings. All Apps is on the shelf. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        List<HomeApps.Item> visible = new ArrayList<>(list);
        visible.add(HomeApps.action(HomeApps.ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        boolean same = visible.size() == shelf.size();
        for (int i = 0; same && i < visible.size(); i++) same = visible.get(i).key.equals(shelf.get(i).key);
        shelf = visible;
        if (!same || tileFocus.length != visible.size()) {
            tileFocus = new Spring[visible.size()];
            tileSlide = new Spring[visible.size()];
            tileRects = new RectF[visible.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, visible.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible), appCount = Math.max(0, shelf.size() - 1);
        int slots = Math.max(1, n - 1);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(168 * k, (full - 2 * pad - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = Math.min(L_TILE_H * k, tw * 0.74f);
        float top = h - MARGIN * k - th - 2 * pad;
        platterRect.set(MARGIN * k, top, CONTENT_R * k, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, appCount - slots));
        float x = platterRect.left + pad - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            float left = i == appCount ? platterRect.right - pad - tw : x;
            tileRects[i].set(left, y, left + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    private void revealShelf(boolean snap) {
        int appCount = Math.max(0, shelf.size() - 1), slots = Math.max(1, shelfVisible - 1);
        if (shelfIndex == appCount || tileRects.length == 0) return;
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - slots + 1), Math.min(shelfIndex, appCount - slots)));
        if (first == shelfFirst) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < appCount; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;
        wake();
    }

    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int appCount = Math.max(0, shelf.size() - 1);
        int last = Math.min(appCount, shelfFirst + shelfVisible - 1);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, tileRects[appCount].left - TILE_GAP * k / 2, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if (i == appCount || (pass == 0) == (i == top)) continue;
                if (!scrolls && (i < shelfFirst || i >= last)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        if (!shelf.isEmpty()) drawShelfTile(c, appCount);
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < appCount) Symbols.draw(getContext(), c, "chevron_right", tileRects[appCount].left - TILE_GAP * k / 2, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tileFill.setShader(null);
        tileFill.setColor(tone(0xFFF4F4F6, 0xFF29292D));
        c.drawRoundRect(cell, r, r, tileFill);
        border.setColor(tone(0xFFE7E7EB, 0xFF3A3A40));
        border.setStrokeWidth(2 * k);
        c.drawRoundRect(cell, r, r, border);
        if (fc > 0.01f) {
            border.setColor(Ui.alpha(tone(L_DIM, 0xFFC7C7CC), fc));
            border.setStrokeWidth(3 * k);
            c.drawRoundRect(cell, r, r, border);
        }
        if (it.kind == HomeApps.ALL_APPS) {
            Symbols.draw(getContext(), c, "apps", cx, cy - 12 * k, 54 * k, tone(L_DIM, WHITE));
            dashboardText(c, Ui.tr("Apps"), cx, cy + 39 * k, 17, false, true, true);
        } else if ("youtube".equals(it.key)) {
            fill.setColor(0xFFFF0000);
            c.drawRoundRect(cx - 70 * k, cy - 20 * k, cx - 14 * k, cy + 20 * k, 12 * k, 12 * k, fill);
            Symbols.draw(getContext(), c, "play", cx - 42 * k, cy, 27 * k, 0xFFFFFFFF);
            dashboardText(c, "YouTube", cx - 4 * k, cy + 7 * k, 19, true, false, false);
        } else if (it.dockIcon != null) {
            tmp.set(cx - 30 * k, cy - 30 * k, cx + 30 * k, cy + 30 * k);
            bitmapPaint.setColorFilter(null);
            c.drawBitmap(it.dockIcon, null, tmp, bitmapPaint);
            bitmapPaint.setColorFilter(null);
        } else if (it.logo != null) {
            drawLogo(c, it, cell, s, 0);
        } else if (it.tile != null) {
            tmp.set(cell.left + 12 * k, cell.top + 12 * k, cell.right - 12 * k, cell.bottom - 12 * k);
            roundImage(c, it.tile, it.tileShader, tmp, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 7 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        return HomeApps.allInputs(c);
    }

    private static List<HomeApps.Item> exploreShortcuts(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.homeShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void setShortcutItems(List<HomeApps.Item> list) {
        boolean same = list.size() == shortcuts.size();
        for (int i = 0; same && i < list.size(); i++) {
            HomeApps.Item item = list.get(i), old = shortcuts.get(i);
            same = item.key.equals(old.key) && item.label.equals(old.label) && item.symbol.equals(old.symbol);
        }
        shortcuts = list;
        if (shortcutFocus.length != list.size()) {
            shortcutFocus = new Spring[list.size()];
            shortcutRects = new RectF[list.size()];
            for (int i = 0; i < list.size(); i++) {
                shortcutFocus[i] = new Spring(0.28f, 0.85f, 0);
                shortcutRects[i] = new RectF();
            }
            shortcutIndex = Math.min(shortcutIndex, Math.max(0, list.size() - 1));
            if (zone == Z_SHORTCUTS && list.isEmpty()) zone = Z_SHELF;
            retarget(true);
        }
        layoutShortcuts();
        if (!same) heroDirty = true;
    }

    private void layoutShortcuts() {
        int n = shortcuts.size();
        if (n == 0 || w == 0) return;
        float gap = 20 * k, available = (CONTENT_R - MARGIN) * k;
        float width = Math.min(260 * k, (available - (n - 1) * gap) / n);
        float total = n * width + (n - 1) * gap, x = (w - total) / 2;
        float top = L_SHORTCUTS_TOP * k;
        for (int i = 0; i < n; i++) {
            shortcutRects[i].set(x, top, x + width, top + L_SHORTCUTS_H * k);
            snap(shortcutRects[i]);
            x += width + gap;
            HomeApps.Item item = shortcuts.get(i);
            Symbols.warm(getContext(), item.symbol, 23 * k);
            Symbols.warm(getContext(), item.symbol, 26 * k);
        }
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        focusOutline(c, cell, r, f);
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(shortcutFocus, !sheetOpen && zone == Z_SHORTCUTS ? shortcutIndex : -1, snap);
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        focusOutline(c, cell, r, f);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> sc = exploreShortcuts(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                setShortcutItems(sc);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (exploreOpen) {
            exitExplore();
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!lightButtons.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private int nearestTopButton(float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < lightButtons.size(); i++) {
            float distance = Math.abs(topRects[3 + i].centerX() - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else if (zone == Z_SHORTCUTS) {
            if (shortcutIndex >= shortcuts.size()) return;
            it = shortcuts.get(shortcutIndex);
            r = shortcutRects[shortcutIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() - 1 || shelf.size() < 3) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size() - 1) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP && it.pkg != null) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** Dashboard panels (weather 0, performance 1): read by TalkBack, never activated by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (exploreOpen) {
            for (int i = 0; i < shelf.size(); i++)
                if (i == shelf.size() - 1 || (i >= shelfFirst && i < shelfFirst + shelfVisible - 1)) out.add(a11yId(Z_SHELF, i));
            for (int i = 0; i < shortcuts.size(); i++) out.add(a11yId(Z_SHORTCUTS, i));
            for (int i = 0; i < 3; i++) out.add(a11yId(Z_TOP, i));
            for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        } else {
            for (int i = 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
            for (int i : widgets()) out.add(a11yId(WIDGETS, i));
            for (int i = 0; i < shelf.size(); i++)
                if (i == shelf.size() - 1 || (i >= shelfFirst && i < shelfFirst + shelfVisible - 1)) out.add(a11yId(Z_SHELF, i));
        }
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHORTCUTS: return label(shortcuts, i);
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : performanceText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3) != exploreOpen) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (!exploreOpen || i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHORTCUTS:
                if (!exploreOpen || i >= shortcuts.size()) return false;
                a11yRect.set(shortcutRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (exploreOpen || !widgets().contains(i)) return false;
                a11yRect.set(i == 0 ? dashboardWeather : dashboardPerformance);
                dy = dashboardLayer.getTranslationY();
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHORTCUTS: return shortcutIndex < shortcuts.size() ? a11yId(Z_SHORTCUTS, shortcutIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** Dashboard panels, exposed to TalkBack on the first screen. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (exploreOpen) return out;
        if (Prefs.weather()) out.add(0);
        out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3) != exploreOpen) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (!exploreOpen || i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHORTCUTS:
                if (!exploreOpen || i >= shortcuts.size()) return;
                if (drawerOpen) closeDrawer();
                shortcutIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    private String performanceText() {
        return Ui.trf("Device performance. CPU {cpu}, RAM {ram}, FPS {fps}.",
                "cpu", performanceCpu, "ram", performanceRam, "fps", performanceFps);
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = 60 * k, right = (CONTENT_R - 150) * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        for (RectF r : cardRects) snap(r);
        layoutShortcuts();
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        heroDirty = statusDirty = true;
    }

    private void layoutDashboard() {
        if (cardTitle == null) return;
        float left = MARGIN * k, right = CONTENT_R * k, top = DASHBOARD_TOP * k;
        float bottom = (DASHBOARD_TOP + DASHBOARD_H) * k, gap = 40 * k;
        if (Prefs.weather()) {
            float available = right - left - gap;
            float split = left + available * 0.585f;
            dashboardWeather.set(left, top, split, bottom);
            dashboardPerformance.set(split + gap, top, right, bottom);
            Symbols.warmWeather(getContext(), 38 * k);
        } else {
            dashboardWeather.setEmpty();
            dashboardPerformance.set(left, top, right, bottom);
        }
        snap(dashboardWeather);
        snap(dashboardPerformance);
        dashboardLayer.invalidate();
    }

    private void refreshDashboardText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        if (wx == textWeather && !settings) return;
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        textWeather = wx;
        weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
        nowTemp = HomeData.degrees(wx.temp);
        highLow = HomeData.highLow(wx, "   ");
        maxText = Ui.tr("Max") + " " + HomeData.degrees(wx.max);
        minText = Ui.tr("Min") + " " + HomeData.degrees(wx.min);
        feelsText = Double.isNaN(wx.feels) ? "" : Ui.tr("Feels like") + " " + HomeData.degrees(wx.feels);
        humidityText = Double.isNaN(wx.humidity) ? "—" : Math.round(wx.humidity) + "%";
        windText = Double.isNaN(wx.wind) ? "—" : Math.round(wx.wind) + " km/h";
        for (int i = 0; i < 4; i++) {
            int at = i - 1;
            forecastTime[i] = i == 0 ? Ui.tr("Now") : at < wx.hours.size()
                    ? Ui.datePattern("format|h:mm a").format(wx.hours.get(at)) : "—";
            forecastTemp[i] = i == 0 ? nowTemp : at < wx.hourTemp.size() ? HomeData.degrees(wx.hourTemp.get(at)) : "—";
            forecastCondition[i] = i == 0 ? HomeData.condition(wx.code, wx.isDay) : at < wx.hourCode.size()
                    ? HomeData.condition(wx.hourCode.get(at), wx.hourDay.get(at)) : "—";
        }
    }

    private void drawDashboard(Canvas c) {
        applyTone();
        if (!exploreOpen) {
            String title = Ui.tr("For You");
            c.drawText(title, MARGIN * k - Ui.leftBearing(lTitle, title), L_MAIN_TITLE_BASE * k, lTitle);

        }
        refreshDashboardText();
        if (Prefs.weather()) drawDashboardWeather(c);
        drawDashboardPerformance(c);
    }

    private void focusOutline(Canvas c, RectF r, float radius, float focus) {
        if (focus < 0.01f) return;
        border.setShader(null);
        border.setColor(Ui.alpha(tone(L_DIM, 0xFFC7C7CC), Ui.clamp(focus)));
        border.setStrokeWidth(3 * k);
        c.drawRoundRect(r, radius, radius, border);
    }

    private void dashboardText(Canvas c, String text, float x, float y, float size, boolean bold, boolean dim, boolean centered) {
        dashboardPaint.setTypeface(bold ? Ui.semibold : Ui.medium);
        dashboardPaint.setTextSize(size * k);
        dashboardPaint.setColor(tone(dim ? L_DIM : L_INK, dim ? 0xADFFFFFF : WHITE));
        dashboardPaint.setTextAlign(centered ? Paint.Align.CENTER : Paint.Align.LEFT);
        c.drawText(text, x, y, dashboardPaint);
    }

    private void dashboardPanel(Canvas c, RectF r) {
        drawerGlass(c, r, 38 * k, 0.76f, 0x11000000, 12 * k);
    }

    private void dashboardCell(Canvas c, float x, float y, float width, float height) {
        cell.set(x, y, x + width, y + height);
        fill.setShader(null);
        fill.setColor(tone(0xFFF8F8FA, 0xFF232327));
        c.drawRoundRect(cell, 24 * k, 24 * k, fill);
        border.setColor(tone(0xFFEAEAEE, 0xFF38383D));
        border.setStrokeWidth(2 * k);
        c.drawRoundRect(cell, 24 * k, 24 * k, border);
    }

    private void dashboardPill(Canvas c, String text, float x, float y, float width, float height) {
        dashboardCell(c, x, y, width, height);
        dashboardText(c, text, x + width / 2, y + height * 0.69f, 18, true, true, true);
    }

    private void dashboardRule(Canvas c, float x, float y, float right) {
        metricDivider.setColor(tone(0xFFE8E8ED, 0xFF38383D));
        c.drawRect(x, y, right, y + 2 * k, metricDivider);
    }

    private void dashboardBar(Canvas c, float x, float y, float width, float height, float fraction) {
        metricTrack.setColor(tone(0xFFE1E1E6, 0xFF45454A));
        metricUsage.setColor(tone(L_INK, WHITE));
        c.drawRoundRect(x, y, x + width, y + height, height / 2, height / 2, metricTrack);
        if (fraction >= 0) c.drawRoundRect(x, y, x + width * Math.max(0.025f, fraction), y + height,
                height / 2, height / 2, metricUsage);
    }

    private void drawDashboardWeather(Canvas c) {
        RectF r = dashboardWeather;
        dashboardPanel(c, r);
        float x = r.left + 56 * k, y = r.top;
        dashboardCell(c, x, y + 58 * k, 68 * k, 68 * k);
        // A location pin drawn on Canvas also works on Android 7.
        fill.setColor(tone(L_INK, WHITE));
        c.drawCircle(x + 34 * k, y + 87 * k, 10 * k, fill);
        c.drawRoundRect(x + 30 * k, y + 87 * k, x + 38 * k, y + 107 * k, 4 * k, 4 * k, fill);
        dashboardText(c, weatherTitle, x + 86 * k, y + 90 * k, 38, true, false, false);
        dashboardText(c, Ui.tr("Local weather"), x + 86 * k, y + 126 * k, 21, false, true, false);
        dashboardPill(c, Ui.tr("Updated"), r.right - 246 * k, y + 72 * k, 190 * k, 44 * k);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            dashboardText(c, Ui.tr("No weather data right now."), x, y + 300 * k, 28, false, true, false);
            return;
        }
        Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), x + 70 * k, y + 266 * k, 136 * k, tone(L_INK, WHITE));
        dashboardText(c, nowTemp, x + 180 * k, y + 266 * k, 108, true, false, false);
        dashboardText(c, HomeData.condition(wx.code, wx.isDay), x + 180 * k, y + 320 * k, 30, true, false, false);
        dashboardText(c, feelsText, x + 180 * k, y + 353 * k, 19, false, true, false);
        float right = r.right - 56 * k, bx = right - 320 * k;
        dashboardPill(c, maxText, bx, y + 182 * k, 154 * k, 50 * k);
        dashboardPill(c, minText, bx + 170 * k, y + 182 * k, 150 * k, 50 * k);
        dashboardCell(c, bx, y + 258 * k, 154 * k, 88 * k);
        dashboardCell(c, bx + 170 * k, y + 258 * k, 150 * k, 88 * k);
        dashboardText(c, Ui.tr("Humidity"), bx + 16 * k, y + 294 * k, 18, false, true, false);
        dashboardText(c, humidityText, bx + 16 * k, y + 325 * k, 23, true, false, false);
        dashboardText(c, Ui.tr("Wind"), bx + 186 * k, y + 294 * k, 18, false, true, false);
        dashboardText(c, windText, bx + 186 * k, y + 325 * k, 23, true, false, false);
        dashboardRule(c, x, y + 392 * k, right);
        float gap = 18 * k, width = (right - x - 3 * gap) / 4;
        for (int i = 0; i < 4; i++) {
            float left = x + i * (width + gap), center = left + width / 2;
            dashboardCell(c, left, y + 418 * k, width, 146 * k);
            dashboardText(c, forecastTime[i], center, y + 461 * k, 19, false, true, true);
            dashboardText(c, forecastTemp[i], center, y + 503 * k, 26, true, false, true);
            dashboardPaint.setTypeface(Ui.medium);
            dashboardPaint.setTextSize(17 * k);
            fitPaint.set(dashboardPaint);
            dashboardText(c, forecastCondition[i], center, y + 541 * k, Math.min(17, (width - 16 * k) / Math.max(1, fitPaint.measureText(forecastCondition[i])) * 17), false, true, true);
        }
    }

    private void drawDashboardPerformance(Canvas c) {
        RectF r = dashboardPerformance;
        dashboardPanel(c, r);
        float x = r.left + 56 * k, right = r.right - 56 * k, y = r.top;
        dashboardCell(c, x, y + 56 * k, 64 * k, 64 * k);
        Symbols.draw(getContext(), c, "settings", x + 32 * k, y + 88 * k, 28 * k, tone(L_INK, WHITE));
        dashboardText(c, Ui.tr("System Monitor"), x + 82 * k, y + 89 * k, 32, true, false, false);
        dashboardText(c, androidText, x + 82 * k, y + 121 * k, 19, false, true, false);
        dashboardRule(c, x, y + 140 * k, right);
        float gap = 24 * k, width = (right - x - gap) / 2, second = x + width + gap;
        dashboardCell(c, x, y + 166 * k, width, 194 * k);
        dashboardCell(c, second, y + 166 * k, width, 194 * k);
        dashboardText(c, Ui.tr("CPU USAGE"), x + 24 * k, y + 213 * k, 18, true, true, false);
        dashboardText(c, cpuState, x + 24 * k, y + 246 * k, 17, false, true, false);
        dashboardText(c, performanceCpu, x + 24 * k, y + 300 * k, 54, true, false, false);
        dashboardBar(c, x + 24 * k, y + 322 * k, width - 48 * k, 12 * k, cpuFraction);
        dashboardText(c, Ui.tr("FRAME RATE"), second + 24 * k, y + 213 * k, 18, true, true, false);
        dashboardText(c, displayText, second + 24 * k, y + 246 * k, 17, false, true, false);
        dashboardText(c, performanceFps, second + 24 * k, y + 300 * k, 54, true, false, false);
        dashboardText(c, "FPS", second + width - 72 * k, y + 299 * k, 19, true, true, false);
        dashboardText(c, Ui.tr("Rendered frames"), second + 24 * k, y + 337 * k, 16, false, true, false);
        dashboardCell(c, x, y + 384 * k, right - x, 180 * k);
        dashboardText(c, Ui.tr("RAM Memory"), x + 24 * k, y + 433 * k, 21, true, false, false);
        dashboardPaint.setTypeface(Ui.semibold);
        dashboardPaint.setTextSize(22 * k);
        String ram = ramText;
        dashboardText(c, ram, right - 24 * k - dashboardPaint.measureText(ram), y + 433 * k, 22, true, false, false);
        dashboardBar(c, x + 24 * k, y + 460 * k, right - x - 48 * k, 17 * k, performanceRamFraction);
        dashboardRule(c, x + 24 * k, y + 495 * k, right - 24 * k);
        dashboardText(c, diskText, x + 24 * k, y + 535 * k, 18, false, true, false);
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void loadWallpaper() {
        String preset = Prefs.background();
        int strength = Prefs.backgroundBlur() ? Prefs.backgroundStrength() : 0;
        int brightness = Prefs.backgroundBrightness();
        String key = preset + ":" + strength + ":" + brightness;
        if (key.equals(wallpaperKey)) return;
        wallpaperKey = key;
        if (preset.equals("auto")) { wallpaperBackdrop = null; return; }
        LOADER.execute(() -> {
            Backdrop result = Wallpaper.make(preset, strength, brightness);
            handler.post(() -> {
                if (destroyed || !key.equals(wallpaperKey)) return;
                wallpaperBackdrop = result;
                showBackdrop(result);
                wake();
                invalidateAll();
            });
        });
    }

    private void updateCards() {
        loadWallpaper();
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = !Prefs.background().equals("auto") ? wallpaperBackdrop
                : own ? Photos.backdrop(getContext(), ownReady) : cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!exploreOpen) return;
        String hello = Ui.tr("Explore");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab) focusOutline(c, r, r.height() / 2, focus);
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(tone(L_INK, WHITE));   // follows the theme even while focused
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        drawShortcuts(c);
    }

    private void drawShortcuts(Canvas c) {
        if (shortcuts.isEmpty()) return;
        for (int i = 0; i < shortcuts.size(); i++) {
            HomeApps.Item item = shortcuts.get(i);
            RectF base = shortcutRects[i];
            float focus = Ui.clamp(shortcutFocus[i].value), scale = 1;
            float cx = base.centerX(), cy = base.centerY();
            cell.set(cx - base.width() * scale / 2, cy - base.height() * scale / 2,
                    cx + base.width() * scale / 2, cy + base.height() * scale / 2);
            float radius = 16 * k * scale;
            drawerGlass(c, cell, radius, 0.72f, 0x16000000, 6 * k);
            focusOutline(c, cell, radius, focus);
            int ink = tone(L_INK, WHITE);
            Symbols.draw(getContext(), c, item.symbol, cell.left + 28 * k, cy, 24 * k * scale, ink);
            drawerLabel.setColor(ink);
            drawerLabel.setTextSize(21 * k * scale);
            drawerLabel.setTextAlign(Paint.Align.LEFT);
            fitPaint.set(drawerLabel);
            float textLeft = cell.left + 48 * k, room = cell.right - textLeft - 12 * k;
            String text = TextUtils.ellipsize(item.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
            c.drawText(text, textLeft, cy + Ui.halfCap(drawerLabel), drawerLabel);
            drawerLabel.setTextAlign(Paint.Align.CENTER);
            drawerLabel.setTextSize(23 * k);
        }
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_MAIN_TITLE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), (L_MAIN_TITLE_BASE + 180) * k, lClock);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, 10 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, i == 0 ? b.wideShader : b.tallShader, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        focusOutline(c, cell, r, f);
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        float cy = L_BUTTON_CY * k;
        headerRect.set(1000 * k, 54 * k, CONTENT_R * k, 152 * k);
        drawerGlass(c, headerRect, 26 * k, 0.82f, 0x0C000000, 8 * k);
        Symbols.draw(getContext(), c, "cast", 1048 * k, cy, 29 * k, tone(L_DIM, WHITE));
        dashboardText(c, networkText, 1080 * k, cy + 8 * k, 21, true, true, false);
        dashboardText(c, resolutionText, 1250 * k, cy + 8 * k, 19, true, true, false);
        dashboardText(c, clockText, 1717 * k, cy + 11 * k, 31, true, false, false);
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value);
            dashboardCell(c, r.left, r.top, r.width(), r.height());
            if (f > 0.01f) {
                border.setColor(Ui.alpha(tone(L_DIM, 0xFFC7C7CC), f));
                border.setStrokeWidth(3 * k);
                c.drawRoundRect(r, 16 * k, 16 * k, border);
            }
            Symbols.draw(getContext(), c, it.symbol, r.centerX(), r.centerY(), 27 * k, tone(L_INK, WHITE));
            if (f > 0.01f) {
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(it.label, r.centerX(), 184 * k, lTip);
            }
        }
        if (!sleepText.isEmpty()) dashboardText(c, sleepText, headerRect.left, 184 * k, 20, false, true, false);
    }

    private void enterExplore() {
        if (exploreOpen) return;
        exploreOpen = true;
        explore.target = 1;
        layoutLight();
        requestLayout();
        zone = shortcuts.isEmpty() ? Z_TOP : Z_SHORTCUTS;
        shortcutIndex = Math.min(shortcutIndex, Math.max(0, shortcuts.size() - 1));
        topIndex = tab;
        retarget(false);
        motionDirty = true;
        wake();
    }

    private void exitExplore() {
        if (!exploreOpen) return;
        exploreOpen = false;
        explore.target = 0;
        layoutLight();
        requestLayout();
        zone = Z_SHELF;
        retarget(false);
        motionDirty = true;
        wake();
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case WIDGETS: {
                if (drawerOpen) return false;
                List<Integer> panels = widgets();
                int at = panels.indexOf(widgetIndex);
                if (dx != 0 && at + dx >= 0 && at + dx < panels.size()) {
                    widgetIndex = panels.get(at + dx);
                    retarget(false);
                    click.move();
                } else if (dy > 0) {
                    zone = Z_SHELF;
                    RectF panel = widgetIndex == 0 ? dashboardWeather : dashboardPerformance;
                    shelfIndex = nearest(tileRects, shelf.size(), panel.centerX());
                    retarget(false);
                    click.move();
                } else if (dy < 0 && !lightButtons.isEmpty()) {
                    zone = Z_TOP;
                    RectF panel = widgetIndex == 0 ? dashboardWeather : dashboardPerformance;
                    topIndex = 3 + nearestTopButton(panel.centerX());
                    retarget(false);
                    click.move();
                }
                return true;
            }
            case Z_SHELF:
                if (dy > 0) {
                    if (exploreOpen) {
                        if (shortcuts.isEmpty()) return true;
                        zone = Z_SHORTCUTS;
                        shortcutIndex = 0;
                        retarget(false);
                        click.move();
                    } else {
                        enterExplore();
                    }
                    return true;
                }
                if (dy < 0) {
                    if (exploreOpen) {
                        exitExplore();
                    } else if (!lightButtons.isEmpty()) {
                        zone = Z_TOP;
                        topIndex = 3 + nearestTopButton(tileRects[shelfIndex].centerX());
                        retarget(false);
                        click.move();
                    }
                    return true;
                }
                return false;
            case Z_STATUS:
                if (dx != 0 && !statusItems.isEmpty()) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next != statusIndex) {
                        statusIndex = next;
                        retarget(false);
                        click.move();
                    }
                } else if (dy > 0) {
                    zone = Z_SHELF;
                    shelfIndex = nearest(tileRects, shelf.size(), statusRects[statusIndex].centerX());
                    retarget(false);
                    click.move();
                }
                return true;
            case Z_SHORTCUTS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shortcuts.size() - 1, shortcutIndex + dx));
                    if (next != shortcutIndex) {
                        shortcutIndex = next;
                        retarget(false);
                        click.move();
                    }
                } else if (dy < 0) {
                    zone = Z_SHELF;
                    shelfIndex = nearest(tileRects, shelf.size(), shortcutRects[shortcutIndex].centerX());
                    retarget(false);
                    click.move();
                } else if (dy > 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                    retarget(false);
                    click.move();
                }
                return true;
            case Z_TOP:
                if (!exploreOpen) {
                    if (lightButtons.isEmpty()) return true;
                    if (dx != 0) {
                        int next = Math.max(3, Math.min(2 + lightButtons.size(), topIndex + dx));
                        if (next != topIndex) {
                            topIndex = next;
                            retarget(false);
                            click.move();
                        }
                    } else if (dy > 0) {
                        enterExplore();
                    }
                    return true;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, topIndex + dx));
                    if (next != topIndex) {
                        topIndex = next;
                        selectTab(next);
                        retarget(false);
                        click.move();
                    }
                } else if (dy < 0) {
                    zone = shortcuts.isEmpty() ? Z_SHELF : Z_SHORTCUTS;
                    if (!shortcuts.isEmpty()) shortcutIndex = nearest(shortcutRects, shortcuts.size(), topRects[topIndex].centerX());
                    retarget(false);
                    click.move();
                } else if (dy > 0 && !cards.isEmpty()) {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    retarget(false);
                    click.move();
                }
                return true;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next != cardIndex) {
                        cardIndex = next;
                        retarget(false);
                        click.move();
                    }
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                    retarget(false);
                    click.move();
                }
                return true;
            default:
                return false;
        }
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
