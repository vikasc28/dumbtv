package tv.dumbtv.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays equal-size tiles out in as many columns as the width holds, then wraps to the next line.
 * The tvOS home screen shape: a block that fills across and goes down, rather than a strip that
 * runs off the right edge. Children size themselves, so {@link Tile} needs no change.
 */
final class Grid extends ViewGroup {
    private final int gap;
    private int cols = 1;

    Grid(Context c, int gap) {
        super(c);
        this.gap = gap;
        setClipChildren(false);
        setClipToPadding(false);
    }

    int cols() {
        return Math.max(1, cols);
    }

    int gap() {
        return gap;
    }

    /**
     * Walks the tiles in reading order rather than by geometry. Android's own search looks for a
     * view lying to the right of the focused one, so the last tile on a line is a dead end and the
     * event escapes upward to the Options/Settings chips. Here right from the end of a line lands
     * on the first tile of the next, and a line shorter than the others still catches a press from
     * the column above it. Up from the first line and down from the last still bubble out, so the
     * chips and the Inputs row stay reachable.
     */
    @Override
    public View focusSearch(View focused, int direction) {
        int i = indexOfChild(focused), n = getChildCount();
        if (i >= 0 && n > 0) {
            int cols = Math.min(cols(), n), last = n - 1;
            switch (direction) {
                case FOCUS_RIGHT:
                    return getChildAt(i < last ? i + 1 : i);
                case FOCUS_LEFT:
                    return getChildAt(i > 0 ? i - 1 : i);
                case FOCUS_DOWN:
                    if (i / cols < last / cols) return getChildAt(Math.min(i + cols, last));
                    break;
                case FOCUS_UP:
                    if (i >= cols) return getChildAt(i - cols);
                    break;
                default:
                    break;
            }
        }
        return super.focusSearch(focused, direction);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int inner = width - getPaddingLeft() - getPaddingRight();
        int n = getChildCount(), w = 0, h = 0;
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            w = Math.max(w, child.getMeasuredWidth());
            h = Math.max(h, child.getMeasuredHeight());
        }
        cols = w <= 0 ? 1 : Math.max(1, Math.min(n, (inner + gap) / (w + gap)));
        int lines = n == 0 ? 0 : (n + cols() - 1) / cols();
        int height = getPaddingTop() + getPaddingBottom()
                + Math.max(0, lines * h + Math.max(0, lines - 1) * gap);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        if (n == 0) return;
        int cw = getChildAt(0).getMeasuredWidth(), ch = getChildAt(0).getMeasuredHeight();
        int cols = Math.min(cols(), n);
        int inner = getWidth() - getPaddingLeft() - getPaddingRight();
        // Widen the gaps into the slack a whole column could not use, up to double, then centre
        // the rest: a short last column reads as deliberate spacing rather than a dead right edge.
        int slack = Math.max(0, inner - (cols * cw + (cols - 1) * gap));
        int spread = cols > 1 ? Math.min(slack / (cols - 1), gap) : 0;
        int step = cw + gap + spread;
        int x0 = getPaddingLeft() + (slack - spread * (cols - 1)) / 2;
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            int x = x0 + i % cols * step;
            int y = getPaddingTop() + i / cols * (ch + gap);
            child.layout(x, y, x + cw, y + ch);
        }
    }
}
