package com.termux.view;

/** Apply speed gain during one-finger dragging; there is no release-time distance. */
final class TerminalScrollSpeed {
    private TerminalScrollSpeed() {}

    static float scaleDistance(float distance, float velocityY, float density) {
        // GestureDetector distance is opposite the finger velocity. Do not boost
        // a reversal while VelocityTracker is still reporting the old direction.
        if (!Float.isFinite(velocityY) || distance * velocityY >= 0f) return distance;
        float speedDp = Math.abs(velocityY) / density;
        // Slow positioning stays 1:1; ramp smoothly from 200 to 1200 dp/s, capped at 4x.
        float gain = 1f + 3f * Math.max(0f, Math.min(1f, (speedDp - 200f) / 1000f));
        return distance * gain;
    }
}
