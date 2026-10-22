package id.rexz.webtoonlite;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Smart realtime translation, driven by screen changes rather than a frame loop:
 *
 * <pre>
 *   frames (only delivered when the screen changes) -> tiny luma signature -> change / motion detection
 *   CHANGING (overlay hidden) --500 ms of stillness--> OCR (PP-OCRv5, boxes) -> group -> SHOWN
 *   SHOWN: cached dialogs painted at once; new ones go to Gemini as ONE text-only batch
 * </pre>
 *
 * Invariants: OCR only runs on an overlay-free frame after the screen has been still; an OCR result or
 * translation is painted only if the screen did not change since (epoch check); translations are cached by
 * text, so stale answers still pay off later. All state lives on the main thread.
 */
final class RealtimeEngine {
    enum State { OFF, RUNNING, PAUSED }
    private enum Phase { CHANGING, OCR, SHOWN }

    interface Host {                          // always called on the main thread
        void showDialogs(List<Dialog> dialogs);
        void hideDialogs();
        int[][] overlayRects();
        int[][] controlRects();
        void status(String text);
    }

    private final Context appContext;
    private final ImageReader reader;
    private final VirtualDisplay display;
    private final int screenW, screenH, topInset;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ocrExec = Executors.newSingleThreadExecutor();
    private final ExecutorService apiExec = Executors.newSingleThreadExecutor();

    // ---- frame side (guarded by frameLock; callbacks on frameThread)
    private final Object frameLock = new Object();
    private HandlerThread frameThread;
    private Handler frameHandler;
    private Image held;                       // newest frame, kept so a static screen can still be read
    private long heldAt, lastSampleAt;
    private boolean samplePending;

    // ---- main-thread state
    private State state = State.OFF;
    private Phase phase = Phase.CHANGING;
    private int epoch;
    private long lastMotion, lastOcrStart, overlayHiddenAt;
    private byte[] prevSig, baseline, ocrSig;
    private boolean ocrBusy, overlayVisible;
    private int grabRetries;
    private List<Dialog> lastDialogs = new ArrayList<>();

    // ---- translation side
    private final LruCache<String, String> cache = new LruCache<>(RealtimeConfig.TRANSLATION_CACHE);
    private final Map<String, Integer> missCount = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ApiGate gate = new ApiGate();
    private boolean apiBusy;
    private int inFlightEpoch, reqSeq, activeReq;
    private long lastSentAt;
    private GeminiTranslator.CancelToken token;

    RealtimeEngine(Context ctx, ImageReader reader, VirtualDisplay display, int screenW, int screenH, int topInset, Host host) {
        this.appContext = ctx.getApplicationContext();
        this.reader = reader; this.display = display;
        this.screenW = screenW; this.screenH = screenH; this.topInset = topInset;
        this.host = host;
    }

    State state() { return state; }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        if (state == State.RUNNING) return;
        state = State.RUNNING;
        epoch++;
        phase = Phase.CHANGING;
        baseline = null; prevSig = null; ocrSig = null;
        lastDialogs = new ArrayList<>();
        overlayVisible = false; overlayHiddenAt = 0; grabRetries = 0;
        missCount.clear();
        frameThread = new HandlerThread("webtoonlite-frames");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        try {
            reader.setOnImageAvailableListener(frameListener, frameHandler);
            display.setSurface(reader.getSurface());
        } catch (Exception e) {
            host.status("Tidak dapat membaca layar: " + e.getMessage());
            state = State.OFF;
            detach();
            return;
        }
        lastMotion = SystemClock.uptimeMillis();
        host.status("Membaca layar…");
        scheduleStable();
    }

    void pause() {
        if (state != State.RUNNING) return;
        state = State.PAUSED;
        teardown();
        host.status("Realtime dijeda");
    }

    void resume() {
        if (state != State.PAUSED) return;
        state = State.OFF;      // start() requires "not running"
        start();
    }

    void stop() {
        if (state == State.OFF) { teardown(); return; }
        state = State.OFF;
        teardown();
        lastDialogs = new ArrayList<>();
        baseline = null;
        host.status("");
        ocrExec.execute(NativePaddleOcr::release);        // free the models right away
    }

    /** Service shutdown: stop and free every thread. */
    void release() {
        stop();
        ocrExec.execute(NativePaddleOcr::release);
        ocrExec.shutdown();
        apiExec.shutdown();
        MlKitBoxes.close();
    }

    private void teardown() {
        epoch++;                                           // anything in flight is now stale
        main.removeCallbacksAndMessages(null);
        if (token != null) token.cancel();
        apiBusy = false; inFlight.clear(); activeReq = 0;   // a late answer is only cached, never painted
        host.hideDialogs();
        overlayVisible = false;
        detach();
    }

    private void detach() {
        try { reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) { }
        try { display.setSurface(null); } catch (Exception ignored) { }   // no frames while idle
        synchronized (frameLock) {
            if (held != null) { held.close(); held = null; }
            samplePending = false;
        }
        if (frameThread != null) { frameThread.quitSafely(); frameThread = null; frameHandler = null; }
    }

    // ------------------------------------------------------------------ frames (frame thread)

    private final ImageReader.OnImageAvailableListener frameListener = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            Image img;
            try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            byte[] sig = null;
            synchronized (frameLock) {
                if (held != null) held.close();
                held = img; heldAt = now;
                if (now - lastSampleAt >= RealtimeConfig.SAMPLE_MIN_MS) {
                    lastSampleAt = now;
                    sig = sampleHeldLocked();
                } else if (!samplePending && frameHandler != null) {
                    samplePending = true;                    // make sure the LAST frame of a burst is judged too
                    frameHandler.postDelayed(sampleLater, RealtimeConfig.SAMPLE_MIN_MS);
                }
            }
            if (sig != null) postSample(sig, now);
        }
    };

    private final Runnable sampleLater = new Runnable() {
        @Override public void run() {
            byte[] sig; long now = SystemClock.uptimeMillis();
            synchronized (frameLock) {
                samplePending = false;
                if (held == null) return;
                lastSampleAt = now;
                sig = sampleHeldLocked();
            }
            if (sig != null) postSample(sig, now);
        }
    };

    private byte[] sampleHeldLocked() {
        try {
            Image.Plane pl = held.getPlanes()[0];
            return FrameSignature.sample(pl.getBuffer(), held.getWidth(), held.getHeight(), pl.getPixelStride(), pl.getRowStride());
        } catch (Exception e) { return null; }
    }

    private void postSample(final byte[] sig, final long at) { main.post(() -> onSample(sig, at)); }

    // ------------------------------------------------------------------ change detection (main)

    private boolean[] controlMask() { return FrameSignature.maskFor(host.controlRects(), screenW, screenH, 6); }

    private boolean[] shownMask() {
        return FrameSignature.union(controlMask(), FrameSignature.maskFor(host.overlayRects(), screenW, screenH, 10));
    }

    private void onSample(byte[] sig, long at) {
        if (state != State.RUNNING) return;
        switch (phase) {
            case SHOWN:
                if (baseline == null) { baseline = sig; return; }
                // Our own overlay is excluded from the comparison, so painting it never counts as a change.
                if (FrameSignature.diff(sig, baseline, shownMask()) > RealtimeConfig.CHANGE_RATIO) beginChanging(sig);
                break;
            case CHANGING:
                if (prevSig != null && FrameSignature.diff(sig, prevSig, controlMask()) > RealtimeConfig.MOTION_RATIO) {
                    lastMotion = at;
                    scheduleStable();
                }
                prevSig = sig;
                break;
            case OCR:
                if (ocrSig != null && FrameSignature.diff(sig, ocrSig, controlMask()) > RealtimeConfig.CHANGE_RATIO) epoch++;
                break;
        }
    }

    private void beginChanging(byte[] sig) {
        epoch++;                                          // reading/translations of the old screen are stale
        phase = Phase.CHANGING;
        prevSig = sig;
        lastMotion = SystemClock.uptimeMillis();
        main.removeCallbacks(retryRunnable);
        if (overlayVisible) {                             // scrolled away: never leave an overlay at a wrong place
            host.hideDialogs();
            overlayVisible = false;
            overlayHiddenAt = lastMotion;
        }
        scheduleStable();
    }

    private void scheduleStable() {
        main.removeCallbacks(stableRunnable);
        main.postDelayed(stableRunnable, RealtimeConfig.DEBOUNCE_MS);
    }

    private final Runnable stableRunnable = new Runnable() {
        @Override public void run() {
            if (state != State.RUNNING || phase != Phase.CHANGING) return;
            long now = SystemClock.uptimeMillis();
            // Came back to the page we already translated: restore instead of reading again.
            if (baseline != null && prevSig != null && !lastDialogs.isEmpty()
                    && FrameSignature.diff(prevSig, baseline, controlMask()) <= RealtimeConfig.CHANGE_RATIO * 0.5f) {
                phase = Phase.SHOWN;
                renderAndRequest();
                return;
            }
            if (ocrBusy) { main.postDelayed(this, 300); return; }
            long wait = lastOcrStart + RealtimeConfig.MIN_OCR_INTERVAL_MS - now;
            if (wait > 0) { main.postDelayed(this, wait); return; }
            // The frame we read must have been drawn AFTER our overlay disappeared.
            if (overlayHiddenAt > 0 && heldAtSnapshot() <= overlayHiddenAt && now - overlayHiddenAt < 1500) {
                main.postDelayed(this, 150);
                return;
            }
            startOcr(now);
        }
    };

    private long heldAtSnapshot() { synchronized (frameLock) { return held == null ? 0 : heldAt; } }

    // ------------------------------------------------------------------ OCR

    private void startOcr(long now) {
        Bitmap bmp = null; byte[] sig = null;
        synchronized (frameLock) {
            if (held != null) {
                try { bmp = FrameGrabber.cropped(held, topInset); sig = sampleHeldLocked(); }
                catch (Exception | OutOfMemoryError e) { bmp = null; }
            }
        }
        if (bmp == null) {
            if (++grabRetries <= 10) main.postDelayed(stableRunnable, 250);
            else { grabRetries = 0; host.status("Layar belum siap dibaca"); }
            return;
        }
        grabRetries = 0;
        overlayHiddenAt = 0;
        phase = Phase.OCR;
        ocrBusy = true;
        lastOcrStart = now;
        ocrSig = sig;
        final int myEpoch = epoch;
        final Bitmap frame = bmp;
        final int[][] ignore = shiftRects(host.controlRects(), -topInset);   // our own buttons are not dialogs
        host.status("Membaca teks…");
        ocrExec.execute(() -> {
            List<Dialog> dialogs;
            try {
                dialogs = readDialogs(frame, ignore);
            } catch (Throwable t) {
                frame.recycle();
                final String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                main.post(() -> onOcrFailed(myEpoch, msg));
                return;
            }
            frame.recycle();
            main.post(() -> onOcrDone(myEpoch, dialogs));
        });
    }

    /** Runs on the OCR thread: boxes -> dialogs -> colours. PP-OCRv5 first, ML Kit only if it cannot run at all. */
    private List<Dialog> readDialogs(Bitmap frame, int[][] ignore) throws Exception {
        List<TextBox> boxes;
        try {
            boxes = BoxCalibration.tightenAll(NativePaddleOcr.recognizeBoxes(appContext.getAssets(), frame, RealtimeConfig.OCR_MAX_SIDE));
        } catch (Exception | UnsatisfiedLinkError nativeUnavailable) {
            android.util.Log.w("WebtoonLite", "Native OCR unavailable in realtime; using ML Kit", nativeUnavailable);
            boxes = MlKitBoxes.read(frame);
        }
        List<Dialog> kept = DialogFilter.keep(DialogGrouper.group(boxes, RealtimeConfig.MAX_DIALOGS), ignore);
        List<Dialog> out = new ArrayList<>(kept.size());
        for (Dialog d : kept) {
            int bg = sampleBackground(frame, d);
            d.bgColor = bg;
            d.fgColor = ColorMath.textColorFor(bg);
            out.add(d.offset(0, topInset));                // back to real screen coordinates
        }
        return out;
    }

    /** Median colour of a thin ring just outside the dialog: that is the balloon / caption background. */
    private static int sampleBackground(Bitmap bmp, Dialog d) {
        int m = Math.max(3, d.lineHeight / 4);
        int l = Math.max(0, d.left - m), t = Math.max(0, d.top - m);
        int r = Math.min(bmp.getWidth() - 1, d.right + m), b = Math.min(bmp.getHeight() - 1, d.bottom + m);
        if (r <= l || b <= t) return 0xFFFFFFFF;
        int[] px = new int[64];
        int n = 0;
        int stepX = Math.max(1, (r - l) / 14), stepY = Math.max(1, (b - t) / 6);
        for (int x = l; x <= r && n < 60; x += stepX) { px[n++] = bmp.getPixel(x, t); px[n++] = bmp.getPixel(x, b); }
        for (int y = t; y <= b && n < 62; y += stepY) { px[n++] = bmp.getPixel(l, y); px[n++] = bmp.getPixel(r, y); }
        return ColorMath.median(px, Math.min(n, px.length));
    }

    private static int[][] shiftRects(int[][] rects, int dy) {
        int[][] out = new int[rects.length][];
        for (int i = 0; i < rects.length; i++) out[i] = new int[]{rects[i][0], rects[i][1] + dy, rects[i][2], rects[i][3] + dy};
        return out;
    }

    private void onOcrFailed(int myEpoch, String message) {
        ocrBusy = false;
        scheduleModelRelease();
        if (state != State.RUNNING) return;
        host.status("OCR gagal: " + message);
        // Nothing to show; keep watching this frame so the next real change triggers one new attempt (no loop).
        lastDialogs = new ArrayList<>();
        baseline = ocrSig;
        phase = Phase.SHOWN;
    }

    private void onOcrDone(int myEpoch, List<Dialog> dialogs) {
        ocrBusy = false;
        scheduleModelRelease();
        if (state != State.RUNNING) return;
        if (myEpoch != epoch) {                            // the screen moved while we were reading
            phase = Phase.CHANGING;
            prevSig = null;
            lastMotion = SystemClock.uptimeMillis();
            scheduleStable();
            return;
        }
        baseline = ocrSig;
        lastDialogs = dialogs;
        phase = Phase.SHOWN;
        host.status("");
        renderAndRequest();
    }

    private final Runnable releaseRunnable = new Runnable() {
        @Override public void run() {
            if (ocrBusy) { main.postDelayed(this, RealtimeConfig.MODEL_IDLE_RELEASE_MS); return; }
            ocrExec.execute(NativePaddleOcr::release);
        }
    };

    private void scheduleModelRelease() {
        main.removeCallbacks(releaseRunnable);
        main.postDelayed(releaseRunnable, RealtimeConfig.MODEL_IDLE_RELEASE_MS);
    }

    // ------------------------------------------------------------------ translation

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() { if (state == State.RUNNING && phase == Phase.SHOWN) renderAndRequest(); }
    };

    /** Paints everything already translated and asks Gemini only for what is new. */
    private void renderAndRequest() {
        List<Dialog> shown = new ArrayList<>(), missing = new ArrayList<>();
        for (Dialog d : lastDialogs) {
            String tr = cache.get(d.key);
            if (tr == null) missing.add(d);
            else if (!tr.isEmpty()) { d.translation = tr; shown.add(d); }   // "" = not a dialog: leave the original
        }
        if (shown.isEmpty()) host.hideDialogs(); else host.showDialogs(shown);
        overlayVisible = !shown.isEmpty();
        planAndSend(missing);
    }

    private void planAndSend(List<Dialog> missing) {
        if (missing.isEmpty()) { if (!apiBusy) host.status(""); return; }
        List<BatchPlanner.Item> items = BatchPlanner.plan(missing, k -> cache.get(k) != null, inFlight,
                RealtimeConfig.BATCH_MAX_ITEMS, RealtimeConfig.BATCH_MAX_CHARS);
        if (items.isEmpty()) return;                       // everything is already on its way
        long now = SystemClock.uptimeMillis();
        int usedToday = QuotaTracker.used(appContext);
        if (usedToday >= QuotaTracker.REALTIME_CEILING) {     // keep the last part of the free daily budget for T / camera
            host.status("Kuota harian hampir habis (" + usedToday + "/" + QuotaTracker.DAILY_LIMIT + "), realtime berhenti menerjemahkan "
                    + "sampai ±" + QuotaClock.clock(QuotaClock.nextResetMs(System.currentTimeMillis()), java.time.ZoneId.systemDefault()));
            return;
        }
        if (apiBusy) {
            // A request for an old screen holds the slot: abort it, its answer is no longer needed here.
            boolean needed = false;
            for (Dialog d : missing) if (inFlight.contains(d.key)) { needed = true; break; }
            if (inFlightEpoch != epoch && !needed && token != null) token.cancel();
            return;                                        // retried when it finishes
        }
        long wait = gate.waitMs(now, lastSentAt);
        if (wait > 0) {
            if (gate.isBlocked(now)) host.status(blockedText);
            main.removeCallbacks(retryRunnable);
            main.postDelayed(retryRunnable, wait + 50);
            return;
        }
        send(items, now);
    }

    private String blockedText = "";

    private void send(List<BatchPlanner.Item> items, long now) {
        apiBusy = true;
        QuotaTracker.record(appContext);
        final int reqId = activeReq = ++reqSeq;
        lastSentAt = now;
        inFlightEpoch = epoch;
        inFlight.clear();
        final List<Integer> ids = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (BatchPlanner.Item it : items) { inFlight.add(it.key); ids.add(it.id); texts.add(it.text); }
        final GeminiTranslator.CancelToken tk = new GeminiTranslator.CancelToken();
        token = tk;
        final String context = historyText();
        host.status("Menerjemahkan…");
        apiExec.execute(() -> {
            Map<Integer, String> result = null; Throwable error = null;
            try {
                String key = new SecureKeyStore(appContext).load();
                if (key.isEmpty()) throw new Exception("API key belum disimpan.");
                result = GeminiTranslator.translateBatch(key, ids, texts, context, tk);
            } catch (Throwable t) { error = t; }
            final Map<Integer, String> res = result; final Throwable err = error;
            main.post(() -> onBatchDone(reqId, items, res, err));
        });
    }

    private void onBatchDone(int reqId, List<BatchPlanner.Item> items, Map<Integer, String> result, Throwable error) {
        final boolean current = reqId == activeReq;
        if (current) { apiBusy = false; inFlight.clear(); }
        long now = SystemClock.uptimeMillis();
        if (error != null) {
            if (error instanceof GeminiTranslator.QuotaExceededException) {
                GeminiTranslator.QuotaExceededException q = (GeminiTranslator.QuotaExceededException) error;
                long wall = System.currentTimeMillis();
                if (q.daily) {
                    gate.onQuotaExceeded(now, QuotaClock.nextResetMs(wall) - wall);
                    blockedText = "Kuota harian habis — lanjut ±" + QuotaClock.clock(QuotaClock.nextResetMs(wall), java.time.ZoneId.systemDefault());
                } else {
                    gate.onQuotaExceeded(now, q.retryAfterMs);
                    blockedText = "Batas per menit — jeda " + Math.max(15, (q.retryAfterMs + 999) / 1000) + " dtk";
                }
                if (state == State.RUNNING) host.status(blockedText);
            } else if (!current || error instanceof GeminiTranslator.CancelledException) {
                if (current && state == State.RUNNING) host.status("");
            } else if (error instanceof IOException) {
                gate.onOffline(now); blockedText = "Offline — mencoba lagi";
                if (state == State.RUNNING) host.status(blockedText);
            } else {
                gate.onError(now);
                String m = error.getMessage() == null ? "kesalahan" : error.getMessage();
                blockedText = "Gagal menerjemahkan: " + (m.length() > 60 ? m.substring(0, 60) : m);
                if (state == State.RUNNING) host.status(blockedText);
            }
            if (current && state == State.RUNNING && phase == Phase.SHOWN) renderAndRequest();   // honours the back-off
            return;
        }
        gate.onSuccess();
        for (BatchPlanner.Item it : items) {
            String tr = result.get(it.id);
            if (tr != null) {
                cache.put(it.key, tr);                     // cached even when the screen already moved on
                missCount.remove(it.key);
                if (!tr.isEmpty()) remember(it.text, tr);
            } else {
                int n = missCount.containsKey(it.key) ? missCount.get(it.key) + 1 : 1;
                missCount.put(it.key, n);
                if (n >= 2) cache.put(it.key, "");         // the model keeps skipping it: stop asking
            }
        }
        if (state != State.RUNNING) return;                // cached for next time, nothing to paint now
        if (current) host.status("");
        if (phase == Phase.SHOWN) renderAndRequest();
    }

    private void remember(String source, String translation) {
        String s = source.length() > 100 ? source.substring(0, 100) : source;
        String t = translation.length() > 100 ? translation.substring(0, 100) : translation;
        history.addLast(s + " => " + t);
        while (history.size() > 6) history.removeFirst();
    }

    private String historyText() {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, history.size() - 4);
        int i = 0;
        for (String h : history) { if (i++ >= skip) sb.append(h).append('\n'); }
        return sb.toString();
    }
}
