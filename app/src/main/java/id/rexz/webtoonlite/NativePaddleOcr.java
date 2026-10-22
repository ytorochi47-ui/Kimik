package id.rexz.webtoonlite;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;

/** CPU-only PP-OCRv5 Mobile (ncnn) bridge. Loads the models lazily on first use. */
public final class NativePaddleOcr {
    /** OCR output plus the engine's character-weighted mean confidence (0..1). */
    public static final class Result {
        public final String text;
        public final float confidence;
        Result(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private static final String LIBRARY = "webtoonocr";
    private static boolean loaded;
    private static boolean initialized;
    private static String failure;

    private NativePaddleOcr() { }

    /** Loads the library and models on first use; shared by every recognize call. */
    private static void ensureReady(AssetManager assets) {
        if (failure != null) throw new IllegalStateException(failure);
        if (!loaded) {
            try { System.loadLibrary(LIBRARY); loaded = true; }
            catch (UnsatisfiedLinkError ex) {
                failure = "Library PP-OCRv5 belum tersedia: " + ex.getMessage();
                throw new IllegalStateException(failure, ex);
            }
        }
        if (!initialized) {
            if (!nativeInitialize(assets)) {
                failure = "Gagal membuka model PP-OCRv5. Periksa proses download saat build.";
                throw new IllegalStateException(failure);
            }
            initialized = true;
        }
    }

    /** Must run off the main/UI thread. */
    public static synchronized Result recognize(AssetManager assets, Bitmap bitmap) throws Exception {
        ensureReady(assets);
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Gambar tidak tersedia.");
        byte[] utf8 = nativeRecognize(bitmap);
        String text = utf8 == null ? "" : new String(utf8, StandardCharsets.UTF_8).trim();
        return new Result(text, text.isEmpty() ? 0f : nativeLastConfidence());
    }

    /**
     * Realtime mode: every recognized line with its box (bitmap pixels) and confidence.
     * Same models/engine as {@link #recognize}; {@code maxSide} caps the detector input size.
     * Must run off the main/UI thread.
     */
    public static synchronized java.util.List<TextBox> recognizeBoxes(AssetManager assets, Bitmap bitmap, int maxSide) throws Exception {
        ensureReady(assets);
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Gambar tidak tersedia.");
        byte[] utf8 = nativeRecognizeLines(bitmap, maxSide);
        return TextBox.parseNative(utf8 == null ? "" : new String(utf8, StandardCharsets.UTF_8));
    }

    /** Frees the ~10 MB of model weights and working buffers. Safe to call at any time. */
    public static synchronized void release() {
        if (loaded && initialized) nativeRelease();
        initialized = false;
    }

    private static native boolean nativeInitialize(AssetManager assets);
    private static native byte[] nativeRecognize(Bitmap bitmap);
    private static native byte[] nativeRecognizeLines(Bitmap bitmap, int maxSide);
    private static native float nativeLastConfidence();
    private static native void nativeRelease();
}
