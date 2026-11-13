package com.termux.terminal;

/**
 * Read-only frame handed to a renderer. It contains the visible screen buffer, cursor, reverse-video
 * state and palette; it has no dependency on the native engine or the Root package. A native adapter builds this frame from visible projected rows.
 */
public final class TerminalRenderFrame {
    public final TerminalBuffer screen;
    public final int rows, columns;
    public final int cursorRow, cursorColumn, cursorStyle;
    public final boolean cursorVisible, reverseVideo;
    public final int[] palette;
    /** Active-screen coordinate of buffer row 0: negative while scrolled into history, 0 at the bottom. */
    public final int sourceTopRow;
    /** Total active-screen rows (history + screen) at capture time. */
    public final int totalRows;
    /** True when screen row 0 is the current native viewport (not active row 0). */
    public final boolean nativeViewport;
    public final java.util.List<TerminalImage> images;
    public final long imageRevision;

    public TerminalRenderFrame(TerminalBuffer screen, int rows, int columns, int cursorRow, int cursorColumn,
                               boolean cursorVisible, int cursorStyle, boolean reverseVideo, int[] palette) {
        this(screen, rows, columns, cursorRow, cursorColumn, cursorVisible, cursorStyle, reverseVideo, palette, 0, rows, false);
    }

    public TerminalRenderFrame(TerminalBuffer screen, int rows, int columns, int cursorRow, int cursorColumn,
                               boolean cursorVisible, int cursorStyle, boolean reverseVideo, int[] palette,
                               int sourceTopRow, int totalRows) {
        this(screen, rows, columns, cursorRow, cursorColumn, cursorVisible, cursorStyle, reverseVideo,
            palette, sourceTopRow, totalRows, false);
    }

    public TerminalRenderFrame(TerminalBuffer screen, int rows, int columns, int cursorRow, int cursorColumn,
                               boolean cursorVisible, int cursorStyle, boolean reverseVideo, int[] palette,
                               int sourceTopRow, int totalRows, boolean nativeViewport) {
        this(screen, rows, columns, cursorRow, cursorColumn, cursorVisible, cursorStyle, reverseVideo,
            palette, sourceTopRow, totalRows, nativeViewport, java.util.Collections.emptyList(), 0);
    }

    private TerminalRenderFrame(TerminalBuffer screen, int rows, int columns, int cursorRow, int cursorColumn,
            boolean cursorVisible, int cursorStyle, boolean reverseVideo, int[] palette, int sourceTopRow,
            int totalRows, boolean nativeViewport, java.util.List<TerminalImage> images, long imageRevision) {
        this.images = images; this.imageRevision = imageRevision;
        this.screen = screen;
        this.rows = rows;
        this.columns = columns;
        this.cursorRow = cursorRow;
        this.cursorColumn = cursorColumn;
        this.cursorVisible = cursorVisible;
        this.cursorStyle = cursorStyle;
        this.reverseVideo = reverseVideo;
        this.palette = palette;
        this.sourceTopRow = sourceTopRow;
        this.totalRows = totalRows;
        this.nativeViewport = nativeViewport;
    }

    public TerminalRenderFrame withImages(java.util.List<TerminalImage> images, long revision) {
        return new TerminalRenderFrame(screen, rows, columns, cursorRow, cursorColumn, cursorVisible,
            cursorStyle, reverseVideo, palette, sourceTopRow, totalRows, nativeViewport, images, revision);
    }
    public TerminalRenderFrame withCursorVisible(boolean visible) {
        return new TerminalRenderFrame(screen, rows, columns, cursorRow, cursorColumn, visible,
            cursorStyle, reverseVideo, palette, sourceTopRow, totalRows, nativeViewport, images, imageRevision);
    }

}
