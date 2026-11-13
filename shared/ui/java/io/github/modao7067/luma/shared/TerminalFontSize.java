package io.github.modao7067.luma.shared;

final class TerminalFontSize {
    static final float DEFAULT = 11f;
    private static final int MAX_PIXELS = 256;

    private TerminalFontSize() {}

    private static int minPixels(float density) {
        return Math.max(1, (int) (4f * density));
    }

    static float clamp(float size, float density) {
        if (!Float.isFinite(size) || size <= 0f) size = DEFAULT;
        return (float) Math.max(minPixels(density) / (double) density,
                Math.min(MAX_PIXELS / (double) density, size));
    }

    static float scale(float size, float factor, float density) {
        size = clamp(size, density);
        if (!Float.isFinite(factor) || factor <= 0f) return size;
        double scaled = size * (double) factor;
        return (float) Math.max(minPixels(density) / (double) density,
                Math.min(MAX_PIXELS / (double) density, scaled));
    }

    static int pixels(float size, float density) {
        return Math.max(minPixels(density), Math.min(MAX_PIXELS,
                Math.round(clamp(size, density) * density)));
    }
}
