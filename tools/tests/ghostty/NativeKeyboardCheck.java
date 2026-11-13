package com.termux.terminal;

import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import io.github.modao7067.luma.ghostty.GhosttyKey;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;

public final class NativeKeyboardCheck {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    static GhosttyTerminalEmulator make() {
        TerminalSession s = new TerminalSession(new SessionCoreCheck.Provider(), 1000,
            SessionCoreCheck.client(), GhosttyTerminalEmulator::new);
        s.initializeEmulator(80, 24, 12, 24);
        return (GhosttyTerminalEmulator) s.getEmulator();
    }
    static void feed(GhosttyTerminalEmulator e, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.append(bytes, bytes.length);
    }
    static void same(int[] a, int[] b, String message) {
        int[] left = a.clone(), right = b.clone();
        left[1] &= ~(1 << 7); right[1] &= ~(1 << 7);
        check(Arrays.equals(left, right), message + " " + Arrays.toString(left) + " / " + Arrays.toString(right));
    }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String str(byte[] value) {
        return value == null ? "<null>" : new String(value, StandardCharsets.UTF_8);
    }
    static void encoded(GhosttyTerminalEmulator e, String expected, int key, int action,
                        int mods, String utf8, int unshifted, boolean composing) {
        byte[] out = e.encodeKey(key, action, mods, 0,
            utf8 == null ? null : bytes(utf8), unshifted, composing);
        check(out != null, "encoder returned a result for " + expected);
        check(Arrays.equals(out, bytes(expected)),
            "encoded bytes " + expected + " got " + str(out));
    }
    static void empty(GhosttyTerminalEmulator e, String message, int key, int action,
                      int mods, String utf8, int unshifted, boolean composing) {
        byte[] out = e.encodeKey(key, action, mods, 0,
            utf8 == null ? null : bytes(utf8), unshifted, composing);
        check(out != null && out.length == 0, message + " got " + str(out));
    }

    public static void main(String[] args) {
        try {
            Looper.prepare();
            GhosttyTerminalEmulator.loadLibrary(args[0]);

            GhosttyTerminalEmulator e = make();
            feed(e, "\033[>1u\033[>3u\033[=4;2u");
            int[] kb = e.exportKeyboardState();
            check(kb != null && kb.length == 21 && kb[0] == 1, "keyboard annex has 21 words");
            check(kb[2] == 2 && kb[4] == 1 && kb[5] == 7 && kb[3] == 0,
                "primary ring stack push/or exact idx and entries");
            feed(e, "\033[<1u");
            kb = e.exportKeyboardState();
            check(kb[2] == 1 && kb[4] == 1 && kb[5] == 0, "pop clears only the popped layer");
            feed(e, "\033[>16u\033[=1;2u\033[=8;3u");
            kb = e.exportKeyboardState();
            check(kb[2] == 2 && kb[5] == 17, "kitty set-or/set-not operate on current layer");

            feed(e, "\033[?1049h\033[>2u\033[>31u");
            kb = e.exportKeyboardState();
            check(kb[11] == 2 && kb[14] == 31 && kb[2] == 2 && kb[5] == 17,
                "alternate screen keeps an independent stack");
            feed(e, "\033[?1049l");
            kb = e.exportKeyboardState();
            check((kb[1] & (1 << 7)) != 0 && kb[11] == 2 && kb[14] == 31 && kb[5] == 17,
                "inactive alternate stack survives a screen switch");

            feed(e, "\033[?1036s\033[?1036l\033[?1035l\033[?67h\033[>4;2m");
            kb = e.exportKeyboardState();
            check((kb[1] & 1) != 0 && (kb[1] & (1 << 1)) == 0 && (kb[1] & (1 << 2)) == 0
                && (kb[1] & (1 << 3)) != 0 && (kb[1] & (1 << 4)) != 0,
                "modifyOtherKeys and extension mode current/saved bits exported");

            int[] v1 = e.exportModeState();
            int[] saved = e.exportKeyboardState();
            GhosttyTerminalEmulator restored = make();
            restored.restoreModeState(v1, saved[20] & 0xff);
            check(restored.restoreKeyboardState(saved) == 0, "bound keyboard annex restores atomically");
            same(saved, restored.exportKeyboardState(), "restored keyboard state is exact");
            int[] invalid = saved.clone(); invalid[2] = 99;
            int[] beforeInvalid = restored.exportKeyboardState();
            check(restored.restoreKeyboardState(invalid) != 0, "invalid keyboard payload is rejected");
            same(beforeInvalid, restored.exportKeyboardState(), "rejected restore leaves both screens untouched");
            feed(e, "\033[<1u"); feed(restored, "\033[<1u");
            same(e.exportKeyboardState(), restored.exportKeyboardState(),
                "pop after restore returns to the same old layer");
            feed(e, "\033[?1049h"); feed(restored, "\033[?1049h");
            same(e.exportKeyboardState(), restored.exportKeyboardState(),
                "inactive alternate stack restored before first switch");

            GhosttyTerminalEmulator ris = make();
            feed(ris, "\033[>1u\033[?1049h\033[>2u\033[>4;2m\033[?1036l");
            feed(ris, "\033c");
            int[] resetV1 = ris.exportModeState();
            int[] resetKb = ris.exportKeyboardState();
            check(resetV1[5] == 0, "RIS returns to the primary screen");
            check(resetKb[1] == ((1 << 1) | (1 << 2) | (1 << 4) | (1 << 5))
                && resetKb[2] == 0 && resetKb[3] == 0
                && resetKb[11] == 0 && resetKb[12] == 0,
                "RIS clears both kitty stacks and restores extension defaults");

            GhosttyTerminalEmulator decstr = make();
            feed(decstr, "\033[?1h\033[?66h\033[>1u\033[>4;2m");
            feed(decstr, "\033[!p");
            int[] dcv = decstr.exportModeState();
            int[] dck = decstr.exportKeyboardState();
            check((dcv[3] & 1) == 0 && (dcv[3] & 32) == 0 && (dcv[3] & 2048) == 0 && dcv[6] == 0 && dcv[7] == 0
                && (dcv[3] & 8) != 0 && (dcv[3] & 16) != 0,
                "DECSTR resets tracked cursor/keypad/insert/margin/wrap/visible modes");
            check(dck[2] == 1 && dck[4] == 1 && (dck[1] & 1) == 0,
                "DECSTR keeps the kitty stack but clears modifyOtherKeys");

            GhosttyTerminalEmulator partial = make();
            feed(partial, "\033[=5;2");
            int[] pv = partial.exportModeState();
            int[] pk = partial.exportKeyboardState();
            check(pk[20] == '=', "partial CSI = intermediate exported in the annex");
            GhosttyTerminalEmulator resumed = make();
            resumed.restoreModeState(pv, pk[20] & 0xff);
            resumed.restoreKeyboardState(pk);
            feed(partial, "u"); feed(resumed, "u");
            same(partial.exportKeyboardState(), resumed.exportKeyboardState(),
                "partial kitty set resumes as set, not plain CSI");
            check(partial.exportKeyboardState()[3] == 5, "partial set applied after resume");

            GhosttyTerminalEmulator partialPop = make();
            feed(partialPop, "\033[>1u\033[<1");
            int[] ppv = partialPop.exportModeState();
            int[] ppk = partialPop.exportKeyboardState();
            check(ppk[20] == '<', "partial CSI < intermediate exported in the annex");
            GhosttyTerminalEmulator popResumed = make();
            popResumed.restoreModeState(ppv, ppk[20] & 0xff);
            popResumed.restoreKeyboardState(ppk);
            feed(partialPop, "u"); feed(popResumed, "u");
            same(partialPop.exportKeyboardState(), popResumed.exportKeyboardState(),
                "partial kitty pop resumes with pop semantics");

            final int[] notifications = {0};
            GhosttyTerminalEmulator listener = make();
            listener.setModeChangeListener(() -> notifications[0]++);
            feed(listener, "\033[>1u");
            check(notifications[0] == 1, "kitty-only change checkpoints");
            notifications[0] = 0;
            for (int i = 0; i < 50; i++) feed(listener, "ordinary 中文 output\033[31m red\033[0m\r\n");
            check(notifications[0] == 0, "ordinary text and SGR do not checkpoint");

            GhosttyTerminalEmulator enc = make();
            encoded(enc, "\033[A", GhosttyKey.ARROW_UP, GhosttyKey.ACTION_PRESS, 0, null, 0, false);
            feed(enc, "\033[?1h");
            encoded(enc, "\033OA", GhosttyKey.ARROW_UP, GhosttyKey.ACTION_PRESS, 0, null, 0, false);
            feed(enc, "\033[?1l");
            encoded(enc, "\033e", GhosttyKey.E, GhosttyKey.ACTION_PRESS, GhosttyKey.MOD_ALT, "e", 'e', false);
            encoded(enc, "\033é", GhosttyKey.E, GhosttyKey.ACTION_PRESS, GhosttyKey.MOD_ALT, "é", 0xe9, false);
            encoded(enc, "\001", GhosttyKey.A, GhosttyKey.ACTION_PRESS, GhosttyKey.MOD_CTRL, "a", 'a', false);
            feed(enc, "\033[>1u");
            encoded(enc, "\033[97;5u", GhosttyKey.A, GhosttyKey.ACTION_PRESS, GhosttyKey.MOD_CTRL, "a", 'a', false);
            empty(enc, "release without report-events is handled with no output",
                GhosttyKey.A, GhosttyKey.ACTION_RELEASE, 0, null, 'a', false);
            feed(enc, "\033[>3u");
            encoded(enc, "\033[97;5:2u", GhosttyKey.A, GhosttyKey.ACTION_REPEAT, GhosttyKey.MOD_CTRL, "a", 'a', false);
            encoded(enc, "\033[97;1:3u", GhosttyKey.A, GhosttyKey.ACTION_RELEASE, 0, null, 'a', false);
            feed(enc, "\033[<8u\033[>4;2m");
            encoded(enc, "\033[27;5;97~", GhosttyKey.A, GhosttyKey.ACTION_PRESS, GhosttyKey.MOD_CTRL, "a", 'a', false);
            feed(enc, "\033[>4m");
            empty(enc, "composing legacy event emits nothing", GhosttyKey.E, GhosttyKey.ACTION_PRESS, 0, "e", 'e', true);
            empty(enc, "modifier-only legacy event is handled with no output",
                GhosttyKey.SHIFT_LEFT, GhosttyKey.ACTION_PRESS, 0, null, 0, false);

            for (GhosttyTerminalEmulator x : new GhosttyTerminalEmulator[]{
                e, restored, ris, decstr, partial, resumed, partialPop, popResumed, listener, enc}) x.dispose();
            System.out.println("NATIVE KEYBOARD OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace(); System.exit(1);
        }
    }
}
