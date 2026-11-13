package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.*;
import android.os.Looper;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.*;
import java.io.*;
import java.util.Arrays;

public final class NativeBackgroundCheck {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void feed(TerminalSession session,String text){ViewViewportCheck.feed(session,text);}
    static int bg(TerminalSession session,int x,int y){return TextStyle.decodeBackColor(session.getEmulator().getRenderFrame().screen.allocateFullLineIfNecessary(y).getStyle(x));}
    static int pixel(byte[] data,int x,int y){int i=(y*ViewViewportCheck.VW+x)*4;return(data[i+3]&255)<<24|(data[i]&255)<<16|(data[i+1]&255)<<8|(data[i+2]&255);}
    static void save(TerminalView view,String file)throws Exception{Bitmap image=Bitmap.createBitmap(ViewViewportCheck.VW,ViewViewportCheck.VH,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.BLACK);view.draw(canvas);try(FileOutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    public static void main(String[]args)throws Exception{
        try{
            Looper.prepareMainLooper();Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[1]);BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(args[0]);ViewViewportCheck.context=ViewViewportCheck.headlessContext(args[3]);
            TerminalSession nativeSession=ViewViewportCheck.session(true);
            TerminalView nativeView=ViewViewportCheck.makeView(nativeSession);
            int cols=nativeSession.getEmulator().mColumns;
            String prompt="\033[?25l\033[2J\033[H\033[48;2;48;54;65m\033[2K\033[2;1H\033[2K> Ask Codex to do anything\033[3;1H\033[2K\033[0m";
            feed(nativeSession,prompt);nativeView.onScreenUpdated();
            save(nativeView,args[2]+"/native-background.png");
            check(bg(nativeSession,cols-1,0)==0xff303641,"RGB erase fills the full blank top row of the prompt");
            check(bg(nativeSession,cols-1,1)==0xff303641,"prompt background extends beyond its text to the right edge");
            check(bg(nativeSession,cols-1,2)==0xff303641,"RGB erase fills the blank bottom row of the prompt");
            check(Arrays.equals(ViewViewportCheck.draw(nativeView),java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[2],"oracle-background-soft.rgba"))),"three-row Codex-style input box matches Java software pixels exactly");
            check(Arrays.equals(ViewViewportCheck.hardware(nativeView),java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[2],"oracle-background-gpu.rgba"))),"three-row Codex-style input box matches Java GPU pixels exactly");
            int x=Math.round((cols-0.5f)*nativeView.mRenderer.getFontWidth());
            int y=nativeView.mRenderer.mFontLineSpacingAndAscent+nativeView.mRenderer.getFontLineSpacing()/2;
            check(pixel(ViewViewportCheck.draw(nativeView),x,y)==0xff303641,"actual View paints an empty background-only cell");
            String palette="\033[4;1H\033[48;5;42m\033[2K\033[5;1H"+" ".repeat(cols)+"\033[0m\033]4;42;#204060\007";
            feed(nativeSession,palette);
            check(bg(nativeSession,cols-1,3)==0xff204060&&bg(nativeSession,cols-1,4)==0xff204060,"palette background-only cells resolve OSC4 updates for both erase and spaces");
            check(Arrays.equals(ViewViewportCheck.draw(nativeView),java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[2],"oracle-palette-soft.rgba"))),"RGB and palette blank background rendering remain pixel-equal");
            feed(nativeSession,"\033[?2026h\033[H\033[48;2;200;0;0m\033[2K");
            check(bg(nativeSession,cols-1,0)==0xff303641,"synchronized output retains the committed blank background");
            feed(nativeSession,"\033[?2026l");check(bg(nativeSession,cols-1,0)==0xffc80000,"release publishes the updated blank background");
            feed(nativeSession,"\033[0m\033[2J\033[H");
            check(bg(nativeSession,cols-1,0)==TextStyle.COLOR_INDEX_BACKGROUND,"default erase restores default background rather than retaining stale color");
            nativeSession.disposeEmulator();
            System.out.println("NATIVE BACKGROUND OK checks="+checks);System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
