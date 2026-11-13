package com.termux.terminal;

import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import io.github.modao7067.luma.ghostty.GhosttyNative;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;

public final class NativeReportCheck {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    static void feed(GhosttyTerminalEmulator e, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.append(bytes, bytes.length);
    }
    static TerminalSession session() {
        TerminalSession s = new TerminalSession(new SessionCoreCheck.Provider(), 1000,
            SessionCoreCheck.client(), GhosttyTerminalEmulator::new);
        s.mShellPid = 1;
        s.initializeEmulator(80, 24, 13, 30);
        return s;
    }
    static String replies(TerminalSession s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = s.mTerminalToProcessIOQueue.read(buffer, false)) > 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public void write(byte[] b, int o, int n) { bytes.write(b, o, n); }
        public void titleChanged(String a, String b) {}
        public void onCopyTextToClipboard(String s) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
        String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
    }
    static String hex(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) out.append(String.format("%02X", b & 0xff));
        return out.toString();
    }

    public static void main(String[] args) {
        try {
            Looper.prepare();
            GhosttyTerminalEmulator.loadLibrary(args[0]);

            TerminalSession s = session();
            GhosttyTerminalEmulator e = (GhosttyTerminalEmulator) s.getEmulator();
            feed(e, "\033[14t");
            check(replies(s).equals("\033[4;720;1040t"), "CSI 14 t reports text area pixels from rows/cols x real cell size");
            feed(e, "\033[16t");
            check(replies(s).equals("\033[6;30;13t"), "CSI 16 t reports the real cell pixel size");
            feed(e, "\033[18t");
            check(replies(s).equals("\033[8;24;80t"), "CSI 18 t reports rows/cols in cells");
            feed(e, "\033[19t");
            check(replies(s).equals("\033[9;24;80t"), "CSI 19 t reports the same text-area character size");

            feed(e, "\033[1");
            check(replies(s).isEmpty(), "partial CSI 14 t writes nothing");
            feed(e, "4t");
            check(replies(s).equals("\033[4;720;1040t"), "split CSI 14 t answers exactly once");

            feed(e, "\033[?2048h");
            check(replies(s).equals("\033[48;24;80;720;1040t"), "mode 2048 enable reports current in-band geometry");
            e.resize(100, 40, 13, 30);
            check(replies(s).equals("\033[48;40;100;1200;1300t"), "resize reports new mode 2048 geometry through the PTY queue");
            feed(e, "\033[18t");
            check(replies(s).equals("\033[8;40;100t"), "query after resize uses the new cell grid");

            feed(e, "\033[?1049h\033[14t");
            check(replies(s).equals("\033[4;1200;1300t"), "alternate screen keeps the real host pixel geometry");
            feed(e, "\033[?1049l");

            feed(e, "\033[?996n");
            check(replies(s).equals("\033[?997;1n"), "default dark background reports a dark color scheme");
            feed(e, "\033[?5h\033[48;2;255;255;255mX\033[?996n");
            check(replies(s).equals("\033[?997;1n"), "reverse video and a white cell background do not flip the theme");
            feed(e, "\033[?5l\033[49m");

            int[] lightPalette = new int[259];
            for (int i = 0; i < 256; i++) lightPalette[i] = 0xff000000;
            lightPalette[256] = 0xff000000;
            lightPalette[257] = 0xffffffff;
            lightPalette[258] = 0xff000000;
            Output lightOut = new Output();
            GhosttyTerminalEmulator light = GhosttyTerminalEmulator.forOutput(
                lightOut, 80, 24, 13, 30, 1000, SessionCoreCheck.client(), lightPalette);
            feed(light, "\033[?996n");
            check(lightOut.text().equals("\033[?997;2n"), "light default background reports a light color scheme");
            light.dispose();

            feed(e, "\033P+q544E\033\\");
            check(replies(s).equals("\033P1+r544E=" + hex("xterm-256color") + "\033\\"),
                "XTGETTCAP TN matches the launched xterm-256color terminfo entry");

            String before = e.getViewportText(0);
            StringBuilder queries = new StringBuilder();
            for (int i = 0; i < 50; i++) queries.append("\033[18t\033[16t");
            feed(e, queries.toString());
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 50; i++) expected.append("\033[8;40;100t\033[6;30;13t");
            check(replies(s).equals(expected.toString()), "bulk queries answer once each and in request order");
            check(before.equals(e.getViewportText(0)), "protocol queries do not alter or capture the screen contents");

            int[] palette = new int[256];
            long handle = GhosttyNative.create(80, 24, 0, palette, 0xffe4eaf5, 0xff171d2b);
            check(handle != 0, "raw native terminal created without Android cell geometry");
            byte[] raw14 = "\033[14t".getBytes(StandardCharsets.UTF_8);
            GhosttyNative.feed(handle, raw14, 0, raw14.length);
            byte[] none = GhosttyNative.takeReplies(handle);
            check(none == null || none.length == 0, "no fake pixel size is reported before a real cell size exists");
            byte[] raw18 = "\033[18t".getBytes(StandardCharsets.UTF_8);
            GhosttyNative.feed(handle, raw18, 0, raw18.length);
            byte[] chars = GhosttyNative.takeReplies(handle);
            check(chars == null || chars.length == 0, "invalid host pixel geometry suppresses the whole report instead of guessing");
            GhosttyNative.resize(handle, 80, 24, 13, 30);
            GhosttyNative.feed(handle, raw14, 0, raw14.length);
            byte[] pixelReply = GhosttyNative.takeReplies(handle);
            check(pixelReply != null && new String(pixelReply, StandardCharsets.UTF_8).equals("\033[4;720;1040t"),
                "host resize makes the real pixel report available");
            GhosttyNative.destroy(handle);

            s.disposeEmulator();
            System.out.println("NATIVE REPORT OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace(System.out); System.out.flush(); System.exit(1);
        }
    }
}
