import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Typeface;
import android.util.LruCache;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.R5StyleSupport;
import com.termux.terminal.TextStyle;
import com.termux.view.R5BeforeRenderer;
import com.termux.view.BundledTerminalFonts;
import com.termux.view.TerminalRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

public final class R5RegressionCheck {
    private static String outDir;
    private static final String variantName = "integrated-r5";
    private static int totalChecks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL [" + variantName + "]: " + message);
        totalChecks++;
    }

    private static byte[] renderGpu(CacheRegression.Target target, TerminalEmulator e, Object renderer,
                                    int topRow, int sy1, int sy2, int sx1, int sx2) throws Exception {
        RecordingCanvas c = target.node.beginRecording(CacheRegression.W, CacheRegression.H);
        c.drawColor(0xff151a25);
        if (renderer instanceof TerminalRenderer) {
            ((TerminalRenderer) renderer).render(e, c, topRow, sy1, sy2, sx1, sx2);
        } else {
            ((R5BeforeRenderer) renderer).render(e, c, topRow, sy1, sy2, sx1, sx2);
        }
        target.node.endRecording();
        return target.image();
    }

    private static void assertGpuAndSoftMatch(CacheRegression.Target candTarget,
                                              CacheRegression.Target baseTarget,
                                              TerminalEmulator e,
                                              TerminalRenderer cand,
                                              R5BeforeRenderer base,
                                              String label,
                                              int topRow, int sy1, int sy2, int sx1, int sx2) throws Exception {
        byte[] expected = renderGpu(baseTarget, e, base, topRow, sy1, sy2, sx1, sx2);
        byte[] actual = renderGpu(candTarget, e, cand, topRow, sy1, sy2, sx1, sx2);
        if (!Arrays.equals(expected, actual)) {
            int diff = 0;
            for (int i = 0; i < expected.length; i++) if (expected[i] != actual[i]) diff++;
            savePng(expected, outDir + "/FAIL-gpu-expected-" + variantName + ".png");
            savePng(actual, outDir + "/FAIL-gpu-actual-" + variantName + ".png");
            throw new AssertionError("FAIL [" + variantName + "]: GPU pixel mismatch [" + label + "] diffBytes=" + diff);
        }
        totalChecks++;

        Bitmap refBitmap = Bitmap.createBitmap(CacheRegression.W, CacheRegression.H, Bitmap.Config.ARGB_8888);
        Bitmap candBitmap = Bitmap.createBitmap(CacheRegression.W, CacheRegression.H, Bitmap.Config.ARGB_8888);
        Canvas refCanvas = new Canvas(refBitmap);
        Canvas candCanvas = new Canvas(candBitmap);
        refCanvas.drawColor(0xff151a25);
        candCanvas.drawColor(0xff151a25);
        base.render(e, refCanvas, topRow, sy1, sy2, sx1, sx2);
        cand.render(e, candCanvas, topRow, sy1, sy2, sx1, sx2);
        boolean identical = refBitmap.sameAs(candBitmap);
        refBitmap.recycle();
        candBitmap.recycle();
        check(identical, "software Canvas pixel mismatch [" + label + "]");
    }

    private static void savePng(byte[] bytes, String path) throws Exception {
        Bitmap b = Bitmap.createBitmap(CacheRegression.W, CacheRegression.H, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));
        try (FileOutputStream out = new FileOutputStream(path)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        b.recycle();
    }

    private static boolean hasMethod(Class<?> cls, String name) {
        for (Method m : cls.getDeclaredMethods()) if (m.getName().equals(name)) return true;
        return false;
    }

    private static boolean hasField(Class<?> cls, String name) {
        for (Field f : cls.getDeclaredFields()) if (f.getName().equals(name)) return true;
        return false;
    }

    private static void verifyP1RevisionContract() throws Exception {
        check(hasMethod(TerminalRow.class, "getRevision"), "row revision API must exist");
        Method getRevision = TerminalRow.class.getDeclaredMethod("getRevision");
        getRevision.setAccessible(true);
        Method setAsciiRun = TerminalRow.class.getDeclaredMethod(
                "setAsciiRun", int.class, byte[].class, int.class, int.class, long.class);
        setAsciiRun.setAccessible(true);

        TerminalRow row = new TerminalRow(16, R5StyleSupport.normal());
        long rev0 = (Long) getRevision.invoke(row);
        check(rev0 > 0, "constructor clear() must advance revision above 0");

        row.setChar(0, 'A', R5StyleSupport.normal(), 1);
        long rev1 = (Long) getRevision.invoke(row);
        check(rev1 > rev0, "narrow BMP setChar fast path must advance revision");

        byte[] ascii = "BCDEF".getBytes(StandardCharsets.US_ASCII);
        setAsciiRun.invoke(row, 1, ascii, 0, ascii.length, R5StyleSupport.normal());
        long rev2 = (Long) getRevision.invoke(row);
        check(rev2 > rev1, "setAsciiRun fast path must advance revision");

        row.setChar(6, 0x6c49, R5StyleSupport.normal(), 2);
        long rev3 = (Long) getRevision.invoke(row);
        check(rev3 > rev2, "wide char setChar must advance revision");

        long previous = rev3;
        for (int i = 0; i < 15; i++) {
            row.setChar(6, 0x0301, R5StyleSupport.normal(), 0);
            long now = (Long) getRevision.invoke(row);
            check(now > previous, "combining char #" + (i + 1) + " must advance revision");
            previous = now;
        }
        long cappedStyle = R5StyleSupport.encode(2, 4, TextStyle.CHARACTER_ATTRIBUTE_BOLD);
        row.setChar(6, 0x0301, cappedStyle, 0);
        long revCap = (Long) getRevision.invoke(row);
        check(revCap > previous, "16th combining char (early return after style write) must advance revision");
        check(row.getStyle(6) == cappedStyle, "16th combining char style write preserved");

        setAsciiRun.invoke(row, 10, ascii, 0, 3, cappedStyle);
        long revFallback = (Long) getRevision.invoke(row);
        check(revFallback > revCap, "setAsciiRun fallback path must advance revision");

        row.clear(R5StyleSupport.normal());
        long revClear = (Long) getRevision.invoke(row);
        check(revClear > revFallback, "clear() must advance revision monotonically");
        check(revClear != rev0, "clear() must not reset revision to a fixed initial value");

        TerminalBuffer buffer = new TerminalBuffer(16, 32, 8);
        buffer.setChar(0, 0, 0x6c49, R5StyleSupport.normal());
        TerminalRow destination = buffer.allocateFullLineIfNecessary(buffer.externalToInternalRow(1));
        long beforeCopy = (Long) getRevision.invoke(destination);
        buffer.blockCopy(0, 0, 4, 1, 0, 1);
        check((Long) getRevision.invoke(destination) > beforeCopy,
                "blockCopy/copyInterval must advance the destination row revision");

        long beforeEffect = (Long) getRevision.invoke(destination);
        buffer.setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, true, false,
                true, 0, 16, 1, 0, 2, 4);
        check((Long) getRevision.invoke(destination) > beforeEffect,
                "setOrClearEffect must advance affected row revision");

        Random rng = new Random(20261026L);
        char[] snapshotText = Arrays.copyOf(row.mText, row.getSpaceUsed());
        long[] snapshotStyle = R5StyleSupport.styles(row, 16);
        long snapshotRevision = (Long) getRevision.invoke(row);
        for (int step = 0; step < 500; step++) {
            int operation = rng.nextInt(5);
            int column = rng.nextInt(15);
            long style = R5StyleSupport.encode(rng.nextInt(16), rng.nextInt(16), rng.nextInt(8));
            if (operation == 0) {
                row.setChar(column, 'a' + rng.nextInt(26), style, 1);
            } else if (operation == 1) {
                row.setChar(column, 0x4e00 + rng.nextInt(200), style, 2);
            } else if (operation == 2) {
                row.setChar(column, 0x0300 + rng.nextInt(16), style, 0);
            } else if (operation == 3) {
                byte[] run = {(byte) ('0' + rng.nextInt(10)), (byte) ('A' + rng.nextInt(26))};
                setAsciiRun.invoke(row, column, run, 0, 2, style);
            } else {
                row.clear(style);
            }
            long currentRevision = (Long) getRevision.invoke(row);
            char[] currentText = Arrays.copyOf(row.mText, row.getSpaceUsed());
            long[] currentStyle = R5StyleSupport.styles(row, 16);
            boolean changed = !Arrays.equals(snapshotText, currentText)
                    || !Arrays.equals(snapshotStyle, currentStyle);
            if (changed) {
                check(currentRevision != snapshotRevision,
                        "step " + step + " changed row content/style but not the revision");
            }
            snapshotText = currentText;
            snapshotStyle = currentStyle;
            snapshotRevision = currentRevision;
        }
        System.out.println("PASS P1 revision contract + 500-step shadow cross-check");
    }

    private static void verifyP1aNarrowShortCircuit(CacheRegression.Target candTarget,
                                                    CacheRegression.Target baseTarget,
                                                    TerminalRenderer cand,
                                                    R5BeforeRenderer base) throws Exception {
        check(hasMethod(TerminalRow.class, "hasNonOneWidthOrSurrogateChars"),
                "row complexity API must exist");
        Method narrow = TerminalRow.class.getDeclaredMethod("hasNonOneWidthOrSurrogateChars");
        narrow.setAccessible(true);

        TerminalEmulator e = RenderComparison.emulator();
        e.resize(80, 20, 12, 24);
        RenderComparison.feed(e, "\033[1;1H\033[3;48;2;40;40;55mItalic narrow ASCII comment\033[0m");
        RenderComparison.feed(e, "\033[2;1H\033[3;48;2;40;55;40mCaf\u00e9 r\u00e9sum\u00e9 na\u00efve narrow Latin-1\033[0m");
        RenderComparison.feed(e, "\033[3;1H\033[3;48;2;55;40;40m拿到主设备先定尺寸申请成为控制终端\033[0m");
        RenderComparison.feed(e, "\033[4;1H\033[1;3;4;9;48;2;32;48;64m粗斜体下划线删除线混排 ABC 中文\033[0m");

        TerminalBuffer screen = e.getScreen();
        TerminalRow narrowAscii = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(0));
        TerminalRow narrowLatin = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(1));
        TerminalRow wideHan = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(2));

        check(!(Boolean) narrow.invoke(narrowAscii), "narrow ASCII italic row must report no wide/surrogate chars");
        check(!(Boolean) narrow.invoke(narrowLatin), "precomposed Latin-1 italic row (width 1, no combining) must report no wide/surrogate chars");
        check((Boolean) narrow.invoke(wideHan), "CJK italic row must report wide chars");

        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "P1a-italic-scenes", 0, -1, -1, -1, -1);

        wideHan.clear(R5StyleSupport.normal());
        check(!(Boolean) narrow.invoke(wideHan), "clear() must reset the wide/surrogate flag");
        RenderComparison.feed(e, "\033[3;1H\033[3mCleared then narrow italic\033[0m");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "P1a-after-clear", 0, -1, -1, -1, -1);
        System.out.println("PASS P1a narrow-row italic short-circuit");
    }

    @SuppressWarnings("unchecked")
    private static void verifyP1bSingleCodePointCache(CacheRegression.Target candTarget,
                                                      CacheRegression.Target baseTarget,
                                                      R5BeforeRenderer base) throws Exception {
        check(hasField(TerminalRenderer.class, "mSingleShapedRuns"),
                "bounded single-code-point cache must exist");
        TerminalRenderer cand = new TerminalRenderer(22, BundledTerminalFonts.typeface());

        Field multiField = TerminalRenderer.class.getDeclaredField("mShapedRuns");
        multiField.setAccessible(true);
        LruCache<String, ?> multiCache = (LruCache<String, ?>) multiField.get(cand);
        Method sizeMethod = LruCache.class.getDeclaredMethod("size");
        sizeMethod.setAccessible(true);
        Field singleField = TerminalRenderer.class.getDeclaredField("mSingleShapedRuns");
        singleField.setAccessible(true);
        Object singleCache = singleField.get(cand);
        Field usedBytesField = singleCache.getClass().getDeclaredField("usedBytes");
        usedBytesField.setAccessible(true);
        Method recomputeMethod = singleCache.getClass().getDeclaredMethod("recomputeUsedBytes");
        recomputeMethod.setAccessible(true);

        TerminalEmulator e = RenderComparison.emulator();
        e.resize(80, 20, 12, 24);
        RenderComparison.feed(e, "\033[1;1H中文单码点缓存测试\033[1m粗体中文\033[3m粗斜体"
                + "\033[22m纯斜体\033[0;5m闪烁算粗体\033[0m");
        int putsBefore = multiCache.putCount();
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "P1b-cjk-only", 0, -1, -1, -1, -1);
        check(multiCache.putCount() == putsBefore,
                "single-codepoint CJK runs must not insert into the String mShapedRuns cache");

        RenderComparison.feed(e, "\033[2;1HMultiCharAsciiRun e\u0301\u0302 汉\u0301 "
                + "\u23be\u23bf \u2500\u2502\u2584\u25cf\u25cb\u2022");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "P1b-mixed-runs", 0, -1, -1, -1, -1);
        check(multiCache.putCount() > putsBefore,
                "multi-char ASCII runs and combining clusters must still use mShapedRuns");

        for (int batch = 0; batch < 12; batch++) {
            StringBuilder sb = new StringBuilder("\033[H");
            for (int r = 0; r < 18; r++) {
                int styleCode = (r % 4 == 0) ? 0 : (r % 4 == 1 ? 1 : (r % 4 == 2 ? 3 : 5));
                sb.append("\033[").append(styleCode).append('m');
                for (int c = 0; c < 36; c++) sb.appendCodePoint(0x4e00 + ((batch * 648 + r * 36 + c) % 4500));
                sb.append("\033[0m\r\n");
            }
            RenderComparison.feed(e, sb.toString());
            assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "P1b-eviction-batch-" + batch,
                    0, -1, -1, -1, -1);
            int singleBytes = (Integer) usedBytesField.get(singleCache);
            int recomputed = (Integer) recomputeMethod.invoke(singleCache);
            check(singleBytes == recomputed,
                    "single-code-point cache counter drifted from live slots: " + singleBytes + " vs " + recomputed);
            int multiBytes = (Integer) sizeMethod.invoke(multiCache);
            check(singleBytes <= 256 * 1024,
                    "SingleCodePointCache exceeded its 256 KiB budget: " + singleBytes);
            check(singleBytes + multiBytes <= 512 * 1024,
                    "combined shaping caches exceeded 512 KiB: " + (singleBytes + multiBytes));
        }
        System.out.println("PASS P1b single-code-point cache and budget eviction");
    }

    private static void verifyScrollAndBoundaries(CacheRegression.Target candTarget,
                                                    CacheRegression.Target baseTarget) throws Exception {
        TerminalRenderer cand = new TerminalRenderer(22, BundledTerminalFonts.typeface());
        R5BeforeRenderer base = new R5BeforeRenderer(22, BundledTerminalFonts.typeface());
        TerminalEmulator e = RenderComparison.emulator();
        e.resize(80, 20, 12, 24);

        for (int i = 0; i < 20; i++) {
            String bg = (i == 0 || i == 19) ? "\033[48;2;30;50;70m" : "";
            RenderComparison.feed(e, String.format(
                    "\033[%d;1H%sRow %02d 中文 \u2713 \033[3m斜体\033[0m %sEnd-%02d\033[0m",
                    i + 1, bg, i, bg, i));
        }
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-initial-20-rows", 0, -1, -1, -1, -1);

        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-static-frame", 0, -1, -1, -1, -1);

        RenderComparison.feed(e, "\033[20;80H\r\n\033[48;2;60;30;50mNew bottom row 中文 \033[3m斜体\033[0m");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-scroll-by-1", 0, -1, -1, -1, -1);

        for (int n : new int[]{2, 5, 19, 25}) {
            for (int k = 0; k < n; k++) {
                RenderComparison.feed(e, "\r\nScroll N=" + n + " line=" + k
                        + " 中文 \033[1;33m彩色\033[0m \033[3m斜体字\033[0m");
            }
            assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-scroll-N=" + n, 0, -1, -1, -1, -1);
        }

        RenderComparison.feed(e, "\033[?25h\033[1;4H\033[44mTopRowWithBgAndCursor\033[0m\033[1;6H");
        for (int shape : new int[]{0, 1, 2}) {
            RenderComparison.feed(e, shape == 0 ? "\033[2 q" : (shape == 1 ? "\033[4 q" : "\033[6 q"));
            assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base,
                    "scroll-toprow-cursor-shape-" + shape, 0, 0, 3, 2, 18);
        }
        RenderComparison.feed(e, "\033[?5h");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-reverse-on", 0, 0, 3, 2, 18);
        RenderComparison.feed(e, "\033[?5l");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-reverse-off", 0, -1, -1, -1, -1);

        RenderComparison.feed(e, "\033[4;16r\033[16;1H");
        for (int k = 0; k < 4; k++) {
            RenderComparison.feed(e, "\r\nDECSTBM scroll " + k + " 中文 \033[45mMarginBg\033[0m");
        }
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-DECSTBM", 0, -1, -1, -1, -1);

        RenderComparison.feed(e, "\033[?69h\033[5;60s\033[16;5H");
        for (int k = 0; k < 3; k++) {
            RenderComparison.feed(e, "\r\nDECLRMM inner scroll " + k + " 汉字");
        }
        RenderComparison.feed(e, "\033[?69l\033[r");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-DECLRMM", 0, -1, -1, -1, -1);

        for (int top : new int[]{-1, -2, -7, -15, -4, 0}) {
            assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-history-top=" + top,
                    top, -1, -1, -1, -1);
        }

        RenderComparison.feed(e, "\033[?1049h\033[2J\033[H\033[42mAlt screen top\033[0m\r\nAlt line 2 中文");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-alt-screen", 0, -1, -1, -1, -1);
        RenderComparison.feed(e, "\033[?1049l");
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-main-restored", 0, -1, -1, -1, -1);

        e.resize(64, 16, 12, 24);
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-resize-64x16", 0, -1, -1, -1, -1);
        e.resize(80, 20, 12, 24);
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-resize-80x20", 0, -1, -1, -1, -1);

        Method clearRowCache = TerminalRenderer.class.getDeclaredMethod("clearRowCache");
        clearRowCache.setAccessible(true);
        clearRowCache.invoke(cand);
        assertGpuAndSoftMatch(candTarget, baseTarget, e, cand, base, "scroll-after-clearRowCache", 0, -1, -1, -1, -1);

        System.out.println("PASS rendering equivalence across scroll, margins, boundaries and lifecycle");
    }

    public static void main(String[] args) throws Exception {
        String fontZip = args[0];
        outDir = args[1];
        System.setOut(new java.io.PrintStream(new File(outDir, "r5-regression-check.txt")));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, fontZip);
            BundledTerminalFonts.initialize(assets);

            verifyP1RevisionContract();

            TerminalRenderer cand = new TerminalRenderer(22, BundledTerminalFonts.typeface());
            R5BeforeRenderer base = new R5BeforeRenderer(22, BundledTerminalFonts.typeface());
            try (CacheRegression.Target candTarget = new CacheRegression.Target();
                 CacheRegression.Target baseTarget = new CacheRegression.Target()) {
                verifyP1aNarrowShortCircuit(candTarget, baseTarget, cand, base);
                verifyP1bSingleCodePointCache(candTarget, baseTarget, base);
                verifyScrollAndBoundaries(candTarget, baseTarget);
            }
            System.out.println("ALL PASS [" + variantName + "] totalChecks=" + totalChecks);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }
}
