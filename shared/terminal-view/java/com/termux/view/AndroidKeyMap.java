package com.termux.view;

import android.view.KeyEvent;

import io.github.modao7067.luma.ghostty.GhosttyKey;

/** Android KeyEvent to the pinned Ghostty key/mods ABI. Physical layout is not inferred from text. */
final class AndroidKeyMap {
    private AndroidKeyMap() {}

    static final int UNKNOWN = -1;

    static int keyForCode(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_GRAVE: return GhosttyKey.BACKQUOTE;
            case KeyEvent.KEYCODE_BACKSLASH: return GhosttyKey.BACKSLASH;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return GhosttyKey.BRACKET_LEFT;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return GhosttyKey.BRACKET_RIGHT;
            case KeyEvent.KEYCODE_COMMA: return GhosttyKey.COMMA;
            case KeyEvent.KEYCODE_PERIOD: return GhosttyKey.PERIOD;
            case KeyEvent.KEYCODE_MINUS: return GhosttyKey.MINUS;
            case KeyEvent.KEYCODE_EQUALS: return GhosttyKey.EQUAL;
            case KeyEvent.KEYCODE_APOSTROPHE: return GhosttyKey.QUOTE;
            case KeyEvent.KEYCODE_SEMICOLON: return GhosttyKey.SEMICOLON;
            case KeyEvent.KEYCODE_SLASH: return GhosttyKey.SLASH;
            case KeyEvent.KEYCODE_SPACE: return GhosttyKey.SPACE;
            case KeyEvent.KEYCODE_TAB: return GhosttyKey.TAB;
            case KeyEvent.KEYCODE_ENTER: return GhosttyKey.ENTER;
            case KeyEvent.KEYCODE_DEL: return GhosttyKey.BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return GhosttyKey.DELETE;
            case KeyEvent.KEYCODE_ESCAPE: return GhosttyKey.ESCAPE;
            case KeyEvent.KEYCODE_BACK: return GhosttyKey.ESCAPE;
            case KeyEvent.KEYCODE_DPAD_UP: return GhosttyKey.ARROW_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return GhosttyKey.ARROW_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return GhosttyKey.ARROW_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return GhosttyKey.ARROW_RIGHT;
            case KeyEvent.KEYCODE_MOVE_HOME: return GhosttyKey.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return GhosttyKey.END;
            case KeyEvent.KEYCODE_INSERT: return GhosttyKey.INSERT;
            case KeyEvent.KEYCODE_PAGE_UP: return GhosttyKey.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return GhosttyKey.PAGE_DOWN;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return GhosttyKey.SHIFT_LEFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return GhosttyKey.SHIFT_RIGHT;
            case KeyEvent.KEYCODE_CTRL_LEFT: return GhosttyKey.CONTROL_LEFT;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return GhosttyKey.CONTROL_RIGHT;
            case KeyEvent.KEYCODE_ALT_LEFT: return GhosttyKey.ALT_LEFT;
            case KeyEvent.KEYCODE_ALT_RIGHT: return GhosttyKey.ALT_RIGHT;
            case KeyEvent.KEYCODE_META_LEFT: return GhosttyKey.META_LEFT;
            case KeyEvent.KEYCODE_META_RIGHT: return GhosttyKey.META_RIGHT;
            case KeyEvent.KEYCODE_CAPS_LOCK: return GhosttyKey.CAPS_LOCK;
            case KeyEvent.KEYCODE_NUM_LOCK: return GhosttyKey.NUM_LOCK;
            case KeyEvent.KEYCODE_SYSRQ: return GhosttyKey.PRINT_SCREEN;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return GhosttyKey.SCROLL_LOCK;
            case KeyEvent.KEYCODE_BREAK: return GhosttyKey.PAUSE;
            case KeyEvent.KEYCODE_MENU: return GhosttyKey.CONTEXT_MENU;
            case KeyEvent.KEYCODE_COPY: return GhosttyKey.COPY;
            case KeyEvent.KEYCODE_CUT: return GhosttyKey.CUT;
            case KeyEvent.KEYCODE_PASTE: return GhosttyKey.PASTE;
            case KeyEvent.KEYCODE_F1: return GhosttyKey.F1;
            case KeyEvent.KEYCODE_F2: return GhosttyKey.F2;
            case KeyEvent.KEYCODE_F3: return GhosttyKey.F3;
            case KeyEvent.KEYCODE_F4: return GhosttyKey.F4;
            case KeyEvent.KEYCODE_F5: return GhosttyKey.F5;
            case KeyEvent.KEYCODE_F6: return GhosttyKey.F6;
            case KeyEvent.KEYCODE_F7: return GhosttyKey.F7;
            case KeyEvent.KEYCODE_F8: return GhosttyKey.F8;
            case KeyEvent.KEYCODE_F9: return GhosttyKey.F9;
            case KeyEvent.KEYCODE_F10: return GhosttyKey.F10;
            case KeyEvent.KEYCODE_F11: return GhosttyKey.F11;
            case KeyEvent.KEYCODE_F12: return GhosttyKey.F12;
            case KeyEvent.KEYCODE_NUMPAD_0: return GhosttyKey.NUMPAD_0;
            case KeyEvent.KEYCODE_NUMPAD_1: return GhosttyKey.NUMPAD_1;
            case KeyEvent.KEYCODE_NUMPAD_2: return GhosttyKey.NUMPAD_2;
            case KeyEvent.KEYCODE_NUMPAD_3: return GhosttyKey.NUMPAD_3;
            case KeyEvent.KEYCODE_NUMPAD_4: return GhosttyKey.NUMPAD_4;
            case KeyEvent.KEYCODE_NUMPAD_5: return GhosttyKey.NUMPAD_5;
            case KeyEvent.KEYCODE_NUMPAD_6: return GhosttyKey.NUMPAD_6;
            case KeyEvent.KEYCODE_NUMPAD_7: return GhosttyKey.NUMPAD_7;
            case KeyEvent.KEYCODE_NUMPAD_8: return GhosttyKey.NUMPAD_8;
            case KeyEvent.KEYCODE_NUMPAD_9: return GhosttyKey.NUMPAD_9;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return GhosttyKey.NUMPAD_ADD;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return GhosttyKey.NUMPAD_SUBTRACT;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return GhosttyKey.NUMPAD_MULTIPLY;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return GhosttyKey.NUMPAD_DIVIDE;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return GhosttyKey.NUMPAD_ENTER;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return GhosttyKey.NUMPAD_DECIMAL;
            case KeyEvent.KEYCODE_NUMPAD_COMMA: return GhosttyKey.NUMPAD_COMMA;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return GhosttyKey.NUMPAD_EQUAL;
            default: break;
        }
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
            return GhosttyKey.A + (code - KeyEvent.KEYCODE_A);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
            return GhosttyKey.DIGIT_0 + (code - KeyEvent.KEYCODE_0);
        return UNKNOWN;
    }

    static int mods(boolean shift, boolean ctrl, boolean alt, boolean meta, boolean caps, boolean num, int metaState) {
        int mods = 0;
        if (shift) {
            mods |= GhosttyKey.MOD_SHIFT;
            if ((metaState & KeyEvent.META_SHIFT_RIGHT_ON) != 0) mods |= GhosttyKey.MOD_SHIFT_SIDE;
        }
        if (ctrl) {
            mods |= GhosttyKey.MOD_CTRL;
            if ((metaState & KeyEvent.META_CTRL_RIGHT_ON) != 0) mods |= GhosttyKey.MOD_CTRL_SIDE;
        }
        if (alt) {
            mods |= GhosttyKey.MOD_ALT;
            if ((metaState & KeyEvent.META_ALT_RIGHT_ON) != 0) mods |= GhosttyKey.MOD_ALT_SIDE;
        }
        if (meta) {
            mods |= GhosttyKey.MOD_SUPER;
            if ((metaState & KeyEvent.META_META_RIGHT_ON) != 0) mods |= GhosttyKey.MOD_SUPER_SIDE;
        }
        if (caps) mods |= GhosttyKey.MOD_CAPS_LOCK;
        if (num) mods |= GhosttyKey.MOD_NUM_LOCK;
        return mods;
    }

    /** KeyHandler's packed virtual-key modifiers to the encoder ABI. */
    static int modsFromKeyHandler(int keyMod) {
        int mods = 0;
        if ((keyMod & com.termux.terminal.KeyHandler.KEYMOD_SHIFT) != 0) mods |= GhosttyKey.MOD_SHIFT;
        if ((keyMod & com.termux.terminal.KeyHandler.KEYMOD_CTRL) != 0) mods |= GhosttyKey.MOD_CTRL;
        if ((keyMod & com.termux.terminal.KeyHandler.KEYMOD_ALT) != 0) mods |= GhosttyKey.MOD_ALT;
        if ((keyMod & com.termux.terminal.KeyHandler.KEYMOD_NUM_LOCK) != 0) mods |= GhosttyKey.MOD_NUM_LOCK;
        return mods;
    }

    /** Special keys always use the terminal protocol bytes, never their UnicodeChar value. */
    static boolean producesText(int key, int codepoint) {
        if (codepoint <= 0) return false;
        if (key == GhosttyKey.SPACE) return true;
        if (key == GhosttyKey.ENTER || key == GhosttyKey.TAB || key == GhosttyKey.BACKSPACE
            || key == GhosttyKey.ESCAPE || key == GhosttyKey.DELETE || key == GhosttyKey.NUMPAD_ENTER)
            return false;
        if (key >= GhosttyKey.ARROW_DOWN && key <= GhosttyKey.ARROW_UP) return false;
        if (key >= GhosttyKey.END && key <= GhosttyKey.PAGE_UP) return false;
        if (key >= GhosttyKey.NUM_LOCK && key <= GhosttyKey.NUMPAD_9) return key >= GhosttyKey.NUMPAD_0;
        if (key >= GhosttyKey.F1 && key <= GhosttyKey.F12) return false;
        if (key >= GhosttyKey.ALT_LEFT && key <= GhosttyKey.TAB) return false;
        if (key == GhosttyKey.PRINT_SCREEN || key == GhosttyKey.SCROLL_LOCK || key == GhosttyKey.PAUSE) return false;
        return true;
    }
}
