import android.content.res.AssetManager;
import android.graphics.*;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AnnotationCheck {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS "+message);}
    static void feed(TerminalEmulator e,String s){RenderComparison.feed(e,s);}
    public static void main(String[] args)throws Exception{
        System.setOut(new PrintStream(args[1]+"/annotation-check.txt"));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
            BundledTerminalFonts.initialize(assets);
            String sample="A\ufff9汉\ufffa注\ufffbZ";
            byte[] raw=sample.getBytes(StandardCharsets.UTF_8);
            Files.write(Path.of(args[1],"annotation-sample.bin"),raw);
            check(new String(Files.readAllBytes(Path.of(args[1],"annotation-sample.bin")),StandardCharsets.UTF_8).equals(sample),"raw UTF-8 bytes preserve all three markers");
            TerminalEmulator e=RenderComparison.emulator();
            for(byte b:raw)e.append(new byte[]{b},1);
            check(e.getCursorCol()==9,"fragmented UTF-8: three single-cell markers and two wide Han characters");
            check(e.getScreen().getSelectedText(0,0,8,0).equals(sample),"selection/copy retains original annotation markers and text");
            for(int cp=0xfff9;cp<=0xfffb;cp++)check(WcWidth.width(cp)==1,"one-cell width U+"+Integer.toHexString(cp));
            e.resize(6,10,12,24);
            check(e.getScreen().getTranscriptText().equals("A\ufff9汉\ufffa 注\ufffbZ"),"resize/reflow retains markers with expected wide-character padding");
            e=RenderComparison.emulator();e.resize(6,10,12,24);feed(e,"12345\ufff9X");
            check(e.getCursorRow()==1&&e.getCursorCol()==1,"last-column marker occupies one cell; following text wraps");
            check(e.getScreen().getSelectedText(0,0,0,1).equals("12345\ufff9X"),"copy across automatic wrap preserves the marker");
            e=RenderComparison.emulator();feed(e,"普通中文😀e\u0301");
            check(e.getScreen().getTranscriptText().equals("普通中文😀e\u0301"),"ordinary Han, emoji and combining accents remain unchanged");
            for(int cp=0xfff9;cp<=0xfffb;cp++){
                TerminalEmulator mark=RenderComparison.emulator();feed(mark,Character.toString(cp));
                TerminalEmulator blank=RenderComparison.emulator();
                Bitmap a=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888);
                Canvas ca=new Canvas(a),cb=new Canvas(b);ca.drawColor(Color.BLACK);cb.drawColor(Color.BLACK);
                new TerminalRenderer(22,BundledTerminalFonts.typeface()).render(mark,ca,0,-1,-1,-1,-1);
                new TerminalRenderer(22,BundledTerminalFonts.typeface()).render(blank,cb,0,-1,-1,-1,-1);
                check(!a.sameAs(b),"unsupported annotation marker remains visibly represented U+"+Integer.toHexString(cp));
                a.recycle();b.recycle();
            }
            System.out.println("ALL PASS: explicit samples; original CLI origin remains unknown");
        }catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
