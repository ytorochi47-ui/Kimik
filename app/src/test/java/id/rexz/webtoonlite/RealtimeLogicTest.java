package id.rexz.webtoonlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Regression tests for the pure-Java logic behind realtime mode. */
public class RealtimeLogicTest {
    private static TextBox box(int l, int t, int r, int b, String text) { return new TextBox(l, t, r, b, 0.9f, text); }

    // ---------------------------------------------------------------- change detection

    private static byte[] frame(int w, int h, int stride, int fromY, int value) {
        ByteBuffer buf = ByteBuffer.allocate(stride * h);
        Arrays.fill(buf.array(), (byte) 100);
        for (int y = fromY; y < h; y++) for (int x = 0; x < w * 4; x++) buf.array()[y * stride + x] = (byte) value;
        return FrameSignature.sample(buf, w, h, 4, stride);
    }

    @Test public void identicalFramesDoNotCountAsChange() {
        byte[] a = frame(720, 1600, 720 * 4 + 64, 1600, 100), b = frame(720, 1600, 720 * 4 + 64, 1600, 100);
        assertEquals(0f, FrameSignature.diff(a, b, null), 0f);
    }

    @Test public void scrollingIsDetectedAndWellAboveTheThreshold() {
        byte[] a = frame(720, 1600, 2944, 1600, 100), b = frame(720, 1600, 2944, 800, 200);
        float d = FrameSignature.diff(a, b, null);
        assertEquals(0.5f, d, 0.02f);
        assertTrue(d > RealtimeConfig.CHANGE_RATIO * 10);
    }

    @Test public void changesInsideOurOwnOverlayAreIgnored() {
        byte[] shown = frame(720, 1600, 2944, 1600, 100), withOverlay = frame(720, 1600, 2944, 800, 200);
        boolean[] mask = FrameSignature.maskFor(new int[][]{{0, 800, 720, 1600}}, 720, 1600, 0);
        assertEquals(0f, FrameSignature.diff(shown, withOverlay, mask), 0f);   // overlay painted over the lower half
        assertTrue(FrameSignature.diff(shown, withOverlay, null) > 0.4f);       // without the mask it would look like a scroll
    }

    @Test public void tinyDifferencesStayBelowTheChangeThreshold() {
        byte[] a = frame(720, 1600, 2944, 1600, 100);
        byte[] b = a.clone();
        for (int i = 0; i < 20; i++) b[i * 7] = (byte) 160;                    // ~0.3 % of the points (cursor, clock...)
        assertTrue(FrameSignature.diff(a, b, null) < RealtimeConfig.CHANGE_RATIO);
    }

    @Test public void nearlyFullyMaskedFrameReportsNoChange() {
        byte[] a = frame(720, 1600, 2944, 1600, 100), b = frame(720, 1600, 2944, 0, 200);
        boolean[] mask = FrameSignature.maskFor(new int[][]{{0, 0, 720, 1580}}, 720, 1600, 0);
        assertEquals(0f, FrameSignature.diff(a, b, mask), 0f);                  // too few points left to judge
    }

    // ---------------------------------------------------------------- grouping

    @Test public void linesOfOneBalloonBecomeOneDialog() {
        List<TextBox> in = new ArrayList<>();
        in.add(box(100, 100, 400, 130, "You can't just leave me here..."));
        in.add(box(110, 136, 390, 166, "I'll find you again."));
        in.add(box(100, 500, 300, 530, "Who are you?"));
        List<Dialog> out = DialogGrouper.group(in, 20);
        assertEquals(2, out.size());
        assertEquals("You can't just leave me here... I'll find you again.", out.get(0).text);
        assertEquals(2, out.get(0).lineCount);
        assertEquals(100, out.get(0).left); assertEquals(100, out.get(0).top);
        assertEquals(400, out.get(0).right); assertEquals(166, out.get(0).bottom);
        assertEquals("Who are you?", out.get(1).text);
    }

    @Test public void wordsSplitByTheDetectorAreJoinedOnOneRow() {
        List<TextBox> in = new ArrayList<>();
        in.add(box(190, 100, 300, 130, "world"));
        in.add(box(100, 101, 180, 131, "Hello"));
        List<Dialog> out = DialogGrouper.group(in, 20);
        assertEquals(1, out.size());
        assertEquals("Hello world", out.get(0).text);
    }

    @Test public void bigSoundEffectIsNotMergedIntoTheDialogNextToIt() {
        List<TextBox> in = new ArrayList<>();
        in.add(box(100, 100, 400, 130, "Don't move."));
        in.add(box(60, 135, 640, 255, "BOOM"));
        assertEquals(2, DialogGrouper.group(in, 20).size());
    }

    @Test public void separateColumnsAreSeparateDialogs() {
        List<TextBox> in = new ArrayList<>();
        in.add(box(20, 100, 200, 130, "Left balloon"));
        in.add(box(480, 104, 700, 134, "Right balloon"));
        assertEquals(2, DialogGrouper.group(in, 20).size());
    }

    @Test public void dialogCountIsCapped() {
        List<TextBox> in = new ArrayList<>();
        for (int i = 0; i < 30; i++) in.add(box(100, 100 + i * 200, 300, 130 + i * 200, "Line " + i));
        assertEquals(20, DialogGrouper.group(in, 20).size());
    }

    @Test public void calibrationShrinksGenerousDetectorBoxesAroundTheirCentre() {
        TextBox raw = box(100, 100, 500, 160, "x");
        TextBox t = BoxCalibration.tighten(raw);
        assertTrue(t.height() < raw.height() && t.width() <= raw.width());
        assertTrue(t.height() >= raw.height() * 0.85f);                       // never cuts into the lettering
        assertEquals(raw.centerY(), t.centerY(), 1f);
    }

    @Test public void nativePayloadIsParsedAndBrokenRecordsSkipped() {
        List<TextBox> l = TextBox.parseNative("10\t20\t110\t60\t912\tHello there\nbroken line\n1\t2\t3\t4\t500\t \n5\t6\t50\t60\t700\tOK");
        assertEquals(2, l.size());
        assertEquals("Hello there", l.get(0).text);
        assertEquals(0.912f, l.get(0).conf, 0.001f);
        assertEquals(50, l.get(1).right);
    }

    // ---------------------------------------------------------------- filtering

    @Test public void onlyRealEnglishLettersAreSentForTranslation() {
        assertTrue(DialogFilter.isTranslatableText("I didn't mean to hurt you."));
        assertTrue(DialogFilter.isTranslatableText("Huh?!"));
        assertFalse(DialogFilter.isTranslatableText("12:30"));
        assertFalse(DialogFilter.isTranslatableText("?!..."));
        assertFalse(DialogFilter.isTranslatableText("https://webtoon.example/read"));
        assertFalse(DialogFilter.isTranslatableText("kimik.example.com"));
        assertFalse(DialogFilter.isTranslatableText("\u3053\u3093\u306b\u3061\u306f\u4e16\u754c"));
    }

    @Test public void dialogsUnderOurOwnButtonsAreDropped() {
        List<Dialog> in = new ArrayList<>();
        in.add(new Dialog(100, 100, 400, 166, "Keep this dialog", 2, 30, 0.9f));
        in.add(new Dialog(600, 300, 700, 340, "Tap here", 1, 30, 0.9f));
        in.add(new Dialog(100, 700, 400, 760, "Low confidence", 1, 30, 0.2f));
        List<Dialog> out = DialogFilter.keep(in, new int[][]{{590, 290, 720, 350}});
        assertEquals(1, out.size());
        assertEquals("Keep this dialog", out.get(0).text);
    }

    // ---------------------------------------------------------------- batching, cache, rate limit

    @Test public void batchContainsOnlyNewDeduplicatedDialogs() {
        List<Dialog> in = new ArrayList<>();
        in.add(new Dialog(0, 0, 10, 10, "Hello", 1, 10, 1f));
        in.add(new Dialog(0, 20, 10, 30, "  hello ", 1, 10, 1f));          // same key
        in.add(new Dialog(0, 40, 10, 50, "Known", 1, 10, 1f));
        in.add(new Dialog(0, 60, 10, 70, "Flying", 1, 10, 1f));
        in.add(new Dialog(0, 80, 10, 90, "World", 1, 10, 1f));
        List<BatchPlanner.Item> items = BatchPlanner.plan(in, k -> k.equals("known"), new HashSet<>(Arrays.asList("flying")), 10, 1200);
        assertEquals(2, items.size());
        assertEquals("hello", items.get(0).key); assertEquals(1, items.get(0).id);
        assertEquals("world", items.get(1).key); assertEquals(2, items.get(1).id);
    }

    @Test public void batchRespectsItemAndCharacterLimits() {
        List<Dialog> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(new Dialog(0, i * 20, 10, i * 20 + 10, "dialog number " + i, 1, 10, 1f));
        assertEquals(12, BatchPlanner.plan(in, k -> false, new HashSet<String>(), 12, 100000).size());
        assertTrue(BatchPlanner.plan(in, k -> false, new HashSet<String>(), 12, 60).size() <= 4);
    }

    @Test public void apiIsRateLimitedAndBacksOffOnQuotaAndOffline() {
        ApiGate g = new ApiGate();
        assertEquals(0, g.waitMs(1000, 0));
        assertEquals(1500, g.waitMs(1500, 1000));                              // 2 s between requests
        g.onQuotaExceeded(5000);
        assertEquals(59000, g.waitMs(6000, 1000));                             // quota: 60 s pause
        assertTrue(g.isBlocked(6000));
        g.onSuccess();
        assertEquals(0, g.waitMs(10000, 1000));

        ApiGate o = new ApiGate();
        o.onOffline(0); assertEquals(4000, o.waitMs(0, 0));
        o.onOffline(0); assertEquals(8000, o.waitMs(0, 0));
        o.onOffline(0); assertEquals(16000, o.waitMs(0, 0));
        o.onOffline(0); assertEquals(30000, o.waitMs(0, 0));                    // capped
    }

    // ---------------------------------------------------------------- colours

    @Test public void backgroundColourIsTheMedianAndTextContrasts() {
        int white = 0xFFF8F8F8, black = 0xFF101010;
        int[] ring = {white, white, white, black, white, white, 0xFFFF0000};   // outline pixels + one stray red
        int bg = ColorMath.median(ring, ring.length);
        assertEquals(0xFFF8F8F8, bg);
        assertEquals(0xFF161616, ColorMath.textColorFor(bg));
        assertEquals(0xFFFFFFFF, ColorMath.textColorFor(0xFF20263A));
    }

    @Test public void dialogIdentityIgnoresPositionButNotSize() {
        Dialog a = new Dialog(100, 100, 400, 166, "Hello there", 2, 30, 1f);
        Dialog moved = a.offset(0, -240);
        Dialog bigger = new Dialog(100, 100, 600, 166, "Hello there", 2, 30, 1f);
        assertEquals(a.areaKey(), moved.areaKey());
        assertFalse(a.areaKey().equals(bigger.areaKey()));
        assertEquals(a.key, bigger.key);                                         // translation cache is by text
    }
}
