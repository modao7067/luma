package io.github.modao7067.luma.shared;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;

final class WallpaperImages {
    static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    static final long MAX_PIXELS = 4L * 1024 * 1024;

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException(com.termux.terminal.LumaLang.tr("无法识别图片尺寸", "Unable to read image dimensions"));
        int sample = 1;
        int limitWidth = Math.max(1, Math.min(4096, targetWidth));
        int limitHeight = Math.max(1, Math.min(4096, targetHeight));
        while (true) {
            long scaledWidth = ((long) width + sample - 1) / sample;
            long scaledHeight = ((long) height + sample - 1) / sample;
            if (scaledWidth * scaledHeight <= MAX_PIXELS && scaledWidth <= limitWidth && scaledHeight <= limitHeight) break;
            if (sample >= 1 << 29) throw new IOException(com.termux.terminal.LumaLang.tr("图片尺寸过大", "Image dimensions are too large"));
            sample *= 2;
        }
        return sample;
    }

    static Bitmap decode(File file, int width, int height) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) throw new IOException(com.termux.terminal.LumaLang.tr("无法解码图片", "Unable to decode image"));
        return bitmap;
    }
}
