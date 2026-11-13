package com.termux.view;

/** Opt-in joined copy: concatenate nonblank lines without inventing separator characters. */
public final class TerminalTextCopy {
    private TerminalTextCopy() {}

    public static String joinLines(String text) {
        if (text.indexOf('\n') < 0) return text;
        String[] lines = text.split("\n", -1);
        String previous = lines[0].trim();
        StringBuilder joined = new StringBuilder(previous);
        for (int i = 1; i < lines.length; i++) {
            String current = lines[i].trim();
            if (previous.isEmpty() || current.isEmpty()) {
                joined.append('\n');
            }
            joined.append(current);
            previous = current;
        }
        return joined.toString();
    }
}
