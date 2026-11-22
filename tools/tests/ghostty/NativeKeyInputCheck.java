package com.termux.view;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRenderFrame;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;

public final class NativeKeyInputCheck {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    static final class Provider implements TerminalSession.PtyProvider {
        public void start(TerminalSession s,int columns,int rows,int cw,int ch){}
        public void resize(int columns,int rows,int cw,int ch){}
        public void closeWindow(){}
        public void detach(){}
    }
    static TerminalSessionClient sessionClient() {
        return (TerminalSessionClient) Proxy.newProxyInstance(NativeKeyInputCheck.class.getClassLoader(),
            new Class[]{TerminalSessionClient.class}, (p,m,a) -> m.getName().equals("getTerminalCursorStyle") ? 0 : null);
    }
    static final class Capture extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public synchronized void write(int value) { bytes.write(value); }
        @Override public synchronized void write(byte[] data,int offset,int count) { bytes.write(data, offset, count); }
        synchronized String take() { String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8); bytes.reset(); return text; }
    }
    static final class BlockingInput extends InputStream {
        volatile boolean open = true;
        @Override public int read() throws IOException {
            while (open) { try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            return -1;
        }
    }
    static final class NullOutput extends TerminalOutput {
        public void write(byte[] data,int offset,int count) {}
        public void titleChanged(String oldTitle,String newTitle) {}
        public void onCopyTextToClipboard(String text) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
    }
    static final class FailingEmulator extends TerminalEmulator {
        FailingEmulator() { super(new NullOutput(), 80, 24, 12, 24, null); }
        public void setModeChangeListener(Runnable listener) {}
        public int[] exportModeState() { return null; }
        public void restoreModeState(int[] state) {}
        public TerminalRenderFrame getRenderFrame() { return null; }
        public TerminalBuffer getScreen() { return null; }
        public int getViewportTopRow() { return 0; }
        public void setViewportTopRow(int topRow) {}
        public void scrollViewportBottom() {}
        public int getViewportHistoryRows() { return 0; }
        public int getViewportTotalRows() { return 24; }
        public String getViewportText(int topRow) { return ""; }
        public String getViewportLink(int column,int row) { return null; }
        public int[] getWordBounds(int column,int row) { return null; }
        public int getValidSelectionColumn(int row,int column) { return column; }
        public String getTextAt(int column,int row) { return ""; }
        public boolean isEmulatorDisposed() { return false; }
        public void captureFinalFrame() {}
        public void dispose() {}
        public void onUserInteraction() {}
        public void setRenderingActive(boolean active) {}
        public void setSelectionRange(int x1,int y1,int x2,int y2) {}
        public int[] getSelectionRange() { return null; }
        public void clearSelectionRange() {}
        public void sendFocusEvent(boolean focused) {}
        public String getWorkingDirectory() { return null; }
        public boolean isAlternateBufferActive() { return false; }
        public void sendMouseEvent(int button,int column,int row,boolean pressed) {}
        public void resize(int columns,int rows,int width,int height) {}
        public int getCursorRow() { return 0; }
        public int getCursorCol() { return 0; }
        public int getCursorStyle() { return 0; }
        public void setCursorStyle() {}
        public boolean isReverseVideo() { return false; }
        public boolean isCursorEnabled() { return true; }
        public boolean shouldCursorBeVisible() { return true; }
        public void setCursorBlinkingEnabled(boolean enabled) {}
        public void setCursorBlinkState(boolean visible) {}
        public boolean isKeypadApplicationMode() { return false; }
        public boolean isCursorKeysApplicationMode() { return false; }
        public boolean isMouseTrackingActive() { return false; }
        public boolean isMouseTrackingAnyEventActive() { return false; }
        public void append(byte[] bytes,int length) {}
        public boolean isAutoScrollDisabled() { return false; }
        public void toggleAutoScrollDisabled() {}
        public void reset() {}
        public String getSelectedText(int x1,int y1,int x2,int y2) { return ""; }
        public String getTitle() { return ""; }
        public void paste(String text) {}
        public int pasteStatus(String text,boolean allowUnsafe) { return -1; }
        @Override public boolean isKeyboardProtocolActive() { return true; }
        @Override public byte[] encodeKey(int key,int action,int mods,int consumedMods,byte[] utf8,
                                          int unshiftedCodepoint,boolean composing) { return null; }
    }
    static final class Session {
        final TerminalSession session; final Capture capture = new Capture(); final BlockingInput input = new BlockingInput();
        Session() {
            session = new TerminalSession(new Provider(), 1000, sessionClient(), GhosttyTerminalEmulator::new);
            session.initializeEmulator(80, 24, 12, 24);
            session.attachExternalPty(4242, input, capture, () -> {});
        }
        String await() {
            long deadline = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < deadline) {
                String text = capture.take();
                if (!text.isEmpty()) return text;
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
            return capture.take();
        }
        String settle() {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            return capture.take();
        }
        void close() {
            input.open = false;
            session.detachExternalPty();
            if (!session.isEmulatorDisposed()) session.getEmulator().dispose();
        }
    }
    static final class Flags { boolean ctrl, alt, shift, fn; }
    static TerminalViewClient viewClient(Flags flags) {
        return (TerminalViewClient) Proxy.newProxyInstance(NativeKeyInputCheck.class.getClassLoader(),
            new Class[]{TerminalViewClient.class}, (p,m,a) -> {
                switch (m.getName()) {
                    case "readControlKey": return flags.ctrl;
                    case "readAltKey": return flags.alt;
                    case "readShiftKey": return flags.shift;
                    case "readFnKey": return flags.fn;
                    default: break;
                }
                Class<?> type = m.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == float.class) return 1f;
                return null;
            });
    }
    static TerminalView view(Context context, TerminalSession session, Flags flags) {
        TerminalView v = new TerminalView(context, null);
        v.setFocusable(true); v.setFocusableInTouchMode(true);
        v.setTerminalViewClient(viewClient(flags));
        v.setTextSize(22);
        v.setTypeface(BundledTerminalFonts.typeface());
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 400, 300);
        v.attachSession(session);
        v.mEmulator = session.getEmulator();
        return v;
    }
    static KeyEvent key(int code,int action,int meta) {
        return new KeyEvent(0, 0, action, code, 0, meta, 1, 0);
    }
    static void feed(TerminalSession s,String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        s.getEmulator().append(bytes, bytes.length);
    }

    public static void main(String[] args) {
        try {
            android.os.Looper.prepareMainLooper();
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, args[1]);
            BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(args[0]);
            Context context = ViewViewportCheck.headlessContext(args[1]);
            Flags flags = new Flags();
            Session a = new Session();
            TerminalView v = view(context, a.session, flags);

            check(v.onKeyDown(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0)),
                "physical printable key handled");
            check("a".equals(a.await()), "legacy physical key sends text once");

            feed(a.session, "\033[>1u");
            check(v.onKeyDown(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)),
                "physical ctrl+a handled");
            check("\033[97;5u".equals(a.await()), "kitty ctrl+a press encoded");
            v.onKeyUp(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0));
            check("".equals(a.settle()), "release without report-events emits nothing");
            feed(a.session, "\033[>3u");
            v.onKeyDown(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON));
            check("\033[97;5u".equals(a.await()), "kitty ctrl+a press with report-events");
            v.onKeyUp(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0));
            check("\033[97;5:3u".equals(a.await()), "kitty release keeps the press modifiers");

            feed(a.session, "\033[<8u");
            check(v.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT,
                key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, 0)),
                "modifier-only mapped key is handled");
            check("".equals(a.settle()), "empty encoder result never falls back to legacy bytes");
            v.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0));

            feed(a.session, "\033[>1u");
            v.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 'a', false, false);
            check("a".equals(a.await()), "plain IME commit stays raw text under kitty flags");
            flags.ctrl = true;
            v.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 'a', false, false);
            check("\033[97;5u".equals(a.await()), "virtual Ctrl + IME commit encodes as a modified key");
            flags.ctrl = false;
            flags.alt = true;
            v.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, 0xe9, false, false);
            check("\033[233;3u".equals(a.await()), "virtual Alt + non-ASCII character encodes with modifiers");
            flags.alt = false;

            feed(a.session, "\033[>8u");
            v.onKeyDown(KeyEvent.KEYCODE_DEL, key(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, 0));
            String backspace = a.await();
            check(backspace.startsWith("\033[127") && !"\177".equals(backspace),
                "delete/backspace functional action uses the modern encoder");

            int unknownCode = -1, unknownCp = 0;
            for (int code : new int[]{KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_POUND,
                    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_PLUS}) {
                int cp = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0, 1, 0).getUnicodeChar(0);
                if (cp >= 32 && cp != 0x7f) { unknownCode = code; unknownCp = cp; break; }
            }
            check(unknownCode != -1, "found an unmapped physical key with Unicode text");
            feed(a.session, "\033[>8u");
            a.capture.take();
            check(v.onKeyDown(unknownCode, key(unknownCode, KeyEvent.ACTION_DOWN, 0)),
                "unmapped printable physical key handled under report_all");
            String unknownBytes = a.await();
            check(unknownBytes.startsWith("\033[")
                    && !unknownBytes.equals(new String(Character.toChars(unknownCp))),
                "unmapped printable physical key is not sent as raw text");

            Session sc = new Session();
            TerminalView sv = view(context, sc.session, new Flags());
            for (int i = 0; i < 100; i++) feed(sc.session, "history-" + i + "\r\n");
            sc.capture.take();
            int beforeScroll = sc.session.getEmulator().getViewportTopRow();
            check(sv.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,
                    key(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON)),
                "shift+pageup handled by the host");
            int afterScroll = sc.session.getEmulator().getViewportTopRow();
            check(afterScroll < beforeScroll, "shift+pageup scrolls local history");
            check("".equals(sc.settle()), "shift+pageup writes no PTY bytes");
            sc.session.getEmulator().setViewportTopRow(0);
            feed(sc.session, "\033[>8u");
            beforeScroll = sc.session.getEmulator().getViewportTopRow();
            sv.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN,
                key(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON));
            check("".equals(sc.settle()), "shift+pagedown writes no PTY bytes in kitty mode");
            feed(sc.session, "\033[<8u");
            sc.session.getEmulator().setViewportTopRow(0);
            sc.capture.take();
            check(sv.onKeyDown(KeyEvent.KEYCODE_PAGE_UP,
                    key(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.ACTION_DOWN, 0)),
                "unmodified pageup is handled");
            check(!sc.await().isEmpty(), "unmodified pageup still encodes to the PTY");

            Session b = new Session();
            feed(a.session, "\033[>3u");
            v.onKeyDown(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0));
            a.await();
            v.attachSession(b.session);
            v.mEmulator = b.session.getEmulator();
            v.onKeyUp(KeyEvent.KEYCODE_A, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0));
            check("".equals(b.settle()), "release is bound to the original window/emulator");

            Session comp = new Session();
            TerminalView cv = view(context, comp.session, new Flags());
            cv.mCombiningAccent = 0xb4;
            comp.capture.take();
            check(cv.onKeyDown(KeyEvent.KEYCODE_O,
                    key(KeyEvent.KEYCODE_O, KeyEvent.ACTION_DOWN, KeyEvent.META_FUNCTION_ON)),
                "composition fallback handled");
            check("ó".equals(comp.await()), "legacy fallback emits the composed text, not the base o");
            check(cv.mCombiningAccent == 0, "resolved composition clears the pending accent");
            check(cv.onKeyUp(KeyEvent.KEYCODE_O, key(KeyEvent.KEYCODE_O, KeyEvent.ACTION_UP, 0)),
                "composition keyup handled");
            check("".equals(comp.settle()), "composition fallback keyup emits no fake release");

            Session compKitty = new Session();
            TerminalView ckv = view(context, compKitty.session, new Flags());
            feed(compKitty.session, "\033[>3u");
            ckv.mCombiningAccent = 0xb4;
            compKitty.capture.take();
            ckv.onKeyDown(KeyEvent.KEYCODE_O, key(KeyEvent.KEYCODE_O, KeyEvent.ACTION_DOWN, 0));
            check("ó".equals(compKitty.await()), "kitty composition submits the final text once");
            ckv.onKeyUp(KeyEvent.KEYCODE_O, key(KeyEvent.KEYCODE_O, KeyEvent.ACTION_UP, 0));
            check("".equals(compKitty.settle()), "composition does not fabricate a key release");

            Session shifted = new Session();
            Flags shiftedFlags = new Flags(); shiftedFlags.shift = true;
            TerminalView shiftedView = view(context, shifted.session, shiftedFlags);
            feed(shifted.session, "\033[>8u");
            shifted.capture.take();
            shiftedView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 'a', false, false);
            check("\033[97;2u".equals(shifted.await()), "virtual Shift + character encodes as a modified key");
            check("".equals(shifted.settle()), "virtual Shift commit produces one encoded event");

            Session failing = new Session();
            java.lang.reflect.Field emulatorField = TerminalSession.class.getDeclaredField("mEmulator");
            emulatorField.setAccessible(true);
            emulatorField.set(failing.session, new FailingEmulator());
            TerminalView failingView = view(context, failing.session, new Flags());
            failing.capture.take();
            failingView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 'a', true, false);
            check("".equals(failing.settle()),
                "protocol-active encoder rejection does not fall back to legacy Ctrl bytes");
            failing.close();

            Session imeKitty = new Session();
            Flags imeFlags = new Flags();
            TerminalView imeView = view(context, imeKitty.session, imeFlags);
            feed(imeKitty.session, "\033[>8u");
            imeKitty.capture.take();
            android.view.inputmethod.EditorInfo editorInfo = new android.view.inputmethod.EditorInfo();
            android.view.inputmethod.InputConnection ic = imeView.onCreateInputConnection(editorInfo);
            ic.commitText("\n", 1);
            String kittyEnter = imeKitty.await();
            check(kittyEnter.startsWith("\033[13") && !kittyEnter.contains("109;5"),
                "IME newline routes via GhosttyKey.ENTER instead of Ctrl+m");
            ic.commitText("\t", 1);
            String kittyTab = imeKitty.await();
            check(kittyTab.startsWith("\033[9") && !kittyTab.contains("105;5"),
                "IME tab routes via GhosttyKey.TAB instead of Ctrl+i");
            imeKitty.close();

            a.close(); b.close(); sc.close(); comp.close(); compKitty.close(); shifted.close();
            System.out.println("NATIVE KEY INPUT OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace(); System.exit(1);
        }
    }
}
