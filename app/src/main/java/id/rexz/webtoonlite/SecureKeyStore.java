package id.rexz.webtoonlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Non-exportable Android Keystore key encrypts the stored Gemini credential. */
public final class SecureKeyStore {
    private static final String ALIAS = "webtoonlite_gemini_aes_1";
    private final SharedPreferences prefs;
    public SecureKeyStore(Context c) { prefs = c.getSharedPreferences("private_credentials", Context.MODE_PRIVATE); }

    private SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            gen.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS, null)).getSecretKey();
    }
    public void save(String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] ciphertext = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("cipher", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }
    public String load() throws Exception {
        String ct = prefs.getString("cipher", "");
        String iv = prefs.getString("iv", "");
        if (ct.isEmpty() || iv.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
