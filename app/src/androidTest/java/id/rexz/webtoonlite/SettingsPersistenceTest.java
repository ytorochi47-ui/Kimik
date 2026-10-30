package id.rexz.webtoonlite;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsPersistenceTest {
    @Test public void keyReplacementDeletionAndPreferencePersistence() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecureKeyStore store = new SecureKeyStore(context);
        AppPreferences prefs = new AppPreferences(context);
        String original = store.load(), source = prefs.source(); boolean ai = prefs.useAi();
        try {
            String first = "test-credential-one-not-a-real-key";
            store.save(first);
            assertEquals(first, new SecureKeyStore(context).load());
            String disk = context.getSharedPreferences("private_credentials", Context.MODE_PRIVATE).getString("cipher", "");
            assertFalse(disk.contains(first));
            store.save("test-credential-two-not-a-real-key");
            prefs.useAi(true); prefs.source("ko");
            assertEquals("test-credential-two-not-a-real-key", AppPreferences.requireKey(context));
            assertEquals("ko", new AppPreferences(context).source());
            prefs.useAi(false);
            try { AppPreferences.requireKey(context); fail("Disabled AI must reject requests"); }
            catch (Exception expected) { assertTrue(expected.getMessage().contains("nonaktif")); }
            store.delete(); assertEquals("", new SecureKeyStore(context).load());
        } finally {
            if (original.isEmpty()) store.delete(); else store.save(original);
            prefs.useAi(ai); prefs.source(source);
        }
    }
}
