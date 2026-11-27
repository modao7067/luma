package com.termux.view;

public final class CursorTrailState {
    public static final float DEFAULT_SHORT_FAST_SECONDS = 0.022f;
    public static final float DEFAULT_SHORT_SLOW_SECONDS = 0.095f;
    public static final float DEFAULT_LONG_FAST_SECONDS = 0.032f;
    public static final float DEFAULT_LONG_SLOW_SECONDS = 0.220f;
    public static final float DEFAULT_CROSS_LINE_EXTRA_SLOW_SECONDS = 0.055f;
    public static final float DEFAULT_BURST_EXTRA_SLOW_SECONDS = 0.220f;
    public static final float CONVERGENCE_EPSILON_PX = 1.25f;
    public static final float CONVERGENCE_SPEED_PX_PER_SEC = 45f;
    public static final long HIDE_GRACE_NANOS = 350_000_000L;
    public static final long COL_ZERO_HOLD_NANOS = 140_000_000L;
    private static final float SPRING_OMEGA_SCALE = 5.0f;
    private static final float SPRING_RANK_EXPONENT = 0.85f;
    private static final float BURST_WINDOW_SECONDS = 0.220f;
    private static final float BURST_DECAY_SECONDS = 0.280f;
    private static final float BURST_STEP_GAIN = 0.50f;
    private static final long BURST_RELEASE_IDLE_NANOS = 85_000_000L;
    private static final float BURST_RELEASE_TAU_SECONDS = 0.070f;
    private static final float LAUNCH_STEP_SECONDS = 0.008f;
    private static final long FALLBACK_STEP_NANOS = 8_333_333L;
    private static final float MIN_DT_SECONDS = 0.001f;
    private static final float MAX_DT_SECONDS = 0.050f;
    private static final float MAX_TAIL_TAPER = 0.30f;
    private static final float TAIL_EDGE_FRACTION = 0.18f;
    private static final float[] CORNER_SIGN_X = {-1f, 1f, 1f, -1f};
    private static final float[] CORNER_SIGN_Y = {-1f, -1f, 1f, 1f};

    private final float mShortFastSeconds;
    private final float mShortSlowSeconds;
    private final float mLongFastSeconds;
    private final float mLongSlowSeconds;

    private final float[] mTargetX = new float[4];
    private final float[] mTargetY = new float[4];
    private final float[] mTrailX = new float[4];
    private final float[] mTrailY = new float[4];
    private final float[] mVelX = new float[4];
    private final float[] mVelY = new float[4];
    private final float[] mCornerProjections = new float[4];
    private final int[] mSortedIndices = new int[4];
    private final int[] mHullIndices = new int[8];
    private final float[] mHullX = new float[4];
    private final float[] mHullY = new float[4];

    private boolean mHasAnchor;
    private boolean mVisible;
    private boolean mAnimating;
    private boolean mHoldingColZero;
    private long mHoldStartNanos;
    private long mLastStepNanos;
    private long mHiddenSinceNanos;

    private int mSessionToken;
    private int mRows;
    private int mColumns;
    private float mCellWidth;
    private float mCellHeight;
    private int mSourceTopRow;
    private int mTotalRows;
    private boolean mAltScreen;
    private int mCursorColor = 0xffffffff;

    private float mTargetLeft;
    private float mTargetTop;
    private float mTargetRight;
    private float mTargetBottom;

    private float mPendingLeft;
    private float mPendingTop;
    private float mPendingRight;
    private float mPendingBottom;

    private float mActiveFastSeconds;
    private float mActiveSlowSeconds;
    private float mBurstHeat;
    private long mLastMoveNanos;
    private int mLastMoveSignX;
    private int mLastMoveSignY;
    private float mMoveDirX = 1f;
    private float mMoveDirY = 0f;

    private int mHullCount;
    private float mBoundsLeft;
    private float mBoundsTop;
    private float mBoundsRight;
    private float mBoundsBottom;
    private float mAxisMinProj;
    private float mAxisSpan = 1f;
    private float mAxisCellExtent = 1f;
    private float mStretchFactor;

    public CursorTrailState() {
        this(
                DEFAULT_SHORT_FAST_SECONDS,
                DEFAULT_SHORT_SLOW_SECONDS,
                DEFAULT_LONG_FAST_SECONDS,
                DEFAULT_LONG_SLOW_SECONDS);
    }

    public CursorTrailState(float decayFastSeconds, float decaySlowSeconds) {
        this(
                Math.max(0.012f, decayFastSeconds * 0.55f),
                Math.max(0.032f, decaySlowSeconds * 0.55f),
                Math.max(0.016f, decayFastSeconds),
                Math.max(0.040f, decaySlowSeconds));
    }

    public CursorTrailState(
            float shortFastSeconds,
            float shortSlowSeconds,
            float longFastSeconds,
            float longSlowSeconds) {
        mShortFastSeconds = Math.max(0.010f, shortFastSeconds);
        mShortSlowSeconds = Math.max(mShortFastSeconds, shortSlowSeconds);
        mLongFastSeconds = Math.max(mShortFastSeconds, longFastSeconds);
        mLongSlowSeconds = Math.max(mLongFastSeconds, longSlowSeconds);
        mActiveFastSeconds = mLongFastSeconds;
        mActiveSlowSeconds = mLongSlowSeconds;
    }

    public void reset() {
        mHasAnchor = false;
        mVisible = false;
        mAnimating = false;
        mHoldingColZero = false;
        mHoldStartNanos = 0L;
        mLastStepNanos = 0L;
        mHiddenSinceNanos = 0L;
        mHullCount = 0;
        mAxisCellExtent = 1f;
        mStretchFactor = 0f;
        mBurstHeat = 0f;
        mLastMoveNanos = 0L;
        mLastMoveSignX = 0;
        mLastMoveSignY = 0;
        clearVelocities();
    }

    public boolean isAnimating() {
        return mAnimating || mHoldingColZero;
    }

    public boolean hasDrawableTrail() {
        return (mAnimating || mHoldingColZero) && mHullCount >= 3;
    }

    public boolean isHoldingColZero() {
        return mHoldingColZero;
    }

    public int getCursorColor() {
        return mCursorColor;
    }

    public int getHullCount() {
        return mHullCount;
    }

    public float getHullX(int index) {
        return mHullX[index];
    }

    public float getHullY(int index) {
        return mHullY[index];
    }

    public float getTargetLeft() {
        return mTargetLeft;
    }

    public float getTargetTop() {
        return mTargetTop;
    }

    public float getTargetRight() {
        return mTargetRight;
    }

    public float getTargetBottom() {
        return mTargetBottom;
    }

    public float getBoundsLeft() {
        return mBoundsLeft;
    }

    public float getBoundsTop() {
        return mBoundsTop;
    }

    public float getBoundsRight() {
        return mBoundsRight;
    }

    public float getBoundsBottom() {
        return mBoundsBottom;
    }

    public float getAxisDirX() {
        return mMoveDirX;
    }

    public float getAxisDirY() {
        return mMoveDirY;
    }

    public float getAxisMinProj() {
        return mAxisMinProj;
    }

    public float getAxisSpan() {
        return mAxisSpan;
    }

    public float getAxisCellExtent() {
        return mAxisCellExtent;
    }

    public float getStretchFactor() {
        return mStretchFactor;
    }

    public int fillTaperedHull(float[] outX, float[] outY) {
        int count = mHullCount;
        if (count < 3) return 0;
        float taper = MAX_TAIL_TAPER * mStretchFactor;
        if (taper <= 0.005f || mAxisSpan <= 1e-3f) {
            for (int i = 0; i < count; i++) {
                outX[i] = mHullX[i];
                outY[i] = mHullY[i];
            }
            return count;
        }
        float tailThreshold = mAxisMinProj + mAxisSpan * TAIL_EDGE_FRACTION;
        float tailSumX = 0f;
        float tailSumY = 0f;
        int tailCount = 0;
        for (int i = 0; i < count; i++) {
            float proj = mHullX[i] * mMoveDirX + mHullY[i] * mMoveDirY;
            if (proj <= tailThreshold) {
                tailSumX += mHullX[i];
                tailSumY += mHullY[i];
                tailCount++;
            }
        }
        if (tailCount != 2) {
            for (int i = 0; i < count; i++) {
                outX[i] = mHullX[i];
                outY[i] = mHullY[i];
            }
            return count;
        }
        float midX = tailSumX * 0.5f;
        float midY = tailSumY * 0.5f;
        for (int i = 0; i < count; i++) {
            float x = mHullX[i];
            float y = mHullY[i];
            float proj = x * mMoveDirX + y * mMoveDirY;
            if (proj <= tailThreshold) {
                outX[i] = x + (midX - x) * taper;
                outY[i] = y + (midY - y) * taper;
            } else {
                outX[i] = x;
                outY[i] = y;
            }
        }
        return count;
    }

    public static int clipConvexHalfPlane(
            float[] inX,
            float[] inY,
            int inCount,
            float dirX,
            float dirY,
            float minProj,
            float[] outX,
            float[] outY) {
        if (inCount < 3) return 0;
        int outCount = 0;
        float prevX = inX[inCount - 1];
        float prevY = inY[inCount - 1];
        float prevD = prevX * dirX + prevY * dirY - minProj;
        boolean prevIn = prevD >= 0f;
        for (int i = 0; i < inCount; i++) {
            float curX = inX[i];
            float curY = inY[i];
            float curD = curX * dirX + curY * dirY - minProj;
            boolean curIn = curD >= 0f;
            if (curIn != prevIn) {
                float denom = prevD - curD;
                if (Math.abs(denom) > 1e-6f) {
                    float t = prevD / denom;
                    outX[outCount] = prevX + (curX - prevX) * t;
                    outY[outCount] = prevY + (curY - prevY) * t;
                    outCount++;
                }
            }
            if (curIn) {
                outX[outCount] = curX;
                outY[outCount] = curY;
                outCount++;
            }
            prevX = curX;
            prevY = curY;
            prevD = curD;
            prevIn = curIn;
        }
        return outCount;
    }

    public void fillEdgeUniforms(float[] edges16) {
        int count = mHullCount;
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            if (i < count) {
                int next = (i + 1 == count) ? 0 : (i + 1);
                edges16[offset] = mHullX[i];
                edges16[offset + 1] = mHullY[i];
                edges16[offset + 2] = mHullX[next];
                edges16[offset + 3] = mHullY[next];
            } else {
                edges16[offset] = 0f;
                edges16[offset + 1] = 0f;
                edges16[offset + 2] = 0f;
                edges16[offset + 3] = 0f;
            }
        }
    }

    public boolean onCursorFrame(
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
            int sessionToken,
            long nowNanos) {
        boolean contextChanged = !mHasAnchor
                || mSessionToken != sessionToken
                || mRows != rows
                || mColumns != columns
                || Math.abs(mCellWidth - cellWidth) > 0.01f
                || Math.abs(mCellHeight - cellHeight) > 0.01f
                || mAltScreen != altScreen
                || mSourceTopRow != sourceTopRow;

        int previousTotalRows = mTotalRows;
        mSessionToken = sessionToken;
        mRows = rows;
        mColumns = columns;
        mCellWidth = cellWidth;
        mCellHeight = cellHeight;
        mAltScreen = altScreen;
        mSourceTopRow = sourceTopRow;
        mTotalRows = totalRows;
        mCursorColor = cursorColor;

        if (sourceTopRow < 0 || cellWidth <= 0f || cellHeight <= 0f || right <= left || bottom <= top) {
            boolean wasDrawable = hasDrawableTrail();
            reset();
            return wasDrawable;
        }

        int scrolledRows = 0;
        if (!contextChanged && sourceTopRow == 0 && !altScreen && previousTotalRows > 0 && totalRows > previousTotalRows) {
            scrolledRows = totalRows - previousTotalRows;
            if (scrolledRows >= rows) {
                contextChanged = true;
            } else {
                shiftVertical(-scrolledRows * cellHeight);
            }
        }

        if (!visible) {
            if (contextChanged) {
                boolean wasDrawable = hasDrawableTrail();
                reset();
                return wasDrawable;
            }
            if (mHasAnchor && mHiddenSinceNanos == 0L) {
                mHiddenSinceNanos = nowNanos;
            }
            if (mHiddenSinceNanos != 0L && nowNanos - mHiddenSinceNanos > HIDE_GRACE_NANOS) {
                boolean wasDrawable = hasDrawableTrail();
                mVisible = false;
                mAnimating = false;
                mHoldingColZero = false;
                mHasAnchor = false;
                mHullCount = 0;
                clearVelocities();
                return wasDrawable;
            }
            return isAnimating();
        }

        if (mHiddenSinceNanos != 0L) {
            long hiddenDuration = nowNanos - mHiddenSinceNanos;
            mHiddenSinceNanos = 0L;
            if (hiddenDuration > HIDE_GRACE_NANOS) {
                contextChanged = true;
            }
        }

        if (contextChanged) {
            snapTo(left, top, right, bottom, nowNanos);
            return false;
        }

        boolean atColZero = left < cellWidth * 0.5f;
        if (mHoldingColZero) {
            if (mTargetBottom <= 0f) {
                snapTo(left, top, right, bottom, nowNanos);
                return false;
            }
            boolean holdExpired = nowNanos - mHoldStartNanos >= COL_ZERO_HOLD_NANOS;
            if (atColZero && !holdExpired) {
                mPendingLeft = left;
                mPendingTop = top;
                mPendingRight = right;
                mPendingBottom = bottom;
                rebuildHull();
                return true;
            }
            mHoldingColZero = false;
            mHoldStartNanos = 0L;
            return launchFromTrail(left, top, right, bottom, nowNanos, true);
        }

        boolean sameTarget = Math.abs(left - mTargetLeft) < 0.25f
                && Math.abs(top - mTargetTop) < 0.25f
                && Math.abs(right - mTargetRight) < 0.25f
                && Math.abs(bottom - mTargetBottom) < 0.25f;

        if (sameTarget) {
            mVisible = true;
            return mAnimating;
        }

        if (mAnimating) {
            step(nowNanos);
        }

        float oldCenterX = (mTargetLeft + mTargetRight) * 0.5f;
        float oldCenterY = (mTargetTop + mTargetBottom) * 0.5f;
        float newCenterX = (left + right) * 0.5f;
        float newCenterY = (top + bottom) * 0.5f;
        float moveDx = newCenterX - oldCenterX;
        float moveDy = newCenterY - oldCenterY;
        float minJumpX = cellWidth * 0.5f;
        float minJumpY = cellHeight * 0.5f;

        if (!mAnimating && Math.abs(moveDx) < minJumpX && Math.abs(moveDy) < minJumpY) {
            snapTo(left, top, right, bottom, nowNanos);
            return false;
        }

        boolean movedDownToColZero = !altScreen
                && sourceTopRow == 0
                && atColZero
                && mTargetLeft >= cellWidth * 0.5f
                && mTargetRight < (columns - 0.5f) * cellWidth
                && (top > mTargetTop + cellHeight * 0.5f || scrolledRows > 0);
        if (movedDownToColZero) {
            if (!mAnimating) {
                setRect(mTrailX, mTrailY, mTargetLeft, mTargetTop, mTargetRight, mTargetBottom);
            }
            clearVelocities();
            mAnimating = false;
            mHoldingColZero = true;
            mHoldStartNanos = nowNanos;
            mLastStepNanos = nowNanos;
            mPendingLeft = left;
            mPendingTop = top;
            mPendingRight = right;
            mPendingBottom = bottom;
            mVisible = true;
            mHasAnchor = true;
            rebuildHull();
            return true;
        }

        boolean freshLaunch = !mAnimating;
        if (freshLaunch) {
            setRect(mTrailX, mTrailY, mTargetLeft, mTargetTop, mTargetRight, mTargetBottom);
            clearVelocities();
        }

        return launchFromTrail(left, top, right, bottom, nowNanos, freshLaunch);
    }

    public boolean step(long nowNanos) {
        if (mHoldingColZero) {
            if (mHiddenSinceNanos != 0L && nowNanos - mHiddenSinceNanos > HIDE_GRACE_NANOS) {
                reset();
                return false;
            }
            if (nowNanos - mHoldStartNanos < COL_ZERO_HOLD_NANOS) {
                mLastStepNanos = nowNanos;
                return true;
            }
            mHoldingColZero = false;
            mHoldStartNanos = 0L;
            return launchFromTrail(mPendingLeft, mPendingTop, mPendingRight, mPendingBottom, nowNanos, true);
        }

        if (!mAnimating) {
            mHullCount = 0;
            return false;
        }
        if (mHiddenSinceNanos != 0L && nowNanos - mHiddenSinceNanos > HIDE_GRACE_NANOS) {
            mVisible = false;
            mAnimating = false;
            mHasAnchor = false;
            mHullCount = 0;
            clearVelocities();
            return false;
        }

        long deltaNanos = nowNanos - mLastStepNanos;
        if (deltaNanos <= 0L) {
            deltaNanos = FALLBACK_STEP_NANOS;
        }
        mLastStepNanos = nowNanos;
        float dt = Math.max(MIN_DT_SECONDS, Math.min(MAX_DT_SECONDS, deltaNanos * 1e-9f));

        if (mBurstHeat > 0f && mLastMoveNanos != 0L && nowNanos - mLastMoveNanos > BURST_RELEASE_IDLE_NANOS) {
            mBurstHeat *= (float) Math.exp(-dt / BURST_RELEASE_TAU_SECONDS);
            if (mBurstHeat < 0.01f) mBurstHeat = 0f;
            updateActiveDurations();
        }

        updateMoveDirection();
        advanceCorners(dt);

        if (maxCornerDistance() <= CONVERGENCE_EPSILON_PX
                && maxCornerSpeed() <= CONVERGENCE_SPEED_PX_PER_SEC) {
            for (int i = 0; i < 4; i++) {
                mTrailX[i] = mTargetX[i];
                mTrailY[i] = mTargetY[i];
            }
            clearVelocities();
            mAnimating = false;
            mHullCount = 0;
            mStretchFactor = 0f;
            return false;
        }

        rebuildHull();
        return true;
    }

    private boolean launchFromTrail(
            float left,
            float top,
            float right,
            float bottom,
            long nowNanos,
            boolean freshLaunch) {
        float prevTargetCx = (mTargetLeft + mTargetRight) * 0.5f;
        float prevTargetCy = (mTargetTop + mTargetBottom) * 0.5f;
        float nextTargetCx = (left + right) * 0.5f;
        float nextTargetCy = (top + bottom) * 0.5f;
        updateBurstHeat(nextTargetCx - prevTargetCx, nextTargetCy - prevTargetCy, nowNanos);

        setTarget(left, top, right, bottom);
        dampOpposingVelocities();
        mVisible = true;
        mHasAnchor = true;
        mLastStepNanos = nowNanos;
        updateMoveDirection();
        updateActiveDurations();

        if (maxCornerDistance() <= CONVERGENCE_EPSILON_PX) {
            snapTo(left, top, right, bottom, nowNanos);
            return false;
        }

        mAnimating = true;
        if (freshLaunch) {
            advanceCorners(LAUNCH_STEP_SECONDS);
            if (maxCornerDistance() <= CONVERGENCE_EPSILON_PX
                    && maxCornerSpeed() <= CONVERGENCE_SPEED_PX_PER_SEC) {
                snapTo(left, top, right, bottom, nowNanos);
                return false;
            }
        }
        rebuildHull();
        return true;
    }

    private void updateBurstHeat(float stepDx, float stepDy, long nowNanos) {
        int signX = Math.abs(stepDx) > 0.5f ? (stepDx > 0f ? 1 : -1) : 0;
        int signY = Math.abs(stepDy) > 0.5f ? (stepDy > 0f ? 1 : -1) : 0;
        boolean axisAligned = (signX != 0 && signY == 0) || (signY != 0 && signX == 0);
        if (mLastMoveNanos != 0L && nowNanos > mLastMoveNanos && axisAligned) {
            float gapSec = (nowNanos - mLastMoveNanos) * 1e-9f;
            if (gapSec <= BURST_WINDOW_SECONDS) {
                float decayed = mBurstHeat * (float) Math.exp(-gapSec / BURST_DECAY_SECONDS);
                if (signX == mLastMoveSignX && signY == mLastMoveSignY) {
                    mBurstHeat = Math.min(1f, decayed + BURST_STEP_GAIN);
                } else {
                    mBurstHeat = Math.min(0.25f, decayed * 0.5f);
                }
            } else {
                mBurstHeat = 0f;
            }
        } else {
            mBurstHeat = 0f;
        }
        mLastMoveNanos = nowNanos;
        mLastMoveSignX = signX;
        mLastMoveSignY = signY;
    }

    private void dampOpposingVelocities() {
        for (int i = 0; i < 4; i++) {
            float dx = mTargetX[i] - mTrailX[i];
            if (mVelX[i] * dx < 0f) {
                mVelX[i] *= 0.25f;
            }
            float dy = mTargetY[i] - mTrailY[i];
            if (mVelY[i] * dy < 0f) {
                mVelY[i] *= 0.25f;
            }
        }
    }

    private void updateActiveDurations() {
        float trailCx = (mTrailX[0] + mTrailX[1] + mTrailX[2] + mTrailX[3]) * 0.25f;
        float trailCy = (mTrailY[0] + mTrailY[1] + mTrailY[2] + mTrailY[3]) * 0.25f;
        float targetCx = (mTargetLeft + mTargetRight) * 0.5f;
        float targetCy = (mTargetTop + mTargetBottom) * 0.5f;
        float dxCells = mCellWidth > 0f ? Math.abs(targetCx - trailCx) / mCellWidth : 0f;
        float dyCells = mCellHeight > 0f ? Math.abs(targetCy - trailCy) / mCellHeight : 0f;
        boolean crossLine = dyCells >= 0.5f;
        float distCells = (float) Math.hypot(dxCells, dyCells * 1.6f);
        float blend = Math.max(0f, Math.min(1f, (distCells - 1.2f) / 2.8f));
        if (crossLine) {
            float lineBlend = Math.min(1f, 0.72f + Math.max(0f, dyCells - 1f) * 0.14f);
            if (lineBlend > blend) blend = lineBlend;
        }
        mActiveFastSeconds = mShortFastSeconds + (mLongFastSeconds - mShortFastSeconds) * blend;
        float baseSlow = mShortSlowSeconds + (mLongSlowSeconds - mShortSlowSeconds) * blend;
        float crossLineExtra = crossLine
                ? DEFAULT_CROSS_LINE_EXTRA_SLOW_SECONDS * Math.min(1f, 0.55f + (dyCells - 0.5f) * 0.35f)
                : 0f;
        mActiveSlowSeconds = baseSlow + crossLineExtra + DEFAULT_BURST_EXTRA_SLOW_SECONDS * mBurstHeat;
    }

    private void shiftVertical(float deltaY) {
        for (int i = 0; i < 4; i++) {
            mTrailY[i] += deltaY;
            mTargetY[i] += deltaY;
        }
        mTargetTop += deltaY;
        mTargetBottom += deltaY;
    }

    private void clearVelocities() {
        for (int i = 0; i < 4; i++) {
            mVelX[i] = 0f;
            mVelY[i] = 0f;
        }
    }

    private void advanceCorners(float dt) {
        float minProj = Float.POSITIVE_INFINITY;
        float maxProj = Float.NEGATIVE_INFINITY;
        float[] projections = mCornerProjections;
        for (int i = 0; i < 4; i++) {
            float p = CORNER_SIGN_X[i] * mMoveDirX + CORNER_SIGN_Y[i] * mMoveDirY;
            projections[i] = p;
            if (p < minProj) minProj = p;
            if (p > maxProj) maxProj = p;
        }

        float projRange = maxProj - minProj;
        for (int i = 0; i < 4; i++) {
            float rank = projRange > 1e-4f ? (projections[i] - minProj) / projRange : 1f;
            float shaped = (float) Math.pow(rank, SPRING_RANK_EXPONENT);
            float duration = mActiveSlowSeconds + (mActiveFastSeconds - mActiveSlowSeconds) * shaped;
            float omega = SPRING_OMEGA_SCALE / Math.max(0.008f, duration);
            float exp = (float) Math.exp(-omega * dt);

            float x0 = mTrailX[i] - mTargetX[i];
            float c1x = mVelX[i] + omega * x0;
            mTrailX[i] = mTargetX[i] + (x0 + c1x * dt) * exp;
            mVelX[i] = (mVelX[i] - omega * c1x * dt) * exp;

            float y0 = mTrailY[i] - mTargetY[i];
            float c1y = mVelY[i] + omega * y0;
            mTrailY[i] = mTargetY[i] + (y0 + c1y * dt) * exp;
            mVelY[i] = (mVelY[i] - omega * c1y * dt) * exp;
        }
    }

    private void snapTo(float left, float top, float right, float bottom, long nowNanos) {
        setTarget(left, top, right, bottom);
        setRect(mTrailX, mTrailY, left, top, right, bottom);
        clearVelocities();
        mHasAnchor = true;
        mVisible = true;
        mAnimating = false;
        mHoldingColZero = false;
        mHoldStartNanos = 0L;
        mLastStepNanos = nowNanos;
        mHullCount = 0;
        mAxisCellExtent = 1f;
        mStretchFactor = 0f;
        mBurstHeat = 0f;
        mLastMoveNanos = 0L;
        mLastMoveSignX = 0;
        mLastMoveSignY = 0;
    }

    private void setTarget(float left, float top, float right, float bottom) {
        mTargetLeft = left;
        mTargetTop = top;
        mTargetRight = right;
        mTargetBottom = bottom;
        setRect(mTargetX, mTargetY, left, top, right, bottom);
    }

    private static void setRect(float[] xs, float[] ys, float left, float top, float right, float bottom) {
        xs[0] = left;
        ys[0] = top;
        xs[1] = right;
        ys[1] = top;
        xs[2] = right;
        ys[2] = bottom;
        xs[3] = left;
        ys[3] = bottom;
    }

    private void updateMoveDirection() {
        float trailCx = (mTrailX[0] + mTrailX[1] + mTrailX[2] + mTrailX[3]) * 0.25f;
        float trailCy = (mTrailY[0] + mTrailY[1] + mTrailY[2] + mTrailY[3]) * 0.25f;
        float targetCx = (mTargetLeft + mTargetRight) * 0.5f;
        float targetCy = (mTargetTop + mTargetBottom) * 0.5f;
        float dx = targetCx - trailCx;
        float dy = targetCy - trailCy;
        float length = (float) Math.hypot(dx, dy);
        if (length > 1e-3f) {
            mMoveDirX = dx / length;
            mMoveDirY = dy / length;
        }
    }

    private float maxCornerDistance() {
        float max = 0f;
        for (int i = 0; i < 4; i++) {
            float d = (float) Math.hypot(mTargetX[i] - mTrailX[i], mTargetY[i] - mTrailY[i]);
            if (d > max) max = d;
        }
        return max;
    }

    private float maxCornerSpeed() {
        float max = 0f;
        for (int i = 0; i < 4; i++) {
            float s = (float) Math.hypot(mVelX[i], mVelY[i]);
            if (s > max) max = s;
        }
        return max;
    }

    private void rebuildHull() {
        for (int i = 0; i < 4; i++) {
            mSortedIndices[i] = i;
        }

        for (int i = 1; i < 4; i++) {
            int key = mSortedIndices[i];
            float kx = mTrailX[key];
            float ky = mTrailY[key];
            int j = i - 1;
            while (j >= 0) {
                int cur = mSortedIndices[j];
                float cx = mTrailX[cur];
                float cy = mTrailY[cur];
                if (cx < kx || (cx == kx && cy <= ky)) break;
                mSortedIndices[j + 1] = cur;
                j--;
            }
            mSortedIndices[j + 1] = key;
        }

        int uniqueCount = 0;
        for (int i = 0; i < 4; i++) {
            int idx = mSortedIndices[i];
            if (uniqueCount == 0
                    || Math.abs(mTrailX[idx] - mTrailX[mSortedIndices[uniqueCount - 1]]) > 0.05f
                    || Math.abs(mTrailY[idx] - mTrailY[mSortedIndices[uniqueCount - 1]]) > 0.05f) {
                mSortedIndices[uniqueCount++] = idx;
            }
        }

        if (uniqueCount < 3) {
            mHullCount = 0;
            return;
        }

        int k = 0;
        for (int i = 0; i < uniqueCount; i++) {
            int idx = mSortedIndices[i];
            while (k >= 2 && cross(mHullIndices[k - 2], mHullIndices[k - 1], idx) <= 1e-3f) {
                k--;
            }
            mHullIndices[k++] = idx;
        }

        int lowerSize = k + 1;
        for (int i = uniqueCount - 2; i >= 0; i--) {
            int idx = mSortedIndices[i];
            while (k >= lowerSize && cross(mHullIndices[k - 2], mHullIndices[k - 1], idx) <= 1e-3f) {
                k--;
            }
            mHullIndices[k++] = idx;
        }

        int count = Math.min(4, k - 1);
        if (count < 3) {
            mHullCount = 0;
            return;
        }

        mHullCount = count;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float minProj = Float.POSITIVE_INFINITY;
        float maxProj = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < count; i++) {
            int idx = mHullIndices[i];
            float x = mTrailX[idx];
            float y = mTrailY[idx];
            mHullX[i] = x;
            mHullY[i] = y;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            float proj = x * mMoveDirX + y * mMoveDirY;
            if (proj < minProj) minProj = proj;
            if (proj > maxProj) maxProj = proj;
        }

        mBoundsLeft = minX;
        mBoundsTop = minY;
        mBoundsRight = maxX;
        mBoundsBottom = maxY;
        mAxisMinProj = minProj;
        mAxisSpan = Math.max(1f, maxProj - minProj);

        float targetMinProj = Float.POSITIVE_INFINITY;
        float targetMaxProj = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float tp = mTargetX[i] * mMoveDirX + mTargetY[i] * mMoveDirY;
            if (tp < targetMinProj) targetMinProj = tp;
            if (tp > targetMaxProj) targetMaxProj = tp;
        }
        float cellExtent = Math.max(1f, targetMaxProj - targetMinProj);
        mAxisCellExtent = cellExtent;
        mStretchFactor = Math.max(0f, Math.min(1f, (mAxisSpan - cellExtent) / Math.max(cellExtent * 1.15f, 1f)));
    }

    private float cross(int o, int a, int b) {
        return (mTrailX[a] - mTrailX[o]) * (mTrailY[b] - mTrailY[o])
                - (mTrailY[a] - mTrailY[o]) * (mTrailX[b] - mTrailX[o]);
    }
}
