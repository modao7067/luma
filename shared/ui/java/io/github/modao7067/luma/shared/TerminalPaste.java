package io.github.modao7067.luma.shared;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

public final class TerminalPaste {
    private TerminalPaste() {}

    public interface Host {
        boolean isAlive();
        TerminalSession currentSession();
    }

    public interface Selection {
        TerminalSession current();
    }

    public static final class Request {
        private static final int PENDING = 0, CONSUMED = 1, CANCELLED = 2;
        private final Host host;
        private final TerminalSession session;
        private final TerminalEmulator emulator;
        private final String text;
        private int state = PENDING;

        public Request(Host host, TerminalSession session, TerminalEmulator emulator, CharSequence captured) {
            if (host == null || session == null || emulator == null) throw new IllegalArgumentException("paste request needs a host, session and emulator");
            this.host = host;
            this.session = session;
            this.emulator = emulator;
            this.text = captured == null ? "" : captured.toString();
        }

        public synchronized int approve() {
            if (state != PENDING) return TerminalEmulator.PASTE_ERROR;
            if (!targetValid()) { state = CANCELLED; return TerminalEmulator.PASTE_ERROR; }
            state = CONSUMED;
            return emulator.pasteStatus(text, true);
        }

        public synchronized void cancel() {
            if (state == PENDING) state = CANCELLED;
        }

        public synchronized boolean isPending() { return state == PENDING; }

        private boolean targetValid() {
            return host.isAlive() && host.currentSession() == session
                && !session.isEmulatorDisposed() && session.getEmulator() == emulator
                && !emulator.isEmulatorDisposed();
        }
    }

    public static final class Pending {
        private Request request;

        public synchronized void begin(Request next) {
            cancel();
            request = next;
        }

        public synchronized void selectionChanged(TerminalSession selected) {
            if (request != null && request.session != selected) cancel();
        }

        public synchronized void cancel() {
            if (request != null) { request.cancel(); request = null; }
        }

        public synchronized boolean isPending() { return request != null && request.isPending(); }
    }

    public static Request request(Activity activity, TerminalSession session, CharSequence captured, Selection selection) {
        if (activity == null || session == null || selection == null || TextUtils.isEmpty(captured)) return null;
        if (session.isEmulatorDisposed()) return null;
        TerminalEmulator emulator = session.getEmulator();
        if (emulator == null || emulator.isEmulatorDisposed()) return null;
        String text = captured.toString();
        int status = emulator.pasteStatus(text, false);
        if (status == TerminalEmulator.PASTE_WRITTEN || status == TerminalEmulator.PASTE_NOTHING) return null;
        if (status == TerminalEmulator.PASTE_TOO_LARGE) {
            Toast.makeText(activity, "粘贴内容过大，未写入", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (status != TerminalEmulator.PASTE_NEEDS_CONFIRMATION) {
            Toast.makeText(activity, "粘贴失败，未写入", Toast.LENGTH_SHORT).show();
            return null;
        }
        Request request = new Request(new ActivityHost(activity, selection), session, emulator, text);
        showConfirm(activity, request);
        return request;
    }

    private static void showConfirm(Activity activity, Request request) {
        LumaUi.Sheet sheet = new LumaUi.Sheet(activity, "粘贴可能执行的文本？",
            "文本包含换行或括号粘贴终止序列。确认后才会写入，取消不会发送任何内容。");
        sheet.dialog.setOnDismissListener(dialog -> request.cancel());

        TextView preview = new TextView(activity);
        preview.setText(preview(request.text));
        preview.setTextColor(LumaUi.TEXT_SECONDARY);
        preview.setTextSize(12.5f);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setGravity(Gravity.START);
        preview.setMaxLines(6);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(LumaUi.dp(activity, 14), LumaUi.dp(activity, 10),
            LumaUi.dp(activity, 14), LumaUi.dp(activity, 10));
        preview.setBackground(LumaUi.roundedRect(0x14ffffff, LumaUi.dp(activity, 14), 1, 0x1affffff));
        sheet.body.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        TextView paste = LumaUi.primaryButton(activity, "粘贴", v -> {
            int status = request.approve();
            sheet.dismiss();
            if (status == TerminalEmulator.PASTE_TOO_LARGE)
                Toast.makeText(activity, "粘贴内容过大，未写入", Toast.LENGTH_SHORT).show();
            else if (status == TerminalEmulator.PASTE_ERROR)
                Toast.makeText(activity, "粘贴失败，未写入", Toast.LENGTH_SHORT).show();
        });
        TextView cancel = LumaUi.secondaryButton(activity, "取消", v -> {
            request.cancel();
            sheet.dismiss();
        });
        sheet.body.addView(LumaUi.buttonRow(activity, paste, cancel), LumaUi.vSpace(activity, 16));
        sheet.show();
    }

    private static final class ActivityHost implements Host {
        private final Activity activity;
        private final Selection selection;
        ActivityHost(Activity activity, Selection selection) {
            this.activity = activity;
            this.selection = selection;
        }
        @Override public boolean isAlive() { return !activity.isFinishing() && !activity.isDestroyed(); }
        @Override public TerminalSession currentSession() { return selection.current(); }
    }

    private static String preview(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 200));
        for (int i = 0; i < text.length() && i < 200; i++) {
            char c = text.charAt(i);
            out.append(c == '\r' ? '␍' : c == '\t' ? '→' : c);
        }
        if (text.length() > 200) out.append('…');
        return out.toString();
    }
}
