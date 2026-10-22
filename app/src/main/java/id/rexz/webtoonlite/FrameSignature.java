package id.rexz.webtoonlite;

import java.nio.ByteBuffer;

/**
 * A tiny luma fingerprint of a frame (COLS x ROWS sample points) used to notice screen changes and
 * scrolling without touching more than a few thousand pixels. Pure Java.
 */
final class FrameSignature {
    static final int COLS = 54, ROWS = 120;
    static final int SIZE = COLS * ROWS;
    /** A sample point counts as changed when its luma moved by more than this (0..255). */
    static final int PIXEL_DELTA = 18;

    private FrameSignature() { }

    /** RGBA_8888 buffer (as delivered by ImageReader) -> luma samples. */
    static byte[] sample(ByteBuffer buf, int width, int height, int pixelStride, int rowStride) {
        byte[] out = new byte[SIZE];
        int limit = buf.limit();
        for (int r = 0; r < ROWS; r++) {
            int y = Math.min(height - 1, (int) ((r + 0.5f) * height / ROWS));
            int rowBase = y * rowStride;
            for (int c = 0; c < COLS; c++) {
                int x = Math.min(width - 1, (int) ((c + 0.5f) * width / COLS));
                int idx = rowBase + x * pixelStride;
                if (idx + 2 >= limit) continue;
                int red = buf.get(idx) & 0xFF, green = buf.get(idx + 1) & 0xFF, blue = buf.get(idx + 2) & 0xFF;
                out[r * COLS + c] = (byte) ((red * 77 + green * 150 + blue * 29) >> 8);
            }
        }
        return out;
    }

    /** Marks the sample points that fall inside any rectangle (screen px, each grown by {@code grow}). */
    static boolean[] maskFor(int[][] rects, int width, int height, int grow) {
        boolean[] mask = new boolean[SIZE];
        if (rects == null) return mask;
        for (int r = 0; r < ROWS; r++) {
            int y = (int) ((r + 0.5f) * height / ROWS);
            for (int c = 0; c < COLS; c++) {
                int x = (int) ((c + 0.5f) * width / COLS);
                for (int[] q : rects)
                    if (x >= q[0] - grow && x < q[2] + grow && y >= q[1] - grow && y < q[3] + grow) { mask[r * COLS + c] = true; break; }
            }
        }
        return mask;
    }

    static boolean[] union(boolean[] a, boolean[] b) {
        if (a == null) return b;
        if (b == null) return a;
        boolean[] out = new boolean[SIZE];
        for (int i = 0; i < SIZE; i++) out[i] = a[i] || b[i];
        return out;
    }

    /**
     * Fraction (0..1) of compared sample points that changed. Points in {@code ignore} are skipped.
     * When too few points remain to judge, returns 0 (cannot tell).
     */
    static float diff(byte[] a, byte[] b, boolean[] ignore) {
        if (a == null || b == null || a.length != SIZE || b.length != SIZE) return 1f;
        int n = 0, changed = 0;
        for (int i = 0; i < SIZE; i++) {
            if (ignore != null && ignore[i]) continue;
            n++;
            if (Math.abs((a[i] & 0xFF) - (b[i] & 0xFF)) > PIXEL_DELTA) changed++;
        }
        return n < 300 ? 0f : (float) changed / n;
    }
}
