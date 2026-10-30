package id.rexz.webtoonlite;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret preferences only; credentials remain in SecureKeyStore. */
final class AppPreferences {
    private final SharedPreferences prefs;
    AppPreferences(Context context) { prefs = context.getSharedPreferences("kimik_preferences", Context.MODE_PRIVATE); }
    boolean useAi() { return prefs.getBoolean("use_ai", true); }
    void useAi(boolean value) { prefs.edit().putBoolean("use_ai", value).apply(); }
    String source() { return prefs.getString("source", "auto"); }
    void source(String value) { prefs.edit().putString("source", value).apply(); }
    static String requireKey(Context context) throws Exception {
        if (!new AppPreferences(context).useAi()) throw new Exception("AI nonaktif. Aktifkan di Pengaturan.");
        String key = new SecureKeyStore(context).load();
        if (key.isEmpty()) throw new Exception("Simpan API key di Pengaturan.");
        return key;
    }
}
