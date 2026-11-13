package io.github.modao7067.luma.ghostty;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;

final class GhosttyScrollbackCompressor implements MessageQueue.IdleHandler {
    static final long IDLE_DELAY_MS = 2000, STEP_DELAY_MS = 16;
    interface Backend {
        long activity();
        int step();
    }
    private final Handler handler;
    private final MessageQueue queue;
    private final Backend backend;
    private final Runnable ready = this::awaitIdle;
    private long activity;
    private boolean observed, pending, registered, closed, unsupported;
    int stepCount, completeCount;
    long firstStepNanos, lastStepNanos, totalStepNanos, maxStepNanos;

    boolean isPending() { return pending; }
    boolean isUnsupported() { return unsupported; }
    boolean isClosed() { return closed; }

    GhosttyScrollbackCompressor(Handler handler, Backend backend) {
        if (Looper.myLooper() != handler.getLooper()) throw new IllegalStateException("compression requires the terminal owner thread");
        this.handler = handler; this.queue = handler.getLooper().getQueue(); this.backend = backend;
        observe();
    }
    void observe() {
        if (closed || unsupported) return;
        long next = backend.activity();
        if (!observed || next != activity) {
            activity = next; observed = true; pending = true; schedule(IDLE_DELAY_MS);
        }
    }
    void userActivity() {
        if (closed || unsupported) return;
        observe();
        if (pending) schedule(IDLE_DELAY_MS);
    }
    private void schedule(long delay) {
        handler.removeCallbacks(ready);
        if (registered) { queue.removeIdleHandler(this); registered = false; }
        handler.postDelayed(ready, delay);
    }
    private void awaitIdle() {
        if (closed || unsupported || !pending || registered) return;
        registered = true; queue.addIdleHandler(this);
    }
    @Override public boolean queueIdle() {
        registered = false;
        if (closed || unsupported || !pending) return false;
        long next = backend.activity();
        if (next != activity) {
            activity = next; observed = true; pending = true; schedule(IDLE_DELAY_MS);
            return false;
        }
        long start = System.nanoTime();
        int result = backend.step();
        long elapsed = System.nanoTime() - start;
        stepCount++; totalStepNanos += elapsed;
        if (elapsed > maxStepNanos) maxStepNanos = elapsed;
        if (firstStepNanos == 0) firstStepNanos = start;
        lastStepNanos = start + elapsed;
        if (result == 1) schedule(STEP_DELAY_MS);
        else if (result == 0 || result == 2) {
            pending = false; unsupported = result == 0;
            if (result == 2) completeCount++;
        }
        else throw new IllegalStateException("native scrollback compression failed: " + result);
        return false;
    }
    void close() {
        closed = true; pending = false; handler.removeCallbacks(ready);
        if (registered) { queue.removeIdleHandler(this); registered = false; }
    }
}
