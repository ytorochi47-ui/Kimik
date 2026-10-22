package id.rexz.webtoonlite;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Realtime-mode checks on the real native path: line boxes from PP-OCRv5, calibration against known
 * ground truth, dialog grouping, batch JSON parsing and overlay layout. Logs to tag WebtoonSmoke.
 */
@RunWith(AndroidJUnit4.class)
public class RealtimeSmokeTest {
    private static final String TAG = "WebtoonSmoke";

    private static final class Truth {
        final String text; final Rect r;
        Truth(String text, Rect r) { this.text = text; this.r = r; }
    }

    private static float overlap(Rect a, int l, int t, int r, int b) {
        int w = Math.min(a.right, r) - Math.max(a.left, l), h = Math.min(a.bottom, b) - Math.max(a.top, t);
        return (w <= 0 || h <= 0) ? 0f : (float) (w * h) / (a.width() * a.height());   // share of the truth that is covered
    }

    @Test
    public void realtimeBuildingBlocksWork() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StringBuilder report = new StringBuilder();
        boolean ok = true;
        try {
            // ---- screen with two balloons: a 2-line one (size 40) and a lone line (size 48)
            Bitmap bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setColor(Color.BLACK);
            List<Truth> truth = new ArrayList<>();
            Object[][] spec = {
                    {"You can't just leave me here...", 60, 300, 40f},
                    {"I'll find you again.", 60, 354, 40f},
                    {"WHO ARE YOU?!", 120, 900, 48f},
            };
            for (Object[] s : spec) {
                String text = (String) s[0]; int x = (Integer) s[1], y = (Integer) s[2];
                p.setTextSize((Float) s[3]);
                c.drawText(text, x, y, p);
                Rect b = new Rect(); p.getTextBounds(text, 0, text.length(), b);
                truth.add(new Truth(text, new Rect(x + b.left, y + b.top, x + b.right, y + b.bottom)));
            }

            long t0 = System.nanoTime();
            List<TextBox> raw = NativePaddleOcr.recognizeBoxes(target.getAssets(), bmp, RealtimeConfig.OCR_MAX_SIDE);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            report.append("raw boxes: ").append(raw.size()).append(" in ").append(ms).append(" ms (x86_64 emulator)\n");
            for (TextBox b : raw)
                report.append(String.format(Locale.ROOT, "  RAW  [%d,%d,%d,%d] h=%d conf=%.2f '%s'\n", b.left, b.top, b.right, b.bottom, b.height(), b.conf, b.text));
            boolean found = raw.size() >= 3;
            ok &= found;

            // ---- measure how generous the detector boxes are (drives BoxCalibration constants)
            for (Truth t : truth) {
                TextBox best = null; float bestCover = 0f;
                for (TextBox b : raw) {
                    float cov = overlap(t.r, b.left, b.top, b.right, b.bottom);
                    if (cov > bestCover) { bestCover = cov; best = b; }
                }
                if (best == null) { report.append("  MISS ").append(t.text).append('\n'); ok = false; continue; }
                report.append(String.format(Locale.ROOT, "  CALIB '%s': truth h=%d w=%d | raw h=%d w=%d | hRatio(truth/raw)=%.3f widthPad/rawH=%.3f coverRaw=%.2f\n",
                        t.text, t.r.height(), t.r.width(), best.height(), best.width(),
                        t.r.height() / (float) best.height(), (best.width() - t.r.width()) / (float) best.height(), bestCover));
                TextBox tight = BoxCalibration.tighten(best);
                float coverTight = overlap(t.r, tight.left, tight.top, tight.right, tight.bottom);
                report.append(String.format(Locale.ROOT, "         tightened h=%d w=%d coverTight=%.2f\n", tight.height(), tight.width(), coverTight));
                ok &= coverTight >= 0.85f;                  // calibrated boxes must still cover the lettering
            }

            // ---- grouping on real detector output
            List<Dialog> dialogs = DialogGrouper.group(BoxCalibration.tightenAll(raw), RealtimeConfig.MAX_DIALOGS);
            for (Dialog d : dialogs)
                report.append(String.format(Locale.ROOT, "  DIALOG [%d,%d,%d,%d] lines=%d lh=%d '%s'\n", d.left, d.top, d.right, d.bottom, d.lineCount, d.lineHeight, d.text));
            boolean grouped = dialogs.size() == 2 && dialogs.get(0).lineCount == 2
                    && dialogs.get(0).text.toLowerCase(Locale.ROOT).contains("leave") && dialogs.get(1).text.toUpperCase(Locale.ROOT).contains("WHO");
            ok &= grouped;
            report.append(grouped ? "OK   " : "FAIL ").append("grouping: 2 balloons, first has 2 lines\n");
            for (Dialog d : dialogs) {
                float best = 0f;
                for (Truth t : truth) best = Math.max(best, overlap(t.r, d.left, d.top, d.right, d.bottom));
                report.append(String.format(Locale.ROOT, "  dialog covers %.2f of its best truth line\n", best));
                ok &= best >= 0.85f;
            }
            bmp.recycle();
            NativePaddleOcr.release();
        } catch (Throwable t) {
            ok = false;
            report.append("EXCEPTION ").append(Log.getStackTraceString(t)).append('\n');
        }

        // ---- batch JSON parser (org.json is real here, stubbed on the JVM)
        try {
            Map<Integer, String> a = GeminiTranslator.parseBatch("[{\"id\":1,\"t\":\"Halo\"},{\"id\":2,\"t\":\"\"}]");
            Map<Integer, String> b = GeminiTranslator.parseBatch("```json\n{\"translations\":[{\"id\":3,\"translation\":\"Hai\"}]}\n```");
            boolean parsed = a.size() == 2 && "Halo".equals(a.get(1)) && "".equals(a.get(2)) && b.size() == 1 && "Hai".equals(b.get(3));
            ok &= parsed;
            report.append(parsed ? "OK   " : "FAIL ").append("batch JSON parser\n");
        } catch (Throwable t) {
            ok = false; report.append("EXCEPTION parseBatch ").append(Log.getStackTraceString(t)).append('\n');
        }

        // ---- overlay layout: long Indonesian text must fit inside the screen and keep its font readable
        try {
            final boolean[] layoutOk = {false};
            final StringBuilder lr = new StringBuilder();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TranslationOverlayView v = new TranslationOverlayView(target);
                Dialog d = new Dialog(60, 280, 560, 380, "You can't just leave me here... I'll find you again.", 2, 30, 0.9f);
                d.translation = "Kamu tidak bisa begitu saja meninggalkanku di sini... Aku pasti akan menemukanmu lagi, ke mana pun kamu pergi.";
                Dialog edge = new Dialog(600, 1200, 718, 1278, "Huh?!", 1, 30, 0.9f);
                edge.translation = "Hah?!";
                List<Dialog> list = new ArrayList<>(); list.add(d); list.add(edge);
                v.setDialogs(list, 720, 1280);
                int[][] rects = v.rects();
                boolean inside = rects.length == 2;
                for (int[] r : rects) inside &= r[0] >= 0 && r[1] >= 0 && r[2] <= 721 && r[3] <= 1281 && r[2] > r[0] && r[3] > r[1];
                for (int[] r : rects) lr.append(String.format(Locale.ROOT, "  OVERLAY rect [%d,%d,%d,%d]\n", r[0], r[1], r[2], r[3]));
                layoutOk[0] = inside;
            });
            ok &= layoutOk[0];
            report.append(lr);
            report.append(layoutOk[0] ? "OK   " : "FAIL ").append("overlay layout stays on screen\n");
        } catch (Throwable t) {
            ok = false; report.append("EXCEPTION overlay ").append(Log.getStackTraceString(t)).append('\n');
        }

        for (String line : report.toString().split("\n")) Log.i(TAG, line);
        Log.i(TAG, ok ? "REALTIME RESULT: PASS" : "REALTIME RESULT: FAIL");
        assertTrue(report.toString(), ok);
    }
}
