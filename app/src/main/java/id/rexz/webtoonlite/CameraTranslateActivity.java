package id.rexz.webtoonlite;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Camera mode ("second phone"): live preview + tap-to-focus -> capture (in memory only) ->
 * frozen frame -> user selects the dialog -> only that crop goes through the shared
 * {@link OcrTranslator} pipeline. Needs the CAMERA permission only: no overlay, no MediaProjection.
 */
public final class CameraTranslateActivity extends ComponentActivity {
    /** Longest side of the frozen frame. Keeps small lettering legible while capping RAM (~20 MB). */
    private static final int MAX_FRAME_SIDE = 2560;

    private enum State { PANEL, PREVIEW, CAPTURING, FROZEN, PROCESSING, RESULT }

    private State state = State.PANEL;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private OrientationEventListener orientationListener;
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private OcrTranslator translator;
    private Bitmap frame;
    private int requestId;
    private String lastTranslation = "";

    private PreviewView previewView;
    private View focusRing;
    private TextView hint;
    private Button captureButton;
    private LinearLayout captureBar;
    private FrozenSelectView selectView;
    private LinearLayout frozenBar;
    private LinearLayout statusCard;
    private TextView statusText;
    private LinearLayout resultCard;
    private TextView resultText;
    private Button copyButton;
    private LinearLayout panel;
    private TextView panelText;
    private Button panelButton;
    private Runnable panelAction;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else showPanel("Izin kamera diperlukan untuk Mode Kamera. Foto tidak disimpan ke galeri; "
                        + "hanya area yang kamu pilih yang dibaca.", "Beri izin kamera", this::requestOrOpenSettings);
            });

    private int dp(float d) { return (int) (getResources().getDisplayMetrics().density * d + .5f); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        translator = new OcrTranslator(this);
        buildUi();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state == State.FROZEN || state == State.RESULT || state == State.PROCESSING) backToCamera();
                else finish();
            }
        });
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN || imageCapture == null) return;
                int rotation;
                if (o >= 45 && o < 135) rotation = Surface.ROTATION_270;
                else if (o >= 135 && o < 225) rotation = Surface.ROTATION_180;
                else if (o >= 225 && o < 315) rotation = Surface.ROTATION_90;
                else rotation = Surface.ROTATION_0;
                imageCapture.setTargetRotation(rotation);   // saved photo comes out upright
            }
        };
        if (hasCameraPermission()) startCamera();
        else permissionLauncher.launch(Manifest.permission.CAMERA);
    }

    @Override protected void onStart() {
        super.onStart();
        if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    @Override protected void onStop() {
        if (orientationListener != null) orientationListener.disable();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from system settings after granting the permission there.
        if (state == State.PANEL && cameraProvider == null && hasCameraPermission()) startCamera();
    }

    @Override protected void onDestroy() {
        requestId++;
        if (translator != null) translator.shutdown();
        captureExecutor.shutdown();
        if (selectView != null) selectView.setBitmap(null);
        releaseFrame();
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- permission / camera

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOrOpenSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            // "Don't ask again" (or first denial on some ROMs): only system settings can grant it now.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void startCamera() {
        showState(State.PREVIEW);
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                showPanel("Kamera tidak dapat dibuka: " + (e.getMessage() == null ? "kesalahan tidak diketahui" : e.getMessage()),
                        "Coba lagi", this::startCamera);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null || isFinishing() || isDestroyed()) return;
        try {
            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                showPanel("Kamera belakang tidak tersedia di perangkat ini.", null, null);
                return;
            }
            AspectRatioStrategy ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY;
            Preview preview = new Preview.Builder()
                    .setResolutionSelector(new ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                    .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            // Capped capture size: enough pixels for comic lettering, far below full 48 MP sensors.
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(new ResolutionStrategy(new Size(2560, 1920),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build())
                    .build();
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            captureButton.setEnabled(true);
        } catch (Exception e) {
            showPanel("Gagal menyalakan kamera: " + (e.getMessage() == null ? "kesalahan tidak diketahui" : e.getMessage()),
                    "Coba lagi", this::startCamera);
        }
    }

    private void unbindCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();   // releases the camera while the frame is frozen
        camera = null; imageCapture = null;
    }

    private void focusAt(float x, float y) {
        if (camera == null || state != State.PREVIEW) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
            showFocusRing(x, y, Color.WHITE);
            final ListenableFuture<FocusMeteringResult> result = camera.getCameraControl().startFocusAndMetering(action);
            result.addListener(() -> {
                try {
                    boolean ok = result.get().isFocusSuccessful();
                    showFocusRing(x, y, ok ? Color.rgb(90, 220, 120) : Color.rgb(255, 110, 90));
                } catch (Exception ignored) { /* cancelled by a newer tap or by unbinding */ }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception ignored) { }
    }

    private void showFocusRing(float x, float y, int color) {
        ((GradientDrawable) focusRing.getBackground()).setStroke(dp(2.5f), color);
        focusRing.setTranslationX(x - focusRing.getWidth() / 2f);
        focusRing.setTranslationY(y - focusRing.getHeight() / 2f);
        focusRing.animate().cancel();
        focusRing.setAlpha(1f); focusRing.setScaleX(1.25f); focusRing.setScaleY(1.25f);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .withEndAction(() -> focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300)
                        .withEndAction(() -> focusRing.setVisibility(View.INVISIBLE)).start()).start();
    }

    // ---------------------------------------------------------------- capture

    private void capture() {
        if (state != State.PREVIEW || imageCapture == null) return;
        showState(State.CAPTURING);
        // In-memory capture: no file is ever written to storage.
        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(ImageProxy image) {
                Bitmap bmp = null; String error = null;
                try { bmp = decodeCapture(image); }
                catch (OutOfMemoryError oom) { error = "Memori tidak cukup untuk memproses foto."; }
                catch (Exception e) { error = "Foto gagal diproses: " + e.getMessage(); }
                finally { image.close(); }
                final Bitmap result = bmp; final String err = error;
                runOnUiThread(() -> onCaptured(result, err));
            }
            @Override public void onError(ImageCaptureException e) {
                runOnUiThread(() -> {
                    Toast.makeText(CameraTranslateActivity.this, "Gagal mengambil foto: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (state == State.CAPTURING) showState(State.PREVIEW);
                });
            }
        });
    }

    /** JPEG -> bitmap with the longest side limited and the rotation applied (CameraX reports it separately). */
    private static Bitmap decodeCapture(ImageProxy image) {
        final int rotation = image.getImageInfo().getRotationDegrees();
        Bitmap bmp;
        if (image.getFormat() == ImageFormat.JPEG && image.getPlanes().length == 1) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = OcrPolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_FRAME_SIDE);
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } else {
            bmp = image.toBitmap();
        }
        if (bmp == null) throw new IllegalStateException("Foto tidak dapat didekode.");
        int[] target = OcrPolicy.fitInside(bmp.getWidth(), bmp.getHeight(), MAX_FRAME_SIDE);
        Matrix m = new Matrix();
        if (target[0] != bmp.getWidth()) m.postScale((float) target[0] / bmp.getWidth(), (float) target[1] / bmp.getHeight());
        if (rotation != 0) m.postRotate(rotation);
        if (m.isIdentity()) return bmp;
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    private void onCaptured(Bitmap bmp, String error) {
        if (isFinishing() || isDestroyed() || state != State.CAPTURING) {
            if (bmp != null) bmp.recycle();
            return;
        }
        if (bmp == null) {
            Toast.makeText(this, error == null ? "Foto gagal." : error, Toast.LENGTH_LONG).show();
            showState(State.PREVIEW);
            return;
        }
        releaseFrame();
        frame = bmp;
        unbindCamera();
        selectView.setBitmap(frame);
        showState(State.FROZEN);
    }

    private void releaseFrame() {
        if (selectView != null) selectView.setBitmap(null);   // never draw a recycled bitmap
        if (frame != null && !frame.isRecycled()) frame.recycle();
        frame = null;
    }

    // ---------------------------------------------------------------- select + translate

    private void translateSelection() {
        if (state != State.FROZEN || frame == null || frame.isRecycled()) return;
        Rect r = selectView.getSelectionInBitmap();
        if (r == null || r.width() < 20 || r.height() < 20) {
            Toast.makeText(this, "Seret kotak di sekitar dialog terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height());
            if (crop == frame) crop = frame.copy(Bitmap.Config.ARGB_8888, false);   // the pipeline recycles its crop
        } catch (Exception | OutOfMemoryError e) {
            Toast.makeText(this, "Gagal memotong area terpilih.", Toast.LENGTH_LONG).show();
            return;
        }
        statusText.setText("Membaca teks dengan PP-OCRv5 ...");
        showState(State.PROCESSING);
        final int id = ++requestId;
        translator.translate(crop, new OcrTranslator.Callback() {
            @Override public void onStatus(String message) { if (id == requestId) statusText.setText(message); }
            @Override public void onResult(String source, String translation, boolean fromCache) {
                if (id != requestId) return;
                lastTranslation = translation;
                resultText.setText((fromCache ? "⚡ Cache lokal\n\n" : "") + translation + "\n\nAsli: " + source);
                copyButton.setEnabled(true); copyButton.setAlpha(1f);
                showState(State.RESULT);
            }
            @Override public void onError(String message) {
                if (id != requestId) return;
                lastTranslation = "";
                resultText.setText(message);
                copyButton.setEnabled(false); copyButton.setAlpha(0.5f);
                showState(State.RESULT);
            }
        });
    }

    private void copyTranslation() {
        if (lastTranslation.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Terjemahan webtoon", lastTranslation));
        Toast.makeText(this, "Terjemahan disalin.", Toast.LENGTH_SHORT).show();
    }

    private void selectAnotherArea() {
        if (frame == null || frame.isRecycled()) { backToCamera(); return; }
        requestId++;
        showState(State.FROZEN);
    }

    /** Discards the frozen frame and returns to the live preview. */
    private void backToCamera() {
        requestId++;          // ignore any result still in flight
        lastTranslation = "";
        releaseFrame();
        showState(State.PREVIEW);
        if (cameraProvider != null) bindCamera(); else startCamera();
    }

    // ---------------------------------------------------------------- UI

    private void showState(State s) {
        state = s;
        boolean live = s == State.PREVIEW || s == State.CAPTURING;
        previewView.setVisibility(s == State.PANEL ? View.INVISIBLE : View.VISIBLE);
        hint.setVisibility(live ? View.VISIBLE : View.GONE);
        captureBar.setVisibility(live ? View.VISIBLE : View.GONE);
        captureButton.setEnabled(s == State.PREVIEW && imageCapture != null);
        captureButton.setAlpha(captureButton.isEnabled() ? 1f : 0.5f);
        selectView.setEnabled(s == State.FROZEN);   // no selection edits while translating / showing a result
        selectView.setVisibility(s == State.FROZEN || s == State.PROCESSING || s == State.RESULT ? View.VISIBLE : View.GONE);
        frozenBar.setVisibility(s == State.FROZEN ? View.VISIBLE : View.GONE);
        statusCard.setVisibility(s == State.PROCESSING ? View.VISIBLE : View.GONE);
        resultCard.setVisibility(s == State.RESULT ? View.VISIBLE : View.GONE);
        panel.setVisibility(s == State.PANEL ? View.VISIBLE : View.GONE);
        if (s != State.PREVIEW && s != State.CAPTURING) focusRing.setVisibility(View.INVISIBLE);
    }

    private void showPanel(String message, String buttonLabel, Runnable action) {
        panelText.setText(message);
        panelAction = action;
        panelButton.setText(buttonLabel == null ? "" : buttonLabel);
        panelButton.setVisibility(buttonLabel == null ? View.GONE : View.VISIBLE);
        showState(State.PANEL);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private Button button(String text, int bg) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackground(rounded(bg, 22));
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        final ViewGroup.LayoutParams match = new FrameLayout.LayoutParams(-1, -1);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);   // what you see is what gets captured
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        final float[] down = new float[2];
        previewView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = ev.getX(); down[1] = ev.getY(); }
            else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    && Math.abs(ev.getX() - down[0]) < dp(16) && Math.abs(ev.getY() - down[1]) < dp(16)) {
                focusAt(ev.getX(), ev.getY());
                v.performClick();
            }
            return true;
        });
        root.addView(previewView, match);

        focusRing = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL); ring.setColor(Color.TRANSPARENT); ring.setStroke(dp(2.5f), Color.WHITE);
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.INVISIBLE);
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(68), dp(68)));

        hint = new TextView(this);
        hint.setText("Arahkan ke layar HP pertama  •  ketuk layar untuk fokus");
        hint.setTextColor(Color.WHITE); hint.setTextSize(13); hint.setGravity(Gravity.CENTER);
        hint.setBackground(rounded(Color.argb(140, 0, 0, 0), 14));
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = dp(16);
        root.addView(hint, hintLp);

        captureBar = new LinearLayout(this);
        captureBar.setGravity(Gravity.CENTER);
        captureBar.setPadding(0, dp(12), 0, dp(24));
        captureButton = button("Capture", Color.rgb(28, 98, 218));
        captureButton.setTypeface(null, Typeface.BOLD);
        captureButton.setOnClickListener(v -> capture());
        captureBar.addView(captureButton, new LinearLayout.LayoutParams(dp(170), dp(56)));
        root.addView(captureBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        selectView = new FrozenSelectView(this);
        selectView.setVisibility(View.GONE);
        root.addView(selectView, match);

        frozenBar = new LinearLayout(this);
        frozenBar.setOrientation(LinearLayout.HORIZONTAL);
        frozenBar.setPadding(dp(12), dp(10), dp(12), dp(20));
        frozenBar.setBackgroundColor(Color.argb(170, 0, 0, 0));
        frozenBar.setClickable(true);
        Button retake = button("Ulangi foto", Color.rgb(90, 98, 112));
        retake.setOnClickListener(v -> backToCamera());
        Button go = button("Terjemahkan area", Color.rgb(28, 98, 218));
        go.setOnClickListener(v -> translateSelection());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1);
        half.setMargins(dp(4), 0, dp(4), 0);
        frozenBar.addView(retake, half);
        frozenBar.addView(go, half);
        frozenBar.setVisibility(View.GONE);
        root.addView(frozenBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        statusCard = card();
        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE); statusText.setTextSize(16);
        statusCard.addView(statusText);
        statusCard.setVisibility(View.GONE);
        root.addView(statusCard, cardParams());

        resultCard = card();
        TextView heading = new TextView(this);
        heading.setText("Webtoon Lite"); heading.setTextColor(Color.rgb(113, 207, 255));
        heading.setTextSize(14); heading.setTypeface(null, Typeface.BOLD);
        resultCard.addView(heading);
        resultText = new TextView(this);
        resultText.setTextColor(Color.WHITE); resultText.setTextSize(16);
        resultText.setPadding(0, dp(8), 0, dp(12));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(resultText);
        resultCard.addView(scroller, new LinearLayout.LayoutParams(-1, dp(210)));
        LinearLayout row = new LinearLayout(this);
        copyButton = button("Salin", Color.rgb(28, 98, 218));
        copyButton.setOnClickListener(v -> copyTranslation());
        Button another = button("Area lain", Color.rgb(90, 98, 112));
        another.setOnClickListener(v -> selectAnotherArea());
        Button cam = button("Kembali ke kamera", Color.rgb(90, 98, 112));
        cam.setOnClickListener(v -> backToCamera());
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0, dp(46), 1);
        third.setMargins(dp(3), 0, dp(3), 0);
        row.addView(copyButton, third); row.addView(another, third); row.addView(cam, third);
        resultCard.addView(row);
        resultCard.setVisibility(View.GONE);
        root.addView(resultCard, cardParams());

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setBackgroundColor(Color.rgb(24, 32, 49));
        panelText = new TextView(this);
        panelText.setTextColor(Color.WHITE); panelText.setTextSize(16); panelText.setGravity(Gravity.CENTER);
        panelText.setPadding(0, 0, 0, dp(18));
        panel.addView(panelText);
        panelButton = button("", Color.rgb(28, 98, 218));
        panelButton.setOnClickListener(v -> { if (panelAction != null) panelAction.run(); });
        panel.addView(panelButton, new LinearLayout.LayoutParams(dp(220), dp(50)));
        panel.setVisibility(View.GONE);
        root.addView(panel, match);

        setContentView(root);
        showState(State.PANEL);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(12));
        c.setBackground(rounded(Color.rgb(24, 32, 49), 18));
        c.setElevation(dp(10));
        c.setClickable(true);   // swallow touches so they never reach the selection view underneath
        return c;
    }

    private FrameLayout.LayoutParams cardParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(10), 0, dp(10), dp(16));
        return lp;
    }
}
