package id.rexz.webtoonlite;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for the pure-Java parts shared by both modes. */
public class CropMathTest {
    @Test public void fullViewSelectionMapsToWholeBitmap() {
        ImageFit f = ImageFit.of(2000, 1500, 1000, 750);   // exact 2:1 downscale, no letterbox
        assertArrayEquals(new int[]{0, 0, 2000, 1500}, f.viewRectToBitmap(0, 0, 1000, 750));
    }

    @Test public void letterboxOffsetIsRemoved() {
        // 1600x1200 bitmap in a 1080x2000 view: width-limited, centred vertically.
        ImageFit f = ImageFit.of(1600, 1200, 1080, 2000);
        assertEquals(0.675f, f.scale, 1e-4f);
        assertEquals(0f, f.dx, 1e-3f);
        assertEquals((2000 - 1200 * 0.675f) / 2f, f.dy, 1e-3f);
        // A box over the exact middle of the image.
        float vx = f.toViewX(400), vy = f.toViewY(300), vx2 = f.toViewX(1200), vy2 = f.toViewY(900);
        int[] r = f.viewRectToBitmap(vx, vy, vx2, vy2);
        assertEquals(400, r[0], 1); assertEquals(300, r[1], 1);
        assertEquals(1200, r[2], 1); assertEquals(900, r[3], 1);
    }

    @Test public void selectionInBlackBarsIsClampedToBitmap() {
        ImageFit f = ImageFit.of(1600, 1200, 1080, 2000);
        int[] r = f.viewRectToBitmap(-50, -50, 5000, 5000);
        assertArrayEquals(new int[]{0, 0, 1600, 1200}, r);
    }

    @Test public void roundTripKeepsPixelsAndNeverShrinksTheBox() {
        ImageFit f = ImageFit.of(2560, 1920, 1080, 1800);
        int[] r = f.viewRectToBitmap(f.toViewX(123.4f), f.toViewY(77.7f), f.toViewX(900.2f), f.toViewY(640.9f));
        assertTrue(r[0] <= 123 && r[1] <= 77);      // rounds outwards
        assertTrue(r[2] >= 901 && r[3] >= 640);
    }

    @Test public void reversedCornersAreNormalised() {
        ImageFit f = ImageFit.of(1000, 1000, 500, 500);
        assertArrayEquals(f.viewRectToBitmap(100, 100, 300, 300), f.viewRectToBitmap(300, 300, 100, 100));
    }

    @Test public void fitInsideOnlyShrinksLargeCrops() {
        assertArrayEquals(new int[]{300, 120}, OcrPolicy.fitInside(300, 120, 1600));
        assertArrayEquals(new int[]{1600, 1600}, OcrPolicy.fitInside(1600, 1600, 1600));
        assertArrayEquals(new int[]{1600, 800}, OcrPolicy.fitInside(3200, 1600, 1600));
        assertArrayEquals(new int[]{800, 1600}, OcrPolicy.fitInside(1200, 2400, 1600));
    }

    @Test public void sampleSizeKeepsTargetResolution() {
        assertEquals(1, OcrPolicy.sampleSizeFor(2560, 1920, 2560));
        assertEquals(1, OcrPolicy.sampleSizeFor(4000, 3000, 2560));   // 2000 would be below target
        assertEquals(2, OcrPolicy.sampleSizeFor(8000, 6000, 2560));   // 4000 >= 2560
        assertEquals(4, OcrPolicy.sampleSizeFor(12000, 9000, 2560));
    }

    @Test public void ocrAcceptanceRuleMatchesTheOriginalOne() {
        assertTrue(OcrPolicy.looksGood("I didn't mean to hurt you.", 0.9f));
        assertFalse(OcrPolicy.looksGood("I didn't mean to hurt you.", 0.3f));   // low confidence -> ML Kit
        assertFalse(OcrPolicy.looksGood("... !?", 0.99f));                       // no words -> ML Kit
        assertFalse(OcrPolicy.looksGood(null, 1f));
        assertTrue(OcrPolicy.hasContent("ok"));
        assertFalse(OcrPolicy.hasContent("?!  ..."));
        assertFalse(OcrPolicy.hasContent(""));
    }

    @Test public void cacheKeyIgnoresCaseAndWhitespace() {
        assertEquals(OcrPolicy.normalizeKey("  Hello   WORLD \n"), OcrPolicy.normalizeKey("hello world"));
    }
}
