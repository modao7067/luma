package com.termux.terminal;

import java.util.Locale;

public final class LumaLang {
    private static volatile String overrideLang;

    private LumaLang() {}

    public static void setOverride(String lang) {
        overrideLang = lang;
    }

    public static boolean isZh() {
        String ov = overrideLang;
        if (ov == null || ov.isEmpty()) ov = System.getProperty("luma.lang");
        if (ov == null || ov.isEmpty()) ov = System.getenv("LUMA_LANG");
        if (ov != null && !ov.isEmpty()) return ov.toLowerCase(Locale.ROOT).startsWith("zh");
        Locale loc = Locale.getDefault();
        return loc != null && "zh".equalsIgnoreCase(loc.getLanguage());
    }

    public static String code() {
        return isZh() ? "zh" : "en";
    }

    public static String tr(String zh, String en) {
        return isZh() ? zh : en;
    }
}
