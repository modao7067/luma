package io.github.modao7067.luma.ghostty;

public final class GhosttyNative {
    private static boolean loaded;
    public static synchronized void load(String path) {
        if (!loaded) { System.load(path); installDecoder(); loaded = true; }
    }
    public static synchronized void loadLibrary() {
        if (!loaded) { System.loadLibrary("luma_vt_jni"); installDecoder(); loaded = true; }
    }
    private static native void installDecoder();
    private static native boolean setFrameDirectory(String path);
    public static synchronized boolean configureFrameDirectory(java.io.File cache) {
        loadLibrary();
        if (cache == null) return false;
        java.io.File frames = new java.io.File(cache,"kitty-frames");
        return (frames.isDirectory() || frames.mkdir()) && setFrameDirectory(frames.getAbsolutePath());
    }
    public static native int setDefaultCursor(long handle, int style);
    public static native void bindHost(long handle, GhosttyTerminalEmulator host);
    public static native long[] imagePlacements(long handle);
    public static native int[] imagePixels(long handle, int imageId);
    public static native boolean copyImage(long handle, int imageId, android.graphics.Bitmap target);
    public static native long animationTick(long handle, long nowMillis);
    public static native int setSelection(long handle, int x1, int y1, int x2, int y2, boolean clear);
    public static native int[] selection(long handle);
    public static native int[] checkpoint(long handle);
    public static native int[] keyboardCheckpoint(long handle);
    public static native int keyboardRestore(long handle, int[] words);
    public static native byte[] encodeKey(long handle, int key, int action, int mods, int consumedMods,
                                         byte[] utf8, int unshiftedCodepoint, boolean composing);
    public static native int[] takeEvents(long handle);
    public static native byte[] stringData(long handle, int kind);
    public static native long[] holdState(long handle);
    public static native int ignoreHold(long handle);
    public static native int ping();
    public static native String buildInfo();
    public static native long create(int cols, int rows, int scrollbackLines, int[] palette, int foreground, int background);
    public static native void destroy(long handle);
    public static native int feed(long handle, byte[] data, int offset, int length);
    public static native int paste(long handle, byte[] text, boolean allowUnsafe);
    public static native int resize(long handle, int cols, int rows, int cellWidth, int cellHeight);
    public static native int queryMode(long handle, int mode);
    public static native int snapshot(long handle, int[] out, int outLen);
    public static native int graphemeCluster(long handle, int row, int column, int[] out);
    public static native int clusters(long handle, int[] out);
    public static native int palette(long handle, int[] out);
    public static native long[] scrollbar(long handle);
    public static native int scrollViewport(long handle, int tag, long value);
    public static native byte[] copyTextBytes(long handle, int startRow, int startCol, int endRow, int endCol,
                                              boolean trim, boolean unwrap);
    public static native byte[] hyperlinkAt(long handle, int screenRow, int column);
    public static native byte[] hyperlinkAtScreen(long handle, int screenRow, int column);
    public static native int[] selectWordAt(long handle, int screenRow, int column);
    public static native int cellWideAtScreen(long handle, int screenRow, int column);
    public static native int[] rowBlankFlags(long handle, int startRow, int endRow);
    public static native int reset(long handle);
    public static native int cancelHold(long handle);
    public static native int destroyChecked(long handle);
    public static native int rowWrap(long handle, int[] out);
    public static native byte[] takeReplies(long handle);
    public static native boolean replyOverflow(long handle);
    public static native int renderHold(long handle);
    public static native int renderHoldEvents(long handle);
    public static native int[] placements(long handle);
    public static native int[] imageMeta(long handle, int imageId);
    public static native long[] memoryUsage(long handle);
    public static native long compressionActivity(long handle);
    public static native int compressStep(long handle);
}
