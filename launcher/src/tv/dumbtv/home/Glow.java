package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** The dock layout's ambient glow, tinted by the focused app's banner. */
final class Glow {
    private Glow() {}

    /** The banner's average colour, lifted so a dark banner still gives a visible glow. */
    static int color(Item it) {
        Drawable d = it.banner != null ? it.banner : it.icon;
        if (d == null || d.getConstantState() == null) return 0xFF8A8B8F;
        Drawable copy = d.getConstantState().newDrawable().mutate();
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 16, 9);
        copy.draw(new Canvas(b));
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF8A8B8F;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[2] = Math.max(hsv[2], 0.75f);
        return Color.HSVToColor(hsv);
    }

    /** A soft radial wash centred low on the screen, behind the apps. */
    static GradientDrawable glow(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setColors(new int[] {(color & 0x00FFFFFF) | 0x59000000, 0x00000000});
        d.setGradientCenter(0.5f, 0.7f);
        d.setGradientRadius(radius);
        return d;
    }
}
