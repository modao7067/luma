import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import java.io.*;
import java.nio.file.*;

public final class ScrollGlassCheck {
    private static final double OCTANT_GAP = 1.0 - Math.sqrt(0.5);
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final float[] SHINE_ENDS = {
        1f / 3f, 2f / 3f, 1f, 5f / 3f,
        7f / 3f, 3f, 11f / 3f, 13f / 3f,
        5f, 5f, 5f, 5f,
    };
    private static final float[] SHINE_SHARES = {
        1.0f, 0.7f, 0.47f, 0.18f,
        0.12f, 0.085f, 0.065f, 0.045f,
        0.035f, 0f, 0f, 0f,
    };

    private static float[] circleOctant(float halfSide, float radius) {
        double a = halfSide;
        double r = Math.min(radius, a);
        double n = 2.0;
        double jointAt = 1.0 - 1.0 / 1.13276676;
        double jx = jointAt * a;
        double jy = Math.pow(Math.max(0.0, 1.0 - Math.pow(jointAt, n)), 1.0 / n) * a;
        double tangent = jy > 1e-9 ? Math.pow(jx / jy, n - 1.0) : 0.0;
        double diagonal = (jx - tangent * jy) / (1.0 - tangent);
        double meet = a - OCTANT_GAP * r;
        double arc = (meet - diagonal) * SQRT2;
        double chordX = meet - jx;
        double chordY = meet - jy;
        double chordDistSq = chordX * chordX + chordY * chordY;
        double chordDist = Math.sqrt(chordDistSq);
        double reach = Math.sqrt(Math.max(0.0, arc * arc - chordDistSq * 0.25));
        double cx = (jx + meet) * 0.5 + (chordY / chordDist) * reach;
        double cy = (jy + meet) * 0.5 + (-chordX / chordDist) * reach;
        return new float[]{(float) n, (float) jx, (float) jy, (float) arc, (float) cx, (float) cy};
    }

    private static float luminance(int pixel) {
        return 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[1]);
        out.mkdirs();
        System.setOut(new PrintStream(new File(out, "scroll-gpu.txt")));
        try {
            String shaderSource = Files.readString(java.nio.file.Path.of(args[2]));
            final int W = GlassClipCheck.W;
            final int H = GlassClipCheck.H;
            final float density = 3.0f;
            final int w = 132;
            final int h = 132;
            final int x = (W - w) / 2;
            final int y = 220;
            final float halfDp = (w / density) * 0.5f;
            final float regularBlurDp = 2.5f;
            final int divisor = Math.max(1, Math.min(8, (int) Math.floor(regularBlurDp * density / 2.0f)));
            final float regularScale = 1.0f / divisor;
            final int regW = Math.max(1, (int) Math.ceil(W * regularScale));
            final int regH = Math.max(1, (int) Math.ceil(H * regularScale));
            final float frost = regularBlurDp * density * regularScale;
            final float softness = regularScale < 0.999f ? 0.5f : 0f;
            final float sigma = frost > softness ? (float) Math.sqrt(frost * frost - softness * softness) : 0f;
            final float blurRadius = Math.max(0.01f, (sigma - 0.5f) / 0.57735f);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            RenderNode scene = GlassClipCheck.node("reading-backdrop", 0, 0, W, H);
            RenderNode regularBackdrop = GlassClipCheck.node("regular-backdrop", 0, 0, regW, regH);
            regularBackdrop.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP));
            RenderNode sample = GlassClipCheck.node("glass-sample", 0, 0, w, h);
            RenderNode overlay = GlassClipCheck.node("return-to-bottom-overlay", x, y, w, h);
            RenderNode root = GlassClipCheck.node("root", 0, 0, W, H);

            float[] oct = circleOctant(halfDp, halfDp);
            RuntimeShader shader = new RuntimeShader(shaderSource);
            shader.setFloatUniform("uBoundsPx", (float) w, (float) h);
            shader.setFloatUniform("uDensity", density, 1.0f / density);
            shader.setFloatUniform("uPlatter", halfDp, halfDp, halfDp, halfDp);
            shader.setFloatUniform("uRadius", halfDp, 20.0f, 0.0f, 1.0f);
            shader.setFloatUniform("uAlong", oct[0], oct[1], oct[2], oct[3]);
            shader.setFloatUniform("uAcross", oct[0], oct[1], oct[2], oct[3]);
            shader.setFloatUniform("uCenters", oct[4], oct[5], oct[4], oct[5]);
            shader.setFloatUniform("uDroplet", 0f, 0f, 0f, 0f);
            shader.setFloatUniform("uOptics", 60.0f, 2.84f, 1.0f);
            shader.setFloatUniform("uTint", 0.20f, 0.20f, 0.22f, 0.62f);
            shader.setFloatUniform("uLight", 0.28f, 1.2f, 0.0f);
            shader.setFloatUniform("uGlow", 0f, 0f, 0f, -0.4f);
            shader.setFloatUniform("uGlowReach", 1.0f);
            shader.setFloatUniform("uRim", 0.17f, 1.0f, 0.38f, 0.75f);
            shader.setFloatUniform("uEdge", 0.0f, 0.0f, 0.0f, 0.0f);
            shader.setFloatUniform("uShineTurns", new float[8]);
            shader.setFloatUniform("uShineLit", new float[8]);
            shader.setFloatUniform("uShineEnds", SHINE_ENDS);
            shader.setFloatUniform("uShineShares", SHINE_SHARES);
            sample.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "scene"));

            int distinctInteriorSamples = 0;
            int prevInterior = 0;
            int rimBrighterFrames = 0;
            int modulatedFrames = 0;
            try (ImageReader reader = ImageReader.newInstance(
                    W, H, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
                HardwareRenderer renderer = new HardwareRenderer();
                renderer.setSurface(reader.getSurface());
                renderer.setContentRoot(root);
                renderer.setOpaque(true);
                for (int frame = 0; frame < 48; frame++) {
                    RecordingCanvas c = scene.beginRecording();
                    c.drawColor(0xff171421);
                    for (int row = 0; row < 12; row++) {
                        float top = row * 48 - (frame * 7 % 48);
                        paint.setColor(row % 2 == 0 ? 0xff4b557e : 0xff3b78d8);
                        c.drawRect(12, top, W - 12, top + 18, paint);
                    }
                    scene.endRecording();

                    c = root.beginRecording();
                    c.drawRenderNode(scene);
                    root.endRecording();
                    Bitmap clean = GlassClipCheck.capture(renderer, reader);
                    if (clean.getPixel(x + w / 2, y + h / 2) == 0xffd50000) {
                        throw new AssertionError("Overlay leaked into backdrop at frame " + frame);
                    }
                    int cleanCorner = clean.getPixel(x + 2, y + 2);
                    int cleanInterior = clean.getPixel(x + w / 2, y + h / 3);
                    clean.recycle();

                    c = regularBackdrop.beginRecording();
                    c.scale(regularScale, regularScale);
                    c.drawRenderNode(scene);
                    regularBackdrop.endRecording();

                    c = sample.beginRecording();
                    c.scale(1.0f / regularScale, 1.0f / regularScale);
                    c.translate(-x * regularScale, -y * regularScale);
                    c.drawRenderNode(regularBackdrop);
                    sample.endRecording();

                    c = overlay.beginRecording();
                    c.drawRenderNode(sample);
                    paint.setColor(0xffd50000);
                    c.drawCircle(w / 2f, h / 2f, 4f, paint);
                    overlay.endRecording();

                    boolean visible = frame % 4 != 0;
                    c = root.beginRecording();
                    c.drawRenderNode(scene);
                    if (visible) c.drawRenderNode(overlay);
                    root.endRecording();
                    Bitmap b = GlassClipCheck.capture(renderer, reader);
                    if (visible) {
                        int center = b.getPixel(x + w / 2, y + h / 2);
                        if (center != 0xffd50000) {
                            throw new AssertionError("Scroll control lost at frame " + frame + " pixel=" + Integer.toHexString(center));
                        }
                        int corner = b.getPixel(x + 2, y + 2);
                        if (corner != cleanCorner) {
                            throw new AssertionError("SDF corner bled at frame " + frame + " expected=" + Integer.toHexString(cleanCorner) + " got=" + Integer.toHexString(corner));
                        }
                        int interior = b.getPixel(x + w / 2, y + h / 3);
                        int rim = b.getPixel(x + w / 2, y + 4);
                        if (interior != cleanInterior) modulatedFrames++;
                        if (luminance(rim) > luminance(interior) + 6.0f) rimBrighterFrames++;
                        if (distinctInteriorSamples == 0 || interior != prevInterior) {
                            distinctInteriorSamples++;
                            prevInterior = interior;
                        }
                    }
                    if (frame == 47) {
                        try (FileOutputStream file = new FileOutputStream(new File(out, "scroll-overlay-gpu.png"))) {
                            b.compress(Bitmap.CompressFormat.PNG, 100, file);
                        }
                    }
                    b.recycle();
                }
                renderer.destroy();
            }
            if (modulatedFrames < 30) {
                throw new AssertionError("Prism glass shader did not modulate backdrop: modulatedFrames=" + modulatedFrames);
            }
            if (rimBrighterFrames < 30) {
                throw new AssertionError("Prism specular rim highlight missing: rimBrighterFrames=" + rimBrighterFrames);
            }
            if (distinctInteriorSamples < 4) {
                throw new AssertionError("Prism glass interior did not track scrolling backdrop: distinct=" + distinctInteriorSamples);
            }
            System.out.println("PASS: 96 HWUI frames; scrolling backdrop, overlay show/hide, production PRISM_GLASS_AGSL with downscaled backdrop blur, verified SDF corner clip, refraction/tint modulation (" + modulatedFrames + "), specular rim (" + rimBrighterFrames + "), scrolling variation (" + distinctInteriorSamples + "), isolated background and visible control pixels");
            System.out.println("Scope: Android hardware render graph, not installed-App gestures or Compose lifecycle.");
        } catch (Throwable e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
