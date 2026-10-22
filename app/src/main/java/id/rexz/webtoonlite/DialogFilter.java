package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Drops OCR groups that are not worth a translation request (UI chrome, URLs, numbers, noise). Pure Java. */
final class DialogFilter {
    static final float MIN_CONF = 0.50f;

    private DialogFilter() { }

    static boolean isTranslatable(Dialog d) {
        return d.conf >= MIN_CONF && isTranslatableText(d.text);
    }

    static boolean isTranslatableText(String text) {
        if (text == null) return false;
        String t = text.trim();
        int letters = 0, latin = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) { letters++; if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++; }
        }
        if (letters < 2 || latin < letters * 0.7f) return false;           // numbers, noise, non-Latin scripts
        if (!t.matches("(?s).*\\p{L}{2,}.*")) return false;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("http") || low.contains("www.") || low.matches("(?s).*\\b[a-z0-9-]+\\.(com|net|org|id|co|io|app)\\b.*")) return false;
        return true;
    }

    /** Applies {@link #isTranslatable} and drops dialogs that touch any ignored screen rectangle (e.g. our own buttons). */
    static List<Dialog> keep(List<Dialog> in, int[][] ignoreRects) {
        List<Dialog> out = new ArrayList<>();
        outer:
        for (Dialog d : in) {
            if (!isTranslatable(d)) continue;
            if (ignoreRects != null)
                for (int[] r : ignoreRects) if (d.intersects(r[0], r[1], r[2], r[3])) continue outer;
            out.add(d);
        }
        return out;
    }
}
