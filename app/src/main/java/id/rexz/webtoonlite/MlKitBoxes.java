package id.rexz.webtoonlite;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Realtime-mode fallback: ML Kit lines with boxes, used only when the native PP-OCRv5 backend is unusable. */
final class MlKitBoxes {
    private static TextRecognizer recognizer;

    private MlKitBoxes() { }

    /** Blocking; call off the main thread. ML Kit boxes are already tight (no calibration needed). */
    static synchronized List<TextBox> read(Bitmap bitmap) throws Exception {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS);
        List<TextBox> out = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks())
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                String t = line.getText() == null ? "" : line.getText().trim();
                if (r == null || t.isEmpty()) continue;
                out.add(new TextBox(r.left, r.top, r.right, r.bottom, 0.9f, t));
            }
        return out;
    }

    static synchronized void close() {
        if (recognizer != null) { recognizer.close(); recognizer = null; }
    }
}
