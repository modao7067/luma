package io.github.modao7067.luma.ghostty;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TextStyle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class GhosttyFrameAdapter implements AutoCloseable {
    private static final int HDR = 12, STRIDE = 6;
    public static final int FLAG_CURSOR_VISIBLE = 1;
    public static final int FLAG_CURSOR_IN_VIEWPORT = 1 << 2;
    public static final int FLAG_SYNC_OUTPUT = 1 << 3;
    public static final int FLAG_ALT_SCREEN = 1 << 4;
    public static final int FLAG_REVERSE_VIDEO = 1 << 5;
    public static final int FLAG_HAS_IMAGES = 1 << 6;
    static final int FEED_HAS_REPLIES = 1 << 0;
    static final int FEED_REPLY_OVERFLOW = 1 << 1;
    static final int FEED_HAS_EVENTS = 1 << 2;
    static final int FEED_HOLD_ACTIVE = 1 << 3;
    static final int FEED_HOLD_CHANGED = 1 << 4;
    static final int FEED_COLORS_CHANGED = 1 << 5;
    static final int FEED_HAS_IMAGES = 1 << 6;
    static final int FEED_ALT_SCREEN = 1 << 7;
    static final int FEED_REVERSE_VIDEO = 1 << 8;
    static final int FEED_ACTIVITY_CHANGED = 1 << 9;
    static final int FEED_AT_BOTTOM = 1 << 10;
    static final int FEED_MODES_MAYBE_CHANGED = 1 << 11;
    private static final int ATTR_HAS_TEXT = 1;
    private static final int ATTR_WIDE = 1 << 1;
    private static final int ATTR_WIDE_TAIL = 1 << 2;
    private static final int ATTR_BOLD = 1 << 4;
    private static final int ATTR_ITALIC = 1 << 5;
    private static final int ATTR_FAINT = 1 << 6;
    private static final int ATTR_BLINK = 1 << 7;
    private static final int ATTR_INVERSE = 1 << 8;
    private static final int ATTR_INVISIBLE = 1 << 9;
    private static final int ATTR_STRIKETHROUGH = 1 << 10;
    private static final int ATTR_UNDERLINE_SHIFT = 12;
    private static final int ATTR_UNDERLINE_MASK = 7;
    private static final int ATTR_ROW_WRAP = 1 << 16;
    private static final int ATTR_ROW_CLEAN = 1 << 17;
    private static final int SCROLL_TOP = 0, SCROLL_BOTTOM = 1, SCROLL_DELTA = 2, SCROLL_ROW = 3;

    private long handle;
    private int cols, rows;
    private TerminalBuffer buffer;
    private int[] cells = new int[0];
    private int[] clusters = new int[0];
    private int[] palette = new int[259];
    private int lastPaletteGen;
    private boolean paletteValid;
    private boolean atBottom = true;
    private int lastFeedStatus;
    private char[] scratchText = new char[0];
    private long[] scratchStyles = new long[0];
    private int[] scratchStart = new int[0];
    private int[] scratchWidth = new int[0];
    private int captureCalls, jniCalls, lastFlags;
    private Runnable activityObserver;

    void setActivityObserver(Runnable observer) { this.activityObserver = observer; }
    private void notifyActivity() { if (activityObserver != null) activityObserver.run(); }
    boolean isViewportAtBottom() { return atBottom; }
    int lastFeedStatus() { return lastFeedStatus; }
    boolean hasImagesInLastFrame() { return (lastFlags & FLAG_HAS_IMAGES) != 0; }

    private GhosttyFrameAdapter(long handle, int cols, int rows, int[] palette) {
        this.handle = handle; this.cols = cols; this.rows = rows; this.palette = palette;
    }

    public static void load(String path) { GhosttyNative.load(path); }

    public static GhosttyFrameAdapter create(int cols, int rows, int scrollback, int[] palette, int fg, int bg) {
        int[] normalized = new int[259];
        System.arraycopy(palette, 0, normalized, 0, Math.min(256, palette.length));
        for (int i = Math.min(256, palette.length); i < 256; i++) normalized[i] = 0;
        if (palette.length >= 259) System.arraycopy(palette, 256, normalized, 256, 3);
        else { normalized[256] = fg; normalized[257] = bg; normalized[258] = fg; }
        long handle = GhosttyNative.create(cols, rows, scrollback, normalized, normalized[256], normalized[257]);
        return handle == 0 ? null : new GhosttyFrameAdapter(handle, cols, rows, normalized);
    }

    public int feed(byte[] data, int offset, int length) {
        int rc = GhosttyNative.feed(handle, data, offset, length); jniCalls++;
        if (rc < 0) return rc;
        lastFeedStatus = rc;
        atBottom = (rc & FEED_AT_BOTTOM) != 0;
        if ((rc & FEED_ALT_SCREEN) != 0) lastFlags |= FLAG_ALT_SCREEN; else lastFlags &= ~FLAG_ALT_SCREEN;
        if ((rc & FEED_REVERSE_VIDEO) != 0) lastFlags |= FLAG_REVERSE_VIDEO; else lastFlags &= ~FLAG_REVERSE_VIDEO;
        return 0;
    }

    public int resize(int cols, int rows, int cellWidth, int cellHeight) {
        int rc = GhosttyNative.resize(handle, cols, rows, cellWidth, cellHeight);
        jniCalls++;
        if (rc == 0) { this.cols = cols; this.rows = rows; this.buffer = null; cells = new int[0]; clusters = new int[0]; }
        return rc;
    }

    public int paste(byte[] text, boolean allowUnsafe) {
        jniCalls++;
        return GhosttyNative.paste(handle, text, allowUnsafe);
    }

    public TerminalRenderFrame capture() {
        if (handle == 0) return null;
        int need = HDR + Math.max(1, cols) * Math.max(1, rows) * STRIDE;
        if (cells.length < need) cells = new int[need];
        cells[0] = (buffer == null) ? -1 : 0;
        int got = GhosttyNative.snapshot(handle, cells, cells.length); jniCalls++;
        if (got < 0) return null;
        if (got > cells.length) {
            cells = new int[got];
            cells[0] = (buffer == null) ? -1 : 0;
            if (GhosttyNative.snapshot(handle, cells, cells.length) != got) return null; jniCalls++;
        }
        int clusterTotal = cells[9];
        if (clusterTotal > 0) {
            if (clusters.length < clusterTotal) clusters = new int[Math.max(16, clusterTotal)];
            int clusterGot = GhosttyNative.clusters(handle, clusters); jniCalls++;
            if (clusterGot > clusters.length) {
                clusters = new int[clusterGot];
                clusterGot = GhosttyNative.clusters(handle, clusters); jniCalls++;
            }
            if (clusterGot != clusterTotal) return null;
        }
        int palGen = cells[7];
        if (!paletteValid || palGen != lastPaletteGen) {
            if (GhosttyNative.palette(handle, palette) != 259) return null; jniCalls++;
            for (int i = 0; i < 259; i++) palette[i] = 0xff000000 | (palette[i] & 0xffffff);
            lastPaletteGen = palGen;
            paletteValid = true;
        }
        captureCalls++;

        int frameCols = cells[1], frameRows = cells[2];
        lastFlags = cells[5];
        atBottom = cells[10] == 0;
        boolean newBuffer = (buffer == null || cols != frameCols || rows != frameRows);
        if (newBuffer) {
            cols = frameCols; rows = frameRows;
            buffer = new TerminalBuffer(cols, rows, rows);
        }
        if (scratchStyles.length != cols) {
            scratchStyles = new long[cols]; scratchStart = new int[cols]; scratchWidth = new int[cols];
        }
        if (scratchText.length < cols * 2 + 32) scratchText = new char[cols * 2 + 32];
        for (int y = 0; y < rows; y++) {
            int rowBase = HDR + (y * cols) * STRIDE;
            int rowAttr0 = cells[rowBase + 1];
            if ((rowAttr0 & ATTR_ROW_WRAP) != 0) buffer.setLineWrap(y); else buffer.clearLineWrap(y);
            if (!newBuffer && (rowAttr0 & ATTR_ROW_CLEAN) != 0) continue;
            int used = 0;
            for (int x = 0; x < cols; x++) {
                int base = rowBase + x * STRIDE;
                int cp = cells[base], attr = cells[base + 1];
                int fg = cells[base + 2], bg = cells[base + 3];
                int clusterLen = cells[base + 4], clusterOffset = cells[base + 5];
                if ((attr & ATTR_WIDE_TAIL) != 0) {
                    scratchStart[x] = x > 0 ? scratchStart[x - 1] : 0;
                    scratchWidth[x] = 0;
                    scratchStyles[x] = x > 0 ? scratchStyles[x - 1] : 0;
                    continue;
                }
                scratchStart[x] = used;
                scratchWidth[x] = (attr & ATTR_WIDE) != 0 ? 2 : 1;
                scratchStyles[x] = encodeStyle(fg, bg, attr);
                if (clusterLen > 0 && clusterOffset >= 0 && clusterOffset + clusterLen <= clusters.length) {
                    int needed = used + clusterLen * 2 + cols + 32;
                    if (scratchText.length < needed) {
                        char[] grown = new char[Math.max(needed, scratchText.length * 2)];
                        System.arraycopy(scratchText, 0, grown, 0, used); scratchText = grown;
                    }
                    for (int i = 0; i < clusterLen; i++) used += Character.toChars(clusters[clusterOffset + i], scratchText, used);
                } else if (cp != 0 && (attr & ATTR_HAS_TEXT) != 0) {
                    if (scratchText.length < used + 2) {
                        char[] grown = new char[Math.max(scratchText.length * 2, used + 2)];
                        System.arraycopy(scratchText, 0, grown, 0, used); scratchText = grown;
                    }
                    used += Character.toChars(cp, scratchText, used);
                } else {
                    if (scratchText.length < used + 1) {
                        char[] grown = new char[Math.max(scratchText.length * 2, used + 1)];
                        System.arraycopy(scratchText, 0, grown, 0, used); scratchText = grown;
                    }
                    scratchText[used++] = ' ';
                }
            }
            buffer.importFrameRow(y, scratchText, used, scratchStyles, scratchStart, scratchWidth);
        }
        int flags = cells[5];
        boolean inViewport = (flags & FLAG_CURSOR_IN_VIEWPORT) != 0;
        boolean visible = (flags & FLAG_CURSOR_VISIBLE) != 0 && inViewport;
        return new TerminalRenderFrame(buffer, rows, cols,
            cells[4] >= 0 ? cells[4] : 0, cells[3] >= 0 ? cells[3] : 0, visible, cells[6],
            (flags & FLAG_REVERSE_VIDEO) != 0, palette, cells[10], cells[11], true);
    }

    private static long encodeStyle(int fg, int bg, int attr) {
        int effect = 0;
        if ((attr & ATTR_BOLD) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_BOLD;
        if ((attr & ATTR_ITALIC) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_ITALIC;
        if ((attr & ATTR_FAINT) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_DIM;
        if ((attr & ATTR_BLINK) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_BLINK;
        if ((attr & ATTR_INVERSE) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
        if ((attr & ATTR_INVISIBLE) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE;
        if ((attr & ATTR_STRIKETHROUGH) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
        if (((attr >> ATTR_UNDERLINE_SHIFT) & ATTR_UNDERLINE_MASK) != 0) effect |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
        int fore = fg >= 0 ? (0xff000000 | (fg & 0xffffff)) : TextStyle.COLOR_INDEX_FOREGROUND;
        int back = bg >= 0 ? (0xff000000 | (bg & 0xffffff)) : TextStyle.COLOR_INDEX_BACKGROUND;
        return TextStyle.encodeFrameStyle(fore, back, effect);
    }

    public long[] scrollbar() { jniCalls++; return GhosttyNative.scrollbar(handle); }
    public long totalRows() { long[] b = scrollbar(); return b == null ? rows : b[0]; }
    public long historyRows() { long[] b = scrollbar(); return b == null ? 0 : b[3]; }
    public long viewportTop() { long[] b = scrollbar(); return b == null ? 0 : b[1]; }
    public int origin() { long[] b = scrollbar(); if (b == null) return 0; return (int)(b[1] - b[3]); }
    public void scrollTop() { atBottom = false; jniCalls++; GhosttyNative.scrollViewport(handle, SCROLL_TOP, 0); notifyActivity(); }
    public void scrollBottom() { atBottom = true; jniCalls++; GhosttyNative.scrollViewport(handle, SCROLL_BOTTOM, 0); notifyActivity(); }
    public void scrollDelta(int delta) { atBottom = false; jniCalls++; GhosttyNative.scrollViewport(handle, SCROLL_DELTA, delta); notifyActivity(); }
    public void scrollToRow(long offset) { atBottom = false; jniCalls++; GhosttyNative.scrollViewport(handle, SCROLL_ROW, offset); notifyActivity(); }
    public String copyText(int startRow, int startCol, int endRow, int endCol, boolean trim, boolean unwrap) {
        long[] bar = scrollbar();
        if (bar == null) return null;
        long history = bar[3];
        jniCalls++;
        byte[] bytes = GhosttyNative.copyTextBytes(handle, (int)(startRow + history), startCol,
            (int)(endRow + history), endCol, trim, unwrap);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
    public String hyperlinkAt(int screenRow, int column) {
        jniCalls++;
        byte[] uri = GhosttyNative.hyperlinkAt(handle, screenRow, column);
        notifyActivity();
        return uri == null ? null : new String(uri, StandardCharsets.UTF_8);
    }
    public String hyperlinkAtActive(int activeRow, int column) {
        long[] bar = scrollbar();
        if (bar == null) return null;
        long history = bar[3];
        long screenRow = activeRow + history;
        if (screenRow < 0 || screenRow >= bar[0] || column < 0 || column >= cols) return null;
        jniCalls++;
        byte[] uri = GhosttyNative.hyperlinkAtScreen(handle, (int) screenRow, column);
        notifyActivity();
        return uri == null ? null : new String(uri, StandardCharsets.UTF_8);
    }
    public int[] wordBounds(int activeRow, int column) {
        long[] bar = scrollbar();
        if (bar == null) return null;
        long history = bar[3];
        long screenRow = activeRow + history;
        if (screenRow < 0 || screenRow >= bar[0] || column < 0 || column >= cols) return null;
        jniCalls++;
        int[] b = GhosttyNative.selectWordAt(handle, (int) screenRow, column);
        notifyActivity();
        if (b == null || b.length != 4) return null;
        b[1] -= (int) history; b[3] -= (int) history;
        return b;
    }
    public int[] blankRowFlags(int startRow, int endRow) {
        long[] bar = scrollbar();
        if (bar == null) return null;
        long history = bar[3];
        long a = startRow + history, b = endRow + history;
        if (a < 0 || b < a || b >= bar[0]) return null;
        jniCalls++;
        int[] flags = GhosttyNative.rowBlankFlags(handle, (int) a, (int) b);
        return flags;
    }
    public int cellWide(int activeRow, int column) {
        long[] bar = scrollbar();
        if (bar == null) return -1;
        long history = bar[3];
        long screenRow = activeRow + history;
        if (screenRow < 0 || screenRow >= bar[0] || column < 0 || column >= cols) return -1;
        jniCalls++;
        int wide = GhosttyNative.cellWideAtScreen(handle, (int) screenRow, column);
        notifyActivity();
        return wide;
    }
    public void resetTerminal() { atBottom = true; lastFlags = 0; paletteValid = false; jniCalls++; GhosttyNative.reset(handle); notifyActivity(); }
    public void cancelHold() { jniCalls++; GhosttyNative.cancelHold(handle); }

    public int[] pollPalette() {
        int[] out = new int[259];
        jniCalls++;
        if (GhosttyNative.palette(handle, out) != 259) return null;
        for (int i = 0; i < 259; i++) out[i] = 0xff000000 | (out[i] & 0xffffff);
        return out;
    }

    public int queryMode(int mode) { jniCalls++; return GhosttyNative.queryMode(handle, mode); }
    public byte[] takeReplies() { jniCalls++; return GhosttyNative.takeReplies(handle); }
    public boolean replyOverflow() { return GhosttyNative.replyOverflow(handle); }
    public int lastFlags() { return lastFlags; }
    public int captureCalls() { return captureCalls; }
    public int jniCalls() { return jniCalls; }
    public long handle() { return handle; }
    public TerminalBuffer buffer() { return buffer; }

    public boolean closeChecked() {
        if (handle == 0) return true;
        if (GhosttyNative.destroyChecked(handle) != 0) return false;
        handle = 0;
        buffer = null; cells = new int[0]; clusters = new int[0];
        return true;
    }

    @Override public void close() { closeChecked(); }
}
