package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen, touch-transparent layer that paints each translated dialog over the original lettering:
 * adaptive background colour, wrapped text, font size fitted to the box. Coordinates are screen pixels.
 */
public final class TranslationOverlayView extends View {
    private static final class Placed {
        final RectF rect = new RectF();
        StaticLayout layout;
        float padX, textTop;
        int bg;
    }

    private final List<Placed> placed = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LruCache<String, Float> sizeCache = new LruCache<>(64);   // areaKey|text|width -> font px
    private final float density, scaled;
    private final int[] loc = new int[2];
    private int screenW, screenH;

    public TranslationOverlayView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        scaled = getResources().getDisplayMetrics().scaledDensity;
    }

    void setDialogs(List<Dialog> dialogs, int screenW, int screenH) {
        this.screenW = screenW; this.screenH = screenH;
        placed.clear();
        for (Dialog d : dialogs) if (d.translation != null && !d.translation.isEmpty()) placed.add(place(d));
        invalidate();
    }

    void clear() { placed.clear(); invalidate(); }

    /** Rectangles actually painted (screen px): the engine ignores these when watching for screen changes. */
    int[][] rects() {
        int[][] out = new int[placed.size()][];
        for (int i = 0; i < out.length; i++) {
            RectF r = placed.get(i).rect;
            out[i] = new int[]{(int) r.left, (int) r.top, (int) Math.ceil(r.right), (int) Math.ceil(r.bottom)};
        }
        return out;
    }

    private StaticLayout build(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
    }

    private Placed place(Dialog d) {
        Placed p = new Placed();
        float pad = Math.max(2f * density, d.lineHeight * 0.18f);
        float padV = pad * 0.7f;
        RectF r = p.rect;
        r.set(d.left - pad, d.top - padV, d.right + pad, d.bottom + padV);
        float minW = 56f * density;
        if (r.width() < minW) { float cx = r.centerX(); r.left = cx - minW / 2f; r.right = cx + minW / 2f; }
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.right > screenW) r.offset(screenW - r.right, 0);

        int innerW = Math.max(1, (int) (r.width() - 2 * pad));
        float innerH = r.height() - 2 * padV;
        float maxSize = Math.max(10f * scaled, d.lineHeight * 1.15f);   // ~ em size of the original lettering
        float minSize = Math.min(maxSize, Math.max(8f * scaled, maxSize * 0.5f));

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(d.fgColor);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        String cacheKey = d.areaKey() + "|" + d.translation.hashCode() + "|" + innerW;
        Float cached = sizeCache.get(cacheKey);
        float size;
        if (cached != null) {
            size = cached;
        } else {
            paint.setTextSize(minSize);
            if (build(d.translation, paint, innerW).getHeight() > innerH) {
                size = minSize;                          // does not fit even small: the box will grow below
            } else {
                float lo = minSize, hi = maxSize;        // largest size that still fits
                for (int i = 0; i < 7; i++) {
                    float mid = (lo + hi) / 2f;
                    paint.setTextSize(mid);
                    if (build(d.translation, paint, innerW).getHeight() <= innerH) lo = mid; else hi = mid;
                }
                size = lo;
            }
            sizeCache.put(cacheKey, size);
        }
        paint.setTextSize(size);
        p.layout = build(d.translation, paint, innerW);
        float need = p.layout.getHeight() + 2 * padV;
        if (need > r.height()) {                         // grow only as far as needed, at most +50%
            float grown = Math.min(need, r.height() * 1.5f);
            float cy = r.centerY();
            r.top = cy - grown / 2f; r.bottom = cy + grown / 2f;
        }
        if (r.top < 0) r.offset(0, -r.top);
        if (r.bottom > screenH) r.offset(0, screenH - r.bottom);
        p.padX = pad;
        p.textTop = r.top + (r.height() - p.layout.getHeight()) / 2f;
        p.bg = (d.bgColor & 0x00FFFFFF) | 0xF2000000;
        return p;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        getLocationOnScreen(loc);
        c.translate(-loc[0], -loc[1]);                    // draw in absolute screen coordinates
        float radius = 6f * density;
        for (Placed p : placed) {
            fill.setColor(p.bg);
            c.drawRoundRect(p.rect, radius, radius, fill);
            c.save();
            c.translate(p.rect.left + p.padX, p.textTop);
            p.layout.draw(c);
            c.restore();
        }
    }
}
