package io.github.modao7067.luma.ghostty;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public final class NativeCompressionCheck {
    static int checks;
    static Handler main;

    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    static final class NoopPty implements TerminalSession.PtyProvider {
        public void start(TerminalSession s, int c, int r, int cw, int ch) {}
        public void resize(int c, int r, int cw, int ch) {}
        public void closeWindow() {}
        public void detach() {}
    }

    static TerminalSessionClient client() {
        return (TerminalSessionClient) Proxy.newProxyInstance(
            NativeCompressionCheck.class.getClassLoader(),
            new Class[]{TerminalSessionClient.class},
            (p, m, a) -> "getTerminalCursorStyle".equals(m.getName()) ? 0 : null);
    }

    static TerminalSession makeSession(int historyRows) {
        TerminalSession s = new TerminalSession(new NoopPty(), historyRows, client(), GhosttyTerminalEmulator::new);
        s.initializeEmulator(80, 24, 12, 24);
        return s;
    }

    static void feed(GhosttyTerminalEmulator e, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.append(bytes, bytes.length);
    }

    static String kittyRgba2x2() {
        byte[] rgba = new byte[]{
            (byte) 0xff, 0x00, 0x00, (byte) 0xff,
            0x00, (byte) 0xff, 0x00, (byte) 0xff,
            0x00, 0x00, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };
        return "\033_Ga=T,f=32,s=2,v=2,c=4,r=2,i=19,p=1,C=1,q=2;" +
            Base64.getEncoder().encodeToString(rgba) + "\033\\";
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        main = new Handler(Looper.getMainLooper());
        GhosttyTerminalEmulator.loadLibrary(args[0]);
        try {
            runBoundaryChecks();
            runSchedulerAndSessionChecks();
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static void runBoundaryChecks() throws Exception {
        boolean badZero = false;
        try { GhosttyNative.compressionActivity(0); } catch (IllegalStateException expected) { badZero = true; }
        check(badZero && GhosttyNative.compressStep(0) == -1, "invalid handle 0 rejected by compression JNI");

        boolean badStale = false;
        try { GhosttyNative.compressionActivity(999999L); } catch (IllegalStateException expected) { badStale = true; }
        check(badStale && GhosttyNative.compressStep(999999L) == -1, "unknown handle rejected by compression JNI");

        TerminalSession s = makeSession(1000);
        GhosttyTerminalEmulator e = (GhosttyTerminalEmulator) s.getEmulator();
        final long handle = e.adapter().handle();
        final boolean[] wrongThreadRejected = {false, false, false};
        Thread worker = new Thread(() -> {
            try {
                GhosttyNative.compressionActivity(handle);
            } catch (IllegalStateException expected) {
                wrongThreadRejected[0] = true;
            }
            wrongThreadRejected[1] = GhosttyNative.compressStep(handle) == -1;
            try {
                new GhosttyScrollbackCompressor(main, new GhosttyScrollbackCompressor.Backend() {
                    public long activity() { return 1; }
                    public int step() { return 2; }
                });
            } catch (IllegalStateException expected) {
                wrongThreadRejected[2] = true;
            }
        });
        worker.start();
        worker.join();
        check(wrongThreadRejected[0] && wrongThreadRejected[1] && wrongThreadRejected[2],
            "wrong-thread compressionActivity, compressStep and compressor construction are rejected");

        GhosttyScrollbackCompressor failing = new GhosttyScrollbackCompressor(main, new GhosttyScrollbackCompressor.Backend() {
            public long activity() { return 7; }
            public int step() { return -1; }
        });
        boolean negativeThrows = false;
        try { failing.queueIdle(); } catch (IllegalStateException expected) { negativeThrows = true; }
        failing.close();
        check(negativeThrows, "negative native compressStep result throws IllegalStateException");

        final long[] raceToken = {10};
        final int[] raceSteps = {0};
        GhosttyScrollbackCompressor race = new GhosttyScrollbackCompressor(main, new GhosttyScrollbackCompressor.Backend() {
            public long activity() { return raceToken[0]; }
            public int step() { return ++raceSteps[0]; }
        });
        raceToken[0] = 11;
        race.queueIdle();
        check(raceSteps[0] == 0 && race.isPending(),
            "token change detected at queueIdle restarts idle timer without calling step()");
        race.close();
        race.queueIdle();
        check(raceSteps[0] == 0 && race.isClosed(),
            "queueIdle after close is a no-op and never touches backend");
        s.disposeEmulator();
    }

    static final class MockBackend implements GhosttyScrollbackCompressor.Backend {
        long token = 100;
        int[] plan = new int[]{1, 1, 2};
        int cursor, steps, activityCalls, maxStepsInOneTurn, stepsThisTurn;
        boolean armedTurnCheck;

        public long activity() {
            activityCalls++;
            return token;
        }

        public int step() {
            steps++;
            if (armedTurnCheck) {
                stepsThisTurn++;
                if (stepsThisTurn > maxStepsInOneTurn) maxStepsInOneTurn = stepsThisTurn;
                Looper.myQueue().addIdleHandler(() -> { stepsThisTurn = 0; return false; });
                main.post(() -> stepsThisTurn = 0);
            }
            int idx = Math.min(cursor, plan.length - 1);
            cursor++;
            return plan[idx];
        }
    }

    static void runSchedulerAndSessionChecks() {
        final MockBackend mock = new MockBackend();
        mock.armedTurnCheck = true;
        final GhosttyScrollbackCompressor sched = new GhosttyScrollbackCompressor(main, mock);

        final int[] unsupportedSteps = {0}, unsupportedActivity = {0};
        final long[] unsupportedToken = {1};
        final GhosttyScrollbackCompressor unsup = new GhosttyScrollbackCompressor(main, new GhosttyScrollbackCompressor.Backend() {
            public long activity() { unsupportedActivity[0]++; return unsupportedToken[0]; }
            public int step() { return ++unsupportedSteps[0] == 1 ? 0 : 1; }
        });

        final int[] closedSteps = {0}, closedActivities = {0};
        final GhosttyScrollbackCompressor closing = new GhosttyScrollbackCompressor(main, new GhosttyScrollbackCompressor.Backend() {
            public long activity() { return ++closedActivities[0]; }
            public int step() { return ++closedSteps[0]; }
        });
        main.postDelayed(closing::close, 400);

        main.postDelayed(() -> {
            try {
                check(mock.steps == 0 && unsupportedSteps[0] == 0,
                    "no compression step runs before the 2000ms idle window expires");
                sched.userActivity();
            } catch (Throwable t) { fail(t); }
        }, 1100);

        main.postDelayed(() -> {
            try {
                check(mock.steps == 0, "userActivity defers pending compression for a full 2000ms idle window");
                check(unsupportedSteps[0] == 1 && unsup.isUnsupported() && !unsup.isPending(),
                    "unsupported (0) stops after one check without busy looping");
                int actBefore = unsupportedActivity[0];
                unsupportedToken[0] = 2;
                unsup.observe();
                unsup.userActivity();
                check(unsupportedActivity[0] == actBefore && !unsup.isPending(),
                    "unsupported compressor ignores subsequent observe/userActivity");
                check(closedSteps[0] == 0 && closing.isClosed(),
                    "closed compressor cancels pending timer and never invokes step()");
            } catch (Throwable t) { fail(t); }
        }, 2300);

        main.postDelayed(() -> {
            try {
                check(mock.steps == 3 && !sched.isPending() && sched.completeCount == 1,
                    "pending steps continue at 16ms intervals and stop when complete (2) is returned");
                check(mock.maxStepsInOneTurn == 1,
                    "each idle callback executes at most one incremental step (never drains)");
                int stepsAtComplete = mock.steps;
                sched.observe();
                sched.userActivity();
                check(!sched.isPending() && mock.steps == stepsAtComplete,
                    "unchanged activity token after complete does not reschedule compression");

                mock.token = 202;
                mock.plan = new int[]{2};
                mock.cursor = 0;
                sched.observe();
                check(sched.isPending(), "changed activity token after complete restarts idle scheduling");
            } catch (Throwable t) { fail(t); }
        }, 3400);

        main.postDelayed(() -> {
            try {
                check(mock.steps == 4 && !sched.isPending() && sched.completeCount == 2,
                    "restarted scheduler runs after 2000ms idle and stops on complete");
                sched.close();
                unsup.close();
                startRealSessionSuite();
            } catch (Throwable t) { fail(t); }
        }, 5600);
    }

    static void startRealSessionSuite() {
        final TerminalSession session = makeSession(10000);
        final GhosttyTerminalEmulator emu = (GhosttyTerminalEmulator) session.getEmulator();
        final long handle = emu.adapter().handle();

        check(emu.getScreen().getActiveTranscriptRows() == 0 && emu.getScreen().getActiveRows() == 24,
            "superclass Java transcript is not allocated as a second full-history copy");

        StringBuilder sb = new StringBuilder(640000);
        sb.append("\033]8;;https://example.com/doc-0\007LINK0\033]8;;\007 ")
          .append("中文✨e\u0301👨\u200d👩\u200d👧\u200d👦🇺🇸 ")
          .append("\033[1;3;4;38;2;18;52;86;48;5;236mSTYLE0\033[0m LINE-00000\r\n");
        for (int i = 1; i < 10000; i++) {
            sb.append(String.format(Locale.ROOT,
                "LINE-%05d repeated compressible terminal scrollback payload alpha beta\r\n", i));
        }
        sb.append("\033]4;17;#abcdef\007\033]10;#112233\007");
        for (int r = 0; r < 23; r++) {
            sb.append(String.format(Locale.ROOT, "ACTIVE-%02d-live-row\r\n", r));
        }
        sb.append("ACTIVE-23-bottom ").append(kittyRgba2x2());
        long feedStart = System.nanoTime();
        feed(emu, sb.toString());
        long feedMs = (System.nanoTime() - feedStart) / 1_000_000L;

        emu.setSelectionRange(0, -9999, 10, -9998);
        int[] selBefore = emu.getSelectionRange();
        check(Arrays.equals(selBefore, new int[]{0, -9999, 10, -9998}),
            "tracked selection anchor is established in cold history before compression");

        TerminalRenderFrame frameBefore = emu.getRenderFrame();
        check(frameBefore.images.size() == 1 && emu.getViewportHistoryRows() == 10000 && emu.getViewportTopRow() == 0,
            "10,000 history rows, bottom viewport and Kitty image are ready before idle compression");

        final long[] memBefore = GhosttyNative.memoryUsage(handle);
        check(memBefore != null && memBefore[7] == 1 && memBefore[2] == 0 && memBefore[3] == 0 && memBefore[5] > 1,
            "before idle delay: compression_supported=1, compressed_pages=0, primary_pages=" +
            (memBefore == null ? -1 : memBefore[5]) + " (feedMs=" + feedMs + ")");

        final TerminalSession[] multi = new TerminalSession[4];
        final long[] multiBeforeResident = new long[4];
        StringBuilder sb1k = new StringBuilder(80000);
        for (int i = 0; i < 1020; i++) {
            sb1k.append(String.format(Locale.ROOT,
                "WIN-LINE-%04d compressible multi-window history text payload\r\n", i));
        }
        String text1k = sb1k.toString();
        for (int w = 0; w < 4; w++) {
            multi[w] = makeSession(1000);
            GhosttyTerminalEmulator we = (GhosttyTerminalEmulator) multi[w].getEmulator();
            feed(we, text1k);
            we.getRenderFrame();
            long[] mu = GhosttyNative.memoryUsage(we.adapter().handle());
            multiBeforeResident[w] = mu[0];
        }

        main.postDelayed(() -> {
            try {
                long[] mid = GhosttyNative.memoryUsage(handle);
                check(mid[2] == 0 && emu.compressor().stepCount == 0,
                    "10,000-line session does not compress before 2000ms idle delay");
            } catch (Throwable t) { fail(t); }
        }, 1000);

        final long deadline = System.nanoTime() + 15_000_000_000L;
        Runnable pollInitial = new Runnable() {
            @Override public void run() {
                try {
                    boolean allMultiDone = true;
                    for (int w = 0; w < 4; w++) {
                        GhosttyTerminalEmulator we = (GhosttyTerminalEmulator) multi[w].getEmulator();
                        if (we.compressor().completeCount < 1 || we.compressor().isPending()) allMultiDone = false;
                    }
                    if (emu.compressor().completeCount < 1 || emu.compressor().isPending() || !allMultiDone) {
                        if (System.nanoTime() > deadline)
                            throw new AssertionError("Timed out waiting for automatic 10,000-line idle compression");
                        main.postDelayed(this, 80);
                        return;
                    }
                    verifyAfterInitialCompression(session, emu, handle, memBefore, multi, multiBeforeResident);
                } catch (Throwable t) { fail(t); }
            }
        };
        main.postDelayed(pollInitial, 2200);
    }

    static void verifyAfterInitialCompression(
            TerminalSession session, GhosttyTerminalEmulator emu, long handle,
            long[] memBefore, TerminalSession[] multi, long[] multiBeforeResident) {
        long[] memAfter = GhosttyNative.memoryUsage(handle);
        GhosttyScrollbackCompressor comp = emu.compressor();
        double totalStepMs = comp.totalStepNanos / 1_000_000.0;
        double maxStepMs = comp.maxStepNanos / 1_000_000.0;
        double spanMs = (comp.lastStepNanos - comp.firstStepNanos) / 1_000_000.0;
        System.out.println(String.format(Locale.ROOT,
            "TELEMETRY_10K resident_before=%d resident_after=%d virtual_before=%d virtual_after=%d " +
            "pages=%d compressed_pages=%d compressed_bytes=%d image_bytes=%d steps=%d " +
            "total_step_ms=%.3f max_step_ms=%.3f schedule_span_ms=%.3f",
            memBefore[0], memAfter[0], memBefore[1], memAfter[1],
            memAfter[5], memAfter[2], memAfter[3], memAfter[4],
            comp.stepCount, totalStepMs, maxStepMs, spanMs));

        check(memAfter[2] > 0 && memAfter[3] > 0 && memAfter[0] < memBefore[0],
            "automatic idle compression reduces native primary resident bytes (" +
            memBefore[0] + " -> " + memAfter[0] + ", compressedPages=" + memAfter[2] + ")");
        check(memAfter[1] >= memBefore[1] && memAfter[5] == memBefore[5] && memAfter[4] == memBefore[4],
            "compression preserves page address space, total page count and image bytes");

        long multiBeforeSum = 0, multiAfterSum = 0, multiCompressedPages = 0, multiMaxStepNs = 0;
        for (int w = 0; w < 4; w++) {
            GhosttyTerminalEmulator we = (GhosttyTerminalEmulator) multi[w].getEmulator();
            long[] mu = GhosttyNative.memoryUsage(we.adapter().handle());
            multiBeforeSum += multiBeforeResident[w];
            multiAfterSum += mu[0];
            multiCompressedPages += mu[2];
            if (we.compressor().maxStepNanos > multiMaxStepNs) multiMaxStepNs = we.compressor().maxStepNanos;
            multi[w].disposeEmulator();
        }
        System.out.println(String.format(Locale.ROOT,
            "TELEMETRY_4X1K resident_before=%d resident_after=%d compressed_pages=%d max_step_ms=%.3f",
            multiBeforeSum, multiAfterSum, multiCompressedPages, multiMaxStepNs / 1_000_000.0));
        check(multiCompressedPages >= 4 && multiAfterSum < multiBeforeSum,
            "4x1000 multi-window automatic compression reduces resident memory across all windows");

        check(emu.getViewportTopRow() == 0 && emu.getViewportHistoryRows() == 10000 && emu.getViewportTotalRows() == 10024,
            "compression does not move viewport or prune history rows");
        check(emu.mColors.mCurrentColors[17] == 0xffabcdef && emu.mColors.mCurrentColors[256] == 0xff112233,
            "custom palette and default foreground survive compression");
        TerminalRenderFrame frameAfter = emu.getRenderFrame();
        check(frameAfter.images.size() == 1 && frameAfter.images.get(0).bitmap.getPixel(0, 0) == 0xffff0000,
            "Kitty image placement and pixels survive scrollback compression");
        check(Arrays.equals(emu.getSelectionRange(), new int[]{0, -9999, 10, -9998}),
            "tracked selection anchor in compressed history remains valid");
        emu.clearSelectionRange();
        long[] memAfterMeta = GhosttyNative.memoryUsage(handle);
        check(memAfterMeta[2] == memAfter[2] && !comp.isPending(),
            "active-frame render and selection-coordinate query do not decompress cold pages or reschedule");

        int stepsBeforeTui = comp.stepCount;
        feed(emu, "\033[1;1H\033[32mTUI-INPLACE\033[0m");
        emu.getRenderFrame();
        check(!comp.isPending() && comp.stepCount == stepsBeforeTui &&
            GhosttyNative.memoryUsage(handle)[2] == memAfter[2],
            "in-place TUI refresh in active area does not decompress cold pages or queue new compression work");

        feed(emu, "\033[?2026h\033[2;1HHELD-DRAFT");
        check(!emu.getScreen().getSelectedText(0, 0, 79, 23).contains("HELD-DRAFT"),
            "mode 2026 hold freezes frame while history is compressed");
        feed(emu, "\033[?2026l");
        check(emu.getScreen().getSelectedText(0, 0, 79, 23).contains("HELD-DRAFT"),
            "mode 2026 release commits frame cleanly with compressed history");
        check(GhosttyNative.memoryUsage(handle)[2] == memAfter[2],
            "mode 2026 hold/release preserves compressed cold pages");

        final long compressedFull = memAfter[2];
        String uri = emu.getViewportLink(1, -10000);
        check("https://example.com/doc-0".equals(uri),
            "OSC 8 hyperlink lookup on compressed cold row -10000 transparently restores and returns URI");
        int[] word = emu.getWordBounds(1, -10000);
        check(Arrays.equals(word, new int[]{0, -10000, 4, -10000}),
            "word-bounds lookup on compressed cold row -10000 returns exact word coordinates");
        check(emu.getValidSelectionColumn(-10000, 7) == 8 && "中".equals(emu.getTextAt(6, -10000)),
            "wide-char column and getTextAt work on restored cold history row");
        String coldCopy = emu.getSelectedText(0, -10000, 79, -9999);
        check(coldCopy.contains("LINK0 中文✨e\u0301👨\u200d👩\u200d👧\u200d👦🇺🇸 STYLE0 LINE-00000") &&
            coldCopy.contains("LINE-00001"),
            "cross-history getSelectedText preserves grapheme clusters and lines from cold history");

        long[] memRestored = GhosttyNative.memoryUsage(handle);
        check(memRestored[2] < compressedFull && comp.isPending(),
            "reading cold history restores the accessed page (" + compressedFull + " -> " +
            memRestored[2] + ") and schedules automatic recompression");

        final int prevCompletes = comp.completeCount;
        final long recompressDeadline = System.nanoTime() + 12_000_000_000L;
        Runnable pollRecompress = new Runnable() {
            @Override public void run() {
                try {
                    if (comp.completeCount <= prevCompletes || comp.isPending()) {
                        if (System.nanoTime() > recompressDeadline)
                            throw new AssertionError("Timed out waiting for cold-read automatic recompression");
                        main.postDelayed(this, 80);
                        return;
                    }
                    long[] memRecompressed = GhosttyNative.memoryUsage(handle);
                    check(memRecompressed[2] == compressedFull,
                        "cold page restored by history read is automatically recompressed after idle (" +
                        memRestored[2] + " -> " + memRecompressed[2] + ")");
                    verifyScrollAndLifecycle(session, emu, handle, compressedFull);
                } catch (Throwable t) { fail(t); }
            }
        };
        main.postDelayed(pollRecompress, 2150);
    }

    static void verifyScrollAndLifecycle(
            TerminalSession session, GhosttyTerminalEmulator emu, long handle, long compressedFull)
            throws Exception {
        GhosttyScrollbackCompressor comp = emu.compressor();

        emu.setViewportTopRow(-10000);
        TerminalRenderFrame topFrame = emu.getRenderFrame();
        String topText = topFrame.screen.getSelectedText(0, 0, 79, 2);
        check(emu.getViewportTopRow() == -10000 &&
            topText.contains("LINK0 中文✨e\u0301👨\u200d👩\u200d👧\u200d👦🇺🇸 STYLE0 LINE-00000"),
            "scrolling to top of compressed history transparently restores and renders oldest rows");

        int styledCells = 0;
        int expectedBg = 0xff000000 | (topFrame.palette[236] & 0xffffff);
        for (int col = 0; col < 40; col++) {
            long style = topFrame.screen.getStyleAt(0, col);
            int effect = TextStyle.decodeEffect(style);
            if (TextStyle.decodeForeColor(style) == 0xff123456 &&
                TextStyle.decodeBackColor(style) == expectedBg &&
                (effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0 &&
                (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 &&
                (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                styledCells++;
            }
        }
        check(styledCells == 6,
            "restored history row preserves exact RGB foreground, palette background and bold/italic/underline styles (styledCells=" + styledCells + ")");

        long[] memAtTop = GhosttyNative.memoryUsage(handle);
        check(memAtTop[2] < compressedFull,
            "rendering scrolled history viewport keeps the visible history page resident (" +
            compressedFull + " -> " + memAtTop[2] + ")");

        emu.scrollViewportBottom();
        emu.getRenderFrame();
        check(emu.getViewportTopRow() == 0 && comp.isPending(),
            "scrolling back to bottom marks compression activity");

        final int completesBeforeScroll = comp.completeCount;
        final long scrollDeadline = System.nanoTime() + 12_000_000_000L;
        Runnable pollScrollRecompress = new Runnable() {
            @Override public void run() {
                try {
                    if (comp.completeCount <= completesBeforeScroll || comp.isPending()) {
                        if (System.nanoTime() > scrollDeadline)
                            throw new AssertionError("Timed out waiting for post-scroll automatic recompression");
                        main.postDelayed(this, 80);
                        return;
                    }
                    long[] memBottom = GhosttyNative.memoryUsage(handle);
                    check(memBottom[2] == compressedFull,
                        "returning viewport to bottom automatically recompresses history page (" +
                        memAtTop[2] + " -> " + memBottom[2] + ")");
                    finishLifecycleChecks(session, emu, handle);
                } catch (Throwable t) { fail(t); }
            }
        };
        main.postDelayed(pollScrollRecompress, 2150);
    }

    static void finishLifecycleChecks(TerminalSession session, GhosttyTerminalEmulator emu, long handle)
            throws Exception {
        feed(emu, "\033[24;1H\r\nPOST-COMPRESS-LINE");
        check(emu.getScreen().getSelectedText(0, 0, 79, 23).contains("POST-COMPRESS-LINE"),
            "appending new output after compression works normally");
        emu.paste("paste-check");
        check(emu.compressor().isPending(), "paste defers pending compression work");

        emu.resize(70, 20, 12, 24);
        check(emu.getScreen().getActiveRows() == 20 && emu.mRows == 20 && emu.mColumns == 70,
            "resize after history compression reflows and updates dimensions");

        feed(emu, "\033[?1049hALT-SCREEN-OK");
        check(emu.isAlternateBufferActive() &&
            emu.getScreen().getSelectedText(0, 0, 69, 19).contains("ALT-SCREEN-OK"),
            "alternate screen switch works while primary history is present");
        feed(emu, "\033[?1049l");
        check(!emu.isAlternateBufferActive(), "leaving alternate screen restores primary screen");

        feed(emu, "\033cAFTER-RIS");
        check(emu.getViewportHistoryRows() == 0 &&
            emu.getScreen().getSelectedText(0, 0, 69, 19).contains("AFTER-RIS"),
            "RIS full reset discards compressed history cleanly and accepts new output");

        final boolean[] wrongDisposeRejected = {false};
        Thread wrongDispose = new Thread(() -> {
            try {
                emu.dispose();
            } catch (IllegalStateException expected) {
                wrongDisposeRejected[0] = true;
            }
        });
        wrongDispose.start();
        wrongDispose.join();
        check(wrongDisposeRejected[0] && !emu.isEmulatorDisposed() &&
            emu.adapter() != null && !emu.compressor().isClosed(),
            "wrong-thread dispose is rejected and preserves live adapter and compressor");

        GhosttyScrollbackCompressor compRef = emu.compressor();
        session.disposeEmulator();
        check(emu.isEmulatorDisposed() && emu.adapter() == null && compRef.isClosed(),
            "owner-thread dispose closes compressor and releases native handle");
        boolean freedActivityThrows = false;
        try { GhosttyNative.compressionActivity(handle); } catch (IllegalStateException expected) { freedActivityThrows = true; }
        check(freedActivityThrows && GhosttyNative.compressStep(handle) == -1,
            "freed handle is rejected by compression JNI after dispose");

        System.out.println("NATIVE COMPRESSION OK checks=" + checks);
        System.exit(0);
    }

    static void fail(Throwable t) {
        t.printStackTrace(System.out);
        System.out.flush();
        System.exit(1);
    }
}
