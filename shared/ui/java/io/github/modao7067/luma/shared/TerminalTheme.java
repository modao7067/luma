package io.github.modao7067.luma.shared;

import com.termux.terminal.TerminalColors;
import java.util.Locale;
import java.util.Properties;

final class TerminalTheme {
    static final int DEFAULT_BACKGROUND = 0xff171d2b;

    static void initialize() {
        int[] palette={0xff353b4b,0xffed8796,0xffa6da95,0xffeed49f,0xff8aadf4,0xffc6a0f6,0xff8bd5ca,0xffcad3f5,0xff6e738d,0xffed8796,0xffa6da95,0xffeed49f,0xff8aadf4,0xffc6a0f6,0xff8bd5ca,0xfff4f7ff};
        Properties colors=new Properties();
        for(int i=0;i<palette.length;i++)colors.setProperty("color"+i,hex(palette[i]));
        colors.setProperty("foreground",hex(0xffe4eaf5));
        colors.setProperty("background",hex(DEFAULT_BACKGROUND));
        colors.setProperty("cursor",hex(0xffb8ddd8));
        TerminalColors.COLOR_SCHEME.updateWith(colors);
    }

    private static String hex(int color){return String.format(Locale.ROOT,"#%06x",color&0xffffff);}
}
