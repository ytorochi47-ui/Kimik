package id.rexz.webtoonlite;

import java.util.Arrays;

/** Colour helpers for the adaptive overlay background (ARGB ints). Pure Java. */
final class ColorMath {
    private ColorMath() { }

    /** Per-channel median of the samples; robust against balloon outlines and stray pixels. */
    static int median(int[] argb, int count) {
        if (count <= 0) return 0xFFFFFFFF;
        int[] r = new int[count], g = new int[count], b = new int[count];
        for (int i = 0; i < count; i++) { r[i] = (argb[i] >> 16) & 0xFF; g[i] = (argb[i] >> 8) & 0xFF; b[i] = argb[i] & 0xFF; }
        Arrays.sort(r); Arrays.sort(g); Arrays.sort(b);
        return 0xFF000000 | (r[count / 2] << 16) | (g[count / 2] << 8) | b[count / 2];
    }

    static float luminance(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
    }

    /** Dark text on light backgrounds, white text on dark ones. */
    static int textColorFor(int bg) { return luminance(bg) > 0.55f ? 0xFF161616 : 0xFFFFFFFF; }
}
