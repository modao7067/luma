package com.termux.view;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Looper;
import com.termux.terminal.*;
import java.nio.file.*;
public final class LegacyPixelOracle {
    static void save(String name,byte[] pixels)throws Exception{Files.write(Path.of(out,name),pixels);}static String out;
    static void saveMode(String name,int[] words)throws Exception{java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(320).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(int word:words)b.putInt(word);save(name,b.array());}
    public static void main(String[]args)throws Exception{
        Looper.prepareMainLooper();Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        AssetManager a=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(a,args[0]);BundledTerminalFonts.initialize(a);
        ViewViewportCheck.context=ViewViewportCheck.headlessContext(args[0]);out=args[1];
        TerminalSession s=ViewViewportCheck.session(false);TerminalView v=ViewViewportCheck.makeView(s);
        ViewViewportCheck.feed(s,"ASCII 中文 ABC\r\nsecond line\r\n");s.getEmulator().setCursorBlinkingEnabled(false);v.onScreenUpdated();
        save("oracle-ascii-soft.rgba",ViewViewportCheck.draw(v));save("oracle-ascii-gpu.rgba",ViewViewportCheck.hardware(v));s.disposeEmulator();
        s=ViewViewportCheck.session(false);v=ViewViewportCheck.makeView(s);int cols=s.getEmulator().mColumns;
        ViewViewportCheck.feed(s,"\033[?25l\033[2J\033[H\033[48;2;48;54;65m\033[2K\033[2;1H\033[2K> Ask Codex to do anything\033[3;1H\033[2K\033[0m");v.onScreenUpdated();
        save("oracle-background-soft.rgba",ViewViewportCheck.draw(v));save("oracle-background-gpu.rgba",ViewViewportCheck.hardware(v));
        ViewViewportCheck.feed(s,"\033[4;1H\033[48;5;42m\033[2K\033[5;1H"+" ".repeat(cols)+"\033[0m\033]4;42;#204060\007");
        save("oracle-palette-soft.rgba",ViewViewportCheck.draw(v));s.disposeEmulator();
        s=ViewViewportCheck.session(false);
        ViewViewportCheck.feed(s,"\033[3;4H\0337\033[?1049h\033[?1;1000;1006;2004h\033[?25l\033[4;5H\0337\033]133;C\007");
        int[] mode=s.getEmulator().exportModeState();mode[70]=s.getCommandState();saveMode("legacy-full.bin",mode);s.disposeEmulator();
        s=ViewViewportCheck.session(false);ViewViewportCheck.feed(s,"\033[?10");saveMode("legacy-csi.bin",s.getEmulator().exportModeState());s.disposeEmulator();
        s=ViewViewportCheck.session(false);ViewViewportCheck.feed(s,"\033[0m");
        s.getEmulator().append(new byte[]{(byte)0xf0,(byte)0x9f},2);saveMode("legacy-utf8.bin",s.getEmulator().exportModeState());s.disposeEmulator();
        System.out.println("REFERENCE PIXELS OK: frozen Java, isolated process, no product classes or PTY");System.exit(0);
    }
}
