package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** Small, static backgrounds. Built on the loader, with no network requests or animation. */
final class Wallpaper {
    static final String[] CHOICES = {"ocean", "dunes", "forest", "sunset", "neutral", "auto"};

    static String name(String id) {
        switch (id) {
            case "ocean": return Ui.tr("Ocean");
            case "dunes": return Ui.tr("Dunes");
            case "forest": return Ui.tr("Forest");
            case "sunset": return Ui.tr("Sunset");
            case "neutral": return Ui.tr("Neutral");
            default: return Ui.tr("Automatic");
        }
    }

    static Backdrop make(String id, int blur, int brightness) {
        int[] colors;
        switch (id) {
            case "dunes": colors = new int[] {0xFFE8D7BD, 0xFFC8A788, 0xFFBD9376, 0xFF93786C}; break;
            case "forest": colors = new int[] {0xFFBCD2C7, 0xFF759D8F, 0xFF527C70, 0xFF365D58}; break;
            case "sunset": colors = new int[] {0xFFDACDE8, 0xFFF2B9A9, 0xFFC08AAB, 0xFF796D9A}; break;
            case "neutral": colors = new int[] {0xFFE7E8EE, 0xFFC7CBD5, 0xFFBEC4D0, 0xFFA5ADBD}; break;
            default: colors = new int[] {0xFFCEE7EF, 0xFF91C8DC, 0xFF629FB9, 0xFF477B9B};
        }
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(0, 0, 640, 360, colors[0], colors[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 640, 360, paint);
        paint.setShader(null);
        Path path = new Path();
        path.moveTo(-20, 240);
        path.cubicTo(140, 80, 300, 330, 660, 120);
        path.lineTo(660, 380); path.lineTo(-20, 380); path.close();
        paint.setColor(colors[2]); canvas.drawPath(path, paint);
        path.reset(); path.moveTo(-20, 300);
        path.cubicTo(210, 170, 400, 380, 660, 240);
        path.lineTo(660, 380); path.lineTo(-20, 380); path.close();
        paint.setColor(colors[3]); canvas.drawPath(path, paint);
        return Backdrop.wallpaper(image, blur, brightness);
    }
}
