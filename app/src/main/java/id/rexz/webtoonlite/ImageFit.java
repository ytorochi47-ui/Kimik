package id.rexz.webtoonlite;

/**
 * Letterbox ("fit center") mapping between a bitmap and the view that shows it.
 * Pure Java so the crop coordinates can be unit-tested.
 */
final class ImageFit {
    final float scale, dx, dy;
    final int bitmapW, bitmapH;

    private ImageFit(int bw, int bh, float scale, float dx, float dy) {
        this.bitmapW = bw; this.bitmapH = bh; this.scale = scale; this.dx = dx; this.dy = dy;
    }

    static ImageFit of(int bw, int bh, int vw, int vh) {
        float s = Math.min((float) vw / bw, (float) vh / bh);
        return new ImageFit(bw, bh, s, (vw - bw * s) / 2f, (vh - bh * s) / 2f);
    }

    float toViewX(float bx) { return bx * scale + dx; }
    float toViewY(float by) { return by * scale + dy; }
    float toBitmapX(float vx) { return (vx - dx) / scale; }
    float toBitmapY(float vy) { return (vy - dy) / scale; }

    float viewLeft() { return dx; }
    float viewTop() { return dy; }
    float viewRight() { return dx + bitmapW * scale; }
    float viewBottom() { return dy + bitmapH * scale; }

    /**
     * Converts a view-space rectangle to bitmap pixels {left, top, right, bottom}.
     * Rounds outwards (never clips a glyph) and clamps to the bitmap.
     */
    int[] viewRectToBitmap(float l, float t, float r, float b) {
        int left = clamp((int) Math.floor(toBitmapX(Math.min(l, r))), 0, bitmapW);
        int top = clamp((int) Math.floor(toBitmapY(Math.min(t, b))), 0, bitmapH);
        int right = clamp((int) Math.ceil(toBitmapX(Math.max(l, r))), 0, bitmapW);
        int bottom = clamp((int) Math.ceil(toBitmapY(Math.max(t, b))), 0, bitmapH);
        return new int[]{left, top, right, bottom};
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
