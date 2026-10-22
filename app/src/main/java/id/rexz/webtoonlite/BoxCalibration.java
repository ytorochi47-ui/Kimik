package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw PP-OCRv5 line boxes from the ncnn pipeline are already close to the lettering. Measured on the CI
 * emulator test (RealtimeSmokeTest, bold sans 40-48 px): real text height = 0.89-0.98 of the raw box
 * height, and no horizontal padding. Boxes are trimmed only slightly so the overlay still covers every
 * glyph (an under-covering overlay would leave English visible; an over-covering one hides art).
 */
final class BoxCalibration {
    /** Real text height as a fraction of the raw box height. */
    static final float HEIGHT_FACTOR = 0.95f;
    /** Horizontal padding contained in a raw box, as a fraction of the raw box height (both sides together). */
    static final float WIDTH_PAD = 0.0f;

    private BoxCalibration() { }

    static TextBox tighten(TextBox b) {
        float h = b.height();
        float newH = Math.max(2f, h * HEIGHT_FACTOR);
        float padX = Math.min(b.width() * 0.4f, h * WIDTH_PAD) / 2f;
        float cy = b.centerY();
        return new TextBox(Math.round(b.left + padX), Math.round(cy - newH / 2f),
                Math.round(b.right - padX), Math.round(cy + newH / 2f), b.conf, b.text);
    }

    static List<TextBox> tightenAll(List<TextBox> in) {
        List<TextBox> out = new ArrayList<>(in.size());
        for (TextBox b : in) out.add(tighten(b));
        return out;
    }
}
