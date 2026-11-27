package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.RenderNode;
import java.util.Arrays;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.LruCache;
import android.graphics.text.PositionedGlyphs;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;
import java.util.Collections;
import java.util.List;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    private final Paint mMeasurePaint = new Paint();
    private final SparseArray<Float> mGlyphWidths = new SparseArray<>();
    /** Advance cache keyed by cluster length + content + base codepoint (native geometry). */
    private final LongSparseArray<Float> mClusterWidths = new LongSparseArray<>();
    private static final int MULTI_RUN_CACHE_BUDGET = 256 * 1024;
    private static final int SINGLE_CP_CACHE_BUDGET = 256 * 1024;
    private final LruCache<String,BundledTerminalFonts.PreparedGlyphs> mShapedRuns =
        new LruCache<String,BundledTerminalFonts.PreparedGlyphs>(MULTI_RUN_CACHE_BUDGET){
            @Override protected int sizeOf(String key,BundledTerminalFonts.PreparedGlyphs value){return key.length()*2+value.bytes();}
        };
    private final SingleCodePointCache mSingleShapedRuns = new SingleCodePointCache();

    /**
     * Bounded 4-way set-associative cache for single-codepoint runs keyed by primitive int
     * {@code ((bold | italic<<1) << 21) | codePoint}. Avoids String key allocation on CJK/symbol
     * runs while keeping combined shaping cache memory within the 512 KiB budget.
     */
    private static final class SingleCodePointCache {
        private static final int WAYS = 4;
        private static final int SETS = 512;
        private static final int SLOTS = SETS * WAYS;
        private static final int TABLE_OVERHEAD_BYTES = SLOTS * 8;
        private static final int ENTRY_KEY_OVERHEAD = 8;
        private final int[] keys = new int[SLOTS];
        private final BundledTerminalFonts.PreparedGlyphs[] values =
                new BundledTerminalFonts.PreparedGlyphs[SLOTS];
        private int usedBytes = TABLE_OVERHEAD_BYTES;
        private int clockHand;

        SingleCodePointCache() {
            Arrays.fill(keys, -1);
        }

        private static int setBase(int key) {
            int h = key * 0x9E3779B9;
            return ((h ^ (h >>> 16)) & (SETS - 1)) << 2;
        }

        BundledTerminalFonts.PreparedGlyphs get(int key) {
            final int base = setBase(key);
            for (int w = 0; w < WAYS; w++) {
                final int idx = base + w;
                if (keys[idx] == key) {
                    final BundledTerminalFonts.PreparedGlyphs found = values[idx];
                    if (w > 0) {
                        for (int k = idx; k > base; k--) {
                            keys[k] = keys[k - 1];
                            values[k] = values[k - 1];
                        }
                        keys[base] = key;
                        values[base] = found;
                    }
                    return found;
                }
            }
            return null;
        }

        /** Removes one slot and subtracts exactly the bytes it accounted for. */
        private void dropSlot(int slot) {
            final BundledTerminalFonts.PreparedGlyphs old = values[slot];
            if (old == null) {
                keys[slot] = -1;
                return;
            }
            usedBytes -= ENTRY_KEY_OVERHEAD + old.bytes();
            keys[slot] = -1;
            values[slot] = null;
        }

        void put(int key, BundledTerminalFonts.PreparedGlyphs value) {
            final int base = setBase(key);
            final int entryBytes = ENTRY_KEY_OVERHEAD + value.bytes();
            // A single entry that cannot fit the whole budget is never cached and must not
            // evict anything on its behalf: usedBytes would otherwise exceed the budget.
            if (entryBytes > SINGLE_CP_CACHE_BUDGET - TABLE_OVERHEAD_BYTES) return;
            // Same-key replacement removes the old entry first so its bytes are not counted twice.
            for (int w = 0; w < WAYS; w++) {
                if (keys[base + w] == key) { dropSlot(base + w); break; }
            }
            // Make room in this set before shifting in the new most-recent way.
            dropSlot(base + WAYS - 1);
            while (usedBytes + entryBytes > SINGLE_CP_CACHE_BUDGET) {
                int victim = -1;
                for (int i = 0; i < SLOTS; i++) {
                    final int slot = clockHand;
                    clockHand = (clockHand + 1) & (SLOTS - 1);
                    if (slot >= base && slot < base + WAYS) continue;
                    if (values[slot] != null) { victim = slot; break; }
                }
                if (victim < 0) {
                    // Only this set still holds entries; drop its oldest remaining way.
                    for (int w = WAYS - 1; w >= 1; w--) {
                        if (values[base + w] != null) { victim = base + w; break; }
                    }
                }
                if (victim < 0) return; // Cannot free enough room: refuse instead of over-accounting.
                dropSlot(victim);
            }
            for (int k = base + WAYS - 1; k > base; k--) {
                keys[k] = keys[k - 1];
                values[k] = values[k - 1];
            }
            keys[base] = key;
            values[base] = value;
            usedBytes += entryBytes;
        }

        /** Recomputed byte total; used by tests to prove the running counter never drifts. */
        int recomputeUsedBytes() {
            int total = TABLE_OVERHEAD_BYTES;
            for (int i = 0; i < SLOTS; i++) {
                if (values[i] != null) total += ENTRY_KEY_OVERHEAD + values[i].bytes();
            }
            return total;
        }

        int entryCount() {
            int total = 0;
            for (int i = 0; i < SLOTS; i++) if (values[i] != null) total++;
            return total;
        }
    }
    private final Paint mCellGraphicPaint = new Paint();
    private static final float HAN_GLYPH_SCALE = 1.12f;
    private static final int[] QUADRANT_MASKS = {4, 8, 1, 13, 9, 7, 11, 2, 6, 14};

    // Display lists retain drawing commands. Image references share the bounded buffer cache; no background work.
    private RowFrame[] mRowFrames = new RowFrame[0];
    private final RenderNode mImageLayer0Node = new RenderNode("terminal images below");
    private final RenderNode mImageLayer2Node = new RenderNode("terminal images above");
    private int[] mCachedPalette;
    private int mCachedWidth, mCachedHeight;
    private boolean mCachedReverse;
    private boolean mCachedKittyUnderText;
    private long mCachedImageRevision = -1;
    private long mCachedImageLayerRevision = -1;
    private int mCachedImageLayerWidth, mCachedImageLayerHeight, mCachedImageLayerColumns;
    private float mCachedImageLayerBottom;
    private TerminalBuffer mCachedScreen;
    private final Rect mImageSource = new Rect();
    private final RectF mImageDestination = new RectF();
    private final Paint mKittyPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean mForceBlockCursor;

    public void setForceBlockCursor(boolean forceBlockCursor) {
        mForceBlockCursor = forceBlockCursor;
    }

    public boolean isForceBlockCursor() {
        return mForceBlockCursor;
    }

    private static final class RowFrame {
        final RenderNode node = new RenderNode("terminal row");
        TerminalRow row;
        long revision = -1;
        int columns = -1, cursor, selectionStart, selectionEnd, cursorShape;
        boolean matches(TerminalRow row, int columns, int cursor, int start, int end, int shape) {
            return this.row == row && this.revision == row.getRevision() && this.columns == columns
                    && this.cursor == cursor && selectionStart == start && selectionEnd == end
                    && cursorShape == shape && node.hasDisplayList();
        }
        void remember(TerminalRow row, int columns, int cursor, int start, int end, int shape) {
            this.row = row;
            this.revision = row.getRevision();
            this.columns = columns;
            this.cursor = cursor; selectionStart = start; selectionEnd = end; cursorShape = shape;
        }
        void discard() { row = null; revision = -1; columns = -1; node.discardDisplayList(); }
    }

    /** Called when a View loses its window or replaces its renderer. */
    void clearRowCache() {
        for (RowFrame frame : mRowFrames) frame.discard();
        mRowFrames = new RowFrame[0];
        mImageLayer0Node.discardDisplayList();
        mImageLayer2Node.discardDisplayList();
        mCachedImageLayerRevision = -1;
        mCachedKittyUnderText = false;
        mCachedPalette = null;
        mCachedScreen = null;
    }

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        // Apply before copying the measurement paint; cell fitting below still
        // preserves terminal columns when a selected punctuation glyph is wider.
        mTextPaint.setFontFeatureSettings(BundledTerminalFonts.FEATURE_SETTINGS);
        mMeasurePaint.set(mTextPaint);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator.getRenderFrame(), canvas, topRow,
            selectionY1, selectionY2, selectionX1, selectionX2);
    }

    /** Render a read-only frame (native projection or the legacy emulator view). */
    public final void render(TerminalRenderFrame renderFrame, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        if (renderFrame.nativeViewport) {
            // Buffer row 0 is the captured native viewport, not active row 0. Selection coordinates
            // from the View are active-screen coordinates; convert them exactly once.
            int origin = renderFrame.sourceTopRow;
            if (selectionX1 >= 0 && selectionX2 >= 0) { selectionY1 -= origin; selectionY2 -= origin; }
            topRow = 0;
        }
        final boolean reverseVideo = renderFrame.reverseVideo;
        final int rows = renderFrame.rows;
        final int endRow = topRow + rows;
        final int columns = renderFrame.columns;
        final int cursorCol = renderFrame.cursorColumn;
        final int cursorRow = renderFrame.cursorRow;
        final boolean cursorVisible = renderFrame.cursorVisible;
        final TerminalBuffer screen = renderFrame.screen;
        final int[] palette = renderFrame.palette;
        final int cursorShape = mForceBlockCursor
                ? TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
                : renderFrame.cursorStyle;
        final float graphicsBottom=Math.min(canvas.getHeight(),mFontLineSpacingAndAscent+rows*mFontLineSpacing);
        boolean kittyUnderText=false;
        for(TerminalImage image:renderFrame.images)if(image.z<0&&image.z>=Integer.MIN_VALUE/2)kittyUnderText=true;
        final long imageRevision = renderFrame.imageRevision;

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        final Canvas target = canvas;
        final boolean cacheRows = canvas.isHardwareAccelerated();
        if (cacheRows) {
            if (mRowFrames.length != rows) {
                for (RowFrame frame : mRowFrames) frame.discard();
                mRowFrames = new RowFrame[rows];
                for (int i = 0; i < mRowFrames.length; i++) mRowFrames[i] = new RowFrame();
            }
            final boolean underTextImagesChanged = (kittyUnderText || mCachedKittyUnderText)
                    && mCachedImageRevision != imageRevision;
            if (mCachedWidth != canvas.getWidth() || mCachedHeight != canvas.getHeight()
                    || mCachedReverse != reverseVideo || !Arrays.equals(mCachedPalette, palette)
                    || mCachedScreen != screen || underTextImagesChanged) {
                for (RowFrame frame : mRowFrames) frame.discard();
                mCachedWidth = canvas.getWidth(); mCachedHeight = canvas.getHeight();
                mCachedReverse = reverseVideo; mCachedPalette = palette.clone();
                mCachedScreen = screen;
            }
            mCachedKittyUnderText = kittyUnderText;
            mCachedImageRevision = imageRevision;
            if (mCachedImageLayerRevision != imageRevision
                    || mCachedImageLayerWidth != target.getWidth()
                    || mCachedImageLayerHeight != target.getHeight()
                    || mCachedImageLayerColumns != columns
                    || mCachedImageLayerBottom != graphicsBottom
                    || (!renderFrame.images.isEmpty() && (!mImageLayer0Node.hasDisplayList() || !mImageLayer2Node.hasDisplayList()))) {
                mImageLayer0Node.discardDisplayList();
                mImageLayer2Node.discardDisplayList();
                if (!renderFrame.images.isEmpty()) {
                    final int w = target.getWidth(), h = target.getHeight();
                    mImageLayer0Node.setPosition(0, 0, w, h);
                    Canvas c0 = mImageLayer0Node.beginRecording(w, h);
                    drawNativeImages(c0, renderFrame.images, 0, 0, graphicsBottom, columns);
                    mImageLayer0Node.endRecording();
                    mImageLayer2Node.setPosition(0, 0, w, h);
                    Canvas c2 = mImageLayer2Node.beginRecording(w, h);
                    drawNativeImages(c2, renderFrame.images, 2, 0, graphicsBottom, columns);
                    mImageLayer2Node.endRecording();
                }
                mCachedImageLayerRevision = imageRevision;
                mCachedImageLayerWidth = target.getWidth();
                mCachedImageLayerHeight = target.getHeight();
                mCachedImageLayerColumns = columns;
                mCachedImageLayerBottom = graphicsBottom;
            }
            if (!renderFrame.images.isEmpty()) target.drawRenderNode(mImageLayer0Node);
        } else {
            drawNativeImages(canvas, renderFrame.images, 0, 0, graphicsBottom, columns);
        }
        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : columns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();
            RowFrame frame = cacheRows ? mRowFrames[row - topRow] : null;
            if (frame != null) {
                if (frame.matches(lineObject, columns, cursorX, selx1, selx2, cursorShape)) {
                    target.drawRenderNode(frame.node);
                    continue;
                }
                frame.node.setPosition(0, 0, mCachedWidth, mCachedHeight);
                canvas = frame.node.beginRecording(mCachedWidth, mCachedHeight);
            }

            final boolean separateBackground = kittyUnderText
                || needsSeparateBackground(lineObject, columns, palette[TextStyle.COLOR_INDEX_BACKGROUND], reverseVideo, selx1, selx2, cursorX);
            for (int pass = 0; pass < (separateBackground ? 2 : 1); pass++) {
                final boolean paintBackground = !separateBackground || pass == 0;
                final boolean paintText = !separateBackground || pass == 1;
                long lastRunStyle = 0;
                boolean lastRunInsideCursor = false;
                boolean lastRunInsideSelection = false;
                int lastRunStartColumn = -1;
                int lastRunStartIndex = 0;
                boolean lastRunFontWidthMismatch = false;
                int currentCharIndex = 0;
                float measuredWidthForRun = 0.f;

                final boolean nativeGeometry = lineObject.hasFrameGeometry();
                if (nativeGeometry) currentCharIndex = lineObject.frameColumnStart(0);
                for (int column = 0; column < columns; ) {
                    final int frameWidth = nativeGeometry ? lineObject.frameColumnWidth(column) : 1;
                    if (nativeGeometry && frameWidth == 0) { column++; continue; } // wide tail: drawn with the base cell
                    if (nativeGeometry) currentCharIndex = lineObject.frameColumnStart(column);
                    // Past the projected cluster text the cell is blank (the projection does not pad with
                    // spaces); draw it as a space without indexing beyond the imported row text.
                    final boolean frameBlank = nativeGeometry && currentCharIndex >= charsUsedInLine;
                    final char charAtIndex = frameBlank ? ' ' : line[currentCharIndex];
                    final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                    int frameNextStart = -1;
                    if (nativeGeometry) {
                        int nextColumn = column + frameWidth;
                        frameNextStart = nextColumn < columns ? lineObject.frameColumnStart(nextColumn) : charsUsedInLine;
                    }
                    final int charsForCodePoint = nativeGeometry
                        ? Math.max(1, frameNextStart - currentCharIndex)
                        : (charIsHighsurrogate ? 2 : 1);
                    final int codePoint = frameBlank ? ' '
                        : (charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex);
                    final long style = lineObject.getStyle(column);
                    final int codePointWcWidth = nativeGeometry ? frameWidth : WcWidth.width(codePoint);
                    final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                    final boolean insideSelection = nativeGeometry
                        ? (column + frameWidth - 1 >= selx1 && column <= selx2)
                        : (column >= selx1 && column <= selx2);
                    // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                    // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                    // smileys which android font renders as wide.
                    // Isolate mismatches so each fallback glyph can fit its own cells
                    // without distorting the other glyphs in a run.
                    final float measuredCodePointWidth;
                    final int baseCharCount = Character.charCount(codePoint);
                    if (charsForCodePoint == 1 && codePoint < asciiMeasures.length) {
                        measuredCodePointWidth = asciiMeasures[codePoint];
                    } else if (charsForCodePoint != baseCharCount) {
                        // A complete cluster: distinct VS/ZWJ/spacing-mark sequences with the same base
                        // codepoint must not share one advance. The key includes the full UTF-16 span.
                        int clusterHash = 1;
                        for (int ci = currentCharIndex; ci < currentCharIndex + charsForCodePoint; ci++)
                            clusterHash = 31 * clusterHash + line[ci];
                        long clusterKey = ((long) charsForCodePoint << 32) | (clusterHash & 0xffffffffL);
                        Float cached = mClusterWidths.get(clusterKey);
                        if (cached == null) {
                            cached = BundledTerminalFonts.shape(line, currentCharIndex, charsForCodePoint, mMeasurePaint).getAdvance();
                            mClusterWidths.put(clusterKey, cached);
                        }
                        measuredCodePointWidth = cached;
                    } else {
                        Float cached=mGlyphWidths.get(codePoint);
                        if(cached==null){
                            cached=BundledTerminalFonts.shape(line,currentCharIndex,charsForCodePoint,mMeasurePaint).getAdvance();
                            mGlyphWidths.put(codePoint,cached);
                        }
                        measuredCodePointWidth=cached;
                    }
                    // Keep fallback fonts and missing glyphs confined to their own cells.
                    final boolean fontWidthMismatch = codePoint>=127
                        || isCellGraphic(codePoint)
                        || Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                    if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                        if (lastRunStartColumn < 0 || column == lastRunStartColumn) {
                            // Skip first column as there is nothing to draw, just record the current style.
                        } else {
                            final int columnWidthSinceLastRun = column - lastRunStartColumn;
                            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                            int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
                            boolean invertCursorTextColor = false;
                            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                                invertCursorTextColor = true;
                            }
                            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                                lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                                cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, paintBackground, paintText);
                        }
                        measuredWidthForRun = 0.f;
                        lastRunStyle = style;
                        lastRunInsideCursor = insideCursor;
                        lastRunInsideSelection = insideSelection;
                        lastRunStartColumn = column;
                        lastRunStartIndex = currentCharIndex;
                        lastRunFontWidthMismatch = fontWidthMismatch;
                    }
                    measuredWidthForRun += measuredCodePointWidth;
                    column += codePointWcWidth;
                    if (nativeGeometry) {
                        currentCharIndex = column < columns ? lineObject.frameColumnStart(column) : charsUsedInLine;
                        continue;
                    }
                    currentCharIndex += charsForCodePoint;
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        // Eat combining chars so that they are treated as part of the last non-combining code point,
                        // instead of e.g. being considered inside the cursor in the next run.
                        currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                    }
                }

                final int columnWidthSinceLastRun = columns - lastRunStartColumn;
                final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
                boolean invertCursorTextColor = false;
                if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                    invertCursorTextColor = true;
                }
                if (lastRunStartColumn >= 0 && columnWidthSinceLastRun > 0) drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                    measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, paintBackground, paintText);
                if (kittyUnderText && pass == 0) {

                    drawNativeImages(canvas, renderFrame.images, 1, heightOffset - mFontLineSpacing, heightOffset, columns);
                }
            }
            if (frame != null) {
                frame.node.endRecording();
                frame.remember(lineObject, columns, cursorX, selx1, selx2, cursorShape);
                canvas = target;
                target.drawRenderNode(frame.node);
            }
        }

        if (cacheRows) {
            if (!renderFrame.images.isEmpty()) target.drawRenderNode(mImageLayer2Node);
        } else {
            drawNativeImages(target, renderFrame.images, 2, 0, graphicsBottom, columns);
        }
    }

    private void drawNativeImages(Canvas canvas, List<TerminalImage> images, int layer, float top, float bottom, int columns) {
        if (images.isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(0, Math.max(top, mFontLineSpacingAndAscent), columns * mFontWidth, bottom);
        for (TerminalImage image : images) {
            int imageLayer = image.z < Integer.MIN_VALUE / 2 ? 0 : image.z < 0 ? 1 : 2;
            if (imageLayer != layer) continue;
            mImageDestination.set(image.cells.left*mFontWidth,
                mFontLineSpacingAndAscent+image.cells.top*mFontLineSpacing,
                image.cells.right*mFontWidth,mFontLineSpacingAndAscent+image.cells.bottom*mFontLineSpacing);
            canvas.drawBitmap(image.bitmap, image.source, mImageDestination, mKittyPaint);
        }
        canvas.restoreToCount(save);
    }

    private static boolean needsSeparateBackground(TerminalRow row, int columns, int defaultBg,
                                                   boolean reverseVideo, int selx1, int selx2, int cursorX) {
        boolean hasPaintedBg = reverseVideo || selx1 >= 0 || cursorX >= 0;
        boolean hasItalic = false;
        for (int column = 0; column < columns; column++) {
            final long style = row.getStyle(column);
            final int effect = TextStyle.decodeEffect(style);
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) {
                hasItalic = true;
            }
            if (!hasPaintedBg) {
                int backColor = TextStyle.decodeBackColor(style);
                boolean inverse = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0;
                if (inverse || (backColor != TextStyle.COLOR_INDEX_BACKGROUND && backColor != defaultBg)) {
                    hasPaintedBg = true;
                }
            }
            if (hasPaintedBg && hasItalic) return true;
        }
        if (!hasPaintedBg) return false;
        final int used = row.getSpaceUsed();
        final char[] text = row.mText;
        for (int i = 0; i < used; i++) {
            if (text[i] >= 127) return true;
        }
        return false;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, boolean paintBackground, boolean paintText) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;
        final float cellRight = right;

        // Standard cell background bounds (same as upstream Termux).
        float cellTop = y - mFontLineSpacingAndAscent + mFontAscent;
        float cellBottom = y;

        if (paintBackground && backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, cellTop, right, cellBottom, mTextPaint);
        }

        if (paintBackground && cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if (paintText && runWidthChars > 0 && startCharIndex < text.length
                && (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            int codePoint = Character.codePointAt(text, startCharIndex, Math.min(text.length, startCharIndex + runWidthChars));
            Paint textPaint = mTextPaint;
            textPaint.setFakeBoldText(bold);
            textPaint.setUnderlineText(underline);
            textPaint.setTextSkewX(italic ? -0.35f : 0.f);
            textPaint.setStrikeThruText(strikeThrough);
            textPaint.setColor(foreColor);
            if(!underline && !strikeThrough){
                boolean spaces=true;for(int i=startCharIndex;i<startCharIndex+runWidthChars;i++)if(text[i]!=' '){spaces=false;break;}
                if(spaces)return;
            }
            if (runWidthChars == Character.charCount(codePoint) && isCellGraphic(codePoint)) {
                drawCellGraphic(canvas, codePoint, left, cellTop, cellRight, cellBottom, foreColor);
            } else {
                // Font fallback advances do not necessarily match terminal cells. Keep
                // the cell grid fixed without widening CJK glyphs or squeezing symbols
                // into ellipses. Oversized glyphs shrink uniformly; smaller glyphs are
                // centered at their natural size. Box drawing retains its cell fitting
                // behavior so existing rule and border glyphs keep their vertical size.
                BundledTerminalFonts.PreparedGlyphs prepared;
                if (runWidthChars == Character.charCount(codePoint)) {
                    final int singleKey = (((bold ? 1 : 0) | (italic ? 2 : 0)) << 21) | codePoint;
                    prepared = mSingleShapedRuns.get(singleKey);
                    if (prepared == null) {
                        prepared = new BundledTerminalFonts.PreparedGlyphs(BundledTerminalFonts.shape(text, startCharIndex, runWidthChars, textPaint));
                        mSingleShapedRuns.put(singleKey, prepared);
                    }
                } else {
                    String shapeKey=(bold?(italic?"3":"1"):(italic?"2":"0"))+new String(text,startCharIndex,runWidthChars);
                    prepared=mShapedRuns.get(shapeKey);
                    if(prepared==null){
                        prepared=new BundledTerminalFonts.PreparedGlyphs(BundledTerminalFonts.shape(text,startCharIndex,runWidthChars,textPaint));
                        mShapedRuns.put(shapeKey,prepared);
                    }
                }
                PositionedGlyphs glyphs=prepared.glyphs;
                mes=glyphs.getAdvance();
                float width = cellRight - left;
                boolean boxDrawing = codePoint >= 0x2500 && codePoint <= 0x257f;
                float scaleX = mes > 0 ? (boxDrawing ? width / mes : Math.min(1.f, width / mes)) : 1.f;
                float scaleY = boxDrawing ? 1.f : scaleX;
                float baseline = y - mFontLineSpacingAndAscent;
                if ((codePoint == 0x23be || codePoint == 0x23bf)
                        && runWidthColumns == 1 && runWidthChars == 1 && mes > 0) {
                    // Native connector outlines span one cell. Fit their ink to the
                    // grid so adjacent rows can join, independently of main-font metrics.
                    if (prepared.connectorBounds == null) {
                        prepared.connectorBounds = new Rect();
                        textPaint.getTextBounds(text, startCharIndex, runWidthChars, prepared.connectorBounds);
                    }
                    Rect bounds = prepared.connectorBounds;
                    scaleX = width / mes;
                    if (bounds.height() > 0) {
                        scaleY = (cellBottom - cellTop) / bounds.height();
                        baseline = cellTop - bounds.top * scaleY;
                    }
                } else if (isHanCharacter(codePoint) && runWidthColumns == 2 && mes > 0) {
                    // Fill more of a Han character's two cells while preserving its
                    // aspect ratio. Only the glyph changes: terminal columns, cursor
                    // coordinates, punctuation, symbols, and emoji keep their layout.
                    if(prepared.hanBounds==null){prepared.hanBounds=new Rect();textPaint.getTextBounds(text,startCharIndex,runWidthChars,prepared.hanBounds);}
                    Rect mHanBounds=prepared.hanBounds;
                    // Wide advances may include generous side bearings (Maple CN).
                    // Fit the actual ink about the advance centre so that padding
                    // does not cancel the Han enlargement or allow italic overhang.
                    float halfAdvance = mes / 2.f;
                    float inkHalfWidth = Math.max(halfAdvance - mHanBounds.left,
                            mHanBounds.right - halfAdvance);
                    float widthLimit = inkHalfWidth > 0 ? width / (2.f * inkHalfWidth) : width / mes;
                    scaleX = Math.min(HAN_GLYPH_SCALE, widthLimit);
                    if (mHanBounds.height() > 0)
                        scaleX = Math.min(scaleX, (cellBottom - cellTop) / mHanBounds.height());
                    scaleY = scaleX;
                    float minimumBaseline = cellTop - mHanBounds.top * scaleY;
                    float maximumBaseline = cellBottom - mHanBounds.bottom * scaleY;
                    baseline = Math.max(minimumBaseline, Math.min(baseline, maximumBaseline));
                }
                float drawLeft = left + (width - mes * scaleX) / 2.f;
                canvas.save();
                canvas.translate(drawLeft, baseline);
                canvas.scale(scaleX, scaleY);
                prepared.draw(canvas,textPaint);
                // drawGlyphs draws glyph outlines; decorations remain terminal styles.
                float stroke=Math.max(1.f,mTextSize/16.f);
                if(underline)canvas.drawRect(0,stroke,mes,stroke*2,textPaint);
                if(strikeThrough)canvas.drawRect(0,-mTextSize*0.3f,mes,-mTextSize*0.3f+stroke,textPaint);
                canvas.restore();
            }
        }
    }

    private static boolean isCellGraphic(int codePoint) {
        return (codePoint >= 0x2580 && codePoint <= 0x259f)
            || codePoint == 0x25cf || codePoint == 0x25cb || codePoint == 0x2022;
    }

    private static boolean isHanCharacter(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4dbf)
            || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
            || (codePoint >= 0xf900 && codePoint <= 0xfaff)
            || (codePoint >= 0x20000 && codePoint <= 0x2fa1f)
            || (codePoint >= 0x30000 && codePoint <= 0x3347f);
    }

    /** Unicode block elements occupy exact fractions of the cell, not font ink bounds. */
    private void drawCellGraphic(Canvas canvas, int codePoint, float left, float top,
                                 float right, float bottom, int color) {
        Paint paint = mCellGraphicPaint;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(false);
        float width = right - left, height = bottom - top;
        if (codePoint == 0x2580) {
            canvas.drawRect(left, top, right, top + height / 2.f, paint);
        } else if (codePoint >= 0x2581 && codePoint <= 0x2588) {
            canvas.drawRect(left, bottom - height * (codePoint - 0x2580) / 8.f, right, bottom, paint);
        } else if (codePoint >= 0x2589 && codePoint <= 0x258f) {
            canvas.drawRect(left, top, left + width * (0x2590 - codePoint) / 8.f, bottom, paint);
        } else if (codePoint == 0x2590) {
            canvas.drawRect(left + width / 2.f, top, right, bottom, paint);
        } else if (codePoint >= 0x2591 && codePoint <= 0x2593) {
            // Shade elements retain foreground/background blending, including a
            // transparent default background, rather than painting an opaque box.
            paint.setAlpha(Math.round(android.graphics.Color.alpha(color) * (codePoint - 0x2590) / 4.f));
            canvas.drawRect(left, top, right, bottom, paint);
        } else if (codePoint == 0x2594) {
            canvas.drawRect(left, top, right, top + height / 8.f, paint);
        } else if (codePoint == 0x2595) {
            canvas.drawRect(right - width / 8.f, top, right, bottom, paint);
        } else if (codePoint >= 0x2596 && codePoint <= 0x259f) {
            // Quadrant bit order: upper left, upper right, lower left, lower right.
            int mask = QUADRANT_MASKS[codePoint - 0x2596];
            float midX = left + width / 2.f, midY = top + height / 2.f;
            if ((mask & 1) != 0) canvas.drawRect(left, top, midX, midY, paint);
            if ((mask & 2) != 0) canvas.drawRect(midX, top, right, midY, paint);
            if ((mask & 4) != 0) canvas.drawRect(left, midY, midX, bottom, paint);
            if ((mask & 8) != 0) canvas.drawRect(midX, midY, right, bottom, paint);
        } else {
            float diameter = Math.min(width * (codePoint == 0x2022 ? 0.4f : 0.72f), mTextSize * 0.6f);
            paint.setAntiAlias(true);
            if (codePoint == 0x25cb) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.f, mTextSize / 18.f));
                diameter = Math.max(0, diameter - paint.getStrokeWidth());
            }
            canvas.drawCircle((left + right) / 2.f, (top + bottom) / 2.f, diameter / 2.f, paint);
        }
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
