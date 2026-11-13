package io.github.modao7067.luma.gui.ui;

import kotlin.text.Regex;
import kotlin.text.MatchResult;
import java.util.Iterator;

public final class HighlightArtCheck {
    private static final Regex LEGACY = new Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b(?:fun|val|var|class|return|if|else|for|while|import|package|public|private|static|void|def|from|const|let|function|async|await|true|false|null|None|try|catch|throw|interface|data|suspend)\\b|\\b\\d+(?:\\.\\d+)?\\b");
    private static int legacy(String text) {
        int count = 0;
        Iterator<MatchResult> tokens = LEGACY.findAll(text, 0).iterator();
        while (tokens.hasNext()) { MatchResult token = tokens.next(); token.getValue().charAt(0); count++; }
        return count;
    }
    private static int scan(String text, int limit) {
        int[] count = {0};
        CodeTokens.scan(text, false, limit, (start, end, kind) -> count[0]++, () -> {});
        return count[0];
    }
    public static void main(String[] args) {
        String unit = "val x = \"text\"; return 123 // comment\n";
        legacy(unit.repeat(20)); scan(unit.repeat(20), Integer.MAX_VALUE);
        for (int lines : new int[]{1000, 3000, 10000}) {
            String text = unit.repeat(lines);
            long before = System.nanoTime(); int old = legacy(text); long legacyNs = System.nanoTime() - before;
            before = System.nanoTime(); int all = scan(text, Integer.MAX_VALUE); long linearNs = System.nanoTime() - before;
            before = System.nanoTime(); int display = scan(text, CodeTokens.DISPLAY_SPAN_LIMIT); long displayNs = System.nanoTime() - before;
            if (old != all || display != Math.min(old, CodeTokens.DISPLAY_SPAN_LIMIT)) throw new AssertionError("token mismatch");
            System.out.printf("chars=%d tokens=%d legacyMs=%.3f linearMs=%.3f displayMs=%.3f%n", text.length(), old, legacyNs/1e6, linearNs/1e6, displayNs/1e6);
        }
        String unfinished = "\"" + "\\x".repeat(500000);
        long before = System.nanoTime(); scan(unfinished, CodeTokens.DISPLAY_SPAN_LIMIT);
        System.out.printf("unfinishedChars=%d linearMs=%.3f%n", unfinished.length(), (System.nanoTime()-before)/1e6);
        System.out.println("PASS Android ART/ICU legacy vs production scanner");
    }
}
