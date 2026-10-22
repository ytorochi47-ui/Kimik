package id.rexz.webtoonlite;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/** Turns an RGBA_8888 {@link Image} from the screen-capture ImageReader into a Bitmap (both modes). */
final class FrameGrabber {
    private FrameGrabber() { }

    /**
     * Bitmap with the reader's row padding kept (width may exceed the screen width); x/y origins
     * match the screen, so callers can crop with screen coordinates directly.
     */
    static Bitmap padded(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap shot = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        shot.copyPixelsFromBuffer(buffer);
        return shot;
    }

    /** Exactly screen-width bitmap without the top {@code cropTop} rows (status bar). The caller owns it. */
    static Bitmap cropped(Image image, int cropTop) {
        Bitmap shot = padded(image);
        int w = image.getWidth(), h = image.getHeight();
        int top = Math.max(0, Math.min(cropTop, h - 16));
        if (shot.getWidth() == w && top == 0) return shot;
        try {
            return Bitmap.createBitmap(shot, 0, top, w, h - top);
        } finally {
            shot.recycle();
        }
    }
}
