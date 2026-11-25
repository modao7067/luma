package io.github.modao7067.luma.ghostty;

import android.os.Handler;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

public final class GhosttyTerminalEmulator extends TerminalEmulator {
    private static boolean sLibraryLoaded;

    public static synchronized void loadLibrary(String path) {
        if (sLibraryLoaded) return;
        GhosttyFrameAdapter.load(path);
        sLibraryLoaded = true;
    }
    public static synchronized boolean isLibraryLoaded() { return sLibraryLoaded; }

    private GhosttyFrameAdapter mAdapter;
    private TerminalRenderFrame mFrame;
    private boolean mDirty = true;
    private boolean mDisposed;
    private int mLastFlags;
    private int mFeedCount;
    private final Handler mHandler = new Handler();
    private TerminalSessionClient mOwnClient;
    private String mNativeTitle, mWorkingDirectory;
    private boolean mBlinkEnabled, mBlinkVisible = true;
    private final GhosttyImages mImages = new GhosttyImages();
    private List<TerminalImage> mHeldImages;
    private long mHeldImageRevision;
    private boolean mRenderingActive = true;
    private long mAnimationDeadline;
    private final Runnable mAnimationTimer = () -> {
        if (mDisposed || mAdapter == null || !mRenderingActive) return;
        mAnimationDeadline = 0; updateAnimations(); mDirty = true;
        getOutputSink().onScreenChanged();
    };
    private long mScheduledDeadline;
    private Runnable mNativeModeListener;
    private int[] mLastCheckpoint;
    private int[] mLastKeyboardCheckpoint;
    private int mKeyboardRestoreStatus = KEYBOARD_RESTORE_UNAVAILABLE;
    private boolean mKeeperKeyboardTracking;
    private GhosttyScrollbackCompressor mHistoryCompressor;
    private boolean mAutoScrollDisabled;
    private final Runnable mHoldTimeout = () -> {
        if (mDisposed || mAdapter == null) return;
        long[] hold = GhosttyNative.holdState(mAdapter.handle());
        if (hold == null || hold[1] == 0) { mScheduledDeadline = 0; return; }
        long remaining = hold[1] - SystemClock.uptimeMillis();
        if (remaining > 0) { mHandler.postDelayed(mHoldTimeoutTask(), remaining); return; }
        mAdapter.cancelHold(); mScheduledDeadline = 0; mDirty = true; mHeldImages = null; mFrame = null;
        updateAnimations(); getOutputSink().onScreenChanged();
    };
    private Runnable mHoldTimeoutTask() { return mHoldTimeout; }

    public static synchronized void loadSystemLibrary() {
        if (!sLibraryLoaded) { GhosttyNative.loadLibrary(); sLibraryLoaded = true; }
    }

    public GhosttyTerminalEmulator(TerminalSession session, int columns, int rows,
                                   int cellWidthPixels, int cellHeightPixels,
                                   Integer transcriptRows, TerminalSessionClient client) {
        this((TerminalOutput)session,columns,rows,cellWidthPixels,cellHeightPixels,transcriptRows,client,null);
    }
    public static GhosttyTerminalEmulator forOutput(TerminalOutput output,int cols,int rows,int cw,int ch,Integer history,TerminalSessionClient client,int[] palette){
        loadSystemLibrary();return new GhosttyTerminalEmulator(output,cols,rows,cw,ch,history,client,palette);
    }
    private GhosttyTerminalEmulator(TerminalOutput output,int columns,int rows,int cellWidthPixels,int cellHeightPixels,Integer transcriptRows,TerminalSessionClient client,int[] initialPalette){
        super(output, columns, rows, cellWidthPixels, cellHeightPixels, client);
        mOwnClient = client;
        if (!sLibraryLoaded) throw new IllegalStateException("libghostty-vt is not loaded");
        if(initialPalette!=null)System.arraycopy(initialPalette,0,mColors.mCurrentColors,0,Math.min(initialPalette.length,mColors.mCurrentColors.length));
        int[] palette = mColors.mCurrentColors.clone();
        mAdapter = GhosttyFrameAdapter.create(columns, rows, transcriptRows == null ? 1000 : transcriptRows,
            palette, palette[256], palette[257]);
        if (mAdapter == null) throw new IllegalStateException("libghostty-vt terminal creation failed");
        if (mAdapter.resize(columns, rows, cellWidthPixels, cellHeightPixels) != 0) {
            mAdapter.close(); throw new IllegalStateException("native cell geometry rejected");
        }
        GhosttyNative.bindHost(mAdapter.handle(), this);
        setCursorStyle();
        final long handle = mAdapter.handle();
        mHistoryCompressor = new GhosttyScrollbackCompressor(mHandler, new GhosttyScrollbackCompressor.Backend() {
            public long activity() { return GhosttyNative.compressionActivity(handle); }
            public int step() { return GhosttyNative.compressStep(handle); }
        });
        mAdapter.setActivityObserver(this::observeHistoryActivity);
    }
    private void observeHistoryActivity() {
        if (mHistoryCompressor != null) mHistoryCompressor.observe();
    }

    @Override public void append(byte[] buffer, int length) {
        if (mDisposed || mAdapter == null) return;
        if (mAdapter.feed(buffer, 0, length) != 0) throw new IllegalStateException("native feed failed");
        mFeedCount++;
        int status = mAdapter.lastFeedStatus();
        if ((status & GhosttyFrameAdapter.FEED_HAS_REPLIES) != 0) {
            byte[] replies = mAdapter.takeReplies();
            if (replies != null && replies.length > 0) getOutputSink().write(replies, 0, replies.length);
        }
        if ((status & GhosttyFrameAdapter.FEED_REPLY_OVERFLOW) != 0)
            mOwnClient.logWarn("GhosttyTerminal", "Native reply capacity/allocation failure; retained replies drained");
        mDirty = true;
        mLastFlags = mAdapter.lastFlags();
        if ((status & GhosttyFrameAdapter.FEED_HAS_EVENTS) != 0) dispatchEvents();
        if ((status & GhosttyFrameAdapter.FEED_COLORS_CHANGED) != 0) {
            int[] current = mAdapter.pollPalette();
            if (current != null) syncPublicColors(current);
        }
        if ((status & (GhosttyFrameAdapter.FEED_HOLD_ACTIVE | GhosttyFrameAdapter.FEED_HOLD_CHANGED)) != 0 || mScheduledDeadline != 0)
            scheduleHoldTimeout();
        if ((status & GhosttyFrameAdapter.FEED_HAS_IMAGES) != 0 || mAnimationDeadline != 0)
            updateAnimations();
        if ((status & GhosttyFrameAdapter.FEED_ACTIVITY_CHANGED) != 0)
            observeHistoryActivity();
        if (mNativeModeListener != null && (mLastCheckpoint == null || (status & GhosttyFrameAdapter.FEED_MODES_MAYBE_CHANGED) != 0)) {
            int[] currentState = exportModeState();
            int[] currentKeyboard = exportKeyboardState();
            boolean changed = GhosttyModeState.modeChanged(mLastCheckpoint, currentState)
                || !java.util.Arrays.equals(mLastKeyboardCheckpoint, currentKeyboard);
            mLastCheckpoint = currentState;
            mLastKeyboardCheckpoint = currentKeyboard;
            if (changed) mNativeModeListener.run();
        }
    }

    @Override public int[] exportModeState() {
        if (mAdapter == null) throw new IllegalStateException("native terminal disposed");
        int[] state = GhosttyNative.checkpoint(mAdapter.handle());
        if (state == null) throw new IllegalStateException("native mode export failed");
        state[15] = mAutoScrollDisabled ? 1 : 0; return state;
    }
    @Override public int[] exportKeyboardState() {
        if (mDisposed || mAdapter == null) return null;
        int[] state = GhosttyNative.keyboardCheckpoint(mAdapter.handle());
        return state != null && state.length == KEYBOARD_STATE_WORDS ? state : null;
    }
    @Override public int restoreKeyboardState(int[] words) {
        if (mDisposed || mAdapter == null || words == null || words.length != KEYBOARD_STATE_WORDS) return -1;
        int result = GhosttyNative.keyboardRestore(mAdapter.handle(), words);
        if (result == 0) {
            mLastKeyboardCheckpoint = exportKeyboardState();
            mDirty = true;
        }
        return result;
    }
    @Override public byte[] encodeKey(int key,int action,int mods,int consumedMods,byte[] utf8,
                                      int unshiftedCodepoint,boolean composing) {
        if (mDisposed || mAdapter == null) return null;
        return GhosttyNative.encodeKey(mAdapter.handle(), key, action, mods, consumedMods,
            utf8, unshiftedCodepoint, composing);
    }
    @Override public boolean isKeyboardProtocolActive() {
        if (mDisposed || mAdapter == null) return false;
        int[] modes = exportModeState();
        int[] keyboard = exportKeyboardState();
        if (modes == null || keyboard == null) return false;
        boolean alternate = modes[5] != 0;
        int index = keyboard[alternate ? 11 : 2];
        int flags = keyboard[(alternate ? 12 : 3) + index];
        return flags != 0 || (keyboard[1] & 1) != 0;
    }
    @Override public void setKeyboardRestoreState(int status,boolean keeperTracking) {
        mKeyboardRestoreStatus = status;
        mKeeperKeyboardTracking = keeperTracking;
    }
    @Override public int getKeyboardRestoreStatus() { return mKeyboardRestoreStatus; }
    @Override public boolean isKeeperKeyboardTracking() { return mKeeperKeyboardTracking; }

    @Override public void restoreModeState(int[] values) {
        restoreModeState(values, 0);
    }
    public void restoreModeState(int[] values,int partialPrefix) {
        byte[] setup = GhosttyModeState.restore(values, partialPrefix, mColumns, mRows);
        if (mAdapter == null || mAdapter.feed(setup, 0, setup.length) != 0)
            throw new IllegalStateException("native mode restore failed");
        mAutoScrollDisabled = values[15] != 0;
        GhosttyNative.takeEvents(mAdapter.handle()); mAdapter.takeReplies();
        mDirty = true; mFrame = null; mLastCheckpoint = exportModeState();
        mLastKeyboardCheckpoint = exportKeyboardState();
        scheduleHoldTimeout(); observeHistoryActivity();
    }
    @Override public void setModeChangeListener(Runnable listener) {
        mNativeModeListener = listener;
        mLastCheckpoint = listener != null && mAdapter != null ? exportModeState() : null;
        mLastKeyboardCheckpoint = listener != null && mAdapter != null ? exportKeyboardState() : null;
    }
    @Override public boolean isAutoScrollDisabled() { return mAutoScrollDisabled; }
    @Override public void toggleAutoScrollDisabled() {
        mAutoScrollDisabled = !mAutoScrollDisabled;
        if (mNativeModeListener != null) mNativeModeListener.run();
    }

    private static final int MAX_CLIPBOARD_CHARS = 262_144;

    private static String clampClipboardText(String text) {
        if (text.length() <= MAX_CLIPBOARD_CHARS) return text;
        int end = MAX_CLIPBOARD_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private void onNativeReplies(byte[] bytes) {
        if (bytes != null && bytes.length > 0) getOutputSink().write(bytes, 0, bytes.length);
    }

    private void onRenderHold(boolean held) {
        try {
            if (held) {
                if (mDisposed || mAdapter == null) { mHeldImages = null; return; }
                mHeldImages = mImages.capture(mAdapter.handle(), getCellWidthPixels(), getCellHeightPixels());
                mHeldImageRevision = mImages.revision;
            } else { mHeldImages = null; mFrame = null; mDirty = true; }
        } catch (RuntimeException | OutOfMemoryError ignored) {
            mHeldImages = null;
        }
    }
    private void updateAnimations() {
        if (mAdapter == null) return;
        long delay = mRenderingActive ? GhosttyNative.animationTick(mAdapter.handle(), SystemClock.uptimeMillis()) : -1;
        long deadline = delay < 0 ? 0 : SystemClock.uptimeMillis() + Math.max(1, delay);
        if (deadline == mAnimationDeadline) return;
        mHandler.removeCallbacks(mAnimationTimer); mAnimationDeadline = deadline;
        if (deadline != 0) mHandler.postAtTime(mAnimationTimer, deadline);
    }
    @Override public void setRenderingActive(boolean active) {
        mRenderingActive = active; updateAnimations();
    }

    private void dispatchEvents() {
        int[] events = GhosttyNative.takeEvents(mAdapter.handle());
        if (events == null) throw new IllegalStateException("native metadata read failed");
        for (int i = 0; i + 1 < events.length; i += 2) {
            switch (events[i]) {
                case 1: {
                    String old = mNativeTitle; mNativeTitle = readString(1);
                    if (!java.util.Objects.equals(old, mNativeTitle)) getOutputSink().titleChanged(old, mNativeTitle);
                    break;
                }
                case 2: mWorkingDirectory = readString(2); break;
                case 3: getOutputSink().onBell(); break;
                case 4: getOutputSink().onCommandState(events[i + 1]); break;
                case 5: {
                    String old = mNativeTitle; mNativeTitle = null; mWorkingDirectory = null;
                    if (old != null) getOutputSink().titleChanged(old, null);
                    break;
                }
                case 6: {
                    String clip = readString(6);
                    if (clip != null) getOutputSink().onCopyTextToClipboard(clampClipboardText(clip));
                    break;
                }
                default: break;
            }
        }
    }
    private String readString(int kind) {
        byte[] bytes = GhosttyNative.stringData(mAdapter.handle(), kind);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
    private void scheduleHoldTimeout() {
        long[] hold = GhosttyNative.holdState(mAdapter.handle());
        long deadline = hold == null ? 0 : hold[1];
        if (deadline == mScheduledDeadline) return;
        mHandler.removeCallbacks(mHoldTimeout); mScheduledDeadline = deadline;
        if (deadline != 0) mHandler.postAtTime(mHoldTimeout, deadline);
    }
    @Override public void onUserInteraction() {
        if (mDisposed || mAdapter == null) return;
        if (mHistoryCompressor != null) mHistoryCompressor.userActivity();
        if (GhosttyNative.ignoreHold(mAdapter.handle()) > 0) {
            mHeldImages = null; mFrame = null; mDirty = true; updateAnimations(); getOutputSink().onScreenChanged();
        }
    }
    @Override public void setSelectionRange(int x1, int y1, int x2, int y2) {
        if (mAdapter != null) {
            GhosttyNative.setSelection(mAdapter.handle(), Math.max(0, Math.min(mColumns-1, x1)), y1,
                Math.max(0, Math.min(mColumns-1, x2)), y2, false);
            observeHistoryActivity();
        }
    }
    @Override public int[] getSelectionRange() {
        return mAdapter == null ? null : GhosttyNative.selection(mAdapter.handle());
    }
    @Override public void clearSelectionRange() {
        if (mAdapter != null) GhosttyNative.setSelection(mAdapter.handle(), 0,0,0,0, true);
    }
    @Override public String getTitle() { return mNativeTitle; }
    @Override public String getWorkingDirectory() { return mWorkingDirectory; }
    @Override public void updateTerminalSessionClient(TerminalSessionClient client) {
        mOwnClient = client; super.updateTerminalSessionClient(client);
    }
    @Override public void setCursorStyle() {
        if (mAdapter == null) return;
        Integer style = mOwnClient == null ? null : mOwnClient.getTerminalCursorStyle();
        GhosttyNative.setDefaultCursor(mAdapter.handle(), style == null ? 0 : style); mDirty = true;
    }
    @Override public void setCursorBlinkingEnabled(boolean enabled) { mBlinkEnabled = enabled; }
    @Override public void setCursorBlinkState(boolean visible) { mBlinkVisible = visible; }
    @Override public void sendFocusEvent(boolean focused) {
        if (mAdapter != null && mAdapter.queryMode(1004) == 1) getOutputSink().write(focused ? "\033[I" : "\033[O");
    }
    @Override public boolean isMouseTrackingPixelMode() {
        return !mDisposed && mAdapter != null && mAdapter.queryMode(1016) == 1;
    }
    @Override public void sendMouseEvent(int button, int column, int row, boolean pressed) {
        column = Math.max(1, Math.min(mColumns, column)); row = Math.max(1, Math.min(mRows, row));
        boolean pixels = isMouseTrackingPixelMode();
        if (pixels) {
            column = (int)Math.min(Integer.MAX_VALUE, (long)(column-1)*Math.max(1,getCellWidthPixels()) + Math.max(1,getCellWidthPixels())/2 + 1);
            row = (int)Math.min(Integer.MAX_VALUE, (long)(row-1)*Math.max(1,getCellHeightPixels()) + Math.max(1,getCellHeightPixels())/2 + 1);
        }
        sendMouseReport(button, column, row, pressed, pixels);
    }
    @Override public void sendMouseEventPixels(int button, int x, int y, boolean pressed) {
        if (isMouseTrackingPixelMode()) sendMouseReport(button, x, y, pressed, true);
    }
    private void sendMouseReport(int button, int x, int y, boolean pressed, boolean pixels) {
        if (mDisposed || mAdapter == null || !isMouseTrackingActive()) return;
        int maxX = pixels ? (int)Math.min(Integer.MAX_VALUE, Math.max(1L,(long)mColumns*getCellWidthPixels())) : mColumns;
        int maxY = pixels ? (int)Math.min(Integer.MAX_VALUE, Math.max(1L,(long)mRows*getCellHeightPixels())) : mRows;
        x = Math.max(1, Math.min(maxX, x)); y = Math.max(1, Math.min(maxY, y));
        boolean any = isMouseTrackingAnyEventActive();
        if ((button == MOUSE_LEFT_BUTTON_MOVED && mAdapter.queryMode(1002) != 1 && !any)
                || (button == MOUSE_NO_BUTTON_MOVED && !any)) return;
        if (pixels || mAdapter.queryMode(1006) == 1) {
            getOutputSink().write(String.format(Locale.ROOT, "\033[<%d;%d;%d%c", button, x, y, pressed ? 'M' : 'm'));
        } else if (x <= 223 && y <= 223) {
            byte[] bytes = {27, '[', 'M', (byte)(32 + (pressed ? button : 3)), (byte)(32 + x), (byte)(32 + y)};
            getOutputSink().write(bytes, 0, bytes.length);
        }
    }

    private TerminalRenderFrame frame() {
        if (mDisposed || mAdapter == null) throw new IllegalStateException("native terminal disposed");
        if (mFrame == null || mDirty) {
            mFrame = mAdapter.capture();
            if (mFrame != null) {
                List<TerminalImage> images = mHeldImages != null ? mHeldImages
                    : (mAdapter.hasImagesInLastFrame()
                        ? mImages.capture(mAdapter.handle(), getCellWidthPixels(), getCellHeightPixels())
                        : mImages.empty());
                mFrame = mFrame.withImages(images, mHeldImages != null ? mHeldImageRevision : mImages.revision);
            }
            mDirty = false;
            if (mFrame != null) {
                syncPublicColors(mFrame.palette);
                mLastFlags = mAdapter.lastFlags();
                if (mFrame.sourceTopRow < 0) observeHistoryActivity();
            }
        }
        if (mFrame == null) throw new IllegalStateException("native frame unavailable");
        return mFrame;
    }

    private void syncPublicColors(int[] palette) {
        boolean changed = false;
        for (int i = 0; i < 259 && i < palette.length; i++) {
            int argb = 0xff000000 | (palette[i] & 0xffffff);
            if (mColors.mCurrentColors[i] != argb) { mColors.mCurrentColors[i] = argb; changed = true; }
        }
        if (changed) getOutputSink().onColorsChanged();
    }

    public TerminalRenderFrame getRenderFrame() {
        TerminalRenderFrame f = frame();
        if (!mBlinkEnabled || mBlinkVisible || !f.cursorVisible) return f;
        return f.withCursorVisible(false);
    }

    @Override public TerminalRenderFrame getRawRenderFrame() {
        return frame();
    }

    @Override public TerminalBuffer getScreen() { return frame().screen; }
    @Override public int getCursorRow() { return frame().cursorRow; }
    @Override public int getCursorCol() { return frame().cursorColumn; }
    @Override public boolean shouldCursorBeVisible() { return frame().cursorVisible && (!mBlinkEnabled || mBlinkVisible); }
    @Override public int getCursorStyle() { return frame().cursorStyle; }
    @Override public boolean isReverseVideo() {
        if (mAdapter == null) return (mLastFlags & (1 << 5)) != 0;
        return (mAdapter.lastFlags() & (1 << 5)) != 0;
    }
    @Override public boolean isAlternateBufferActive() {
        if (mAdapter == null) return (mLastFlags & (1 << 4)) != 0;
        return (mAdapter.lastFlags() & (1 << 4)) != 0;
    }
    @Override public boolean isCursorEnabled() { return mAdapter != null && mAdapter.queryMode(25) == 1; }
    @Override public boolean isCursorKeysApplicationMode() { return mAdapter != null && mAdapter.queryMode(1) == 1; }
    @Override public boolean isKeypadApplicationMode() { return mAdapter != null && mAdapter.queryMode(66) == 1; }
    @Override public boolean isMouseTrackingActive() {
        if (mAdapter == null) return false;
        return mAdapter.queryMode(1000) == 1 || mAdapter.queryMode(1002) == 1 || mAdapter.queryMode(1003) == 1;
    }
    @Override public boolean isMouseTrackingAnyEventActive() { return mAdapter != null && mAdapter.queryMode(1003) == 1; }

    @Override public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mDisposed || mAdapter == null) return;
        if (mAdapter.resize(columns, rows, cellWidthPixels, cellHeightPixels) != 0)
            throw new IllegalArgumentException("native resize rejected");
        byte[] replies = mAdapter.takeReplies();
        if (replies != null && replies.length > 0) getOutputSink().write(replies, 0, replies.length);
        if (mAdapter.replyOverflow()) throw new IllegalStateException("native reply buffer overflow");
        mRows = rows; mColumns = columns;
        setCellSizePixels(cellWidthPixels, cellHeightPixels);
        mHeldImages = null;
        mFrame = null; mDirty = true; scheduleHoldTimeout(); observeHistoryActivity();
    }

    @Override public void reset() {
        if (mDisposed) return;
        if (mAdapter == null) return;
        mAdapter.resetTerminal();
        mAdapter.cancelHold();
        mHeldImages = null;
        mImages.clear();
        mLastFlags = 0; mFrame = null; mDirty = true; dispatchEvents(); scheduleHoldTimeout();
    }

    @Override public String getSelectedText(int x1, int y1, int x2, int y2) {
        if (mDisposed || mAdapter == null) return "";
        onUserInteraction();
        if (y1 > y2 || (y1 == y2 && x1 > x2)) {
            int tx = x1; x1 = x2; x2 = tx;
            int ty = y1; y1 = y2; y2 = ty;
        }
        long history = mAdapter.historyRows();
        int minRow = (int) -history;
        if (y1 < minRow) y1 = minRow;
        if (y2 > mRows - 1) y2 = mRows - 1;
        if (y2 < y1) return "";
        int left = Math.max(0, Math.min(x1, mColumns - 1));
        int right = Math.max(0, Math.min(x2, mColumns - 1));
        if (left > right && y1 == y2) return "";
        String text = mAdapter.copyText(y1, left, y2, right, true, true);
        if (text == null) text = "";
        int[] blanks = mAdapter.blankRowFlags(y1, y2);
        if (blanks != null && blanks.length > 0) {
            int lastText = -1;
            for (int i = 0; i < blanks.length; i++) if (blanks[i] == 0) lastText = i;
            int trailing = lastText < 0 ? blanks.length - 1 : blanks.length - 1 - lastText;
            for (int i = 0; i < trailing; i++) text += "\n";
        }
        observeHistoryActivity(); return text;
    }

    @Override public int getViewportTopRow() {
        if (mDisposed || mAdapter == null) return 0;
        return mAdapter.origin();
    }
    @Override public void setViewportTopRow(int topRow) {
        if (mDisposed || mAdapter == null) return;
        onUserInteraction();
        long history = mAdapter.historyRows();
        long clamped = Math.max(-history, Math.min(0, topRow));
        if (clamped == 0) mAdapter.scrollBottom();
        else mAdapter.scrollToRow(history + clamped);
        mDirty = true;
    }
    @Override public void scrollViewportBottom() {
        if (mDisposed || mAdapter == null) return;
        if (mAdapter.isViewportAtBottom()) return;
        mAdapter.scrollBottom(); mDirty = true;
    }
    @Override public int getViewportHistoryRows() {
        return (mDisposed || mAdapter == null) ? 0 : (int) mAdapter.historyRows();
    }
    @Override public int getViewportTotalRows() {
        return (mDisposed || mAdapter == null) ? mRows : (int) mAdapter.totalRows();
    }
    @Override public String getViewportText(int topRow) {
        if (mDisposed || mAdapter == null) return "";
        if (GhosttyNative.renderHold(mAdapter.handle()) == 1) {
            TerminalRenderFrame held = frame();
            return held.screen.getSelectedText(0, 0, mColumns - 1, mRows - 1);
        }
        String text = mAdapter.copyText(topRow, 0, topRow + mRows - 1, mColumns - 1, false, true);
        observeHistoryActivity(); return text == null ? "" : text;
    }
    @Override public String getViewportLink(int column, int activeRow) {
        if (mDisposed || mAdapter == null) return null;
        return mAdapter.hyperlinkAtActive(activeRow, column);
    }
    @Override public int[] getWordBounds(int column, int activeRow) {
        if (mDisposed || mAdapter == null) return null;
        return mAdapter.wordBounds(activeRow, column);
    }
    @Override public int getValidSelectionColumn(int activeRow, int column) {
        if (mDisposed || mAdapter == null) return column;
        int wide = mAdapter.cellWide(activeRow, column);
        return wide == 2 ? Math.min(column + 1, mColumns) : column;
    }
    @Override public String getTextAt(int column, int activeRow) {
        if (mDisposed || mAdapter == null) return "";
        String text = mAdapter.copyText(activeRow, column, activeRow, column, false, false);
        observeHistoryActivity(); return text == null ? "" : text;
    }
    @Override public boolean isEmulatorDisposed() { return mDisposed; }
    @Override public void captureFinalFrame() {
        if (!mDisposed) { mDirty = true; frame(); }
    }

    private int pasteInternal(String text, boolean allowUnsafe, boolean notifyOverflow) {
        if (mDisposed || mAdapter == null) return PASTE_ERROR;
        if (text == null || text.isEmpty()) return PASTE_NOTHING;
        if (mHistoryCompressor != null) mHistoryCompressor.userActivity();
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int status = mAdapter.paste(utf8, allowUnsafe);
        if (status == PASTE_WRITTEN) {
            byte[] replies = mAdapter.takeReplies();
            if (replies != null && replies.length > 0 && !getOutputSink().writeChecked(replies, 0, replies.length)) {
                if (notifyOverflow && mClient != null && getOutputSink() instanceof com.termux.terminal.TerminalSession) {
                    mClient.onInputBufferFull((com.termux.terminal.TerminalSession) getOutputSink(), replies.length);
                }
                status = PASTE_ERROR;
            }
        }
        if (mAdapter.replyOverflow()) throw new IllegalStateException("native reply buffer overflow");
        return status;
    }

    @Override public int pasteStatus(String text, boolean allowUnsafe) {
        return pasteInternal(text, allowUnsafe, false);
    }

    @Override public void paste(String text) {
        pasteInternal(text, false, true);
    }

    public int feedCount() { return mFeedCount; }
    public GhosttyFrameAdapter adapter() { return mAdapter; }
    GhosttyScrollbackCompressor compressor() { return mHistoryCompressor; }

    @Override public void dispose() {
        if (mDisposed) return;
        if (mAdapter == null) { mDisposed = true; mFrame = null; return; }
        if (!mAdapter.closeChecked()) throw new IllegalStateException("native dispose requires the owner thread");
        mAdapter.setActivityObserver(null);
        if (mHistoryCompressor != null) { mHistoryCompressor.close(); mHistoryCompressor = null; }
        mHandler.removeCallbacks(mHoldTimeout); mScheduledDeadline = 0;
        mHandler.removeCallbacks(mAnimationTimer); mAnimationDeadline = 0;
        mHeldImages = null; mImages.clear();
        mAdapter = null;
        mFrame = null;
        mLastFlags = 0;
        mLastCheckpoint = null; mLastKeyboardCheckpoint = null;
        mDisposed = true;
    }
}
