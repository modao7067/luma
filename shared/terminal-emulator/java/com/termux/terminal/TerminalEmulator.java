package com.termux.terminal;
/** Android host contract, with no VT parser, history ring or shadow main/alternate screen. */
public abstract class TerminalEmulator {
    public static final int MOUSE_LEFT_BUTTON = 0;
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_NO_BUTTON_MOVED = 35;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;
    public static final int UNICODE_REPLACEMENT_CHAR = 0xFFFD;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;
    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;
    public static final Integer[] TERMINAL_CURSOR_STYLES_LIST = new Integer[]{TERMINAL_CURSOR_STYLE_BLOCK, TERMINAL_CURSOR_STYLE_UNDERLINE, TERMINAL_CURSOR_STYLE_BAR};
    public static final int MODE_STATE_WORDS=80;
    public static final int KEYBOARD_STATE_WORDS=21;
    /** Restore confidence surfaced to the host; STALE/UNAVAILABLE never claim a full keyboard restore. */
    public static final int KEYBOARD_RESTORE_FRESH=0, KEYBOARD_RESTORE_FULL=1,
        KEYBOARD_RESTORE_STALE=2, KEYBOARD_RESTORE_UNAVAILABLE=3;
    public int mRows,mColumns;
    public final TerminalColors mColors=new TerminalColors();
    private final TerminalOutput output;private int cellWidth,cellHeight;
    protected TerminalSessionClient mClient;
    protected TerminalEmulator(TerminalOutput output,int columns,int rows,int cw,int ch,TerminalSessionClient client){
        this.output=java.util.Objects.requireNonNull(output);mColumns=columns;mRows=rows;cellWidth=cw;cellHeight=ch;mClient=client;
    }
    protected final TerminalOutput getOutputSink(){return output;}
    protected final void setCellSizePixels(int cw,int ch){cellWidth=cw;cellHeight=ch;}
    public final int getRows(){return mRows;}public final int getColumns(){return mColumns;}
    public final int getCellWidthPixels(){return cellWidth;}public final int getCellHeightPixels(){return cellHeight;}
    public final boolean usesNativeViewport(){return true;}
    public void updateTerminalSessionClient(TerminalSessionClient client){mClient=client;setCursorStyle();setCursorBlinkState(true);}
    // Native anchors replace the old Java scroll delta.
    public final int getScrollCounter(){return 0;}public final void clearScrollCounter(){}
    public abstract void setModeChangeListener(Runnable listener);
    public abstract int[] exportModeState();
    public abstract void restoreModeState(int[] state);
    /** Partial CSI intermediate from the keyboard annex; 0 keeps the historical v1 behavior. */
    public void restoreModeState(int[] state,int partialPrefix){restoreModeState(state);}
    public int[] exportKeyboardState(){return null;}
    /** 0 success; negative means no keyboard state was committed. */
    public int restoreKeyboardState(int[] words){return -1;}
    /** Null means the encoder rejected the event; an empty array means handled with no output. */
    public byte[] encodeKey(int key,int action,int mods,int consumedMods,byte[] utf8,int unshiftedCodepoint,boolean composing){return null;}
    public boolean isKeyboardProtocolActive(){return false;}
    public void setKeyboardRestoreState(int status,boolean keeperTracking){}
    public int getKeyboardRestoreStatus(){return KEYBOARD_RESTORE_UNAVAILABLE;}
    public boolean isKeeperKeyboardTracking(){return false;}
    public abstract TerminalRenderFrame getRenderFrame();
    public TerminalRenderFrame getRawRenderFrame(){return getRenderFrame();}
    public abstract TerminalBuffer getScreen();
    public abstract int getViewportTopRow();
    public abstract void setViewportTopRow(int topRow);
    public abstract void scrollViewportBottom();
    public abstract int getViewportHistoryRows();
    public abstract int getViewportTotalRows();
    public abstract String getViewportText(int topRow);
    public abstract String getViewportLink(int column,int row);
    public abstract int[] getWordBounds(int column,int row);
    public abstract int getValidSelectionColumn(int row,int column);
    public abstract String getTextAt(int column,int row);
    public abstract boolean isEmulatorDisposed();
    public abstract void captureFinalFrame();
    public abstract void dispose();
    public abstract void onUserInteraction();
    public abstract void setRenderingActive(boolean active);
    public abstract void setSelectionRange(int x1,int y1,int x2,int y2);
    public abstract int[] getSelectionRange();
    public abstract void clearSelectionRange();
    public abstract void sendFocusEvent(boolean focused);
    public abstract String getWorkingDirectory();
    public abstract boolean isAlternateBufferActive();
    /** Existing callers always supply one-based character cells, even in pixel reporting mode. */
    public abstract void sendMouseEvent(int button,int column,int row,boolean pressed);
    public boolean isMouseTrackingPixelMode(){return false;}
    /** One-based terminal pixels, not Android screen or character-cell coordinates. */
    public void sendMouseEventPixels(int button,int x,int y,boolean pressed){
        throw new UnsupportedOperationException("Pixel mouse reporting is not supported");
    }
    public abstract void resize(int columns,int rows,int width,int height);
    public abstract int getCursorRow();
    public abstract int getCursorCol();
    public abstract int getCursorStyle();
    public abstract void setCursorStyle();
    public abstract boolean isReverseVideo();
    public abstract boolean isCursorEnabled();
    public abstract boolean shouldCursorBeVisible();
    public abstract void setCursorBlinkingEnabled(boolean enabled);
    public abstract void setCursorBlinkState(boolean visible);
    public abstract boolean isKeypadApplicationMode();
    public abstract boolean isCursorKeysApplicationMode();
    public abstract boolean isMouseTrackingActive();
    public abstract boolean isMouseTrackingAnyEventActive();
    public abstract void append(byte[] bytes,int length);
    public abstract boolean isAutoScrollDisabled();
    public abstract void toggleAutoScrollDisabled();
    public abstract void reset();
    public abstract String getSelectedText(int x1,int y1,int x2,int y2);
    public abstract String getTitle();
    public abstract void paste(String text);
    /** Result of a native paste classification: written, needs user confirmation, or nothing/error. */
    public static final int PASTE_WRITTEN = 1, PASTE_NEEDS_CONFIRMATION = 0, PASTE_NOTHING = 2,
        PASTE_ERROR = -1, PASTE_TOO_LARGE = -2;
    /**
     * Paste one captured clipboard text through the native state-aware encoder. The first call uses
     * {@code allowUnsafe=false}; on {@link #PASTE_NEEDS_CONFIRMATION} the caller shows its dialog and
     * calls again with the same text and {@code true}. No output is written for any other result.
     */
    public abstract int pasteStatus(String text, boolean allowUnsafe);
}
