package com.termux.terminal;

import java.util.*;
import java.util.regex.*;

/** Standard OSC 8 links only. Visible text, colors and filenames never create a link. */
public final class TerminalLinks {
    private static final String EXT="(?:png|jpe?g|webp|gif|bmp|avif|heic|pdf|apk|md|markdown|txt|log|jsonl?|ya?ml|csv|tsv|xml|html?|css|js|ts|java|py|sh|toml|ini|conf|c|h|cpp|rs|go|sql)";
    private static final Pattern SCHEME=Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
    private static final Pattern COMPLETE_FILE=Pattern.compile(".*\\."+EXT+"(?:(?::[0-9]+(?::[0-9]+)?)|#L[0-9A-Za-z-]+)?[)\\]}]*$",Pattern.CASE_INSENSITIVE);
    private TerminalLinks(){}
    public static boolean isSystemUri(String value){
        if(value==null)return false;Matcher m=SCHEME.matcher(value);if(!m.find())return false;
        String scheme=m.group(1).toLowerCase(Locale.ROOT);
        if(Arrays.asList("file","javascript","data","intent","about").contains(scheme))return false;
        return value.substring(m.end()).startsWith("//")||Arrays.asList("mailto","tel","sms","smsto","geo","market").contains(scheme);
    }
    /** Validate a supplied OSC 8 destination; never search terminal text for one. */
    public static boolean isOpenable(String value){
        if(value==null||value.isEmpty())return false;
        if(value.matches("(?is)^(?:javascript|data|intent|about):.*"))return false;
        if(COMPLETE_FILE.matcher(value).matches())return true;
        Matcher scheme=SCHEME.matcher(value);
        if(scheme.find())return scheme.group(1).equalsIgnoreCase("file")||isSystemUri(value);
        return value.startsWith("/")||value.startsWith("~/")||value.startsWith("./")||value.startsWith("../")||value.indexOf('/')>0;
    }
}
