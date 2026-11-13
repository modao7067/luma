package com.termux.view;
import android.content.res.AssetManager;
import android.graphics.*;
import android.os.Looper;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.*;
import java.util.Arrays;

public final class NativeStreamCheck {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void feed(TerminalSession session,String text){ViewViewportCheck.feed(session,text);}
    static String row(TerminalSession session,int row){TerminalRenderFrame f=session.getEmulator().getRenderFrame();return f.screen.getSelectedText(0,row,f.columns-1,row).trim();}
    public static void main(String[]args)throws Exception{
        try{
            Looper.prepareMainLooper();Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[1]);BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(args[0]);ViewViewportCheck.context=ViewViewportCheck.headlessContext(args[3]);
            TerminalSession session=ViewViewportCheck.session(true);TerminalView view=ViewViewportCheck.makeView(session);
            feed(session,"\033[?25l\033[2J\033[HA0\r\nB0\r\nC0");ViewViewportCheck.draw(view);
            feed(session,"\033[2;1HB1\033[?2026h");
            check(row(session,1).equals("B1"),"hold captures an update arriving before the hold in the same packet");
            feed(session,"\033[3;1HC1\033[?2026l");
            check(row(session,1).equals("B1")&&row(session,2).equals("C1"),"release does not revert untouched rows to an older live snapshot");
            feed(session,"\033[?2026h\033[1;1HA2\033[?2026l\033[?2026h\033[2;1HB2");
            check(row(session,0).equals("A2")&&row(session,1).equals("B1")&&row(session,2).equals("C1"),"same-packet consecutive groups keep all previously committed rows");
            feed(session,"\033[?2026l");check(row(session,1).equals("B2"),"second group releases its own changed row");
            byte[] committed=ViewViewportCheck.hardware(view);String last="B2";
            for(int n=0;n<12;n++){
                feed(session,"\033[?2026h\033[2;1H\033[2K");view.onScreenUpdated();
                check(row(session,1).equals(last),"next hold retains the latest committed row rather than an older held row "+n);
                check(Arrays.equals(committed,ViewViewportCheck.hardware(view)),"GPU never paints the cleared half-frame "+n);
                String next="stream-frame-"+n;
                for(int i=0;i<next.length();i+=3){feed(session,next.substring(i,Math.min(next.length(),i+3)));view.onScreenUpdated();check(row(session,1).equals(last),"fragment stays hidden until release "+n+"/"+i);}
                feed(session,"\033[?2026l");view.onScreenUpdated();
                check(row(session,1).equals(next)&&row(session,2).equals("C1"),"completed frame preserves unchanged neighboring rows "+n);
                byte[] now=ViewViewportCheck.hardware(view);check(!Arrays.equals(now,committed),"GPU commits the completed stream frame "+n);committed=now;last=next;
            }
            session.disposeEmulator();System.out.println("NATIVE STREAM OK checks="+checks);System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
