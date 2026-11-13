package com.termux.view;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.view.View;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import io.github.modao7067.luma.ghostty.GhosttyFrameAdapter;
import io.github.modao7067.luma.ghostty.GhosttyNative;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OffscreenBenchCheck {
    static Context ctx;
    static JSONObject report = new JSONObject();

    static final class BenchPty implements TerminalSession.PtyProvider {
        public void start(TerminalSession s, int c, int r, int cw, int ch) {}
        public void resize(int c, int r, int cw, int ch) {}
        public void closeWindow() {}
        public void detach() {}
    }

    static TerminalSessionClient silentClient() {
        return (TerminalSessionClient) Proxy.newProxyInstance(
            OffscreenBenchCheck.class.getClassLoader(),
            new Class[]{TerminalSessionClient.class},
            (p, m, a) -> "getTerminalCursorStyle".equals(m.getName()) ? 0 : null);
    }

    static TerminalSession createSession(boolean nativeEngine, int historyRows, int cols, int rows) {
        TerminalSession s = new TerminalSession(
            new BenchPty(),
            historyRows,
            silentClient(),
            nativeEngine
                ? GhosttyTerminalEmulator::new
                : (ss, c, r, cw, ch, tr, cl) -> new TerminalEmulator(ss, c, r, cw, ch, tr, cl));
        s.initializeEmulator(cols, rows, 12, 24);
        return s;
    }

    static TerminalView createSizedView(TerminalSession s, int widthPx, int heightPx, int cols, int rows) {
        TerminalView v = new TerminalView(ctx, null);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setTerminalViewClient(ViewViewportCheck.viewClient());
        v.setTextSize(22);
        v.setTypeface(BundledTerminalFonts.typeface());
        v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, widthPx, heightPx);
        v.attachSession(s);
        s.updateSize(cols, rows, 12, 24);
        return v;
    }

    static final class GpuSurface implements AutoCloseable {
        final int width, height;
        final ImageReader reader;
        final HardwareRenderer renderer;
        final RenderNode node;
        final Bitmap softBitmap;
        final Canvas softCanvas;

        GpuSurface(int width, int height) {
            this.width = width;
            this.height = height;
            this.reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN);
            this.renderer = new HardwareRenderer();
            this.node = new RenderNode("bench-root");
            this.node.setPosition(0, 0, width, height);
            this.renderer.setSurface(reader.getSurface());
            this.renderer.setOpaque(true);
            this.renderer.setContentRoot(node);
            this.softBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            this.softCanvas = new Canvas(softBitmap);
        }

        long drawSoft(TerminalView view, TerminalRenderFrame frame) {
            long t0 = System.nanoTime();
            softCanvas.drawColor(Color.BLACK);
            view.mRenderer.render(frame, softCanvas, view.getTopRow(), -1, -1, -1, -1);
            return System.nanoTime() - t0;
        }

        byte[] captureSoftPixels(TerminalView view, TerminalRenderFrame frame) {
            drawSoft(view, frame);
            ByteBuffer buf = ByteBuffer.allocate(width * height * 4);
            softBitmap.copyPixelsToBuffer(buf);
            return buf.array();
        }

        byte[] captureGpuPixels(TerminalView view, TerminalRenderFrame frame) {
            drawGpuSync(view, frame);
            byte[] out = new byte[width * height * 4];
            drainReadback(out);
            return out;
        }

        long drawGpuSync(TerminalView view, TerminalRenderFrame frame) {
            long t0 = System.nanoTime();
            RecordingCanvas c = node.beginRecording(width, height);
            c.drawColor(Color.BLACK);
            view.mRenderer.render(frame, c, view.getTopRow(), -1, -1, -1, -1);
            node.endRecording();
            renderer.createRenderRequest()
                .setVsyncTime(System.nanoTime())
                .setWaitForPresent(true)
                .syncAndDraw();
            return System.nanoTime() - t0;
        }

        long drainReadback(byte[] outPixels) {
            long t0 = System.nanoTime();
            try (Image image = reader.acquireLatestImage()) {
                if (image != null && outPixels != null) {
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    for (int y = 0; y < height; y++) {
                        buf.position(y * plane.getRowStride());
                        buf.get(outPixels, y * width * 4, width * 4);
                    }
                }
            }
            return System.nanoTime() - t0;
        }

        @Override public void close() {
            renderer.destroy();
            node.discardDisplayList();
            reader.close();
            softBitmap.recycle();
        }
    }

    static JSONObject summarizeNanos(long[] samplesNs) throws Exception {
        long[] copy = samplesNs.clone();
        Arrays.sort(copy);
        int n = copy.length;
        double medianUs = n % 2 == 1
            ? copy[n / 2] / 1000.0
            : (copy[n / 2 - 1] + copy[n / 2]) / 2000.0;
        int p95Idx = Math.min(n - 1, (int) Math.ceil(0.95 * n) - 1);
        int p99Idx = Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1);
        long sum = 0;
        JSONArray rawUs = new JSONArray();
        for (long v : samplesNs) {
            sum += v;
            rawUs.put(Math.round(v / 10.0) / 100.0);
        }
        JSONObject out = new JSONObject();
        out.put("count", n);
        out.put("min_us", Math.round(copy[0] / 10.0) / 100.0);
        out.put("median_us", Math.round(medianUs * 100.0) / 100.0);
        out.put("mean_us", Math.round((sum / (double) n) / 10.0) / 100.0);
        out.put("p95_us", Math.round(copy[p95Idx] / 10.0) / 100.0);
        out.put("p99_us", Math.round(copy[p99Idx] / 10.0) / 100.0);
        out.put("max_us", Math.round(copy[n - 1] / 10.0) / 100.0);
        out.put("samples_us", rawUs);
        return out;
    }

    public static void main(String[] args) throws Exception {
        try {
            Looper.prepareMainLooper();
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, args[1]);
            BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(args[0]);
            ctx = ViewViewportCheck.headlessContext(args[3]);
            ViewViewportCheck.context = ctx;

            File outDir = new File(args[2]);
            outDir.mkdirs();

            report.put("timestamp_start_ms", System.currentTimeMillis());
            report.put("device_state_before", readDeviceState());
            report.put("workload_1_cli_stream", runWorkload1CliStream());
            report.put("workload_2_tui_2026_100x40", runWorkload2Tui2026(outDir));
            report.put("workload_3_codex_7b_stream", runWorkload3Codex7Byte(outDir));
            report.put("workload_4_history_10k", runWorkload4History10k());
            report.put("device_state_after", readDeviceState());
            report.put("timestamp_end_ms", System.currentTimeMillis());

            File jsonFile = new File(outDir, "offscreen-bench.json");
            Files.write(jsonFile.toPath(), (report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("OFFSCREEN BENCH OK json=" + jsonFile.getAbsolutePath());
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static JSONObject readDeviceState() {
        JSONObject info = new JSONObject();
        try {
            info.put("loadavg", new String(Files.readAllBytes(new File("/proc/loadavg").toPath()), StandardCharsets.UTF_8).trim());
        } catch (Throwable ignored) {}
        try {
            File tz = new File("/sys/class/thermal/thermal_zone0/temp");
            if (tz.isFile()) {
                info.put("thermal_zone0_mC", new String(Files.readAllBytes(tz.toPath()), StandardCharsets.UTF_8).trim());
            }
        } catch (Throwable ignored) {}
        return info;
    }

    static int resolveColor(int rawColor, int[] palette) {
        if ((rawColor & 0xff000000) == 0xff000000) return rawColor;
        if (rawColor >= 0 && rawColor < palette.length) return 0xff000000 | (palette[rawColor] & 0xffffff);
        return rawColor;
    }

    static int[] exportResolvedCellStyles(TerminalRenderFrame rf, int cols, int rows) {
        int[] out = new int[rows * cols * 3];
        int at = 0;
        for (int y = 0; y < rows; y++) {
            String rowText = rf.screen.getSelectedText(0, y, cols - 1, y);
            boolean allBlank = rowText == null || rowText.trim().isEmpty();
            TerminalRow line = rf.screen.allocateFullLineIfNecessary(rf.screen.externalToInternalRow(y));
            int prevStart = -1;
            for (int x = 0; x < cols; x++) {
                int start = line.findStartOfColumn(x);
                boolean wideTail = (x > 0 && start == prevStart && start < line.getSpaceUsed());
                long s = line.getStyle(wideTail ? x - 1 : x);
                char ch = start < line.getSpaceUsed() ? line.mText[start] : ' ';
                boolean blank = allBlank || ch == ' ' || ch == 0;
                out[at++] = blank ? 0 : resolveColor(TextStyle.decodeForeColor(s), rf.palette);
                out[at++] = resolveColor(TextStyle.decodeBackColor(s), rf.palette);
                out[at++] = blank ? 0 : (TextStyle.decodeEffect(s) & 0x1ff);
                prevStart = start;
            }
        }
        return out;
    }

    static void assertFrameParity(String tag, FrameSnapshot a, FrameSnapshot b) {
        if (!a.fullText.equals(b.fullText))
            throw new AssertionError(tag + ": full screen text mismatch");
        if (a.cursorRow != b.cursorRow || a.cursorCol != b.cursorCol ||
                a.cursorVisible != b.cursorVisible || a.cursorStyle != b.cursorStyle)
            throw new AssertionError(tag + ": cursor state mismatch");
        if (!Arrays.equals(a.cellStyles, b.cellStyles)) {
            for (int i = 0; i < a.cellStyles.length; i += 3) {
                if (a.cellStyles[i] != b.cellStyles[i] || a.cellStyles[i+1] != b.cellStyles[i+1] || a.cellStyles[i+2] != b.cellStyles[i+2]) {
                    int cell = i / 3;
                    throw new AssertionError(tag + ": style mismatch at cell=" + cell +
                        " java=(" + Integer.toHexString(a.cellStyles[i]) + "," + Integer.toHexString(a.cellStyles[i+1]) + "," + a.cellStyles[i+2] + ")" +
                        " native=(" + Integer.toHexString(b.cellStyles[i]) + "," + Integer.toHexString(b.cellStyles[i+1]) + "," + b.cellStyles[i+2] + ")");
                }
            }
        }
        if (!Arrays.equals(a.softPixels, b.softPixels))
            throw new AssertionError(tag + ": software Canvas pixels mismatch");
        if (!Arrays.equals(a.gpuPixels, b.gpuPixels))
            throw new AssertionError(tag + ": hardware RenderNode GPU pixels mismatch");
    }

    static final class FrameSnapshot {
        final JSONObject metrics;
        final String fullText;
        final int cursorRow, cursorCol, cursorStyle;
        final boolean cursorVisible;
        final int[] cellStyles;
        final byte[] softPixels, gpuPixels;

        FrameSnapshot(JSONObject metrics, TerminalView v, TerminalEmulator emu, GpuSurface gpu, int cols, int rows) {
            this.metrics = metrics;
            TerminalRenderFrame rf = emu.getRenderFrame();
            this.fullText = rf.screen.getSelectedText(0, 0, cols - 1, rows - 1);
            this.cursorRow = rf.cursorRow;
            this.cursorCol = rf.cursorColumn;
            this.cursorVisible = rf.cursorVisible;
            this.cursorStyle = rf.cursorStyle;
            this.cellStyles = exportResolvedCellStyles(rf, cols, rows);
            this.softPixels = gpu.captureSoftPixels(v, rf);
            this.gpuPixels = gpu.captureGpuPixels(v, rf);
        }
    }

    static JSONObject runWorkload1CliStream() throws Exception {
        JSONObject res = new JSONObject();
        String line = "2026-11-01 INFO [build] 编译通过 src/main.zig -> libluma_vt.so 中文✨e\u0301👨\u200d👩\u200d👧\u200d👦 \033[32mOK\033[0m\r\n";
        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);

        for (int chunkSize : new int[]{512, 4096, 65536}) {
            byte[] payload = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) payload[i] = lineBytes[i % lineBytes.length];

            JSONObject jA = measureCliChunk(false, payload, 10, 40);
            JSONObject nA = measureCliChunk(true, payload, 10, 40);
            JSONObject jB = measureCliChunk(false, payload, 5, 40);
            JSONObject nB = measureCliChunk(true, payload, 5, 40);
            if (jA.getInt("cursor_row") != nA.getInt("cursor_row") || jA.getInt("cursor_col") != nA.getInt("cursor_col"))
                throw new AssertionError("W1 cursor mismatch for chunkSize=" + chunkSize);
            res.put("chunk_" + chunkSize + "_java_A", jA);
            res.put("chunk_" + chunkSize + "_native_A", nA);
            res.put("chunk_" + chunkSize + "_java_B", jB);
            res.put("chunk_" + chunkSize + "_native_B", nB);
        }
        return res;
    }

    static JSONObject measureCliChunk(boolean nativeEngine, byte[] payload, int warmup, int iters) throws Exception {
        TerminalSession s = createSession(nativeEngine, 2000, 80, 24);
        TerminalView v = createSizedView(s, 800, 600, 80, 24);
        TerminalEmulator emu = s.getEmulator();
        GhosttyFrameAdapter adapter = nativeEngine ? ((GhosttyTerminalEmulator) emu).adapter() : null;

        try (GpuSurface gpu = new GpuSurface(800, 600)) {
            long[] appendNs = new long[iters];
            long[] screenUpdNs = new long[iters];
            long[] projNs = new long[iters];
            long[] softNs = new long[iters];
            long[] gpuNs = new long[iters];
            long[] e2eNs = new long[iters];
            int totalCaptures = 0, totalJni = 0;

            for (int i = -warmup; i < iters; i++) {
                int cap0 = adapter != null ? adapter.captureCalls() : 0;
                int jni0 = adapter != null ? adapter.jniCalls() : 0;

                long tStart = System.nanoTime();
                emu.append(payload, payload.length);
                long tAppend = System.nanoTime();
                v.onScreenUpdated();
                long tUpd = System.nanoTime();
                TerminalRenderFrame rf = emu.getRenderFrame();
                long tProj = System.nanoTime();
                long dGpu = gpu.drawGpuSync(v, rf);
                long tEnd = System.nanoTime();
                gpu.drainReadback(null);
                long dSoft = gpu.drawSoft(v, rf);

                if (i >= 0) {
                    appendNs[i] = tAppend - tStart;
                    screenUpdNs[i] = tUpd - tAppend;
                    projNs[i] = tProj - tUpd;
                    softNs[i] = dSoft;
                    gpuNs[i] = dGpu;
                    e2eNs[i] = tEnd - tStart;
                    if (adapter != null) {
                        totalCaptures += adapter.captureCalls() - cap0;
                        totalJni += adapter.jniCalls() - jni0;
                    }
                }
            }
            JSONObject out = new JSONObject();
            out.put("engine", nativeEngine ? "libghostty" : "java");
            out.put("append", summarizeNanos(appendNs));
            out.put("screen_update", summarizeNanos(screenUpdNs));
            out.put("frame_projection", summarizeNanos(projNs));
            out.put("soft_draw", summarizeNanos(softNs));
            out.put("gpu_record_sync", summarizeNanos(gpuNs));
            out.put("e2e_frame", summarizeNanos(e2eNs));
            if (adapter != null) {
                out.put("captures_per_iter", totalCaptures / (double) iters);
                out.put("jni_calls_per_iter", totalJni / (double) iters);
            }
            out.put("cursor_row", emu.getCursorRow());
            out.put("cursor_col", emu.getCursorCol());
            return out;
        } finally {
            s.disposeEmulator();
        }
    }

    static JSONObject runWorkload2Tui2026(File outDir) throws Exception {
        int cols = 100, rows = 40;
        int frames = 60;
        List<byte[][]> framePackets = new ArrayList<>(frames);
        for (int f = 0; f < frames; f++) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("\033[?2026h\033[?25l\033[H");
            sb.append(String.format(Locale.ROOT,
                "\033[1;38;2;220;230;245;48;2;24;32;48m LUMA TUI BENCH frame=%03d | mode=2026 | 100x40 \033[K\033[0m\r\n", f));
            for (int r = 2; r <= 36; r++) {
                if (f == 0 || (r + f) % 5 == 0) {
                    sb.append(String.format(Locale.ROOT,
                        "\033[%d;1H\033[38;5;%dm  %02d │ fn render_row_%02d(tick=%03d) -> Result<Frame> { /* 中文状态检查 */ }\033[K",
                        r, 30 + ((r + f) % 180), r, r, f));
                }
            }
            sb.append("\033[38;1H\033[48;2;48;54;65m\033[2K")
              .append(String.format(Locale.ROOT, "\033[39;1H\033[38;2;240;244;248m> Ask Codex (step %03d): explain offscreen latency\033[K", f))
              .append("\033[40;1H\033[2K\033[0m\033[39;52H\033[?25h\033[?2026l");
            byte[] full = sb.toString().getBytes(StandardCharsets.UTF_8);
            int chunkSize = 512;
            int count = (full.length + chunkSize - 1) / chunkSize;
            byte[][] chunks = new byte[count][];
            for (int c = 0; c < count; c++) {
                chunks[c] = Arrays.copyOfRange(full, c * chunkSize, Math.min(full.length, (c + 1) * chunkSize));
            }
            framePackets.add(chunks);
        }

        JSONObject res = new JSONObject();
        FrameSnapshot jA = measureTuiFrames(false, cols, rows, framePackets, outDir, true);
        FrameSnapshot nA = measureTuiFrames(true, cols, rows, framePackets, outDir, true);
        FrameSnapshot jB = measureTuiFrames(false, cols, rows, framePackets, outDir, false);
        FrameSnapshot nB = measureTuiFrames(true, cols, rows, framePackets, outDir, false);
        assertFrameParity("W2_TUI_100x40", jA, nA);
        if (!nA.metrics.getBoolean("held_protected_mid_frame") || !nB.metrics.getBoolean("held_protected_mid_frame"))
            throw new AssertionError("W2 mode 2026 mid-frame hold was not active");
        res.put("java", jA.metrics);
        res.put("libghostty", nA.metrics);
        res.put("java_B", jB.metrics);
        res.put("libghostty_B", nB.metrics);
        res.put("parity_verified", "full_40x100_text_styles_cursor_soft_and_gpu_pixels_equal");
        return res;
    }

    static FrameSnapshot measureTuiFrames(boolean nativeEngine, int cols, int rows, List<byte[][]> frames, File outDir, boolean savePng) throws Exception {
        TerminalSession s = createSession(nativeEngine, 2000, cols, rows);
        TerminalView v = createSizedView(s, 1340, 1220, cols, rows);
        TerminalEmulator emu = s.getEmulator();
        GhosttyFrameAdapter adapter = nativeEngine ? ((GhosttyTerminalEmulator) emu).adapter() : null;

        try (GpuSurface gpu = new GpuSurface(1340, 1220)) {
            for (byte[] chunk : frames.get(0)) {
                emu.append(chunk, chunk.length);
                v.onScreenUpdated();
            }
            gpu.drawGpuSync(v, emu.getRenderFrame());
            gpu.drainReadback(null);

            int n = frames.size();
            long[] appendNs = new long[n];
            long[] screenUpdNs = new long[n];
            long[] projNs = new long[n];
            long[] softNs = new long[n];
            long[] gpuNs = new long[n];
            long[] e2eNs = new long[n];
            int totalCaptures = 0, totalJni = 0;
            boolean heldProtectedMidFrame = true;

            for (int f = 0; f < n; f++) {
                byte[][] chunks = frames.get(f);
                int cap0 = adapter != null ? adapter.captureCalls() : 0;
                int jni0 = adapter != null ? adapter.jniCalls() : 0;
                long appendSum = 0, updSum = 0;
                long tFrameStart = System.nanoTime();
                for (int c = 0; c < chunks.length; c++) {
                    byte[] chunk = chunks[c];
                    long t0 = System.nanoTime();
                    emu.append(chunk, chunk.length);
                    long t1 = System.nanoTime();
                    v.onScreenUpdated();
                    long t2 = System.nanoTime();
                    appendSum += (t1 - t0);
                    updSum += (t2 - t1);
                    if (nativeEngine && c == 0 && chunks.length > 1) {
                        if (adapter.queryMode(2026) != 1) heldProtectedMidFrame = false;
                    }
                }
                long tProj0 = System.nanoTime();
                TerminalRenderFrame rf = emu.getRenderFrame();
                long tProj1 = System.nanoTime();
                long dGpu = gpu.drawGpuSync(v, rf);
                long tFrameEnd = System.nanoTime();
                gpu.drainReadback(null);
                long dSoft = gpu.drawSoft(v, rf);

                appendNs[f] = appendSum;
                screenUpdNs[f] = updSum;
                projNs[f] = tProj1 - tProj0;
                softNs[f] = dSoft;
                gpuNs[f] = dGpu;
                e2eNs[f] = tFrameEnd - tFrameStart;
                if (adapter != null) {
                    totalCaptures += adapter.captureCalls() - cap0;
                    totalJni += adapter.jniCalls() - jni0;
                }
            }

            if (savePng) {
                File shot = new File(outDir, nativeEngine ? "bench-tui-libghostty.png" : "bench-tui-java.png");
                try (FileOutputStream fos = new FileOutputStream(shot)) {
                    gpu.softBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }

            JSONObject out = new JSONObject();
            out.put("engine", nativeEngine ? "libghostty" : "java");
            out.put("append", summarizeNanos(appendNs));
            out.put("screen_update", summarizeNanos(screenUpdNs));
            out.put("frame_projection", summarizeNanos(projNs));
            out.put("soft_draw", summarizeNanos(softNs));
            out.put("gpu_record_sync", summarizeNanos(gpuNs));
            out.put("e2e_frame", summarizeNanos(e2eNs));
            if (adapter != null) {
                out.put("captures_per_frame", totalCaptures / (double) n);
                out.put("jni_calls_per_frame", totalJni / (double) n);
                out.put("held_protected_mid_frame", heldProtectedMidFrame);
            }
            out.put("final_text_sample", emu.getSelectedText(0, 0, cols - 1, 2).trim());
            out.put("cursor_row", emu.getCursorRow());
            out.put("cursor_col", emu.getCursorCol());
            return new FrameSnapshot(out, v, emu, gpu, cols, rows);
        } finally {
            s.disposeEmulator();
        }
    }

    static JSONObject runWorkload3Codex7Byte(File outDir) throws Exception {
        int frames = 60;
        List<byte[][]> packets = new ArrayList<>(frames);
        for (int f = 0; f < frames; f++) {
            String msg = String.format(Locale.ROOT,
                "\033[%d;1H\033[38;2;180;220;255m●\033[0m token_%03d: 终端离屏渲染与增量状态分析 ( latency=%d )\033[K",
                2 + (f % 20), f, f * 3);
            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
            int step = 7;
            int count = (bytes.length + step - 1) / step;
            byte[][] chunks = new byte[count][];
            for (int i = 0; i < count; i++) {
                chunks[i] = Arrays.copyOfRange(bytes, i * step, Math.min(bytes.length, (i + 1) * step));
            }
            packets.add(chunks);
        }
        JSONObject res = new JSONObject();
        FrameSnapshot jBatchA = measureCodex7Byte(false, packets, false);
        FrameSnapshot nBatchA = measureCodex7Byte(true, packets, false);
        FrameSnapshot jBatchB = measureCodex7Byte(false, packets, false);
        FrameSnapshot nBatchB = measureCodex7Byte(true, packets, false);
        assertFrameParity("W3_Codex_15x7B_Batch", jBatchA, nBatchA);
        res.put("java", jBatchA.metrics);
        res.put("libghostty", nBatchA.metrics);
        res.put("java_B", jBatchB.metrics);
        res.put("libghostty_B", nBatchB.metrics);

        List<byte[][]> subPackets = packets.subList(0, 10);
        FrameSnapshot jPerA = measureCodex7Byte(false, subPackets, true);
        FrameSnapshot nPerA = measureCodex7Byte(true, subPackets, true);
        FrameSnapshot jPerB = measureCodex7Byte(false, subPackets, true);
        FrameSnapshot nPerB = measureCodex7Byte(true, subPackets, true);
        assertFrameParity("W3_Codex_Per7B_Draw", jPerA, nPerA);
        res.put("java_per_7b_chunk_draw", jPerA.metrics);
        res.put("libghostty_per_7b_chunk_draw", nPerA.metrics);
        res.put("java_per_7b_chunk_draw_B", jPerB.metrics);
        res.put("libghostty_per_7b_chunk_draw_B", nPerB.metrics);
        res.put("parity_verified", "full_24x80_text_styles_cursor_soft_and_gpu_pixels_equal");
        return res;
    }

    static FrameSnapshot measureCodex7Byte(boolean nativeEngine, List<byte[][]> packets, boolean drawEachChunk) throws Exception {
        TerminalSession s = createSession(nativeEngine, 2000, 80, 24);
        TerminalView v = createSizedView(s, 800, 600, 80, 24);
        TerminalEmulator emu = s.getEmulator();
        GhosttyFrameAdapter adapter = nativeEngine ? ((GhosttyTerminalEmulator) emu).adapter() : null;

        try (GpuSurface gpu = new GpuSurface(800, 600)) {
            int n = drawEachChunk ? packets.size() * packets.get(0).length : packets.size();
            List<Long> appendList = new ArrayList<>(n);
            List<Long> updList = new ArrayList<>(n);
            List<Long> projList = new ArrayList<>(n);
            List<Long> softList = new ArrayList<>(n);
            List<Long> gpuList = new ArrayList<>(n);
            List<Long> readList = new ArrayList<>(n);
            List<Long> e2eList = new ArrayList<>(n);
            byte[] pixelBuf = new byte[800 * 600 * 4];
            int totalCaptures = 0, totalJni = 0, totalChunks = 0;

            for (int f = 0; f < packets.size(); f++) {
                byte[][] chunks = packets.get(f);
                totalChunks += chunks.length;
                if (!drawEachChunk) {
                    int cap0 = adapter != null ? adapter.captureCalls() : 0;
                    int jni0 = adapter != null ? adapter.jniCalls() : 0;
                    long appendSum = 0, updSum = 0;
                    long tStart = System.nanoTime();
                    for (byte[] chunk : chunks) {
                        long t0 = System.nanoTime();
                        emu.append(chunk, chunk.length);
                        long t1 = System.nanoTime();
                        v.onScreenUpdated();
                        long t2 = System.nanoTime();
                        appendSum += (t1 - t0);
                        updSum += (t2 - t1);
                    }
                    long tProj0 = System.nanoTime();
                    TerminalRenderFrame rf = emu.getRenderFrame();
                    long tProj1 = System.nanoTime();
                    long dGpu = gpu.drawGpuSync(v, rf);
                    long tEnd = System.nanoTime();
                    long dRead = gpu.drainReadback(pixelBuf);
                    long dSoft = gpu.drawSoft(v, rf);

                    appendList.add(appendSum);
                    updList.add(updSum);
                    projList.add(tProj1 - tProj0);
                    softList.add(dSoft);
                    gpuList.add(dGpu);
                    readList.add(dRead);
                    e2eList.add(tEnd - tStart);
                    if (adapter != null) {
                        totalCaptures += adapter.captureCalls() - cap0;
                        totalJni += adapter.jniCalls() - jni0;
                    }
                } else {
                    for (byte[] chunk : chunks) {
                        int cap0 = adapter != null ? adapter.captureCalls() : 0;
                        int jni0 = adapter != null ? adapter.jniCalls() : 0;
                        long t0 = System.nanoTime();
                        emu.append(chunk, chunk.length);
                        long t1 = System.nanoTime();
                        v.onScreenUpdated();
                        long t2 = System.nanoTime();
                        TerminalRenderFrame rf = emu.getRenderFrame();
                        long t3 = System.nanoTime();
                        long dGpu = gpu.drawGpuSync(v, rf);
                        long t4 = System.nanoTime();
                        long dRead = gpu.drainReadback(null);
                        long dSoft = gpu.drawSoft(v, rf);

                        appendList.add(t1 - t0);
                        updList.add(t2 - t1);
                        projList.add(t3 - t2);
                        softList.add(dSoft);
                        gpuList.add(dGpu);
                        readList.add(dRead);
                        e2eList.add(t4 - t0);
                        if (adapter != null) {
                            totalCaptures += adapter.captureCalls() - cap0;
                            totalJni += adapter.jniCalls() - jni0;
                        }
                    }
                }
            }
            int sampleCount = e2eList.size();
            JSONObject out = new JSONObject();
            out.put("engine", nativeEngine ? "libghostty" : "java");
            out.put("chunks_per_frame", totalChunks / (double) packets.size());
            out.put("append_all_7b_chunks", summarizeNanos(toArray(appendList)));
            out.put("screen_update_all_7b_chunks", summarizeNanos(toArray(updList)));
            out.put("frame_projection", summarizeNanos(toArray(projList)));
            out.put("soft_draw", summarizeNanos(toArray(softList)));
            out.put("gpu_record_sync", summarizeNanos(toArray(gpuList)));
            out.put("gpu_readback_only", summarizeNanos(toArray(readList)));
            out.put("e2e_frame", summarizeNanos(toArray(e2eList)));
            if (adapter != null) {
                out.put("captures_per_frame", totalCaptures / (double) sampleCount);
                out.put("jni_calls_per_frame", totalJni / (double) sampleCount);
            }
            out.put("final_screen_text", emu.getScreen().getSelectedText(0, 0, 79, 23).trim());
            return new FrameSnapshot(out, v, emu, gpu, 80, 24);
        } finally {
            s.disposeEmulator();
        }
    }

    static long[] toArray(List<Long> list) {
        long[] arr = new long[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    static JSONObject runWorkload4History10k() throws Exception {
        StringBuilder sb = new StringBuilder(750000);
        for (int i = 0; i < 10024; i++) {
            sb.append(String.format(Locale.ROOT,
                "HIST-%05d repeated compressible terminal scrollback line中文 payload", i));
            if (i + 1 < 10024) sb.append("\r\n");
        }
        byte[] bulk = sb.toString().getBytes(StandardCharsets.UTF_8);

        List<String> javaCopies = new ArrayList<>();
        List<String> nativeHotCopies = new ArrayList<>();
        List<String> nativeColdCopies = new ArrayList<>();
        JSONObject res = new JSONObject();
        JSONObject jHist = measureHistory10k(false, bulk, javaCopies, null);
        JSONObject nHist = measureHistory10k(true, bulk, nativeHotCopies, nativeColdCopies);
        if (javaCopies.size() != nativeHotCopies.size() || !javaCopies.equals(nativeHotCopies))
            throw new AssertionError("W4 full 200-row hot history copy strings differ between Java and libghostty");
        for (int i = 0; i < nativeColdCopies.size(); i++) {
            int base = -9800 + i * 600;
            if (!nativeColdCopies.get(i).startsWith(String.format(Locale.ROOT, "HIST-%05d ", 10000 + base)))
                throw new AssertionError("W4 cold copy content mismatch at i=" + i);
        }
        res.put("java", jHist);
        res.put("libghostty", nHist);
        res.put("copy_parity_verified", "all_20_hot_200row_blocks_13599_chars_each_and_all_15_cold_blocks_equal");
        return res;
    }

    static long estimateJavaScreenBytes(TerminalBuffer buf) throws Exception {
        Field f = TerminalBuffer.class.getDeclaredField("mLines");
        f.setAccessible(true);
        TerminalRow[] lines = (TerminalRow[]) f.get(buf);
        Field styleF = TerminalRow.class.getDeclaredField("mStyle");
        styleF.setAccessible(true);
        long bytes = 24 + (long) lines.length * 8;
        for (TerminalRow row : lines) {
            if (row == null) continue;
            bytes += 64;
            if (row.mText != null) bytes += 16 + (long) row.mText.length * 2;
            long[] st = (long[]) styleF.get(row);
            if (st != null) bytes += 16 + (long) st.length * 8;
        }
        return bytes;
    }

    static JSONObject measureHistory10k(boolean nativeEngine, byte[] bulk, List<String> hotCopiesOut, List<String> coldCopiesOut) throws Exception {
        TerminalSession s = createSession(nativeEngine, 10000, 80, 24);
        TerminalView v = createSizedView(s, 800, 600, 80, 24);
        TerminalEmulator emu = s.getEmulator();
        GhosttyFrameAdapter adapter = nativeEngine ? ((GhosttyTerminalEmulator) emu).adapter() : null;

        try (GpuSurface gpu = new GpuSurface(800, 600)) {
            long tFeed0 = System.nanoTime();
            for (int off = 0; off < bulk.length; off += 65536) {
                int len = Math.min(65536, bulk.length - off);
                byte[] part = Arrays.copyOfRange(bulk, off, off + len);
                emu.append(part, part.length);
            }
            v.onScreenUpdated();
            long feedPopulateUs = (System.nanoTime() - tFeed0) / 1000L;

            JSONObject out = new JSONObject();
            out.put("engine", nativeEngine ? "libghostty" : "java");
            out.put("populate_10k_us", feedPopulateUs);
            out.put("java_buffer_array_bytes", estimateJavaScreenBytes(emu.getScreen()));

            if (nativeEngine) {
                long[] memHot = GhosttyNative.memoryUsage(adapter.handle());
                out.put("native_hot_resident_bytes", memHot[0]);
                out.put("native_hot_virtual_bytes", memHot[1]);
                out.put("native_pages", memHot[5]);
            }

            int scrollSteps = 40;
            long[] hotScrollE2eNs = new long[scrollSteps];
            long[] hotScrollProjNs = new long[scrollSteps];
            long[] hotScrollGpuNs = new long[scrollSteps];
            for (int i = 0; i < scrollSteps; i++) {
                int topRow = -9800 + i * 240;
                long t0 = System.nanoTime();
                if (nativeEngine) emu.setViewportTopRow(topRow);
                TerminalRenderFrame rf = emu.getRenderFrame();
                long t1 = System.nanoTime();
                if (!nativeEngine) {
                    RecordingCanvas c = gpu.node.beginRecording(800, 600);
                    c.drawColor(Color.BLACK);
                    v.mRenderer.render(rf, c, topRow, -1, -1, -1, -1);
                    gpu.node.endRecording();
                    gpu.renderer.createRenderRequest().setVsyncTime(System.nanoTime()).setWaitForPresent(true).syncAndDraw();
                } else {
                    gpu.drawGpuSync(v, rf);
                }
                long t2 = System.nanoTime();
                gpu.drainReadback(null);
                hotScrollProjNs[i] = t1 - t0;
                hotScrollGpuNs[i] = t2 - t1;
                hotScrollE2eNs[i] = t2 - t0;
            }
            if (nativeEngine) emu.scrollViewportBottom();
            out.put("hot_scroll_proj", summarizeNanos(hotScrollProjNs));
            out.put("hot_scroll_gpu", summarizeNanos(hotScrollGpuNs));
            out.put("hot_scroll_e2e", summarizeNanos(hotScrollE2eNs));

            long[] hotCopyNs = new long[20];
            String copiedSample = "";
            for (int i = 0; i < 20; i++) {
                int base = -9000 + i * 200;
                long t0 = System.nanoTime();
                copiedSample = emu.getSelectedText(0, base, 79, base + 199);
                hotCopyNs[i] = System.nanoTime() - t0;
                if (hotCopiesOut != null) hotCopiesOut.add(copiedSample);
            }
            out.put("hot_cross_page_copy_200rows", summarizeNanos(hotCopyNs));
            out.put("copied_sample_len", copiedSample.length());
            out.put("copied_first_line", copiedSample.substring(0, copiedSample.indexOf('\n')));

            if (nativeEngine) {
                List<String> expectedColdCopies = new ArrayList<>(15);
                for (int i = 0; i < 15; i++) {
                    int base = -9800 + i * 600;
                    expectedColdCopies.add(emu.getSelectedText(0, base, 79, base + 199));
                }
                emu.scrollViewportBottom();
                emu.getRenderFrame();
                int steps = 0;
                long compressWallNs = 0;
                while (steps < 64) {
                    long t0 = System.nanoTime();
                    int rc = GhosttyNative.compressStep(adapter.handle());
                    compressWallNs += System.nanoTime() - t0;
                    steps++;
                    if (rc != 1) break;
                }
                long[] memCold = GhosttyNative.memoryUsage(adapter.handle());
                out.put("native_cold_resident_bytes", memCold[0]);
                out.put("native_cold_virtual_bytes", memCold[1]);
                out.put("native_cold_compressed_pages", memCold[2]);
                out.put("native_cold_compressed_bytes", memCold[3]);
                out.put("native_compress_steps", steps);
                out.put("native_compress_wall_us", Math.round(compressWallNs / 10.0) / 100.0);

                int coldSteps = 17;
                long[] coldScrollProjNs = new long[coldSteps];
                long[] coldScrollGpuNs = new long[coldSteps];
                long[] coldScrollE2eNs = new long[coldSteps];
                for (int i = 0; i < coldSteps; i++) {
                    int topRow = -9900 + i * 580;
                    long t0 = System.nanoTime();
                    emu.setViewportTopRow(topRow);
                    TerminalRenderFrame rf = emu.getRenderFrame();
                    long t1 = System.nanoTime();
                    gpu.drawGpuSync(v, rf);
                    long t2 = System.nanoTime();
                    gpu.drainReadback(null);
                    coldScrollProjNs[i] = t1 - t0;
                    coldScrollGpuNs[i] = t2 - t1;
                    coldScrollE2eNs[i] = t2 - t0;
                }
                out.put("cold_scroll_proj", summarizeNanos(coldScrollProjNs));
                out.put("cold_scroll_gpu", summarizeNanos(coldScrollGpuNs));
                out.put("cold_scroll_e2e", summarizeNanos(coldScrollE2eNs));

                emu.scrollViewportBottom();
                emu.getRenderFrame();
                while (GhosttyNative.compressStep(adapter.handle()) == 1) {}
                long[] coldCopyNs = new long[15];
                for (int i = 0; i < 15; i++) {
                    int base = -9800 + i * 600;
                    long t0 = System.nanoTime();
                    String sCold = emu.getSelectedText(0, base, 79, base + 199);
                    coldCopyNs[i] = System.nanoTime() - t0;
                    if (!sCold.equals(expectedColdCopies.get(i)))
                        throw new AssertionError("Cold copy content mismatch at i=" + i + " base=" + base);
                    if (coldCopiesOut != null) coldCopiesOut.add(sCold);
                }
                out.put("cold_cross_page_copy_200rows", summarizeNanos(coldCopyNs));
            }
            return out;
        } finally {
            s.disposeEmulator();
        }
    }
}
