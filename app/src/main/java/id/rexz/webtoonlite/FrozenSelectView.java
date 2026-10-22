package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows a frozen camera frame (fit-center) and lets the user draw / move / resize one selection box.
 * All coordinate conversion goes through {@link ImageFit}, so the crop matches what is on screen.
 */
public final class FrozenSelectView extends View {
    private static final int NONE = 0, NEW = 1, MOVE = 2, RESIZE = 3;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dim = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private Bitmap bitmap;
    private ImageFit fit;
    private RectF sel;                  // selection in view coordinates, null when none
    private int mode = NONE;
    private float anchorX, anchorY;     // fixed corner while creating / resizing
    private float lastX, lastY;         // previous touch while moving

    public FrozenSelectView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        dim.setColor(Color.argb(120, 0, 0, 0));
        line.setColor(Color.rgb(85, 220, 255)); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3f * density);
        handle.setColor(Color.rgb(85, 220, 255));
        hint.setColor(Color.WHITE); hint.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setShadowLayer(4f * density, 0, 0, Color.BLACK);
        setBackgroundColor(Color.BLACK);
    }

    /** The view never recycles the bitmap; pass null BEFORE recycling it. */
    public void setBitmap(Bitmap b) {
        bitmap = b; sel = null; mode = NONE;
        updateFit();
        invalidate();
    }

    public boolean hasSelection() { return sel != null; }

    /** Selection in bitmap pixels, or null when nothing is selected. */
    public android.graphics.Rect getSelectionInBitmap() {
        if (bitmap == null || bitmap.isRecycled() || fit == null || sel == null) return null;
        int[] r = fit.viewRectToBitmap(sel.left, sel.top, sel.right, sel.bottom);
        if (r[2] - r[0] < 1 || r[3] - r[1] < 1) return null;
        return new android.graphics.Rect(r[0], r[1], r[2], r[3]);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        // Re-map the selection through bitmap space so it keeps covering the same pixels.
        android.graphics.Rect keep = getSelectionInBitmap();
        super.onSizeChanged(w, h, ow, oh);
        updateFit();
        if (keep != null && fit != null)
            sel = new RectF(fit.toViewX(keep.left), fit.toViewY(keep.top), fit.toViewX(keep.right), fit.toViewY(keep.bottom));
    }

    private void updateFit() {
        fit = (bitmap == null || bitmap.isRecycled() || getWidth() == 0 || getHeight() == 0) ? null
                : ImageFit.of(bitmap.getWidth(), bitmap.getHeight(), getWidth(), getHeight());
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        if (fit == null) updateFit();
        if (fit == null) return;
        c.save();
        c.translate(fit.dx, fit.dy);
        c.scale(fit.scale, fit.scale);
        c.drawBitmap(bitmap, 0, 0, bitmapPaint);
        c.restore();
        if (sel == null) {
            c.drawText("Seret kotak di sekitar dialog yang ingin diterjemahkan", getWidth() / 2f, 48f * density, hint);
            return;
        }
        float L = fit.viewLeft(), T = fit.viewTop(), R = fit.viewRight(), B = fit.viewBottom();
        c.drawRect(L, T, R, sel.top, dim);
        c.drawRect(L, sel.bottom, R, B, dim);
        c.drawRect(L, sel.top, sel.left, sel.bottom, dim);
        c.drawRect(sel.right, sel.top, R, sel.bottom, dim);
        c.drawRect(sel, line);
        float rad = 7f * density;
        c.drawCircle(sel.left, sel.top, rad, handle); c.drawCircle(sel.right, sel.top, rad, handle);
        c.drawCircle(sel.right, sel.bottom, rad, handle); c.drawCircle(sel.left, sel.bottom, rad, handle);
    }

    private float clampX(float x) { return Math.max(fit.viewLeft(), Math.min(fit.viewRight(), x)); }
    private float clampY(float y) { return Math.max(fit.viewTop(), Math.min(fit.viewBottom(), y)); }

    /** 0 TL, 1 TR, 2 BR, 3 BL, or -1. */
    private int hitCorner(float x, float y) {
        float slop = 30f * density;
        float[][] corners = {{sel.left, sel.top}, {sel.right, sel.top}, {sel.right, sel.bottom}, {sel.left, sel.bottom}};
        int best = -1; float bestD = slop * slop;
        for (int i = 0; i < 4; i++) {
            float dx = x - corners[i][0], dy = y - corners[i][1], d = dx * dx + dy * dy;
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (fit == null || !isEnabled()) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int corner = sel == null ? -1 : hitCorner(x, y);
                if (corner >= 0) {
                    mode = RESIZE;
                    int opp = (corner + 2) % 4;
                    anchorX = (opp == 0 || opp == 3) ? sel.left : sel.right;
                    anchorY = (opp == 0 || opp == 1) ? sel.top : sel.bottom;
                } else if (sel != null && sel.contains(x, y)) {
                    mode = MOVE; lastX = x; lastY = y;
                } else {
                    mode = NEW;
                    anchorX = clampX(x); anchorY = clampY(y);
                    sel = new RectF(anchorX, anchorY, anchorX, anchorY);
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (mode == NEW || mode == RESIZE) {
                    float cx = clampX(x), cy = clampY(y);
                    sel.set(Math.min(anchorX, cx), Math.min(anchorY, cy), Math.max(anchorX, cx), Math.max(anchorY, cy));
                } else if (mode == MOVE) {
                    float dx = x - lastX, dy = y - lastY;
                    dx = Math.max(fit.viewLeft() - sel.left, Math.min(fit.viewRight() - sel.right, dx));
                    dy = Math.max(fit.viewTop() - sel.top, Math.min(fit.viewBottom() - sel.bottom, dy));
                    sel.offset(dx, dy); lastX = x; lastY = y;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (sel != null && (sel.width() < 24f * density || sel.height() < 16f * density)) sel = null; // stray tap
                mode = NONE;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
