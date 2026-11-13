package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

/** Immutable image placement for one borrowed terminal frame; the bitmap is never recycled. */
public final class TerminalImage {
    public final Bitmap bitmap;
    public final Rect source;
    /** Destination in viewport cell units, before font spacing and the top inset are applied. */
    public final RectF cells;
    public final int z;
    public TerminalImage(Bitmap bitmap, Rect source, RectF cells, int z) {
        this.bitmap = bitmap; this.source = new Rect(source); this.cells = new RectF(cells); this.z = z;
    }
}
