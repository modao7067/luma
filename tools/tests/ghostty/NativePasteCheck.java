package com.termux.terminal;

import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import io.github.modao7067.luma.root.TerminalPaste;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;

public final class NativePasteCheck {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    static final int WRITTEN = TerminalEmulator.PASTE_WRITTEN;
    static final int NEEDS = TerminalEmulator.PASTE_NEEDS_CONFIRMATION;
    static final int NOTHING = TerminalEmulator.PASTE_NOTHING;
    static final int ERROR = TerminalEmulator.PASTE_ERROR;
    static final int TOO_LARGE = TerminalEmulator.PASTE_TOO_LARGE;
    static final int LIMIT = 256 * 1024;

    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public void write(byte[] b, int o, int n) { bytes.write(b, o, n); }
        public void titleChanged(String a, String b) {}
        public void onCopyTextToClipboard(String s) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
        String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
        int length() { return bytes.size(); }
        void reset() { bytes.reset(); }
    }
    static GhosttyTerminalEmulator output(Output out) {
        return GhosttyTerminalEmulator.forOutput(out, 80, 24, 13, 30, 1000, SessionCoreCheck.client(), null);
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
        byte[] buffer = new byte[8192];
        int n;
        while ((n = s.mTerminalToProcessIOQueue.read(buffer, false)) > 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static String show(String s) { return s.replace("\033", "<ESC>").replace("\r", "<CR>").replace("\n", "<NL>").replace("\0", "<NUL>"); }

    static final class Host implements TerminalPaste.Host {
        boolean alive = true;
        TerminalSession current;
        public boolean isAlive() { return alive; }
        public TerminalSession currentSession() { return current; }
    }

    public static void main(String[] args) {
        try {
            Looper.prepare();
            GhosttyTerminalEmulator.loadLibrary(args[0]);
            Output out = new Output();
            GhosttyTerminalEmulator e = output(out);

            check(e.pasteStatus("", false) == NOTHING && out.length() == 0, "empty text writes nothing");

            check(e.pasteStatus("abc", false) == WRITTEN && out.text().equals("abc"),
                "safe unbracketed text is written verbatim");
            out.reset();

            check(e.pasteStatus("a\nb", false) == NEEDS && out.length() == 0,
                "unbracketed newline requires confirmation and writes nothing");
            check(e.pasteStatus("a\nb", true) == WRITTEN && out.text().equals("a\rb"),
                "confirmed unbracketed newline becomes a carriage return");
            out.reset();
            check(e.pasteStatus("a\rb", false) == NEEDS && out.length() == 0,
                "unbracketed carriage return requires confirmation");
            check(e.pasteStatus("a\rb", true) == WRITTEN && out.text().equals("a\rb"),
                "confirmed carriage return is written once");
            out.reset();
            check(e.pasteStatus("a\r\nb", false) == NEEDS && out.length() == 0,
                "unbracketed CRLF requires confirmation");
            check(e.pasteStatus("a\r\nb", true) == WRITTEN && out.text().equals("a\r\rb"),
                "confirmed CRLF keeps the existing native newline conversion");
            out.reset();

            feed(e, "\033[?2004h");
            check(e.pasteStatus("a\nb", false) == WRITTEN && out.text().equals("\033[200~a\nb\033[201~"),
                "bracketed multi-line text is written without confirmation");
            out.reset();
            check(e.pasteStatus("x\033[201~y", false) == NEEDS && out.length() == 0,
                "bracketed paste terminator requires confirmation and writes nothing");
            check(e.pasteStatus("x\033[201~y", true) == WRITTEN && out.text().equals("\033[200~x [201~y\033[201~"),
                "confirmed terminator is neutralised by the native encoder inside one bracket");
            check(out.text().indexOf("\033[201~") == out.text().length() - 6,
                "the only terminator in the approved output is the final bracketed suffix");
            out.reset();
            feed(e, "\033[?2004l");

            String controls = "a\0b\u0001c\u007f";
            check(e.pasteStatus(controls, false) == WRITTEN, "NUL/C0/DEL text needs no confirmation");
            check(out.text().equals("a b\u0001c ") && out.text().indexOf('\0') < 0 && out.text().indexOf(0x7f) < 0,
                "NUL/DEL follow the pinned xterm replacement set while other C0 stay text ("
                    + show(out.text()) + ")");
            out.reset();
            String escAndControl = "a\u001bb\u0003c";
            check(e.pasteStatus(escAndControl, false) == WRITTEN && out.text().equals("a b c"),
                "ESC and terminal control keys are replaced by spaces by the native encoder");
            out.reset();

            String unicode = "中文😀";
            check(e.pasteStatus(unicode, false) == WRITTEN
                    && java.util.Arrays.equals(out.bytes.toByteArray(), unicode.getBytes(StandardCharsets.UTF_8)),
                "Chinese and emoji paste as their exact UTF-8 bytes");
            out.reset();

            String captured = "line1\nline2";
            int first = e.pasteStatus(captured, false);
            check(first == NEEDS && out.length() == 0, "the first pass only classifies the captured text");
            check(e.pasteStatus(captured, true) == WRITTEN && out.text().equals("line1\rline2"),
                "the approved retry writes the same captured text exactly once");
            out.reset();

            char[] chars = new char[LIMIT];
            java.util.Arrays.fill(chars, 'a');
            String atLimit = new String(chars);
            check(e.pasteStatus(atLimit, false) == WRITTEN && out.length() == LIMIT,
                "a paste at the budget is written whole (" + LIMIT + " bytes)");
            out.reset();
            char[] over = new char[LIMIT + 1];
            java.util.Arrays.fill(over, 'a');
            check(e.pasteStatus(new String(over), false) == TOO_LARGE && out.length() == 0,
                "a paste over the budget is refused before any output");
            out.reset();

            e.dispose();
            check(e.pasteStatus("abc", false) == ERROR && out.length() == 0, "disposed engine rejects paste with no output");

            TerminalSession s1 = session();
            TerminalSession s2 = session();
            GhosttyTerminalEmulator e1 = (GhosttyTerminalEmulator) s1.getEmulator();
            GhosttyTerminalEmulator e2 = (GhosttyTerminalEmulator) s2.getEmulator();
            feed(e1, "\033[18t");
            check(replies(s1).equals("\033[8;24;80t"), "a prior query reply is queued first");
            check(e1.pasteStatus("ok", false) == WRITTEN && replies(s1).equals("ok"),
                "paste output follows existing replies without waiting for another append");
            check(replies(s2).isEmpty(), "paste does not reach a different session");
            check(e2.getViewportText(0).isEmpty(), "paste does not mutate another session's screen");

            e1.pasteStatus("zz", false);
            replies(s1);
            s1.writeCodePoint(false, 0x4e2d);
            byte[] ime = new byte[8];
            int n = s1.mTerminalToProcessIOQueue.read(ime, false);
            check(n == 3 && new String(ime, 0, n, StandardCharsets.UTF_8).equals("中"),
                "IME code points remain plain UTF-8 input with no paste confirmation or framing");

            Host host = new Host(); host.current = s1;
            TerminalPaste.Request request = new TerminalPaste.Request(host, s1, e1, "a\nb");
            check(request.isPending() && request.approve() == WRITTEN && replies(s1).equals("a\rb"),
                "confirmation approval writes the captured text exactly once");
            check(request.approve() == ERROR && replies(s1).isEmpty(),
                "a second approval of the same request writes nothing");
            request.cancel();
            check(request.approve() == ERROR && replies(s1).isEmpty(),
                "dismissal after a legitimate approval cannot write again or undo it");

            Host switched = new Host(); switched.current = s2;
            TerminalPaste.Request switchedRequest = new TerminalPaste.Request(switched, s1, e1, "x\ny");
            check(switchedRequest.approve() == ERROR && replies(s1).isEmpty(),
                "approval after the selected window changed writes nothing to the old session");
            switched.current = s1;
            check(switchedRequest.approve() == ERROR && replies(s1).isEmpty() && !switchedRequest.isPending(),
                "a switched-window request stays invalid if the old window is selected again");

            TerminalPaste.Pending hostPending = new TerminalPaste.Pending();
            Host aHost = new Host(); aHost.current = s1;
            TerminalPaste.Request aRequest = new TerminalPaste.Request(aHost, s1, e1, "a\nb");
            hostPending.begin(aRequest);
            hostPending.selectionChanged(s2);
            hostPending.selectionChanged(s1);
            check(!aRequest.isPending() && aRequest.approve() == ERROR && replies(s1).isEmpty(),
                "real A->B->A selection change invalidates the pending confirmation without an intermediate approval");

            TerminalPaste.Pending samePending = new TerminalPaste.Pending();
            Host sameHost = new Host(); sameHost.current = s1;
            TerminalPaste.Request sameRequest = new TerminalPaste.Request(sameHost, s1, e1, "k\nk");
            samePending.begin(sameRequest);
            samePending.selectionChanged(s1);
            check(sameRequest.isPending() && sameRequest.approve() == WRITTEN && replies(s1).equals("k\rk"),
                "selecting the same window keeps the pending confirmation and it still writes once");

            TerminalPaste.Pending destroyPending = new TerminalPaste.Pending();
            Host destroyHost = new Host(); destroyHost.current = s1;
            TerminalPaste.Request destroyRequest = new TerminalPaste.Request(destroyHost, s1, e1, "d\nd");
            destroyPending.begin(destroyRequest);
            destroyPending.cancel();
            check(destroyRequest.approve() == ERROR && replies(s1).isEmpty(),
                "the onDestroy cancel hook invalidates a pending confirmation");

            Host cancelledHost = new Host(); cancelledHost.current = s1;
            TerminalPaste.Request cancelled = new TerminalPaste.Request(cancelledHost, s1, e1, "x\ny");
            cancelled.cancel();
            check(!cancelled.isPending() && cancelled.approve() == ERROR && replies(s1).isEmpty(),
                "a late approval after cancel writes nothing");

            Host deadHost = new Host(); deadHost.current = s1; deadHost.alive = false;
            TerminalPaste.Request dead = new TerminalPaste.Request(deadHost, s1, e1, "x\ny");
            check(dead.approve() == ERROR && replies(s1).isEmpty(),
                "a late approval after Activity destruction writes nothing");

            Host captureHost = new Host(); captureHost.current = s1;
            StringBuilder captureBuffer = new StringBuilder("old\nline");
            TerminalPaste.Request capturedRequest = new TerminalPaste.Request(captureHost, s1, e1, captureBuffer);
            captureBuffer.setLength(0); captureBuffer.append("changed\nline");
            check(capturedRequest.approve() == WRITTEN && replies(s1).equals("old\rline"),
                "approval uses the originally captured text, not a later clipboard value");

            Host disposedHost = new Host(); disposedHost.current = s1;
            TerminalPaste.Request disposed = new TerminalPaste.Request(disposedHost, s1, e1, "x\ny");
            s1.disposeEmulator();
            check(disposed.approve() == ERROR && !disposed.isPending(),
                "a late approval after the original session is disposed writes nothing");
            s2.disposeEmulator();
            System.out.println("NATIVE PASTE OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace(System.out); System.out.flush(); System.exit(1);
        }
    }

    static void feed(GhosttyTerminalEmulator e, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.append(bytes, bytes.length);
    }
}
