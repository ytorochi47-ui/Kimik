package id.rexz.webtoonlite;

/** Tuning knobs of realtime mode in one place. */
final class RealtimeConfig {
    private RealtimeConfig() { }

    /** Screen must stay still this long before OCR runs. */
    static final long DEBOUNCE_MS = 500;
    /** At most one frame signature per this interval, however fast frames arrive. */
    static final long SAMPLE_MIN_MS = 100;
    /** Minimum gap between two OCR starts. */
    static final long MIN_OCR_INTERVAL_MS = 1200;
    /** Share of changed sample points that counts as "the page changed / scrolled". */
    static final float CHANGE_RATIO = 0.012f;
    /** Share of changed points between two consecutive samples that counts as "still moving". */
    static final float MOTION_RATIO = 0.008f;
    /** Detector input long side for a full screen (a 720x1600 phone is scaled by 0.8). */
    static final int OCR_MAX_SIDE = 1280;
    static final int MAX_DIALOGS = 20;
    static final int BATCH_MAX_ITEMS = 12;
    static final int BATCH_MAX_CHARS = 1200;
    /** Free the OCR models after this long without OCR. */
    static final long MODEL_IDLE_RELEASE_MS = 90_000;
    static final int TRANSLATION_CACHE = 200;
}
