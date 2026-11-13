package com.termux.view;

public class ScrollChecks {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static void near(float a, float b, String message) {
        check(Math.abs(a - b) < 0.001f, message + ": " + a + " vs " + b);
    }
    public static void main(String[] args) {
        for (float density : new float[] {1f, 2f, 2.75f, 4f}) {
            float distance = -7f * density;
            near(TerminalScrollSpeed.scaleDistance(distance, 100f * density, density), distance,
                    "slow drag stays one to one");
            check(TerminalScrollSpeed.scaleDistance(distance, 2000f * density, density) < distance * 3,
                    "fast movement visibly exceeds slow movement before release");
            near(TerminalScrollSpeed.scaleDistance(distance, 50000f * density, density), distance * 4,
                    "extreme speed remains bounded");
            near(TerminalScrollSpeed.scaleDistance(distance, -2000f * density, density), distance,
                    "old opposite velocity does not boost a reversal");
            near(TerminalScrollSpeed.scaleDistance(distance, 0f, density), distance,
                    "stopped speed does not boost");
            float previous = 1f;
            for (int speed = 0; speed <= 2000; speed += 100) {
                float scaled = TerminalScrollSpeed.scaleDistance(distance, speed * density, density);
                float gain = scaled / distance;
                check(gain >= previous && gain >= 1f && gain <= 4f, "gain is monotonic and bounded");
                near(scaled / density, TerminalScrollSpeed.scaleDistance(-7f, speed, 1f),
                        "density-independent physical gesture");
                near(scaled, -TerminalScrollSpeed.scaleDistance(-distance, -speed * density, density),
                        "same response in both directions");
                previous = gain;
            }
        }
        near(TerminalScrollSpeed.scaleDistance(-5f, Float.NaN, 1f), -5f, "invalid velocity ignored");
        near(TerminalScrollSpeed.scaleDistance(-5f, Float.POSITIVE_INFINITY, 1f), -5f, "infinite velocity ignored");
        near(TerminalScrollSpeed.scaleDistance(0f, 2000f, 1f), 0f, "zero distance stays zero");
        System.out.println("PASS drag speed policy: " + checks + " assertions");
    }
}
