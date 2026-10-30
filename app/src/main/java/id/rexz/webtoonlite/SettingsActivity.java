package id.rexz.webtoonlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.View;
import android.widget.*;

/** Credential entry is never prefilled or saved in the Activity instance state. */
public final class SettingsActivity extends Activity {
    private EditText input;
    private TextView keyStatus;
    private SecureKeyStore store;
    private AppPreferences prefs;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store=new SecureKeyStore(this); prefs=new AppPreferences(this);
        LinearLayout root=UiStyle.page(this);
        UiStyle.button(this,root,"‹  Pengaturan",false).setOnClickListener(v -> finish());
        LinearLayout keyCard=UiStyle.card(this,root);
        keyCard.addView(UiStyle.text(this,"API Key",20,true));
        keyStatus=UiStyle.text(this,"",14,false); keyCard.addView(keyStatus); refreshKey();
        input=new EditText(this); input.setSingleLine(true); input.setHint("Masukkan API key Gemini");
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setMinHeight(UiStyle.dp(this,52)); keyCard.addView(input);
        UiStyle.button(this,keyCard,"Simpan",true).setOnClickListener(v -> save());
        UiStyle.button(this,keyCard,"Ganti",false).setOnClickListener(v -> {
            input.setText(""); input.requestFocus();
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        UiStyle.button(this,keyCard,"Hapus",false).setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Hapus API key?").setMessage("Translator akan dihentikan.")
            .setNegativeButton("Batal",null).setPositiveButton("Hapus",(d,w) -> {
                try { store.delete(); stopTranslator(); input.setText(""); refreshKey(); }
                catch(Exception e) { message("Gagal menghapus key. Coba lagi."); }
            }).show());
        keyCard.addView(UiStyle.text(this,"Tersimpan terenkripsi di perangkat. Dipakai otomatis saat AI aktif.",13,false));
        LinearLayout preferences=UiStyle.card(this,root); preferences.addView(UiStyle.text(this,"Preferensi",20,true));
        Switch useAi=new Switch(this); useAi.setText("Gunakan AI tersimpan"); useAi.setTextColor(UiStyle.INK);
        useAi.setMinHeight(UiStyle.dp(this,56)); useAi.setChecked(prefs.useAi()); preferences.addView(useAi);
        useAi.setOnCheckedChangeListener((b,checked) -> { prefs.useAi(checked); stopTranslator(); });
        preferences.addView(UiStyle.text(this,"Bahasa sumber",15,true));
        Spinner source=new Spinner(this); String[] values={"auto","en","ko"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"Otomatis","Inggris","Korea"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); source.setAdapter(adapter);
        source.setSelection("en".equals(prefs.source())?1:"ko".equals(prefs.source())?2:0);
        source.setMinimumHeight(UiStyle.dp(this,52)); source.setContentDescription("Bahasa sumber"); preferences.addView(source);
        source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View v,int position,long id) {
                if(!values[position].equals(prefs.source())) { prefs.source(values[position]); stopTranslator(); }
            }
        });
        preferences.addView(UiStyle.text(this,"Tujuan: Indonesia. Model OCR Korea diunduh sekali melalui Google Play Services. Perubahan berlaku saat translator dimulai kembali.",13,false));
        UiStyle.fade(preferences);
    }
    private void save() {
        String key=input.getText().toString().trim();
        if(key.length()<15) { message("Masukkan API key yang valid."); return; }
        try { store.save(key); stopTranslator(); input.setText(""); refreshKey(); message("API key tersimpan."); }
        catch(Exception e) { message("Gagal menyimpan key secara aman. Coba lagi."); }
    }
    private void refreshKey() {
        try { keyStatus.setText(store.load().isEmpty()?"Belum ada key":"Key tersimpan"); }
        catch(Exception e) { keyStatus.setText("Key tidak dapat dibaca. Simpan ulang."); }
    }
    private void stopTranslator() { stopService(new Intent(this,ScreenTranslatorService.class)); }
    private void message(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    @Override public void finish() { super.finish(); overridePendingTransition(R.anim.fade_in,R.anim.fade_out); }
}
