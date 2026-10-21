package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** A single passive selection overlay; no accessibility monitoring, no continuous capture. */
public final class SelectionOverlay extends View {
    public interface Listener { void onSelected(Rect region); void onCancelled(); }
    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float startX, startY, endX, endY;
    private boolean drawing = false;

    public SelectionOverlay(Context ctx, Listener callback) {
        super(ctx); listener = callback;
        fill.setColor(Color.argb(45, 12, 20, 35));
        line.setColor(Color.rgb(85, 220, 255)); line.setStrokeWidth(4f); line.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE); text.setTextSize(18f * getResources().getDisplayMetrics().scaledDensity);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.argb(85, 0, 0, 0));
        canvas.drawText("Seret kotak pada dialog  •  ketuk untuk batal", 16, 65, text);
        if (drawing) {
            RectF r = new RectF(Math.min(startX, endX), Math.min(startY, endY),
                    Math.max(startX, endX), Math.max(startY, endY));
            canvas.drawRect(r, fill); canvas.drawRect(r, line);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = endX = e.getX(); startY = endY = e.getY();
                drawing = true; invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                endX = e.getX(); endY = e.getY(); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                endX = e.getX(); endY = e.getY(); invalidate();
                Rect r = new Rect((int)Math.min(startX, endX), (int)Math.min(startY,endY),
                        (int)Math.max(startX,endX), (int)Math.max(startY,endY));
                drawing = false;
                if (r.width() < 30 || r.height() < 25) listener.onCancelled();
                else {
                    int[] loc = new int[2]; getLocationOnScreen(loc);
                    r.offset(loc[0], loc[1]);
                    listener.onSelected(r);
                }
                return true;
            case MotionEvent.ACTION_CANCEL: drawing = false; listener.onCancelled(); return true;
            default: return true;
        }
    }
}
