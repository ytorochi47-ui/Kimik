package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.List;

/** One OCR text line with an axis-aligned box in bitmap pixels. Pure Java. */
final class TextBox {
    final int left, top, right, bottom;
    final float conf;     // 0..1
    final String text;

    TextBox(int left, int top, int right, int bottom, float conf, String text) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.conf = conf; this.text = text;
    }

    int width() { return right - left; }
    int height() { return bottom - top; }
    float centerX() { return (left + right) / 2f; }
    float centerY() { return (top + bottom) / 2f; }

    TextBox offset(int dx, int dy) { return new TextBox(left + dx, top + dy, right + dx, bottom + dy, conf, text); }

    /** Parses "left\ttop\tright\tbottom\tconf_permille\ttext" records, one per line. Bad records are skipped. */
    static List<TextBox> parseNative(String payload) {
        List<TextBox> out = new ArrayList<>();
        if (payload == null || payload.isEmpty()) return out;
        for (String rec : payload.split("\n")) {
            String[] f = rec.split("\t", 6);
            if (f.length < 6) continue;
            try {
                String text = f[5].trim();
                if (text.isEmpty()) continue;
                out.add(new TextBox(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                        Integer.parseInt(f[3]), Integer.parseInt(f[4]) / 1000f, text));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
