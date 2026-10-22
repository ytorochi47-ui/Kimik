package id.rexz.webtoonlite;

import java.util.Locale;

/** Pure-Java decisions shared by both modes (kept free of Android types so they are unit-testable). */
final class OcrPolicy {
    /** Longest side allowed for a crop handed to OCR. Screen crops are normally below this. */
    static final int MAX_CROP_SIDE = 1600;

    private OcrPolicy() { }

    /** Native OCR is trusted when it read real words with decent confidence. */
    static boolean looksGood(String text, float confidence) {
        return text != null && confidence >= 0.55f && text.matches("(?s).*\\p{L}{2,}.*");
    }

    /** True when the text holds something other than punctuation/whitespace. */
    static boolean hasContent(String text) {
        return text != null && !text.isEmpty() && !text.matches("[\\p{Punct}\\s]+");
    }

    static String normalizeKey(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Target size {w, h} so the longest side is at most {@code max}; unchanged when already small. */
    static int[] fitInside(int w, int h, int max) {
        int longest = Math.max(w, h);
        if (longest <= max) return new int[]{w, h};
        float s = (float) max / longest;
        return new int[]{Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))};
    }

    /** Largest power-of-two sample size that still leaves the longest side >= {@code target}. */
    static int sampleSizeFor(int w, int h, int target) {
        int longest = Math.max(w, h), sample = 1;
        while (longest / (sample * 2) >= target) sample *= 2;
        return sample;
    }
}
