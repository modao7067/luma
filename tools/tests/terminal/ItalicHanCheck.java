import android.content.res.AssetManager;
import android.graphics.*;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ItalicHanCheck {
    static final int BG=0xff202020;
    static int checks;
    static String directory;
    static TerminalEmulator sample(String text,String style,boolean defaultMatches){
        TerminalEmulator e=RenderComparison.emulator();e.resize(64,8,12,24);
        if(defaultMatches)e.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]=BG;
        RenderComparison.feed(e,"\033[2;3H\033[48;2;32;32;32;"+style+"m"+text+"\033[0m");
        return e;
    }
    static void save(byte[] pixels,String name)throws Exception{
        Bitmap b=Bitmap.createBitmap(CacheRegression.W,CacheRegression.H,Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        try(FileOutputStream f=new FileOutputStream(directory+"/"+name+".png")){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();
    }
    static byte[] gpu(CacheRegression.Target target,TerminalEmulator e,TerminalRenderer renderer,boolean selected){
        RecordingCanvas c=target.node.beginRecording(CacheRegression.W,CacheRegression.H);c.drawColor(BG);
        renderer.render(e,c,0,selected?1:-1,selected?1:-1,selected?3:-1,selected?10:-1);target.node.endRecording();return target.image();
    }
    static void scene(String text,String style,int size,String name)throws Exception{
        scene(text,style,size,name,false);
    }
    static void scene(String text,String style,int size,String name,boolean selected)throws Exception{
        TerminalEmulator expected=sample(text,style,true),actual=sample(text,style,false);
        if(selected){RenderComparison.feed(expected,"\033[?25h\033[2;5H");RenderComparison.feed(actual,"\033[?25h\033[2;5H");}
        TerminalRenderer ref=new TerminalRenderer(size,BundledTerminalFonts.typeface()),test=new TerminalRenderer(size,BundledTerminalFonts.typeface());
        Bitmap a=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888);
        Canvas ca=new Canvas(a),cb=new Canvas(b);ca.drawColor(BG);cb.drawColor(BG);
        ref.render(expected,ca,0,selected?1:-1,selected?1:-1,selected?3:-1,selected?10:-1);test.render(actual,cb,0,selected?1:-1,selected?1:-1,selected?3:-1,selected?10:-1);
        if(!a.sameAs(b)){
            try(FileOutputStream out=new FileOutputStream(directory+"/italic-han-expected.png")){a.compress(Bitmap.CompressFormat.PNG,100,out);}
            try(FileOutputStream out=new FileOutputStream(directory+"/italic-han-actual.png")){b.compress(Bitmap.CompressFormat.PNG,100,out);}
            throw new AssertionError(name+" size="+size+": cell backgrounds erase software glyph ink");
        }
        a.recycle();b.recycle();checks++;
        try(CacheRegression.Target t=new CacheRegression.Target();CacheRegression.Target r=new CacheRegression.Target()){
            byte[] wanted=gpu(r,expected,ref,selected),seen=gpu(t,actual,test,selected);
            if(!Arrays.equals(wanted,seen)){save(wanted,"italic-han-gpu-expected");save(seen,"italic-han-gpu-actual");throw new AssertionError(name+" size="+size+": GPU glyph ink mismatch");}
            if(!Arrays.equals(seen,gpu(t,actual,test,selected)))throw new AssertionError("cached row changed");
            checks+=2;
        }
        if(!actual.getScreen().getTranscriptText().equals(expected.getScreen().getTranscriptText()))throw new AssertionError("text changed");
        System.out.println("PASS "+name+" size="+size+" software/GPU/cache");
    }
    public static void main(String[]args)throws Exception{
        directory=args[1];System.setOut(new PrintStream(directory+"/italic-han-check.txt"));
        try{
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager am=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[0]);BundledTerminalFonts.initialize(am);
            for(int size:new int[]{18,28,40}){
                scene("拿到主设备先定尺寸申请成为","3",size,"screenshot Han italic");
                scene("先定尺寸，再 fork 中文 ABC","1;3",size,"bold italic mixed");
                scene("申请成为控制终端","3;4;9",size,"italic underline/strike");
                scene("普通中文与 English","0",size,"upright control");
                scene("Italic English only","3",size,"Latin control");
                scene("拿到主设备先定尺寸","3",size,"Han italic cursor/selection",true);
            }
            System.out.println("ALL PASS "+checks+" checks: italic Han ink, software/GPU/cache and controls");
        }catch(Throwable e){e.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
