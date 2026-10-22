package id.rexz.webtoonlite;

/** A group of OCR lines that form one speech balloon / caption, in screen pixels. Pure Java. */
final class Dialog {
    final int left, top, right, bottom;
    final String text;
    final String key;          // normalized text: translation-cache identity
    final int lineCount;
    final int lineHeight;      // median height of the member lines (calibrated text height, px)
    final float conf;

    // Filled in later (main thread / OCR thread): what to draw.
    String translation = "";
    int bgColor = 0xFFFFFFFF;
    int fgColor = 0xFF111111;

    Dialog(int left, int top, int right, int bottom, String text, int lineCount, int lineHeight, float conf) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.text = text; this.key = OcrPolicy.normalizeKey(text);
        this.lineCount = lineCount; this.lineHeight = lineHeight; this.conf = conf;
    }

    int width() { return right - left; }
    int height() { return bottom - top; }

    /**
     * Identity of the area: same text in a region of about the same size. Position is left out on
     * purpose so a dialog that merely scrolled keeps its identity (layout work can be reused).
     */
    String areaKey() { return key + "@" + (width() / 8) + "x" + (height() / 8); }

    /** Same dialog moved by (dx, dy); colours and translation are carried over. */
    Dialog offset(int dx, int dy) {
        Dialog d = new Dialog(left + dx, top + dy, right + dx, bottom + dy, text, lineCount, lineHeight, conf);
        d.translation = translation; d.bgColor = bgColor; d.fgColor = fgColor;
        return d;
    }

    boolean intersects(int l, int t, int r, int b) { return left < r && right > l && top < b && bottom > t; }
}
