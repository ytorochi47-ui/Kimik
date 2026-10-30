package id.rexz.webtoonlite;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;


/** Source-aware realtime OCR client, owned and closed by its worker. */
final class MlKitBoxes {
    private TextRecognizer recognizer;
    private boolean korean;

    MlKitBoxes() { }

    /** Blocking; call off the main thread. ML Kit boxes are already tight (no calibration needed). */
    synchronized List<TextBox> read(Bitmap bitmap, boolean useKorean) throws Exception {
        if (recognizer != null && korean != useKorean) close();
        if (recognizer == null) {
            korean = useKorean;
            recognizer = useKorean ? TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
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

    synchronized void close() {
        if (recognizer != null) { recognizer.close(); recognizer = null; }
    }
}
