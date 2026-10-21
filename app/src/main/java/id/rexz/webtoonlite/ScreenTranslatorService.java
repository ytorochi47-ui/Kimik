package id.rexz.webtoonlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight on-demand projection -> ML Kit OCR -> Gemini -> popup. */
public final class ScreenTranslatorService extends Service {
    public static final String ACTION_START = "id.rexz.webtoonlite.START";
    public static final String ACTION_STOP = "id.rexz.webtoonlite.STOP";
    public static final String EXTRA_CODE = "projection_code";
    public static final String EXTRA_DATA = "projection_data";
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL = "webtoonlite_capture";
    private WindowManager wm;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final LruCache<String, String> cache = new LruCache<>(64);
    private final LinkedList<String> history = new LinkedList<>();
    private TextView trigger;
    private WindowManager.LayoutParams triggerParams;
    private SelectionOverlay selector;
    private LinearLayout card;
    private TextView cardBody;
    private String cardResult = "";
    private Bitmap screenshot;
    private int screenWidth, screenHeight;
    private boolean busy;

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        NotificationManager mgr = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Webtoon Lite — tangkap layar", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (projection != null) { toast("Translator sudah aktif."); return START_NOT_STICKY; }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Webtoon Lite aktif")
                .setContentText("Ketuk tombol T untuk menerjemahkan dialog")
                .setContentIntent(pending).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, notification);
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = (Intent)intent.getParcelableExtra(EXTRA_DATA);
            if (code == 0 || data == null) throw new IllegalArgumentException("Izin tangkap layar tidak tersedia.");
            MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("MediaProjection tidak tersedia.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { handler.post(() -> stopSelf()); }
            }, handler);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay("WebtoonLite",
                    screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            // Stop feeding frames while idle; reattach only after user taps T.
            virtualDisplay.setSurface(null);
            showTrigger();
        } catch (Exception e) { toast("Tidak dapat memulai tangkap layar: " + e.getMessage()); stopSelf(); }
        return START_NOT_STICKY;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private WindowManager.LayoutParams overlayParams(int width, int height, int gravity) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity;
        return params;
    }
    private void showTrigger() {
        trigger = new TextView(this);
        trigger.setText("T"); trigger.setGravity(Gravity.CENTER);
        trigger.setTextColor(Color.WHITE); trigger.setTextSize(21); trigger.setTypeface(null, Typeface.BOLD);
        trigger.setBackground(rounded(Color.rgb(28,98,218), 100));
        trigger.setElevation(dp(8));
        triggerParams = overlayParams(dp(52), dp(52), Gravity.TOP | Gravity.LEFT);
        triggerParams.x = screenWidth - dp(70); triggerParams.y = screenHeight / 3;
        trigger.setOnTouchListener(new View.OnTouchListener() {
            private float x0, y0; private int origX, origY; private boolean dragged;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = event.getRawX(); y0 = event.getRawY();
                        origX = triggerParams.x; origY = triggerParams.y; dragged = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - x0, dy = event.getRawY() - y0;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) dragged = true;
                        if (dragged) {
                            triggerParams.x = Math.max(0, Math.min(screenWidth - dp(52), origX + (int)dx));
                            triggerParams.y = Math.max(0, Math.min(screenHeight - dp(52), origY + (int)dy));
                            if (trigger != null) wm.updateViewLayout(trigger, triggerParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) requestCapture();
                        return true;
                    default: return true;
                }
            }
        });
        wm.addView(trigger, triggerParams);
    }

    private void requestCapture() {
        if (busy || reader == null || trigger == null) return;
        busy = true;
        hideCard();
        trigger.setVisibility(View.INVISIBLE);
        try {
            // Discard buffered frames from earlier captures so we translate the current page.
            Image stale = reader.acquireLatestImage();
            if (stale != null) stale.close();
            if (virtualDisplay != null) virtualDisplay.setSurface(reader.getSurface());
        }
        catch (Exception e) { finishWithError("Capture tidak aktif: " + e.getMessage()); return; } // Never OCR the application's own floating button.
        handler.postDelayed(() -> captureAttempt(0), 180);
    }
    private void captureAttempt(int attempt) {
        if (reader == null || !busy) return;
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                if (attempt < 12) handler.postDelayed(() -> captureAttempt(attempt + 1), 100);
                else finishWithError("Layar belum siap ditangkap. Coba ketuk T lagi.");
                return;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            screenshot = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();
            if (virtualDisplay != null) virtualDisplay.setSurface(null);
            showSelector();
        } catch (Exception e) { finishWithError("Screenshot gagal: " + e.getMessage()); }
        finally { if (image != null) image.close(); }
    }
    private void showSelector() {
        selector = new SelectionOverlay(this, new SelectionOverlay.Listener() {
            @Override public void onSelected(Rect rect) { removeSelector(); analyze(rect); }
            @Override public void onCancelled() { removeSelector(); releaseScreenshot(); finish(); }
        });
        WindowManager.LayoutParams p = overlayParams(-1, -1, Gravity.TOP | Gravity.LEFT);
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        wm.addView(selector, p);
    }

    private void analyze(Rect region) {
        final Bitmap frame = screenshot;
        if (frame == null || frame.isRecycled()) { finishWithError("Frame tidak tersedia."); return; }
        region.intersect(0, 0, frame.getWidth(), frame.getHeight());
        if (region.width() < 20 || region.height() < 20) { finishWithError("Kotak terlalu kecil."); return; }
        showCard("Membaca dialog ...");
        Task<Text> task = recognizer.process(InputImage.fromBitmap(frame, 0));
        task.addOnSuccessListener(text -> {
            OCRResult parsed = selectText(text, region);
            if (parsed.target.isEmpty()) {
                // Full-screen recognition can miss stylized small lettering. Retry on crop.
                Bitmap crop = Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height());
                recognizer.process(InputImage.fromBitmap(crop, 0))
                        .addOnSuccessListener(extra -> {
                            String found = extra.getText().trim();
                            if (found.isEmpty()) finishWithError("Tidak ada teks terbaca. Coba kotak yang lebih rapat dan lebih besar.");
                            else sendTranslation(found, parsed.nearby);
                        })
                        .addOnFailureListener(err -> finishWithError("OCR gagal: " + err.getMessage()))
                        .addOnCompleteListener(done -> { crop.recycle(); releaseScreenshot(); });
            } else {
                releaseScreenshot();
                sendTranslation(parsed.target, parsed.nearby);
            }
        }).addOnFailureListener(e -> finishWithError("OCR gagal: " + e.getMessage()))
          .addOnCompleteListener(done -> {
              // Screen bitmap retained through fallback OCR when needed.
              if (!done.isSuccessful()) releaseScreenshot();
          });
    }

    private static final class Line {
        String content; Rect rect;
        Line(String s, Rect r) { content=s; rect=r; }
    }
    private static final class OCRResult { String target="", nearby=""; }
    private OCRResult selectText(Text recognized, Rect selected) {
        ArrayList<Line> lines = new ArrayList<>();
        for (Text.TextBlock block : recognized.getTextBlocks())
            for (Text.Line ln : block.getLines()) {
                Rect r = ln.getBoundingBox();
                if (r != null && !ln.getText().trim().isEmpty()) lines.add(new Line(ln.getText().trim(),r));
            }
        Collections.sort(lines, Comparator.comparingInt((Line l) -> l.rect.top).thenComparingInt(l -> l.rect.left));
        StringBuilder target = new StringBuilder();
        ArrayList<Line> other = new ArrayList<>();
        for (Line l : lines) {
            if (Rect.intersects(selected, l.rect)) {
                if (target.length() > 0) target.append('\n');
                target.append(l.content);
            } else other.add(l);
        }
        // Other visible text is context only, never to be translated automatically.
        final int centerY = selected.centerY();
        other.sort(Comparator.comparingInt(l -> Math.abs(l.rect.centerY() - centerY)));
        ArrayList<Line> near = new ArrayList<>();
        for (Line l : other) {
            if (near.size() >= 5) break;
            if (Math.abs(l.rect.centerY() - centerY) <= dp(390)) near.add(l);
        }
        near.sort(Comparator.comparingInt((Line l) -> l.rect.top).thenComparingInt(l -> l.rect.left));
        StringBuilder vicinity = new StringBuilder();
        for (Line l : near) { if (vicinity.length()>0) vicinity.append('\n'); vicinity.append(l.content); }
        OCRResult result = new OCRResult(); result.target=target.toString(); result.nearby=vicinity.toString();
        return result;
    }

    private void sendTranslation(String target, String context) {
        if (target.trim().isEmpty()) { finishWithError("Teks kosong."); return; }
        final String cacheKey = target.trim() + "\n##" + context.trim();
        String cached = cache.get(cacheKey);
        if (cached != null) { showTranslation(target, cached, true); return; }
        if (cardBody != null) cardBody.setText("Menerjemahkan dengan konteks cerita ...");
        final String previous = historyText();
        network.execute(() -> {
            String translation;
            try {
                String key = new SecureKeyStore(this).load();
                if (key.isEmpty()) throw new Exception("API key belum disimpan.");
                translation = GeminiTranslator.translate(key, target, context, previous);
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "Gagal menghubungi AI." : e.getMessage();
                handler.post(() -> finishWithError(message)); return;
            }
            final String result = translation;
            handler.post(() -> {
                cache.put(cacheKey, result);
                history.addFirst("Inggris: " + target + "\nIndonesia: " + result);
                while (history.size() > 3) history.removeLast();
                showTranslation(target, result, false);
            });
        });
    }
    private String historyText() {
        StringBuilder historyText = new StringBuilder();
        for (String item : history) historyText.append(item).append("\n---\n");
        return historyText.toString();
    }
    private void showTranslation(String source, String result, boolean cached) {
        cardResult = result;
        if (cardBody != null) cardBody.setText((cached ? "⚡ Cache lokal\n\n" : "") + result + "\n\nAsli: " + source);
        finish();
    }
    private void showCard(String content) {
        hideCard();
        cardResult = "";
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(10));
        card.setBackground(rounded(Color.rgb(24,32,49), 18));
        card.setElevation(dp(10));
        TextView heading = new TextView(this); heading.setText("Webtoon Lite");
        heading.setTextColor(Color.rgb(113,207,255)); heading.setTextSize(14);
        heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        cardBody = new TextView(this); cardBody.setText(content); cardBody.setTextColor(Color.WHITE);
        cardBody.setTextSize(16); cardBody.setPadding(0, dp(8), 0, dp(12));
        // Long dialogue must scroll rather than filling the entire phone screen.
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(cardBody);
        card.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this); copy.setText("Salin");
        copy.setOnClickListener(v -> {
            if (!cardResult.isEmpty()) {
                ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Terjemahan webtoon", cardResult));
                toast("Terjemahan disalin.");
            }
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = new Button(this); close.setText("Tutup");
        close.setOnClickListener(v -> hideCard());
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.addView(buttons);
        WindowManager.LayoutParams p = overlayParams(screenWidth - dp(20), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.y = dp(22);
        try { wm.addView(card,p); } catch (Exception e) { card=null; cardBody=null; toast(content); }
    }
    private void finishWithError(String error) {
        try { if (virtualDisplay != null) virtualDisplay.setSurface(null); } catch (Exception ignored) {}
        if (cardBody == null) showCard(error);
        else cardBody.setText(error);
        releaseScreenshot(); finish();
    }
    private void finish() { busy = false; if (trigger != null) trigger.setVisibility(View.VISIBLE); }
    private void releaseScreenshot() {
        if (screenshot != null && !screenshot.isRecycled()) screenshot.recycle(); screenshot=null;
    }
    private void removeSelector() {
        if (selector != null) { safeRemove(selector); selector=null; }
    }
    private void hideCard() {
        if (card != null) { safeRemove(card); card=null; cardBody=null; }
    }
    private void safeRemove(View view) { try { wm.removeView(view); } catch (Exception ignored) { } }
    @Override public void onDestroy() {
        busy=false; removeSelector(); hideCard();
        if(trigger != null) { safeRemove(trigger); trigger=null; }
        releaseScreenshot();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay=null; }
        if (reader != null) { reader.close(); reader=null; }
        if (projection != null) { MediaProjection p=projection; projection=null; p.stop(); }
        if(recognizer != null) recognizer.close();
        network.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
