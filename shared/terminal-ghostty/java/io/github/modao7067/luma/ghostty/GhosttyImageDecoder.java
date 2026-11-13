package io.github.modao7067.luma.ghostty;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

public final class GhosttyImageDecoder {
    private static final long MAX_PIXELS = 4L * 1024 * 1024;
    private GhosttyImageDecoder() {}
    public static int[] decodePng(byte[] data) { return decodeImage(data, 0, 0, true); }
    public static int[] decodeBase64Image(byte[] encoded, int width, int height, boolean preserve) {
        try { return decodeImage(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), width, height, preserve); }
        catch (IllegalArgumentException | OutOfMemoryError invalidImage) { return null; }
    }
    private static int[] decodeImage(byte[] data, int requestedWidth, int requestedHeight, boolean preserve) {
        if (data == null || data.length > 8 * 1024 * 1024) return null;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0 || (long)width * height > MAX_PIXELS) return null;
            int targetWidth = requestedWidth > 0 ? requestedWidth : width;
            int targetHeight = requestedHeight > 0 ? requestedHeight : height;
            if (requestedWidth > 0 && requestedHeight <= 0) targetHeight = Math.max(1, Math.round((float)height * targetWidth/width));
            else if (requestedHeight > 0 && requestedWidth <= 0) targetWidth = Math.max(1, Math.round((float)width * targetHeight/height));
            else if (preserve && requestedWidth > 0 && requestedHeight > 0) {
                float scale = Math.min((float)targetWidth/width,(float)targetHeight/height);
                targetWidth = Math.max(1,Math.round(width*scale)); targetHeight = Math.max(1,Math.round(height*scale));
            }
            if (targetWidth <= 0 || targetHeight <= 0 || (long)targetWidth*targetHeight > MAX_PIXELS) return null;
            options.inJustDecodeBounds = false; options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) return null;
            if (width != targetWidth || height != targetHeight) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,targetWidth,targetHeight,true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }
            int[] result = new int[2 + targetWidth*targetHeight]; result[0] = targetWidth; result[1] = targetHeight;
            bitmap.getPixels(result, 2, targetWidth, 0, 0, targetWidth, targetHeight); return result;
        } catch (RuntimeException | OutOfMemoryError invalidImage) { return null; }
        finally { if (bitmap != null) bitmap.recycle(); }
    }
}
