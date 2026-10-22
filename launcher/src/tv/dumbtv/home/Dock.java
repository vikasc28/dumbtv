package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The Apple TV-style dock: the first apps in a frosted band. Left and right stop at its ends
 * instead of escaping to whatever lies geometrically beyond, the way the Grid wraps.
 */
final class Dock extends LinearLayout {
    static final int SIZE = 4;

    Dock(Context c, int pad, float radius) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.glass(c, radius));
    }

    @Override
    public View focusSearch(View focused, int direction) {
        int i = indexOfChild(focused);
        if (i >= 0 && (direction == FOCUS_LEFT && i == 0 || direction == FOCUS_RIGHT && i == getChildCount() - 1)) {
            return focused;
        }
        return super.focusSearch(focused, direction);
    }

    /** The banner's average colour, lifted so a dark banner still gives a visible glow. */
    static int glowColor(Item it) {
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

    /** A soft radial wash centred low on the screen, behind the dock. */
    static GradientDrawable glow(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setColors(new int[] {(color & 0x00FFFFFF) | 0x59000000, 0x00000000});
        d.setGradientCenter(0.5f, 0.78f);
        d.setGradientRadius(radius);
        return d;
    }
}
