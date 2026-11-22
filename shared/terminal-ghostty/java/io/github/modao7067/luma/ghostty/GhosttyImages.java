package io.github.modao7067.luma.ghostty;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import com.termux.terminal.TerminalImage;
import java.util.*;

final class GhosttyImages {
    private static final int STRIDE = 16;
    private static final class Cached {
        final long generation; final Bitmap bitmap;
        Cached(long generation, Bitmap bitmap) { this.generation = generation; this.bitmap = bitmap; }
    }
    private final Map<Integer,Cached> cache = new HashMap<>();
    private long[] lastGeometry;
    private List<TerminalImage> lastResult = Collections.emptyList();
    private int lastCellWidth, lastCellHeight;
    long revision;
    List<TerminalImage> capture(long handle, int cellWidth, int cellHeight) {
        long[] raw = GhosttyNative.imagePlacements(handle);
        if (raw == null || raw.length < 2) return empty();
        long count = raw[1];
        if (count < 0 || 2L + count * STRIDE > raw.length) return empty();
        if (cellWidth == lastCellWidth && cellHeight == lastCellHeight && Arrays.equals(lastGeometry, raw)) {
            return lastResult;
        }
        if (!Arrays.equals(lastGeometry, raw)) { lastGeometry = raw; revision++; }
        lastCellWidth = cellWidth; lastCellHeight = cellHeight;
        List<TerminalImage> result = new ArrayList<>(); Set<Integer> used = new HashSet<>();
        float cw = Math.max(1,cellWidth), ch = Math.max(1,cellHeight);
        for (int i = 0; i < count; i++) {
            int p = 2 + i * STRIDE, id = (int)raw[p]; long generation = raw[p+13];
            Cached image = cache.get(id);
            if (image == null || image.generation != generation) {
                long width = raw[p+14], height = raw[p+15];
                if (width <= 0 || height <= 0 || width > 4L * 1024 * 1024 / height) continue;
                Bitmap bitmap;
                try {
                    bitmap = Bitmap.createBitmap((int)width, (int)height, Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError ignored) {
                    continue;
                }
                if (!GhosttyNative.copyImage(handle, id, bitmap)) { bitmap.recycle(); continue; }
                bitmap.prepareToDraw();
                image = new Cached(generation,bitmap); cache.put(id,image);
            }
            used.add(id);
            float left = raw[p+3] + raw[p+5]/cw, top = raw[p+4] + raw[p+6]/ch;
            Rect source = new Rect((int)raw[p+9],(int)raw[p+10],(int)(raw[p+9]+raw[p+11]),(int)(raw[p+10]+raw[p+12]));
            RectF cells = new RectF(left,top,left+raw[p+7]/cw,top+raw[p+8]/ch);
            result.add(new TerminalImage(image.bitmap,source,cells,(int)raw[p+2]));
        }
        cache.keySet().retainAll(used); result.sort(Comparator.comparingInt(image -> image.z));
        lastResult = Collections.unmodifiableList(result);
        return lastResult;
    }
    List<TerminalImage> empty() {
        if (!cache.isEmpty() || (lastGeometry != null && lastGeometry.length > 2)) {
            cache.clear();
            lastGeometry = new long[]{0, 0};
            lastResult = Collections.emptyList();
            revision++;
        }
        return Collections.emptyList();
    }
    void clear() { cache.clear(); lastGeometry = null; lastResult = Collections.emptyList(); revision++; }
}
