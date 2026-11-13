package com.termux.view;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.LongSparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.termux.terminal.*;
import com.termux.view.textselection.TextSelectionCursorController;
import com.termux.view.textselection.TextSelectionHandleView;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ViewViewportCheck {
    static int checks;
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        checks++; System.out.println("PASS " + msg);
    }
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String show(String s) { return s == null ? "<null>" : s.replace("\n", "<NL>"); }

    static final class Provider implements TerminalSession.PtyProvider {
        public void start(TerminalSession s, int c, int r, int cw, int ch) {}
        public void resize(int c, int r, int cw, int ch) {}
        public void closeWindow() {}
        public void detach() {}
    }
    static int sessionUpdates;
    static TerminalSessionClient sessionClient() {
        return (TerminalSessionClient) Proxy.newProxyInstance(ViewViewportCheck.class.getClassLoader(),
            new Class[]{TerminalSessionClient.class}, (p, m, v) -> {
                if (m.getName().equals("getTerminalCursorStyle")) return 0;
                if (m.getName().equals("onTextChanged")) {
                    sessionUpdates++;
                    ((TerminalSession) v[0]).getEmulator().getScreen();
                }
                return null;
            });
    }
    static TerminalSession session(boolean nativeMode) {
        TerminalSession s = new TerminalSession(new Provider(), 1000, sessionClient(),
            GhosttyTerminalEmulator::new);
        s.initializeEmulator(80, 24, 12, 24);
        return s;
    }
    static TerminalViewClient viewClient() {
        return (TerminalViewClient) Proxy.newProxyInstance(ViewViewportCheck.class.getClassLoader(),
            new Class[]{TerminalViewClient.class}, (p, m, v) -> {
                Class<?> rt = m.getReturnType();
                if (rt == float.class) return 1f;
                if (rt == int.class) return 0;
                if (rt == boolean.class) return false;
                return null;
            });
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    static Context headlessContext(String resourceApk) throws Exception {
        final AssetManager resourcesAssets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(resourcesAssets, resourceApk);
        java.lang.reflect.Constructor<Resources> rc = Resources.class.getDeclaredConstructor(
            AssetManager.class, android.util.DisplayMetrics.class, android.content.res.Configuration.class);
        rc.setAccessible(true);
        final Resources appResources = rc.newInstance(resourcesAssets,
            Resources.getSystem().getDisplayMetrics(), new android.content.res.Configuration());
        Context context = new ContextWrapper(null) {
            @Override public int getDeviceId() { return 0; }
            @Override public boolean isUiContext() { return false; }
            @Override public Resources getResources() { return appResources; }
            @Override public AssetManager getAssets() { return resourcesAssets; }
            @Override public android.content.res.Resources.Theme getTheme() { return appResources.newTheme(); }
            @Override public String getPackageName() { return "io.github.modao7067.luma.view.checks"; }
            @Override public int checkCallingOrSelfPermission(String permission) {
                return android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            @Override public int checkPermission(String permission, int pid, int uid) {
                return android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            @Override public Looper getMainLooper() { return Looper.getMainLooper(); }
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) { return null; }
            @Override public String getSystemServiceName(Class<?> serviceClass) { return null; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(); info.targetSdkVersion = 35; return info;
            }
        };
        Method keyMethod = ViewConfiguration.class.getDeclaredMethod("createKey", Context.class);
        keyMethod.setAccessible(true);
        Field configurations = ViewConfiguration.class.getDeclaredField("sConfigurations");
        configurations.setAccessible(true);
        ViewConfiguration defaults = new ViewConfiguration();
        Field withContext = ViewConfiguration.class.getDeclaredField("mConstructedWithContext");
        withContext.setAccessible(true); withContext.setBoolean(defaults, true);
        ((LongSparseArray<ViewConfiguration>) configurations.get(null)).put(
            ((Number) keyMethod.invoke(null, context)).longValue(), defaults);
        return context;
    }

    static final int VW = 800, VH = 600;
    static Context context;

    static TerminalView makeView(TerminalSession s) {
        TerminalView v = new TerminalView(context, null);
        v.setFocusable(true); v.setFocusableInTouchMode(true);
        v.setTerminalViewClient(viewClient());
        v.setTextSize(22);
        v.setTypeface(BundledTerminalFonts.typeface());
        v.measure(View.MeasureSpec.makeMeasureSpec(VW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VH, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, VW, VH);
        check(v.attachSession(s), "real TerminalView attaches session");
        return v;
    }

    static byte[] draw(TerminalView v) {
        Bitmap bm = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(Color.BLACK);
        v.draw(c);
        ByteBuffer px = ByteBuffer.allocate(VW * VH * 4);
        bm.copyPixelsToBuffer(px); bm.recycle();
        return px.array();
    }
    static byte[] hardware(android.view.View view) {
        try (ImageReader reader = ImageReader.newInstance(VW, VH, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN)) {
            HardwareRenderer renderer = new HardwareRenderer();
            RenderNode node = new RenderNode("native terminal view");
            try {
                node.setPosition(0, 0, VW, VH);
                renderer.setSurface(reader.getSurface()); renderer.setOpaque(true); renderer.setContentRoot(node);
                android.graphics.RecordingCanvas canvas = node.beginRecording(VW, VH);
                canvas.drawColor(Color.BLACK); view.draw(canvas); node.endRecording();
                renderer.createRenderRequest().setVsyncTime(System.nanoTime()).setWaitForPresent(true).syncAndDraw();
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) throw new AssertionError("View GPU frame missing");
                    Image.Plane plane = image.getPlanes()[0]; ByteBuffer data = plane.getBuffer();
                    byte[] result = new byte[VW * VH * 4];
                    for (int y = 0; y < VH; y++) {
                        data.position(y * plane.getRowStride()); data.get(result, y * VW * 4, VW * 4);
                    }
                    return result;
                }
            } finally { renderer.destroy(); node.discardDisplayList(); }
        }
    }

    static int nonBlack(byte[] p) {
        int n = 0;
        for (int i = 0; i < p.length; i += 4)
            if ((p[i] & 0xff) + (p[i + 1] & 0xff) + (p[i + 2] & 0xff) > 24) n++;
        return n;
    }
    static void feed(TerminalSession s, String text) {
        byte[] d = b(text);
        s.getEmulator().append(d, d.length);
    }
    static void lateQueue(TerminalSession s, String text) throws Exception {
        Field qf = TerminalSession.class.getDeclaredField("mProcessToTerminalIOQueue");
        qf.setAccessible(true);
        Object queue = qf.get(s);
        Method write = queue.getClass().getMethod("write", byte[].class, int.class, int.class);
        byte[] d = b(text); write.invoke(queue, d, 0, d.length);
        Field hf = TerminalSession.class.getDeclaredField("mMainThreadHandler");
        hf.setAccessible(true);
        android.os.Handler h = (android.os.Handler) hf.get(s);
        h.handleMessage(android.os.Message.obtain(h, 1));
    }

    static TextSelectionCursorController controller(TerminalView v) {
        return v.getTextSelectionCursorController();
    }
    static TextSelectionHandleView handle(TextSelectionCursorController c, boolean start) throws Exception {
        Field f = TextSelectionCursorController.class.getDeclaredField(start ? "mStartHandle" : "mEndHandle");
        f.setAccessible(true);
        return (TextSelectionHandleView) f.get(c);
    }
    static void select(TextSelectionCursorController c, int row, int col, boolean start) throws Exception {
        check(c != null, "selection controller available");
        TextSelectionHandleView h = handle(c, start);
        int x = Math.round(col * c.terminalViewWidth());
        int y = Math.round((row - c.terminalViewTopRow()) * c.terminalViewLineSpacing() + 40);
        c.updatePosition(h, x, y);
    }
    static MotionEvent event(int row, int col, long t) {
        return MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_DOWN,
            (col + 0.5f) * 12, (row + 0.5f) * 24, 0);
    }

    static void setShellPid(TerminalSession s) throws Exception {
        Field f = TerminalSession.class.getDeclaredField("mShellPid");
        f.setAccessible(true); f.setInt(s, 1);
    }
    static String clickReplies(TerminalSession s) throws Exception {
        Field f = TerminalSession.class.getDeclaredField("mTerminalToProcessIOQueue");
        f.setAccessible(true);
        Object queue = f.get(s);
        Method read = queue.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        read.setAccessible(true);
        byte[] buffer = new byte[4096];
        int n = (Integer) read.invoke(queue, buffer, false);
        return n > 0 ? new String(buffer, 0, n, StandardCharsets.UTF_8) : "";
    }

    public static void main(String[] args) throws Exception {
        String lib = args[0], fonts = args[1], dir = args[2], resourceApk = args[3];
        System.setOut(new PrintStream(new FileOutputStream(dir + "/view-viewport-check.txt")));
        try {
            Looper.prepareMainLooper();
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, fonts);
            BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(lib);
            context = headlessContext(resourceApk);

            TerminalSession ns = session(true);
            TerminalView nv = makeView(ns);
            String text = "ASCII 中文 ABC\r\nsecond line\r\n";
            feed(ns, text);
            ns.getEmulator().setCursorBlinkingEnabled(false);
            nv.onScreenUpdated();
            check(ns.getEmulator().usesNativeViewport(), "real session uses native viewport");
            check(nv.getTopRow() == 0 && ns.getEmulator().getViewportTopRow() == 0, "first frame is at bottom");
            byte[] np = draw(nv), jp = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(dir,"oracle-ascii-soft.rgba"));
            check(nonBlack(np) > 1000, "real View onDraw paints text (" + nonBlack(np) + "px)");
            check(java.util.Arrays.equals(np, jp), "real View native/frozen independent Java software pixels equal (ASCII/CJK)");
            byte[] nativeGpu = hardware(nv), javaGpu = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(dir,"oracle-ascii-gpu.rgba"));
            check(nonBlack(nativeGpu) > 1000 && java.util.Arrays.equals(nativeGpu, javaGpu),
                "real View native/frozen independent Java hardware RenderNode pixels equal (ASCII/CJK)");
            Bitmap screenshot = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
            Canvas screenshotCanvas = new Canvas(screenshot); screenshotCanvas.drawColor(Color.BLACK); nv.draw(screenshotCanvas);
            try (FileOutputStream output = new FileOutputStream(dir + "/view-native.png")) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            screenshot.recycle();
            String visible = ns.getEmulator().getViewportText(nv.getTopRow());
            check(visible.contains("ASCII 中文 ABC") && visible.contains("second line"), "visible viewport text from native");

            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < 200; i++) lines.append(String.format("LINE%03d\r\n", i));
            feed(ns, lines.toString()); nv.onScreenUpdated();
            int history = ns.getEmulator().getViewportHistoryRows();
            check(history > 100, "native history rows available (" + history + ")");
            check(nv.computeVerticalScrollRange() == ns.getEmulator().getViewportTotalRows(), "scrollbar range comes from native total rows");
            MotionEvent scroll = MotionEvent.obtain(0, 1, MotionEvent.ACTION_SCROLL, 10, 10, 0);
            nv.doScroll(scroll, -10);
            check(nv.getTopRow() < 0 && ns.getEmulator().getViewportTopRow() == nv.getTopRow(), "doScroll moves native viewport and mirrors top row");
            String scrolled = ns.getEmulator().getViewportText(nv.getTopRow());
            check(scrolled.contains("LINE19") || scrolled.contains("LINE20"), "scrolled viewport text is earlier history: " + show(scrolled.split("\n")[0]));
            byte[] sp = draw(nv);
            check(!java.util.Arrays.equals(np, sp), "scrolled real View pixels differ from bottom");
            check(nonBlack(hardware(nv)) > 1000, "scrolled history draws through the real View hardware path");
            String anchorTop = scrolled.split("\n")[0];
            feed(ns, "NEWTAIL\r\n"); nv.onScreenUpdated();
            String anchored = ns.getEmulator().getViewportText(nv.getTopRow());
            check(anchored.contains(anchorTop), "feed while scrolled keeps native content anchor: " + show(anchorTop));
            nv.scrollToBottom(); nv.onScreenUpdated();
            check(nv.getTopRow() == 0 && ns.getEmulator().getViewportTopRow() == 0, "scrollToBottom returns to native bottom");

            feed(ns, "\033[?1049h"); nv.onScreenUpdated();
            check(ns.getEmulator().getViewportHistoryRows() == 0 && ns.getEmulator().isAlternateBufferActive(), "alt screen has zero history");
            nv.doScroll(scroll, -10);
            check(nv.getTopRow() == 0, "alt screen does not scroll history");
            feed(ns, "\033[?1049l"); nv.onScreenUpdated();
            check(ns.getEmulator().getViewportHistoryRows() > 0, "leaving alt screen restores history");

            TerminalSession w = session(true); TerminalView wv = makeView(w);
            int wcols = w.getEmulator().mColumns;
            String wrapText = "abcdefghij".repeat(wcols / 10 + 2).substring(0, wcols + 5);
            feed(w, wrapText); wv.onScreenUpdated();
            check(wrapText.equals(w.getEmulator().getSelectedText(0, 0, wcols - 1, 1)),
                "soft-wrapped selection unwraps without inserted newline (" + wcols + " cols)");
            String uuid = "12345678-1234-5678-9abc-123456789abc";
            String base = "https://example.invalid/";
            int padding = Math.floorMod(-base.length() - "?state=".length() - 18, wcols);
            String prefix = base + "p".repeat(padding) + "?state=";
            String url = prefix + uuid;
            feed(w, "\033[2J\033[H" + url); wv.onScreenUpdated();
            int endRow = (url.length() - 1) / wcols, endCol = (url.length() - 1) % wcols;
            check(url.equals(w.getEmulator().getSelectedText(0, 0, endCol, endRow)),
                "synthetic state UUID soft wrap exports byte-for-byte without whitespace");
            check(url.equals(TerminalTextCopy.joinLines(w.getEmulator().getSelectedText(0, 0, endCol, endRow))),
                "joined copy does not alter native-unwrapped synthetic URL");
            feed(w, "\033[2J\033[H" + url.substring(0, wcols) + "\r\n" + url.substring(wcols)); wv.onScreenUpdated();
            String hardUrl = w.getEmulator().getSelectedText(0, 0, endCol, endRow);
            check((url.substring(0, wcols) + "\n" + url.substring(wcols)).equals(hardUrl),
                "ordinary copy preserves the synthetic TUI hard-wrap newline");
            check(url.equals(TerminalTextCopy.joinLines(hardUrl)),
                "joined copy removes the TUI hard break without inserting a space inside state");
            feed(w, "\033[2J\033[H" + url); wv.onScreenUpdated();
            int stateRow = prefix.length() / wcols, stateCol = prefix.length() % wcols;
            check(uuid.equals(w.getEmulator().getSelectedText(stateCol, stateRow, endCol, endRow)),
                "partial selection across soft wrap keeps exactly the synthetic UUID");
            check(uuid.equals(w.getEmulator().getSelectedText(endCol, endRow, stateCol, stateRow)),
                "reverse selection preserves the same wrapped UUID endpoints");
            String spaced = "x".repeat(wcols - 1) + "  tail";
            feed(w, "\033[2J\033[H" + spaced); wv.onScreenUpdated();
            check(spaced.equals(w.getEmulator().getSelectedText(0, 0, 5, 1)),
                "real spaces on both sides of a soft-wrap boundary survive");
            feed(w, "\033[2J\033[H" + "a  b\r\n  c d"); wv.onScreenUpdated();
            check("a  b\n  c d".equals(w.getEmulator().getSelectedText(0, 0, 4, 1)),
                "hard newline, leading spaces and internal spaces survive native export");
            TerminalSession blank = session(true); TerminalView bv = makeView(blank);
            feed(blank, "A\r\n\r\n"); bv.onScreenUpdated();
            check("A\n\n".equals(blank.getEmulator().getSelectedText(0, 0, 7, 2)), "explicit trailing blank rows keep A<NL><NL>");
            String wide = "中A";
            feed(blank, "\033[2J\033[H" + wide); bv.onScreenUpdated();
            String tail = blank.getEmulator().getSelectedText(1, 0, 1, 0);
            check("中".equals(tail), "wide tail selection copies the complete wide cell: " + show(tail));

            TerminalSession sel = session(true); TerminalView sv = makeView(sel);
            StringBuilder sl = new StringBuilder();
            for (int i = 0; i < 80; i++) sl.append("LINE" + String.format("%03d", i) + " hello world\r\n");
            feed(sel, sl.toString()); sv.onScreenUpdated();
            TextSelectionCursorController sc = controller(sv);
            float wordX = 9.5f * sv.mRenderer.getFontWidth();
            float wordY = sv.mRenderer.mFontLineSpacingAndAscent + 2;
            MotionEvent me = MotionEvent.obtain(0, 20, MotionEvent.ACTION_DOWN, wordX, wordY, 0);
            sc.show(me);
            check(sc.isActive(), "real selection controller active");
            String word = sc.getSelectedText();
            check("hello".equals(word), "real controller word expansion selects hello: " + show(word));
            select(sc, sv.getTopRow() + 5, 9, false);
            select(sc, sv.getTopRow() - 10, 0, true);
            String cross = sc.getSelectedText();
            check(cross.contains("LINE") && cross.contains("hello"), "cross-page selection copies native text: " + show(cross.substring(0, Math.min(40, cross.length()))));
            int[] selectors = new int[4]; sc.getSelectors(selectors);
            TerminalRenderFrame actualFrame = sel.getEmulator().getRenderFrame();
            check(actualFrame.sourceTopRow < 0, "selection is tested in negative history rows");
            TerminalRenderFrame expectedFrame = new TerminalRenderFrame(actualFrame.screen, actualFrame.rows,
                actualFrame.columns, actualFrame.cursorRow, actualFrame.cursorColumn, actualFrame.cursorVisible,
                actualFrame.cursorStyle, actualFrame.reverseVideo, actualFrame.palette);
            Bitmap expectedSelection = Bitmap.createBitmap(VW, VH, Bitmap.Config.ARGB_8888);
            Canvas expectedCanvas = new Canvas(expectedSelection); expectedCanvas.drawColor(Color.BLACK);
            sv.mRenderer.render(expectedFrame, expectedCanvas, 0,
                selectors[0] - actualFrame.sourceTopRow, selectors[1] - actualFrame.sourceTopRow,
                selectors[2], selectors[3]);
            ByteBuffer expectedPixels = ByteBuffer.allocate(VW * VH * 4);
            expectedSelection.copyPixelsToBuffer(expectedPixels); expectedSelection.recycle();
            check(java.util.Arrays.equals(draw(sv), expectedPixels.array()),
                "real View history selection pixels map active rows to viewport exactly once");
            int topBefore = sv.getTopRow();
            check(cross.contains("hello") && sv.getTopRow() == topBefore, "copy does not move the viewport");

            feed(sel,"new output\r\n".repeat(20));sv.onScreenUpdated();
            check(cross.equals(sc.getSelectedText()),"active cross-page selection tracks its native content through later output");
            feed(sel,"prune history\r\n".repeat(1400));sv.onScreenUpdated();
            check(sc.getSelectedText().isEmpty()&&!sc.isActive(),"pruned native selection is invalidated instead of copying unrelated replacement text");

            TerminalSession link = session(true); TerminalView lv = makeView(link);
            String linkLine = "\033]8;;file:///tmp/p9.png\033\\LINK\033]8;;\033\\ plain \033]8;;file:///tmp/h.png\033\\\033[8mHIDE\033[28m\033]8;;\033\\";
            feed(link, linkLine + "\r\n"); lv.onScreenUpdated();
            String uri = link.getEmulator().getViewportLink(1, 0);
            check("file:///tmp/p9.png".equals(uri), "OSC8 URI from native cell: " + show(uri));
            check(link.getEmulator().getViewportLink(7, 0) == null, "ordinary visible text is not a link");
            check(link.getEmulator().getViewportLink(15, 0) == null, "invisible text with OSC8 is not clickable");
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 80; i++) many.append("filler" + i + "\r\n");
            feed(link, many.toString()); lv.onScreenUpdated();
            int lh = link.getEmulator().getViewportHistoryRows();
            lv.setTopRow(-lh);
            int linkRow = lv.getTopRow();
            String histUri = link.getEmulator().getViewportLink(1, linkRow);
            check("file:///tmp/p9.png".equals(histUri), "history OSC8 link maps after scrolling");
            int lbefore = link.getEmulator().getViewportTopRow();
            check(link.getEmulator().getViewportLink(1, linkRow) != null && link.getEmulator().getViewportTopRow() == lbefore,
                "history link lookup does not move viewport");

            int linkTop = lv.getTopRow();
            float tapX = 1.5f * lv.mRenderer.getFontWidth();
            float tapY = lv.mRenderer.mFontLineSpacingAndAscent + 2;
            MotionEvent tap = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, tapX, tapY, 0);
            check("file:///tmp/p9.png".equals(lv.getLinkAt(tap)), "real TerminalView getLinkAt returns the native OSC8 URI");
            tap.recycle();
            Method getText = TerminalView.class.getDeclaredMethod("getText");
            getText.setAccessible(true);
            String a11y = String.valueOf(getText.invoke(lv));
            check(a11y.contains("LINK") && a11y.contains("filler") && !a11y.contains("filler79"),
                "accessibility text reads the visible viewport only");

            TerminalSession upd = session(true); TerminalView uv = makeView(upd);
            feed(upd, "one\r\ntwo\r\nthree"); uv.onScreenUpdated();
            byte[] beforePixels = draw(uv);
            feed(upd, "\033[2;1HX"); uv.onScreenUpdated();
            byte[] afterPixels = draw(uv);
            int minY = VH, maxY = -1, diff = 0;
            for (int y = 0; y < VH; y++) for (int x = 0; x < VW; x++) {
                int i = (y * VW + x) * 4;
                if (beforePixels[i] != afterPixels[i] || beforePixels[i+1] != afterPixels[i+1]
                        || beforePixels[i+2] != afterPixels[i+2]) {
                    diff++; minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            check(diff > 0 && maxY < 6 * uv.mRenderer.getFontLineSpacing(),
                "single line edit redraws only its row band (diff rows " + minY + ".." + maxY + ")");

            TerminalSession ev = session(true); TerminalView evv = makeView(ev);
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 1200; i++) huge.append(String.format("EVICT%04d\r\n", i));
            feed(ev, huge.toString()); evv.onScreenUpdated();
            int eh = ev.getEmulator().getViewportHistoryRows();
            check(eh > 0 && eh < 1200
                    && ev.getEmulator().getViewportTotalRows() == eh + ev.getEmulator().mRows,
                "large history stays within its bounded native budget (" + eh + " of 1200)");
            evv.setTopRow(-eh - 500); evv.onScreenUpdated();
            check(evv.getTopRow() >= -ev.getEmulator().getViewportHistoryRows(), "viewport clamps to real native history after eviction");
            check(!ev.getEmulator().getViewportText(evv.getTopRow()).isEmpty(), "top history viewport remains readable after eviction");

            sessionUpdates = 0;
            TerminalSession life = session(true);
            feed(life, "final screen"); life.getEmulator().getRenderFrame();
            int exitUpdates = sessionUpdates;
            life.externalProcessExited(0);
            check(!life.isEmulatorDisposed(), "normal externalProcessExited does not dispose");
            check(life.getEmulator().getRenderFrame() != null, "normal exit keeps readable final frame");
            byte[] lp = draw(makeView(life));
            check(nonBlack(lp) > 100, "normal-exit final frame still draws");
            life.disposeEmulator();
            check(life.isEmulatorDisposed(), "explicit disposeEmulator releases native");
            check(sessionUpdates == exitUpdates, "dispose does not emit a screen notification");
            lateQueue(life, "late");
            check(sessionUpdates == exitUpdates, "late queued input after dispose does not notify/read screen");
            boolean getterSafe;
            try { life.getEmulator().isCursorEnabled(); getterSafe = true; } catch (NullPointerException e) { getterSafe = false; }
            check(getterSafe, "disposed mode getter is NPE-free");
            try { life.getEmulator().getRenderFrame(); check(false, "disposed frame must be explicit"); }
            catch (IllegalStateException expected) { check(true, "disposed frame getter fails explicitly, not NPE"); }

            TerminalSession wt = session(true); TerminalView wtv = makeView(wt);
            feed(wt, "alive"); wtv.onScreenUpdated();
            final Throwable[] disposeError = new Throwable[1];
            Thread t = new Thread(() -> { try { wt.disposeEmulator(); } catch (Throwable e) { disposeError[0] = e; } });
            t.start(); t.join();
            check(disposeError[0] instanceof IllegalStateException && !wt.isEmulatorDisposed(),
                "wrong-thread session dispose rejected without mutation");
            feed(wt, " still"); check(wt.getEmulator().getRenderFrame() != null, "owner can still use session after rejected dispose");

            TerminalView dv = makeView(wt);
            dv.onDetachedFromWindow();
            check(!wt.isEmulatorDisposed(), "View detach does not dispose session");
            wt.disposeEmulator();
            check(!dv.attachSession(wt), "View refuses to bind a disposed session");

            TerminalSession rz = session(true); TerminalView rv = makeView(rz);
            feed(rz, lines.toString()); rv.onScreenUpdated();
            rv.setTopRow(-5); int beforeTop = rv.getTopRow();
            rv.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            rv.layout(0, 0, 640, 500); rv.updateSize(); rv.onScreenUpdated();
            check(rz.getEmulator().mColumns < 80, "resize reprojects to new columns");
            check(rv.getTopRow() == Math.max(-rz.getEmulator().getViewportHistoryRows(),
                Math.min(0, beforeTop)) || rv.getTopRow() <= 0, "resize clamps instead of jumping to newest");

            TerminalSession clk = session(true); setShellPid(clk); TerminalView clkv = makeView(clk);
            float fw = clkv.mRenderer.getFontWidth(), ls = clkv.mRenderer.mFontLineSpacing;
            float asc = clkv.mRenderer.mFontLineSpacingAndAscent;
            int cellW = clk.getEmulator().getCellWidthPixels(), cellH = clk.getEmulator().getCellHeightPixels();
            check(cellW > 0 && cellH > 0, "native cell pixel geometry is valid (" + cellW + "x" + cellH + ")");
            feed(clk, "\033[?1000;1006;1016h"); clickReplies(clk);
            float px = 3.25f * fw, py = asc + 2.5f * ls;
            int cellCol = (int) (px / fw) + 1, cellRow = (int) ((py - asc) / ls) + 1;
            int pixX = (int) Math.floor(px / fw * cellW) + 1, pixY = (int) Math.floor((py - asc) / ls * cellH) + 1;
            check(pixX != cellCol || pixY != cellRow, "SGR-Pixels coordinates differ from character cells");
            MotionEvent ce = MotionEvent.obtain(0, 20, MotionEvent.ACTION_DOWN, px, py, 0);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
            String pixelReport = clickReplies(clk);
            check(pixelReport.equals("\033[<0;" + pixX + ";" + pixY + "M\033[<0;" + pixX + ";" + pixY + "m"),
                "1016 press/release use one-based terminal pixels: " + show(pixelReport));
            feed(clk, "\033[?1016l"); clickReplies(clk);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
            String cellReport = clickReplies(clk);
            check(cellReport.equals("\033[<0;" + cellCol + ";" + cellRow + "M\033[<0;" + cellCol + ";" + cellRow + "m"),
                "1006 alone keeps character-cell SGR reports: " + show(cellReport));
            feed(clk, "\033[?1006l"); clickReplies(clk);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
            clkv.sendMouseEventCode(ce, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
            String x10Report = clickReplies(clk);
            byte[] expectedX10 = {27, '[', 'M', (byte) (32 + TerminalEmulator.MOUSE_LEFT_BUTTON),
                (byte) (32 + cellCol), (byte) (32 + cellRow),
                27, '[', 'M', (byte) (32 + 3), (byte) (32 + cellCol), (byte) (32 + cellRow)};
            check(x10Report.equals(new String(expectedX10, StandardCharsets.ISO_8859_1)),
                "legacy X10 reports remain unchanged: " + show(x10Report));
            feed(clk, "\033[?1006;1016h"); clickReplies(clk);
            float gx = 2.5f * fw, gy = asc + 1.5f * ls;
            MotionEvent gDown = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, gx, gy, 0);
            clkv.onTouchEvent(gDown);
            MotionEvent gMove = MotionEvent.obtain(1000, 1100, MotionEvent.ACTION_MOVE, gx, gy + 4 * ls, 0);
            clkv.onTouchEvent(gMove);
            MotionEvent gCancel = MotionEvent.obtain(1000, 1150, MotionEvent.ACTION_CANCEL, gx, gy + 4 * ls, 0);
            clkv.onTouchEvent(gCancel);
            clickReplies(clk);
            MotionEvent cDown = MotionEvent.obtain(5000, 5000, MotionEvent.ACTION_DOWN, gx, gy, 0);
            clkv.onTouchEvent(cDown);
            MotionEvent cUp = MotionEvent.obtain(5000, 5020, MotionEvent.ACTION_UP, gx, gy, 0);
            clkv.onTouchEvent(cUp);
            int gPixX = (int) Math.floor(gx / fw * cellW) + 1, gPixY = (int) Math.floor((gy - asc) / ls * cellH) + 1;
            String afterCancel = clickReplies(clk);
            check(afterCancel.equals("\033[<0;" + gPixX + ";" + gPixY + "M\033[<0;" + gPixX + ";" + gPixY + "m"),
                "click after a CANCEL-scrolled gesture is not swallowed: " + show(afterCancel));
            feed(clk, "\033[?1016l\033[?1016s");
            int[] off = clk.getEmulator().exportModeState();
            check((off[3] & 16384) == 0 && (off[4] & 16384) == 0 && off.length == 80,
                "checkpoint saves a disabled 1016 current mode without inventing a saved bit");
            feed(clk, "\033[?1016h\033[?1016s");
            int[] on = clk.getEmulator().exportModeState();
            check((on[3] & 16384) != 0 && (on[4] & 16384) != 0, "checkpoint carries 1016 current and saved bits");
            feed(clk, "\033[?1016l");
            int[] currentOff = clk.getEmulator().exportModeState();
            check((currentOff[3] & 16384) == 0 && (currentOff[4] & 16384) != 0,
                "disabling 1016 clears current without rewriting the saved value");
            feed(clk, "\033[?1016r");
            check((clk.getEmulator().exportModeState()[3] & 16384) != 0, "DECRST restore returns the saved 1016 mode");

            System.out.println("VIEW VIEWPORT OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace(System.out); System.out.flush(); System.exit(1);
        }
    }
}
