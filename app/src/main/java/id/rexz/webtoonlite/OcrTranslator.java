package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single OCR + translation pipeline used by BOTH modes (screenshot service and camera):
 * cropped bitmap -> PP-OCRv5 (ncnn, CPU) -> ML Kit fallback -> Gemini (text only) -> callback.
 *
 * Call {@link #translate} from the main thread; callbacks also arrive on the main thread.
 * The pipeline owns the crop bitmap it receives and recycles it. Models load lazily on first use
 * and are released after {@link #OCR_IDLE_RELEASE_MS} of inactivity or on {@link #shutdown()}.
 */
public final class OcrTranslator {
    public interface Callback {
        void onStatus(String message);
        void onResult(String source, String translation, boolean fromCache);
        void onError(String message);
    }

    // The loaded OCR models hold >100 MB of native memory. Drop them when the reader pauses.
    private static final long OCR_IDLE_RELEASE_MS = 90_000;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, String> cache = new LruCache<>(64);
    private final LinkedList<String> history = new LinkedList<>();
    private TextRecognizer recognizer;   // created lazily: only the fallback path needs it
    private boolean destroyed;
    private int active;                  // jobs in flight (main thread only)

    private final Runnable idleRelease = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (active > 0) { handler.postDelayed(this, OCR_IDLE_RELEASE_MS); return; }
            worker.execute(NativePaddleOcr::release);
        }
    };

    public OcrTranslator(Context context) { appContext = context.getApplicationContext(); }

    private void scheduleIdleRelease() {
        handler.removeCallbacks(idleRelease);
        handler.postDelayed(idleRelease, OCR_IDLE_RELEASE_MS);
    }

    /** Takes ownership of {@code crop}. */
    public void translate(Bitmap crop, Callback cb) {
        if (destroyed) { recycle(crop); return; }
        active++;
        final Bitmap work = capSize(crop);
        worker.execute(() -> {
            NativePaddleOcr.Result nat = null;
            try {
                nat = NativePaddleOcr.recognize(appContext.getAssets(), work);
            } catch (Exception | UnsatisfiedLinkError ex) {
                // Stay usable if this device cannot load the native backend.
                android.util.Log.w("WebtoonLite", "Native OCR failed; using ML Kit", ex);
            }
            final NativePaddleOcr.Result nativeResult = nat;
            handler.post(() -> {
                if (destroyed) { recycle(work); return; }
                scheduleIdleRelease();
                if (nativeResult != null && OcrPolicy.looksGood(nativeResult.text, nativeResult.confidence)) {
                    recycle(work);
                    sendTranslation(nativeResult.text, cb);
                } else {
                    runMlKitFallback(work, nativeResult, cb);
                }
            });
        });
    }

    /** Frees models, recognizer and threads. No callback is delivered afterwards. */
    public void shutdown() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        // Free the OCR models once any running OCR/translation has finished.
        worker.execute(NativePaddleOcr::release);
        worker.shutdown();
    }

    /** Large camera crops are scaled down; screen-sized crops pass through untouched. */
    private static Bitmap capSize(Bitmap src) {
        int[] size = OcrPolicy.fitInside(src.getWidth(), src.getHeight(), OcrPolicy.MAX_CROP_SIDE);
        if (size[0] == src.getWidth() && size[1] == src.getHeight()) return src;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, size[0], size[1], true);
            if (scaled != src) src.recycle();
            return scaled;
        } catch (OutOfMemoryError oom) {
            return src;
        }
    }

    private static void recycle(Bitmap b) { if (b != null && !b.isRecycled()) b.recycle(); }

    private void fail(Callback cb, String message) {
        if (destroyed) return;
        if (active > 0) active--;
        cb.onError(message);
    }

    /** ML Kit is only a fallback for crops the PP-OCRv5 pass could not read confidently. */
    private void runMlKitFallback(Bitmap crop, NativePaddleOcr.Result nativeResult, Callback cb) {
        final String weakNative = nativeResult == null ? "" : nativeResult.text;
        try {
            if (recognizer == null)
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(text -> {
                        if (destroyed) return;
                        String fallback = text.getText().trim();
                        if (!fallback.isEmpty()) sendTranslation(fallback, cb);
                        else if (OcrPolicy.hasContent(weakNative)) sendTranslation(weakNative, cb);
                        else fail(cb, "Teks belum terbaca. Coba pilih area sedikit lebih rapat.");
                    })
                    .addOnFailureListener(err -> {
                        if (destroyed) return;
                        if (OcrPolicy.hasContent(weakNative)) sendTranslation(weakNative, cb);
                        else fail(cb, "OCR gagal: " + err.getMessage());
                    })
                    .addOnCompleteListener(done -> recycle(crop));
        } catch (Exception e) {
            recycle(crop);
            fail(cb, "OCR gagal: " + e.getMessage());
        }
    }

    private void sendTranslation(String target, Callback cb) {
        if (destroyed) return;
        if (target.trim().isEmpty()) { fail(cb, "Teks kosong."); return; }
        final String cacheKey = OcrPolicy.normalizeKey(target);
        String cached = cache.get(cacheKey);
        if (cached != null) { deliver(cb, target, cached, true); return; }
        cb.onStatus("Menerjemahkan ...");
        final String previous = historyText();
        worker.execute(() -> {
            String translation;
            try {
                String key = new SecureKeyStore(appContext).load();
                if (key.isEmpty()) throw new Exception("API key belum disimpan.");
                translation = GeminiTranslator.translate(key, target, previous);
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Gagal menghubungi AI." : e.getMessage();
                handler.post(() -> fail(cb, message));
                return;
            }
            final String result = translation;
            handler.post(() -> {
                if (destroyed) return;
                cache.put(cacheKey, result);
                history.addFirst(clip(target, 220) + " => " + clip(result, 220));
                while (history.size() > 2) history.removeLast();
                deliver(cb, target, result, false);
            });
        });
    }

    private void deliver(Callback cb, String source, String translation, boolean cached) {
        if (active > 0) active--;
        cb.onResult(source, translation, cached);
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private String historyText() {
        StringBuilder sb = new StringBuilder();
        for (String item : history) sb.append(item).append('\n');
        return sb.toString();
    }
}
