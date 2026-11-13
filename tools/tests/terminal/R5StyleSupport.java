package com.termux.terminal;

public final class R5StyleSupport {
    private R5StyleSupport() {}

    public static long normal() {
        return TextStyle.NORMAL;
    }

    public static long encode(int foreColor, int backColor, int effect) {
        return TextStyle.encode(foreColor, backColor, effect);
    }

    public static long[] styles(TerminalRow row, int columns) {
        long[] copy = new long[columns];
        for (int i = 0; i < columns; i++) copy[i] = row.getStyle(i);
        return copy;
    }
}
