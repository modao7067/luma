package io.github.modao7067.luma.gui.ui;

import java.util.Set;

public final class CodeTokens {
    public static final int STRING = 1, COMMENT = 2, NUMBER = 3, KEYWORD = 4,
            ADDED = 5, REMOVED = 6, HUNK = 7;
    public static final int DISPLAY_SPAN_LIMIT = 1024;
    private static final Set<String> KEYWORDS = Set.of("fun", "val", "var", "class", "return",
            "if", "else", "for", "while", "import", "package", "public", "private", "static",
            "void", "def", "from", "const", "let", "function", "async", "await", "true",
            "false", "null", "None", "try", "catch", "throw", "interface", "data", "suspend");
    private CodeTokens() {}
    public interface Sink { void token(int start, int end, int kind); }
    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    private static final class Cursor {
        final String text;
        final Runnable checkpoint;
        int at;
        Cursor(String text, Runnable checkpoint) { this.text = text; this.checkpoint = checkpoint; }
        boolean has() { return at < text.length(); }
        char get() { return text.charAt(at); }
        void step() { if ((++at & 4095) == 0) checkpoint.run(); }
    }
    public static void scan(String text, boolean diff, int limit, Sink sink, Runnable checkpoint) {
        checkpoint.run();
        Cursor c = new Cursor(text, checkpoint);
        int count = 0;
        while (c.has() && count < limit) {
            int start = c.at, kind = 0;
            char ch = c.get();
            if (diff) {
                kind = ch == '+' ? ADDED : ch == '-' ? REMOVED :
                        ch == '@' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '@' ? HUNK : 0;
                while (c.has() && c.get() != '\n' && c.get() != '\r') c.step();
                if (kind != 0) { sink.token(start, c.at, kind); count++; }
                if (c.has() && c.get() == '\r') c.step();
                if (c.has() && c.get() == '\n') c.step();
                continue;
            } else if (ch == '"' || ch == '\'') {
                kind = STRING;
                c.step();
                while (c.has()) {
                    char next = c.get(); c.step();
                    if (next == '\\' && c.has()) c.step();
                    else if (next == ch) break;
                }
            } else if (ch == '#' || (ch == '/' && c.at + 1 < text.length() && text.charAt(c.at + 1) == '/')) {
                kind = COMMENT;
                while (c.has() && c.get() != '\n') c.step();
            } else if (word(ch)) {
                c.step();
                while (c.has() && word(c.get())) c.step();
                boolean number = ch >= '0' && ch <= '9';
                for (int i = start + 1; number && i < c.at; i++) number = text.charAt(i) >= '0' && text.charAt(i) <= '9';
                if (number) {
                    kind = NUMBER;
                    if (c.at + 1 < text.length() && c.get() == '.' && Character.isDigit(text.charAt(c.at + 1))) {
                        c.step();
                        while (c.has() && Character.isDigit(c.get())) c.step();
                    }
                } else if (c.at - start <= 9 && KEYWORDS.contains(text.substring(start, c.at))) kind = KEYWORD;
            } else c.step();
            if (kind != 0) { sink.token(start, c.at, kind); count++; }
        }
    }
}
