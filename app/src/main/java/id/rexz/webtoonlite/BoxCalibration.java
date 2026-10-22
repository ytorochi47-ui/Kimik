package id.rexz.webtoonlite;

import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCRv5's detector boxes are deliberately generous (unclipped). Overlays and grouping work on
 * boxes that hug the real lettering, so raw boxes are tightened with factors measured on the CI
 * emulator test (see NativeOcrSmokeTest "boxes" log).
 */
final class BoxCalibration {
    /** Real text height as a fraction of the raw box height. */
    static final float HEIGHT_FACTOR = 0.55f;
    /** Horizontal padding contained in a raw box, as a fraction of the raw box height (both sides together). */
    static final float WIDTH_PAD = 0.25f;

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
