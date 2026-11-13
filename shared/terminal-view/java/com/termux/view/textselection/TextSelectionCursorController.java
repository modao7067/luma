package com.termux.view.textselection;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.WcWidth;
import com.termux.view.R;
import com.termux.view.TerminalView;
import com.termux.view.TerminalTextCopy;

public class TextSelectionCursorController implements CursorController {

    private final TerminalView terminalView;
    private final TextSelectionHandleView mStartHandle, mEndHandle;
    private String mStoredSelectedText;
    private boolean mIsSelectingText = false;
    private long mShowStartTime = System.currentTimeMillis();

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;

    private ActionMode mActionMode;
    public final int ACTION_COPY = 1;
    public final int ACTION_PASTE = 2;
    public final int ACTION_MORE = 3;
    public final int ACTION_COPY_JOINED = 4;

    public TextSelectionCursorController(TerminalView terminalView) {
        this.terminalView = terminalView;
        mStartHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT);
        mEndHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT);

        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight());
    }

    @Override
    public void show(MotionEvent event) {
        setInitialTextSelectionPosition(event);
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true);

        setActionModeCallBacks();
        mShowStartTime = System.currentTimeMillis();
        mIsSelectingText = true;
        saveNativeSelection();
    }

    @Override
    public boolean hide() {
        if (!isActive()) return false;

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }

        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsSelectingText = false;
        terminalView.mEmulator.clearSelectionRange();

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }

    public void setInitialTextSelectionPosition(MotionEvent event) {
        terminalView.mEmulator.onUserInteraction();
        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        mSelX1 = mSelX2 = columnAndRow[0];
        mSelY1 = mSelY2 = columnAndRow[1];

        int[] word = terminalView.mEmulator.getWordBounds(mSelX1, mSelY1);
        if (word != null) {
            mSelX1 = word[0]; mSelY1 = word[1]; mSelX2 = word[2]; mSelY2 = word[3];
            return;
        }
        if (terminalView.mEmulator.usesNativeViewport()) return;   // no word under whitespace
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        if (!" ".equals(screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1))) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && !"".equals(screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1 - 1, mSelY1))) {
                mSelX1--;
            }
            while (mSelX2 < terminalView.mEmulator.mColumns - 1 && !"".equals(screen.getSelectedText(mSelX2 + 1, mSelY1, mSelX2 + 1, mSelY1))) {
                mSelX2++;
            }
        }
    }
    
    public void setActionModeCallBacks() {
        final ActionMode.Callback callback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                int show = MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT;

                ClipboardManager clipboard = (ClipboardManager) terminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_COPY_JOINED, Menu.NONE, R.string.copy_joined_text).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text).setEnabled(clipboard != null && clipboard.hasPrimaryClip()).setShowAsAction(show);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!isActive()) {
                    // Fix issue where the dialog is pressed while being dismissed.
                    return true;
                }

                switch (item.getItemId()) {
                    case ACTION_COPY:
                    case ACTION_COPY_JOINED:
                        String selectedText = getSelectedText();
                        if (item.getItemId() == ACTION_COPY_JOINED)
                            selectedText = TerminalTextCopy.joinLines(selectedText);
                        terminalView.mTermSession.onCopyTextToClipboard(selectedText);
                        terminalView.stopTextSelectionMode();
                        break;
                    case ACTION_PASTE:
                        terminalView.stopTextSelectionMode();
                        terminalView.pasteFromClipboard();
                        break;
                    case ACTION_MORE:
                        // We first store the selected text in case TerminalViewClient needs the
                        // selected text before MORE button was pressed since we are going to
                        // stop selection mode
                        mStoredSelectedText = getSelectedText();
                        // The text selection needs to be stopped before showing context menu,
                        // otherwise handles will show above popup
                        terminalView.stopTextSelectionMode();
                        terminalView.showContextMenu();
                        break;
                }

                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }

        };

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mActionMode = terminalView.startActionMode(callback);
            return;
        }

        //noinspection NewApi
        mActionMode = terminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return callback.onCreateActionMode(mode, menu);
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // Ignore.
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                int x1 = Math.round(mSelX1 * terminalView.mRenderer.getFontWidth());
                int x2 = Math.round(mSelX2 * terminalView.mRenderer.getFontWidth());
                int y1 = Math.round((mSelY1 - 1 - terminalView.getTopRow()) * terminalView.mRenderer.getFontLineSpacing());
                int y2 = Math.round((mSelY2 + 1 - terminalView.getTopRow()) * terminalView.mRenderer.getFontLineSpacing());

                if (x1 > x2) {
                    int tmp = x1;
                    x1 = x2;
                    x2 = tmp;
                }

                int terminalBottom = terminalView.getBottom();
                int top = y1 + mHandleHeight;
                int bottom = y2 + mHandleHeight;
                if (top > terminalBottom) top = terminalBottom;
                if (bottom > terminalBottom) bottom = terminalBottom;

                outRect.set(x1, top, x2, bottom);
            }
        }, ActionMode.TYPE_FLOATING);
    }

    @Override
    public void updatePosition(TextSelectionHandleView handle, int x, int y) {
        syncNativeSelection();
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int scrollRows = terminalView.mEmulator.getViewportHistoryRows();
        if (handle == mStartHandle) {
            mSelX1 = terminalView.getCursorX(x);
            mSelY1 = terminalView.getCursorY(y);
            if (mSelX1 < 0) {
                mSelX1 = 0;
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows;

            } else if (mSelY1 > terminalView.mEmulator.mRows - 1) {
                mSelY1 = terminalView.mEmulator.mRows - 1;

            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY1 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY1 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX1 = getValidCurX(screen, mSelY1, mSelX1);

        } else {
            mSelX2 = terminalView.getCursorX(x);
            mSelY2 = terminalView.getCursorY(y);
            if (mSelX2 < 0) {
                mSelX2 = 0;
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows;
            } else if (mSelY2 > terminalView.mEmulator.mRows - 1) {
                mSelY2 = terminalView.mEmulator.mRows - 1;
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY2 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY2 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX2 = getValidCurX(screen, mSelY2, mSelX2);
        }

        saveNativeSelection();
        terminalView.invalidate();
    }

    private void saveNativeSelection() {
        if (terminalView.mEmulator.usesNativeViewport())
            terminalView.mEmulator.setSelectionRange(mSelX1, mSelY1, mSelX2, mSelY2);
    }
    private void syncNativeSelection() {
        if (!mIsSelectingText || !terminalView.mEmulator.usesNativeViewport()) return;
        int[] range = terminalView.mEmulator.getSelectionRange();
        if (range == null) {
            mIsSelectingText = false; mStartHandle.hide(); mEndHandle.hide();
            mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        } else { mSelX1 = range[0]; mSelY1 = range[1]; mSelX2 = range[2]; mSelY2 = range[3]; }
    }

    private int getValidCurX(TerminalBuffer screen, int cy, int cx) {
        if (terminalView.mEmulator.usesNativeViewport())
            return terminalView.mEmulator.getValidSelectionColumn(cy, cx);
        String line = screen.getSelectedText(0, cy, cx, cy);
        if (!TextUtils.isEmpty(line)) {
            int col = 0;
            for (int i = 0, len = line.length(); i < len; i++) {
                char ch1 = line.charAt(i);
                if (ch1 == 0) {
                    break;
                }

                int wc;
                if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    char ch2 = line.charAt(++i);
                    wc = WcWidth.width(Character.toCodePoint(ch1, ch2));
                } else {
                    wc = WcWidth.width(ch1);
                }

                final int cend = col + wc;
                if (cx > col && cx < cend) {
                    return cend;
                }
                if (cend == col) {
                    return col;
                }
                col = cend;
            }
        }
        return cx;
    }

    public int terminalViewWidth() { return terminalView.getWidth(); }
    public int terminalViewTopRow() { return terminalView.getTopRow(); }
    public float terminalViewLineSpacing() { return terminalView.mRenderer.getFontLineSpacing(); }

    /** Keep active selection endpoints inside the history that still exists. */
    public void clampToHistory(int minRow) {
        syncNativeSelection();
        if (!isActive()) return;
        if (mSelY1 < minRow) mSelY1 = minRow;
        if (mSelY2 < minRow) mSelY2 = minRow;
    }

    public void decrementYTextSelectionCursors(int decrement) {
        mSelY1 -= decrement;
        mSelY2 -= decrement;
    }

    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode();
        }
    }

    @Override
    public void onDetached() {
    }

    @Override
    public boolean isActive() {
        return mIsSelectingText;
    }

    public void getSelectors(int[] sel) {
        syncNativeSelection();
        if (sel == null || sel.length != 4) {
            return;
        }

        sel[0] = mSelY1;
        sel[1] = mSelY2;
        sel[2] = mSelX1;
        sel[3] = mSelX2;
    }

    /** Get the currently selected text. */
    public String getSelectedText() {
        syncNativeSelection();
        if (!mIsSelectingText) return "";
        return terminalView.mEmulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mStoredSelectedText;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        mStoredSelectedText = null;
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /**
     * @return true if this controller is currently used to move the start selection.
     */
    public boolean isSelectionStartDragged() {
        return mStartHandle.isDragging();
    }

    /**
     * @return true if this controller is currently used to move the end selection.
     */
    public boolean isSelectionEndDragged() {
        return mEndHandle.isDragging();
    }

}
