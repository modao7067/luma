package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

public final class CursorTrailView extends View {
    private final CursorTrailState mState = new CursorTrailState();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mQuadPath = new Path();

    private Runnable mOnSettled;
    private boolean mEnabled = true;
    private boolean mActive = true;
    private boolean mFrameScheduled;
    private float mTargetFrameRate = 60f;
    private final Choreographer.FrameCallback mFrameCallback = this::onVsyncFrame;

    public CursorTrailView(Context context) {
        this(context, null);
    }

    public CursorTrailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        mPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnSettledListener(Runnable listener) {
        mOnSettled = listener;
    }

    public boolean isTrailEnabled() {
        return mEnabled;
    }

    public boolean shouldSuppressStaticCursor() {
        return mEnabled
                && mActive
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && isHardwareAccelerated()
                && mState.hasDrawableTrail();
    }

    public void setTrailEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (!enabled) {
            stopAndClear(true);
            setVisibility(GONE);
        } else {
            mState.reset();
            setVisibility(VISIBLE);
            applyRequestedFrameRate();
            invalidate();
        }
    }

    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (!active) {
            stopAndClear(true);
        }
    }

    public void setTargetFrameRate(float frameRate) {
        if (frameRate > 0f) {
            mTargetFrameRate = frameRate;
            applyRequestedFrameRate();
        }
    }

    public void resetTrail() {
        stopAndClear(true);
    }

    public void onCursorFrame(
            boolean visible,
            float left,
            float top,
            float right,
            float bottom,
            int cursorColor,
            int sourceTopRow,
            int totalRows,
            boolean altScreen,
            int rows,
            int columns,
            float cellWidth,
            float cellHeight,
            int sessionToken) {
        if (!mEnabled || !mActive || getVisibility() != VISIBLE) return;
        boolean wasAnimating = mState.isAnimating();
        boolean changed = mState.onCursorFrame(
                visible,
                left,
                top,
                right,
                bottom,
                cursorColor,
                sourceTopRow,
                totalRows,
                altScreen,
                rows,
                columns,
                cellWidth,
                cellHeight,
                sessionToken,
                System.nanoTime());
        if (mState.isAnimating()) {
            applyRequestedFrameRate();
            scheduleNextFrame();
            invalidate();
        } else if (changed || wasAnimating) {
            cancelScheduledFrame();
            invalidate();
        }
    }

    private void onVsyncFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        if (!mEnabled || !mActive || !isAttachedToWindow() || getVisibility() != VISIBLE) {
            stopAndClear(true);
            return;
        }
        boolean keepGoing = mState.step(frameTimeNanos);
        invalidate();
        if (keepGoing) {
            scheduleNextFrame();
        } else if (mOnSettled != null) {
            mOnSettled.run();
        }
    }

    private void scheduleNextFrame() {
        if (mFrameScheduled || !mEnabled || !mActive || !isAttachedToWindow()) return;
        mFrameScheduled = true;
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }

    private void cancelScheduledFrame() {
        if (!mFrameScheduled) return;
        mFrameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(mFrameCallback);
    }

    private void stopAndClear(boolean notifySettled) {
        cancelScheduledFrame();
        boolean hadTrail = mState.hasDrawableTrail();
        mState.reset();
        if (hadTrail) {
            invalidate();
            if (notifySettled && mOnSettled != null) {
                mOnSettled.run();
            }
        }
    }

    private void applyRequestedFrameRate() {
        if (Build.VERSION.SDK_INT >= 35 && mEnabled) {
            setRequestedFrameRate(mTargetFrameRate);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyRequestedFrameRate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            stopAndClear(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAndClear(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mEnabled || !mActive || !canvas.isHardwareAccelerated() || !mState.hasDrawableTrail()) {
            return;
        }

        int count = mState.getHullCount();
        if (count < 3) return;
        mQuadPath.reset();
        mQuadPath.moveTo(mState.getHullX(0), mState.getHullY(0));
        for (int i = 1; i < count; i++) {
            mQuadPath.lineTo(mState.getHullX(i), mState.getHullY(i));
        }
        mQuadPath.close();

        float alpha = 0.94f - 0.38f * mState.getStretchFactor();
        int rgb = mState.getCursorColor() & 0x00ffffff;
        mPaint.setColor((Math.round(alpha * 255f) << 24) | rgb);
        canvas.drawPath(mQuadPath, mPaint);
    }
}
