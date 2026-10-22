package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Floating realtime controls (R / pause-resume / stop + a one-line status) and the touch-transparent
 * translation layer. Owned by ScreenTranslatorService; every method runs on the main thread.
 */
final class RealtimeUi {
    interface Listener { void onStart(); void onPauseResume(); void onStop(); }
    enum Mode { IDLE, RUNNING, PAUSED }

    private static final int ICON_R = 0, ICON_PAUSE = 1, ICON_PLAY = 2, ICON_STOP = 3;

    private final Context ctx;
    private final WindowManager wm;
    private final int screenW, screenH;
    private final Listener listener;
    private final float density;

    private LinearLayout root, buttons;
    private TextView status;
    private WindowManager.LayoutParams rootParams;
    private TranslationOverlayView overlay;
    private boolean overlayAttached, rootAttached;
    private Mode mode = Mode.IDLE;

    RealtimeUi(Context ctx, WindowManager wm, int screenW, int screenH, Listener listener) {
        this.ctx = ctx; this.wm = wm; this.screenW = screenW; this.screenH = screenH; this.listener = listener;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return (int) (v * density + .5f); }

    private WindowManager.LayoutParams params(int w, int h, int extraFlags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | extraFlags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        return p;
    }

    // ------------------------------------------------------------------ controls

    void show(int x, int y) {
        if (rootAttached) return;
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);
        status = new TextView(ctx);
        status.setTextColor(Color.WHITE); status.setTextSize(11);
        status.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.argb(190, 20, 26, 40)); pill.setCornerRadius(dp(10));
        status.setBackground(pill);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(4);
        root.addView(status, slp);
        rootParams = params(-2, -2, 0);
        rootParams.x = x; rootParams.y = y;
        wm.addView(root, rootParams);
        rootAttached = true;
        rebuildButtons();
    }

    private void rebuildButtons() {
        if (buttons == null) return;
        buttons.removeAllViews();
        if (mode == Mode.IDLE) addButton(ICON_R, listener::onStart);
        else {
            addButton(mode == Mode.RUNNING ? ICON_PAUSE : ICON_PLAY, listener::onPauseResume);
            addButton(ICON_STOP, listener::onStop);
        }
        root.post(this::clampIntoScreen);
    }

    private void addButton(int icon, Runnable tap) {
        IconView v = new IconView(ctx, icon, density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.leftMargin = dp(4);
        v.setOnTouchListener(new DragTap(tap));
        buttons.addView(v, lp);
    }

    void setMode(Mode m) { mode = m; rebuildButtons(); }

    void setStatus(String text) {
        if (status == null) return;
        if (text == null || text.isEmpty()) status.setVisibility(View.GONE);
        else { status.setText(text); status.setVisibility(View.VISIBLE); }
        root.post(this::clampIntoScreen);
    }

    private void clampIntoScreen() {
        if (!rootAttached || root == null) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0) return;
        int nx = Math.max(0, Math.min(screenW - w, rootParams.x));
        int ny = Math.max(0, Math.min(screenH - h, rootParams.y));
        if (nx != rootParams.x || ny != rootParams.y) { rootParams.x = nx; rootParams.y = ny; safeUpdate(); }
    }

    private void safeUpdate() { try { wm.updateViewLayout(root, rootParams); } catch (Exception ignored) { } }

    /** Screen rectangles of the controls, so OCR/change detection can ignore them. */
    int[][] controlRects() {
        if (!rootAttached || root == null) return new int[0][];
        int w = Math.max(root.getWidth(), dp(46)), h = Math.max(root.getHeight(), dp(46));
        return new int[][]{{rootParams.x, rootParams.y, rootParams.x + w, rootParams.y + h}};
    }

    // ------------------------------------------------------------------ translation layer

    /** Adds the overlay window below the controls (touch-transparent, never focusable). */
    void startOverlay() {
        if (overlayAttached) return;
        overlay = new TranslationOverlayView(ctx);
        WindowManager.LayoutParams p = params(-1, -1,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        p.x = 0; p.y = 0;
        try { wm.addView(overlay, p); overlayAttached = true; } catch (Exception e) { overlay = null; return; }
        // Re-add the controls so they stay above the translation layer.
        if (rootAttached) {
            try { wm.removeView(root); wm.addView(root, rootParams); } catch (Exception ignored) { }
        }
    }

    void stopOverlay() {
        if (overlay != null && overlayAttached) { try { wm.removeView(overlay); } catch (Exception ignored) { } }
        overlay = null; overlayAttached = false;
    }

    void showDialogs(List<Dialog> dialogs) {
        if (overlay == null) return;
        if (dialogs.isEmpty()) overlay.clear(); else overlay.setDialogs(dialogs, screenW, screenH);
    }

    void hideDialogs() { if (overlay != null) overlay.clear(); }

    int[][] overlayRects() { return overlay == null ? new int[0][] : overlay.rects(); }

    void destroy() {
        stopOverlay();
        if (rootAttached && root != null) { try { wm.removeView(root); } catch (Exception ignored) { } }
        rootAttached = false; root = null;
    }

    // ------------------------------------------------------------------ touch + icons

    /** Tap runs the action; dragging moves the whole control group. */
    private final class DragTap implements View.OnTouchListener {
        private final Runnable tap;
        private float x0, y0; private int origX, origY; private boolean dragged;
        DragTap(Runnable tap) { this.tap = tap; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX(); y0 = e.getRawY(); origX = rootParams.x; origY = rootParams.y; dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        rootParams.x = Math.max(0, Math.min(screenW - root.getWidth(), origX + (int) dx));
                        rootParams.y = Math.max(0, Math.min(screenH - root.getHeight(), origY + (int) dy));
                        safeUpdate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged) { v.performClick(); tap.run(); }
                    return true;
                default: return true;
            }
        }
    }

    /** Round button drawn with canvas shapes (no dependency on emoji/symbol fonts of custom ROMs). */
    private static final class IconView extends View {
        private final int icon;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        IconView(Context c, int icon, float density) {
            super(c);
            this.icon = icon; this.density = density;
            int color = icon == ICON_R ? Color.rgb(28, 98, 218) : icon == ICON_PAUSE ? Color.rgb(224, 150, 20)
                    : icon == ICON_PLAY ? Color.rgb(36, 160, 90) : Color.rgb(200, 60, 60);
            bg.setColor(color); fg.setColor(Color.WHITE);
            fg.setTextAlign(Paint.Align.CENTER); fg.setFakeBoldText(true);
            setElevation(6f * density);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            c.drawCircle(cx, cy, r, bg);
            float u = r * 0.42f;
            switch (icon) {
                case ICON_R:
                    fg.setTextSize(r * 1.05f);
                    c.drawText("R", cx, cy + r * 0.37f, fg);
                    break;
                case ICON_PAUSE:
                    c.drawRoundRect(new RectF(cx - u, cy - u, cx - u * 0.25f, cy + u), 2 * density, 2 * density, fg);
                    c.drawRoundRect(new RectF(cx + u * 0.25f, cy - u, cx + u, cy + u), 2 * density, 2 * density, fg);
                    break;
                case ICON_PLAY:
                    path.reset();
                    path.moveTo(cx - u * 0.7f, cy - u); path.lineTo(cx + u, cy); path.lineTo(cx - u * 0.7f, cy + u); path.close();
                    c.drawPath(path, fg);
                    break;
                default:
                    c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u * 0.9f, cx + u * 0.9f, cy + u * 0.9f), 2 * density, 2 * density, fg);
            }
        }
    }
}
