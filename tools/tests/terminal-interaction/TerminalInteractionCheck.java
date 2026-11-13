package io.github.modao7067.luma.shared;

import com.termux.view.TerminalTextCopy;

public final class TerminalInteractionCheck {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void joined(String input, String expected) {
        String actual = TerminalTextCopy.joinLines(input);
        check(actual.equals(expected), "joined copy: expected <" + expected + "> got <" + actual + ">");
    }

    public static void main(String[] args) {
        joined("", "");
        joined("  one line  ", "  one line  ");
        joined("这是连续文字\n不应该出现换行", "这是连续文字不应该出现换行");
        joined("  第一行\n    第二行", "第一行第二行");
        joined("English words\ncontinue here", "English wordscontinue here");
        joined("First sentence.\nNext sentence.", "First sentence.Next sentence.");
        joined("hello\n, world", "hello, world");
        joined("(\ntext\n)", "(text)");
        joined("第一段\n续行\n\n第二段\n续行", "第一段续行\n\n第二段续行");
        joined("paragraph\n   \nnext paragraph", "paragraph\n\nnext paragraph");
        joined("a\n\n\nb", "a\n\n\nb");
        joined("\n第一行\n第二行\n", "\n第一行第二行\n");
        joined("漢字\nかな\nカナ\n한글", "漢字かなカナ한글");
        joined("\ud840\udc00\n中文", "\ud840\udc00中文");
        joined("你好，\n世界。", "你好，世界。");
        joined("Emoji \ud83d\ude00\ncontinues", "Emoji \ud83d\ude00continues");
        joined("e\u0301\nword", "e\u0301word");
        String uuid = "12345678-1234-5678-9abc-123456789abc";
        String prefix = "https://example.invalid/login?state=";
        String url = prefix + uuid;
        joined(url, url);
        for (int split = 1; split < uuid.length(); split++) {
            joined(prefix + uuid.substring(0, split) + "\n" + uuid.substring(split), url);
        }
        joined("  curl  --header 'a  b'\n   https://example.invalid/path", "curl  --header 'a  b'https://example.invalid/path");
        joined("abc\ndef\nghi", "abcdefghi");
        joined("a  b\nc  d", "a  bc  d");
        check(TerminalFontSize.DEFAULT == 11f, "default remains 11");
        for (float density : new float[]{0.75f, 1f, 1.5f, 2.625f, 3.5f}) {
            int termuxMinimum = Math.max(1, (int) (4f * density));
            float size = TerminalFontSize.DEFAULT;
            for (int i = 0; i < 300; i++) size = TerminalFontSize.scale(size, 0.9f, density);
            check(TerminalFontSize.pixels(size, density) == termuxMinimum,
                    "minimum matches Termux physical pixels at density " + density);
            for (int i = 0; i < 300; i++) size = TerminalFontSize.scale(size, 1.1f, density);
            check(TerminalFontSize.pixels(size, density) == 256,
                    "upper limit remains drawable at density " + density);
            check(TerminalFontSize.clamp(16f, density) == 16f, "existing preference retained");
            check(TerminalFontSize.clamp(Float.NaN, density) == 11f, "invalid preference restores default");
            float fine = 11f;
            for (int i = 0; i < 100; i++) fine = TerminalFontSize.scale(fine, 1.001f, density);
            check(Math.abs(fine - 11 * Math.pow(1.001f, 100)) < 0.001,
                    "small gesture deltas accumulate continuously");
            float restored = TerminalFontSize.scale(TerminalFontSize.scale(11, 1.1f, density), 1f / 1.1f, density);
            check(Math.abs(restored - 11) < 0.0001, "scale can return without integer rounding drift");
            check(TerminalFontSize.scale(11, Float.NaN, density) == 11, "invalid scale ignored");
            check(TerminalFontSize.scale(11, 0, density) == 11, "zero scale ignored");
        }
        System.out.println("ALL PASS " + checks + " joined-copy and pinch-font checks");
    }
}
