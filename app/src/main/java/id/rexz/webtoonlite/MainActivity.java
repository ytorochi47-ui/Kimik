package id.rexz.webtoonlite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 203;
    private static final int REQUEST_NOTIF = 204;
    private EditText keyField;
    private SecureKeyStore keyStore;

    private int dp(float d) { return (int)(getResources().getDisplayMetrics().density * d + .5f); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        keyStore = new SecureKeyStore(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(32), dp(22), dp(20));
        root.setBackgroundColor(Color.rgb(247,249,253));
        scroll.addView(root);

        TextView title = label("Webtoon Lite", 28, true);
        root.addView(title);
        root.addView(label("Terjemahkan dialog Inggris → Indonesia hanya saat kamu memilihnya.", 15, false));
        spacer(root);
        root.addView(label("Gemini API Key (Free Tier)", 17, true));
        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setHint("Tempel API key dari Google AI Studio");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        try { keyField.setText(keyStore.load()); }
        catch (Exception e) { Toast.makeText(this, "Kunci tersimpan tidak dapat dibaca; masukkan kembali.", Toast.LENGTH_LONG).show(); }
        root.addView(keyField);
        root.addView(label("Gunakan project Free Tier tanpa mengaktifkan billing. Kunci disimpan terenkripsi di perangkat.", 13, false));
        spacer(root);
        Button openKey = button("Buka Google AI Studio", root);
        openKey.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"))));
        TextView apiStatus = label("Status API: belum diuji di perangkat.", 13, false);
        root.addView(apiStatus);
        Button test = button("Tes koneksi Gemini", root);
        test.setOnClickListener(v -> testApi(test, apiStatus));
        spacer(root);
        root.addView(label("Pilih mode", 20, true));

        // ---- Mode Screenshot (existing behaviour, unchanged) ----
        root.addView(label("Mode Screenshot & Realtime", 17, true));
        root.addView(label("Dua tombol melayang di layar HP ini: T = pilih dialog sendiri, R = terjemahan realtime otomatis.", 13, false));
        Button start = button("Mulai translator", root);
        start.setOnClickListener(v -> startTranslate());
        Button stop = button("Hentikan translator", root);
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScreenTranslatorService.class);
            intent.setAction(ScreenTranslatorService.ACTION_STOP);
            startService(intent);
        });
        root.addView(label("1. Masukkan API key.\n2. Tekan Mulai dan beri izin overlay + tangkap layar.\n3. Buka webtoon di Chrome/Webtoon.\n4. Ketuk ikon T yang melayang.\n5. Seret kotak mengelilingi dialog yang sulit.\n6. Terjemahan muncul dalam popup.\n\nMode Realtime: ketuk R. Setelah layar berhenti bergerak sekitar setengah detik, dialog dibaca dan diganti terjemahan Indonesia di posisinya; saat digulir, terjemahan disembunyikan lalu muncul lagi. Tombol jeda/lanjut dan stop ada di samping R.\n\nKetuk Stop untuk mematikan penangkapan layar.", 14, false));

        // ---- Mode Kamera (second phone) ----
        spacer(root);
        root.addView(label("Mode Kamera (HP kedua)", 17, true));
        root.addView(label("Arahkan kamera belakang ke layar HP lain, ambil foto, lalu pilih dialognya. Hanya butuh izin kamera; foto tidak disimpan.", 13, false));
        Button camera = button("Buka Mode Kamera", root);
        camera.setOnClickListener(v -> openCameraMode());
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIF);
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(35,42,54));
        t.setPadding(0, dp(5), 0, dp(10));
        if(bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }
    private void spacer(LinearLayout root) { View v = new View(this); root.addView(v, new LinearLayout.LayoutParams(1, dp(13))); }
    private Button button(String name, LinearLayout root) {
        Button b = new Button(this); b.setText(name); root.addView(b,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))); return b;
    }

    private void testApi(Button button, TextView status) {
        final String key = keyField.getText().toString().trim();
        if (key.length() < 15) {
            status.setText("Isi API key Gemini terlebih dahulu.");
            return;
        }
        button.setEnabled(false);
        status.setText("Menghubungi Gemini ...");
        new Thread(() -> {
            String resultMessage;
            try {
                String answer = GeminiTranslator.translate(key, "I didn't mean to hurt you.", "");
                resultMessage = "Gemini tersambung. Contoh: " + answer;
            } catch (Exception e) {
                resultMessage = "Tes gagal: " + (e.getMessage() == null ? "Periksa internet dan API key." : e.getMessage());
            }
            final String message = resultMessage;
            runOnUiThread(() -> {
                button.setEnabled(true);
                status.setText(message);
            });
        }, "gemini-api-check").start();
    }

    /** Validates and stores the key (encrypted). Shared by both modes; shows its own error toast. */
    private boolean saveKeyOrWarn() {
        String key = keyField.getText().toString().trim();
        if (key.length() < 15) { Toast.makeText(this, "Masukkan kunci API Gemini milikmu dahulu.", Toast.LENGTH_LONG).show(); return false; }
        try { keyStore.save(key); }
        catch (Exception ex) { Toast.makeText(this, "Gagal menyimpan kunci secara aman: " + ex.getMessage(), Toast.LENGTH_LONG).show(); return false; }
        return true;
    }

    /** Camera mode needs only the CAMERA permission (asked inside the camera screen): no overlay, no projection. */
    private void openCameraMode() {
        if (!saveKeyOrWarn()) return;
        startActivity(new Intent(this, CameraTranslateActivity.class));
    }

    private void startTranslate() {
        if (!saveKeyOrWarn()) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan izin tampil di atas aplikasi lain, lalu kembali dan tekan Mulai lagi.", Toast.LENGTH_LONG).show();
            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(overlay);
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenTranslatorService.class);
            service.setAction(ScreenTranslatorService.ACTION_START);
            service.putExtra(ScreenTranslatorService.EXTRA_CODE, resultCode);
            service.putExtra(ScreenTranslatorService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            Toast.makeText(this, "Translator aktif. Buka webtoon dan ketuk tombol T.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
    }
}
