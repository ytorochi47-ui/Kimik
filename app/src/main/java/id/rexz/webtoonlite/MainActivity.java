package id.rexz.webtoonlite;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int CAPTURE = 203, OVERLAY = 205;
    private Button ai, start, stop;
    private TextView status;
    private String waiting = "";
    private boolean notificationAsked;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) waiting = state.getString("waiting", "");
        LinearLayout root = UiStyle.page(this);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(UiStyle.text(this,"Kimik",20,true), new LinearLayout.LayoutParams(0,-2,1));
        Button settings = new Button(this); settings.setText("⚙"); settings.setContentDescription("Pengaturan");
        bar.addView(settings,new LinearLayout.LayoutParams(UiStyle.dp(this,56),UiStyle.dp(this,56)));
        settings.setOnClickListener(v -> openSettings()); root.addView(bar);
        LinearLayout brand = UiStyle.card(this,root); brand.setGravity(Gravity.CENTER);
        ImageView mascot = new ImageView(this); mascot.setImageResource(R.drawable.capybara);
        mascot.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mascot,new LinearLayout.LayoutParams(UiStyle.dp(this,120),UiStyle.dp(this,120)));
        brand.addView(UiStyle.text(this,"Kimik",28,true));
        brand.addView(UiStyle.text(this,"Baca santai, pahami ceritanya.",14,false));
        ai = UiStyle.button(this,root,"AI Nonaktif",false); ai.setOnClickListener(v -> openSettings());
        start = UiStyle.button(this,root,"Mulai Translator",true); start.setOnClickListener(v -> begin());
        stop = UiStyle.button(this,root,"Hentikan Translator",false);
        stop.setOnClickListener(v -> { stopService(new Intent(this,ScreenTranslatorService.class)); waiting=""; refresh(); });
        LinearLayout card = UiStyle.card(this,root);
        card.addView(UiStyle.text(this,"STATUS TRANSLATOR",12,true));
        status = UiStyle.text(this,"Belum aktif",18,true); status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); card.addView(status);
        card.addView(UiStyle.text(this,"T · Pilih dialog    R · Realtime",14,false)); UiStyle.fade(card);
        IntentFilter filter = new IntentFilter(ScreenTranslatorService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver,filter);
    }
    private void openSettings() {
        startActivity(new Intent(this,SettingsActivity.class)); overridePendingTransition(R.anim.fade_in,R.anim.fade_out);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onSaveInstanceState(Bundle b) { b.putString("waiting",waiting); super.onSaveInstanceState(b); }
    @Override protected void onDestroy() { unregisterReceiver(stateReceiver); super.onDestroy(); }
    private void refresh() {
        boolean enabled;
        try { enabled = new AppPreferences(this).useAi() && !new SecureKeyStore(this).load().isEmpty(); }
        catch (Exception e) { enabled=false; }
        ai.setText(enabled ? "AI Aktif" : "AI Nonaktif");
        boolean running = ScreenTranslatorService.isRunning();
        start.setEnabled(!running && waiting.isEmpty()); stop.setEnabled(running);
        UiStyle.status(status,running ? "Aktif" : waiting.isEmpty() ? "Belum aktif" : waiting);
    }
    private void begin() {
        try { AppPreferences.requireKey(this); }
        catch (Exception e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show(); openSettings(); return; }
        if (Build.VERSION.SDK_INT >= 33 && !notificationAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked=true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},204);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            waiting="Menunggu izin overlay"; refresh();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY); return;
        }
        waiting="Menunggu izin tangkap layar"; refresh();
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(),CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request==204) begin(); // Notification permission is optional for capture.
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==OVERLAY) {
            waiting="";
            if(Settings.canDrawOverlays(this)) begin();
            else Toast.makeText(this,"Izin overlay belum diberikan.",Toast.LENGTH_SHORT).show();
        }
        if(request==CAPTURE) {
            waiting="";
            if(result==RESULT_OK && data!=null) {
                Intent service=new Intent(this,ScreenTranslatorService.class).setAction(ScreenTranslatorService.ACTION_START);
                service.putExtra(ScreenTranslatorService.EXTRA_CODE,result).putExtra(ScreenTranslatorService.EXTRA_DATA,data);
                startForegroundService(service);
            } else Toast.makeText(this,"Izin tangkap layar dibatalkan.",Toast.LENGTH_SHORT).show();
        }
        refresh();
    }
}
