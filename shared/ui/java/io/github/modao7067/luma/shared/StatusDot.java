package io.github.modao7067.luma.shared;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import com.termux.terminal.TerminalSession;

final class StatusDot extends TextView {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int state = -1;
    private boolean pulsePosted;
    private boolean bright = true;
    private boolean activityActive = true;
    private long pulseUntil;
    private static final long OUTPUT_PULSE_MS = 1200;
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            pulsePosted = false;
            if (!canBlink()) { showFilled(true); return; }
            showFilled(!bright);
            schedulePulse();
        }
    };

    StatusDot(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setTextSize(10f);
        setText("●");
        setState(TerminalSession.COMMAND_NONE);
    }

    void showSession(TerminalSession session) {
        setState(session == null ? TerminalSession.COMMAND_NONE
            : session.getPid() < 0 ? TerminalSession.COMMAND_FAILED : session.getCommandState());
    }

    void setActivityActive(boolean active) { activityActive = active; updateBlink(); }

    void onOutput(TerminalSession session) {
        showSession(session);
        if (state == TerminalSession.COMMAND_RUNNING && canShowActivity()) {
            pulseUntil = SystemClock.uptimeMillis() + OUTPUT_PULSE_MS;
            updateBlink();
        }
    }

    private void setState(int value) {
        if (state == value) { updateBlink(); return; }
        state = value;
        pulseUntil = 0;
        setVisibility(value == TerminalSession.COMMAND_NONE ? View.GONE : View.VISIBLE);
        setTextColor(value == TerminalSession.COMMAND_RUNNING ? 0xffffffff
            : value == TerminalSession.COMMAND_SUCCESS ? 0xff9fdf91 : 0xffff7777);
        setContentDescription(value == TerminalSession.COMMAND_RUNNING ? "命令运行中"
            : value == TerminalSession.COMMAND_SUCCESS ? "命令成功完成"
            : value == TerminalSession.COMMAND_FAILED ? "命令失败或会话已结束" : "尚未运行命令");
        updateBlink();
    }

    private void showFilled(boolean filled) {
        if (bright == filled) return;
        bright = filled;
        setText(filled ? "●" : "○");
    }

    private boolean canShowActivity() {
        return activityActive && isAttachedToWindow()
            && isShown() && getWindowVisibility() == VISIBLE && hasWindowFocus();
    }

    private boolean canBlink() {
        return state == TerminalSession.COMMAND_RUNNING && canShowActivity()
            && SystemClock.uptimeMillis() < pulseUntil;
    }

    private void schedulePulse() {
        if (!pulsePosted && canBlink()) {
            pulsePosted = true;
            handler.postDelayed(pulse, Math.min(600, Math.max(1, pulseUntil - SystemClock.uptimeMillis())));
        }
    }

    private void updateBlink() {
        if (handler == null || pulse == null) return;
        if (canBlink()) schedulePulse();
        else { handler.removeCallbacks(pulse); pulsePosted = false; pulseUntil = 0; showFilled(true); }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateBlink(); }
    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(pulse); pulsePosted = false; pulseUntil = 0; showFilled(true);
        super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); updateBlink(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateBlink(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); updateBlink(); }
}
