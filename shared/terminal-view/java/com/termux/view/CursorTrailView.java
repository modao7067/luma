package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

public final class CursorTrailView extends View {
    private static final float MIN_STRETCH_FOR_GRADIENT = 0.04f;
    private static final float TAIL_MID_ALPHA = 0.46f;
    private static final float HEAD_TARGET_ALPHA = 0.98f;
    private static final float HEAD_SOLID_INSET_FRACTION = 0.18f;
    private static final float[] GRADIENT_STOPS = {0f, 0.55f, 1f};

    private final CursorTrailState mState = new CursorTrailState();
    private final Paint mSolidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrailPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path mQuadPath = new Path();
    private final Matrix mShaderMatrix = new Matrix();
    private final float[] mMatrixValues = new float[9];
    private final float[] mPolyX = new float[8];
    private final float[] mPolyY = new float[8];
    private final int[] mGradientColors = new int[3];

    private LinearGradient mTrailGradient;
    private int mGradientColor;
    private Runnable mOnSettled;
    private boolean mEnabled = true;
    private boolean mActive = true;
    private boolean mFrameScheduled;
    private float mTargetFrameRate = 60f;
    private float mAppliedFrameRate = -1f;
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
        mSolidPaint.setStyle(Paint.Style.FILL);
        mTrailPaint.setStyle(Paint.Style.FILL);
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
            applyRequestedFrameRate(false);
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
            applyRequestedFrameRate(mState.isAnimating());
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
            applyRequestedFrameRate(true);
            scheduleNextFrame();
            invalidate();
        } else if (changed || wasAnimating) {
            applyRequestedFrameRate(false);
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
        } else {
            applyRequestedFrameRate(false);
            if (mOnSettled != null) {
                mOnSettled.run();
            }
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
        applyRequestedFrameRate(false);
        boolean hadTrail = mState.hasDrawableTrail();
        mState.reset();
        if (hadTrail) {
            invalidate();
            if (notifySettled && mOnSettled != null) {
                mOnSettled.run();
            }
        }
    }

    private void applyRequestedFrameRate(boolean boosting) {
        if (Build.VERSION.SDK_INT < 35) return;
        float desired = (mEnabled && mActive && boosting) ? mTargetFrameRate : 0f;
        if (mAppliedFrameRate == desired) return;
        mAppliedFrameRate = desired;
        setRequestedFrameRate(desired);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAppliedFrameRate = -1f;
        applyRequestedFrameRate(mState.isAnimating());
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

        int polyCount = mState.fillTaperedHull(mPolyX, mPolyY);
        if (polyCount < 3) return;

        int cursorColor = mState.getCursorColor();
        float stretch = mState.getStretchFactor();
        float span = mState.getAxisSpan();
        float cellExtent = mState.getAxisCellExtent();
        float tailSpan = Math.max(0f, span - cellExtent);

        if (stretch <= MIN_STRETCH_FOR_GRADIENT || tailSpan <= 1.5f) {
            mQuadPath.reset();
            mQuadPath.moveTo(mPolyX[0], mPolyY[0]);
            for (int i = 1; i < polyCount; i++) {
                mQuadPath.lineTo(mPolyX[i], mPolyY[i]);
            }
            mQuadPath.close();
            int rgb = cursorColor & 0x00ffffff;
            int baseAlphaInt = (cursorColor >>> 24) & 0xff;
            float baseAlpha = baseAlphaInt > 0 ? baseAlphaInt / 255f : 1f;
            int headAlpha = Math.round(HEAD_TARGET_ALPHA * baseAlpha * 255f);
            mSolidPaint.setColor((headAlpha << 24) | rgb);
            canvas.drawPath(mQuadPath, mSolidPaint);
            return;
        }

        ensureTrailGradient(cursorColor);
        float dirX = mState.getAxisDirX();
        float dirY = mState.getAxisDirY();
        float startProj = mState.getAxisMinProj();
        float headStart = Math.max(0.02f, Math.min(0.94f, tailSpan / Math.max(1f, span)));
        float headSolidStart = headStart + HEAD_SOLID_INSET_FRACTION * (1f - headStart);
        float gradLen = Math.max(1f, span * headSolidStart);
        float invGradLen = 1f / gradLen;

        float perpSum = 0f;
        for (int i = 0; i < polyCount; i++) {
            perpSum += -mPolyX[i] * dirY + mPolyY[i] * dirX;
        }
        float midPerp = perpSum / polyCount;
        float originX = dirX * startProj - dirY * midPerp;
        float originY = dirY * startProj + dirX * midPerp;

        mQuadPath.reset();
        for (int i = 0; i < polyCount; i++) {
            float dx = mPolyX[i] - originX;
            float dy = mPolyY[i] - originY;
            float localX = (dx * dirX + dy * dirY) * invGradLen;
            float localY = (-dx * dirY + dy * dirX) * invGradLen;
            if (i == 0) {
                mQuadPath.moveTo(localX, localY);
            } else {
                mQuadPath.lineTo(localX, localY);
            }
        }
        mQuadPath.close();

        mMatrixValues[0] = dirX * gradLen;
        mMatrixValues[1] = -dirY * gradLen;
        mMatrixValues[2] = originX;
        mMatrixValues[3] = dirY * gradLen;
        mMatrixValues[4] = dirX * gradLen;
        mMatrixValues[5] = originY;
        mMatrixValues[6] = 0f;
        mMatrixValues[7] = 0f;
        mMatrixValues[8] = 1f;
        mShaderMatrix.setValues(mMatrixValues);

        int saveCount = canvas.save();
        canvas.concat(mShaderMatrix);
        canvas.drawPath(mQuadPath, mTrailPaint);
        canvas.restoreToCount(saveCount);
    }

    private void ensureTrailGradient(int cursorColor) {
        if (mTrailGradient != null && mGradientColor == cursorColor) return;
        mGradientColor = cursorColor;
        int rgb = cursorColor & 0x00ffffff;
        int baseAlphaInt = (cursorColor >>> 24) & 0xff;
        float baseAlpha = baseAlphaInt > 0 ? baseAlphaInt / 255f : 1f;
        int midAlpha = Math.round(TAIL_MID_ALPHA * baseAlpha * 255f);
        int headAlpha = Math.round(HEAD_TARGET_ALPHA * baseAlpha * 255f);
        mGradientColors[0] = rgb;
        mGradientColors[1] = (midAlpha << 24) | rgb;
        mGradientColors[2] = (headAlpha << 24) | rgb;
        mTrailGradient = new LinearGradient(
                0f, 0f, 1f, 0f,
                mGradientColors,
                GRADIENT_STOPS,
                Shader.TileMode.CLAMP);
        mTrailPaint.setShader(mTrailGradient);
    }
}
