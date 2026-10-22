package id.rexz.webtoonlite;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * Smoke test for the real native path: assets -> JNI -> ncnn -> PP-OCRv5 -> UTF-8 text.
 * Results go to logcat tag WebtoonSmoke so CI can print them.
 */
@RunWith(AndroidJUnit4.class)
public class NativeOcrSmokeTest {
    private static final String TAG = "WebtoonSmoke";
    private static final String[] MODEL_FILES = {
            "PP_OCRv5_mobile_det.ncnn.param", "PP_OCRv5_mobile_det.ncnn.bin",
            "PP_OCRv5_mobile_rec.ncnn.param", "PP_OCRv5_mobile_rec.ncnn.bin"};

    private static Bitmap render(String[] lines, boolean invert, boolean outline) {
        int textSize = 40, pad = 28, lineH = 62;
        Bitmap bmp = Bitmap.createBitmap(640, pad * 2 + lineH * lines.length, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(invert ? Color.rgb(20, 20, 26) : Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(textSize);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < lines.length; i++) {
            float y = pad + lineH * i + textSize;
            if (outline) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6f); p.setColor(Color.BLACK);
                c.drawText(lines[i], 320, y, p);
                p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE);
                c.drawText(lines[i], 320, y, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(invert ? Color.WHITE : Color.BLACK);
                c.drawText(lines[i], 320, y, p);
            }
        }
        return bmp;
    }

    private static String norm(String s) { return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    private static int lev(String a, String b) {
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0]; d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = d[j];
                d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = tmp;
            }
        }
        return d[b.length()];
    }

    @Test
    public void nativeOcrReadsEnglishDialogue() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        try {
            // 1) Models must be stored uncompressed in the APK (memory-mappable).
            for (String f : MODEL_FILES) {
                try (AssetFileDescriptor fd = target.getAssets().openFd(f)) {
                    report.append("asset ").append(f).append(" uncompressed, ").append(fd.getLength()).append(" bytes\n");
                } catch (Exception e) {
                    ok = false; report.append("asset ").append(f).append(" NOT uncompressed/readable: ").append(e).append('\n');
                }
            }

            // 2) Real OCR on rendered dialogue (clean, white-on-dark, outlined).
            Object[][] cases = {
                    {new String[]{"I DIDN'T MEAN TO HURT YOU."}, false, false},
                    {new String[]{"You can't just leave me here...", "I'll find you again."}, false, false},
                    {new String[]{"WHO ARE YOU?!"}, true, false},
                    {new String[]{"Run! They're coming!"}, false, true},
            };
            long nativeBefore = Debug.getNativeHeapAllocatedSize();
            long worstMs = 0;
            for (Object[] cs : cases) {
                String[] lines = (String[]) cs[0];
                Bitmap bmp = render(lines, (Boolean) cs[1], (Boolean) cs[2]);
                long t0 = System.nanoTime();
                NativePaddleOcr.Result r = NativePaddleOcr.recognize(target.getAssets(), bmp);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                worstMs = Math.max(worstMs, ms);
                bmp.recycle();
                String want = norm(String.join("", lines)), got = norm(r.text);
                int dist = lev(want, got);
                // Row structure: words on one line must not be split by newlines.
                int newlines = r.text.length() - r.text.replace("\n", "").length();
                boolean pass = dist <= Math.max(2, want.length() / 8) && newlines == lines.length - 1;
                ok &= pass;
                report.append(pass ? "OK   " : "FAIL ").append(ms).append(" ms conf=").append(String.format(Locale.ROOT, "%.2f", r.confidence))
                      .append(" dist=").append(dist).append(" rows=").append(newlines + 1).append(" | want=").append(String.join(" / ", lines))
                      .append(" | got=").append(r.text.replace('\n', '/')).append('\n');
            }
            long nativeAfter = Debug.getNativeHeapAllocatedSize();
            NativePaddleOcr.release();
            long nativeReleased = Debug.getNativeHeapAllocatedSize();
            report.append(String.format(Locale.ROOT, "native heap: before=%d KB, with models=%d KB, after release=%d KB, slowest OCR=%d ms (x86_64 emulator, not a phone)\n",
                    nativeBefore / 1024, nativeAfter / 1024, nativeReleased / 1024, worstMs));

            // 3) Engine must survive release + reuse, and tolerate junk input.
            Bitmap blank = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.rgb(200, 200, 200));
            NativePaddleOcr.Result empty = NativePaddleOcr.recognize(target.getAssets(), blank);
            boolean emptyOk = empty.text.isEmpty();
            ok &= emptyOk;
            report.append(emptyOk ? "OK   " : "FAIL ").append("blank image -> '").append(empty.text).append("'\n");
        } catch (Throwable t) {
            ok = false;
            report.append("EXCEPTION ").append(Log.getStackTraceString(t)).append('\n');
        }
        for (String line : report.toString().split("\n")) Log.i(TAG, line);
        Log.i(TAG, ok ? "SMOKE RESULT: PASS" : "SMOKE RESULT: FAIL");
        assertTrue(report.toString(), ok);
    }
}
