import android.content.res.AssetManager;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public final class CacheRegression {
    static final int W=1100,H=850;
    static final class Target implements AutoCloseable {
        final ImageReader reader=ImageReader.newInstance(W,H,PixelFormat.RGBA_8888,3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN);
        final HardwareRenderer hw=new HardwareRenderer();
        final RenderNode node=new RenderNode("persistent root");
        Target(){node.setPosition(0,0,W,H);hw.setSurface(reader.getSurface());hw.setOpaque(true);hw.setContentRoot(node);}
        byte[] image(){
            hw.createRenderRequest().setVsyncTime(System.nanoTime()).setWaitForPresent(true).syncAndDraw();
            try(Image image=reader.acquireLatestImage()){
                if(image==null)throw new AssertionError("no image");
                Image.Plane p=image.getPlanes()[0];ByteBuffer b=p.getBuffer();byte[] bytes=new byte[W*H*4];
                if(p.getPixelStride()!=4)throw new AssertionError("pixel stride");
                for(int y=0;y<H;y++){b.position(y*p.getRowStride());b.get(bytes,y*W*4,W*4);}return bytes;
            }
        }
        public void close(){hw.destroy();reader.close();}
    }
    static int scenes;
    static String directory;
    static Target reference;
    static void compare(Target target,TerminalEmulator e,BeforeRenderer before,TerminalRenderer after,
                        String name,int top,int a,int b,int c,int d)throws Exception{
        RenderNode n1=reference.node,n2=target.node;
        RecordingCanvas c1=n1.beginRecording(W,H);c1.drawColor(0xff151a25);before.render(e,c1,top,a,b,c,d);n1.endRecording();
        RecordingCanvas c2=n2.beginRecording(W,H);c2.drawColor(0xff151a25);after.render(e,c2,top,a,b,c,d);n2.endRecording();
        byte[] expected=reference.image(),actual=target.image();
        int diff=0;for(int i=0;i<expected.length;i++)if(expected[i]!=actual[i])diff++;

        System.out.println("SCENE "+name+" different_bytes="+diff);System.out.flush();
        if(diff!=0){
            for(int j=0;j<2;j++){
                Bitmap image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
                image.copyPixelsFromBuffer(ByteBuffer.wrap(j==0?expected:actual));
                try(OutputStream out=new FileOutputStream(directory+"/cache-"+j+".png")){image.compress(Bitmap.CompressFormat.PNG,100,out);}
            }
            throw new AssertionError(name+": pixel mismatch");
        }
        scenes++;
    }
    static void normal(Target t,TerminalEmulator e,BeforeRenderer b,TerminalRenderer f,String name)throws Exception{compare(t,e,b,f,name,0,-1,-1,-1,-1);}
    public static void main(String[]args)throws Exception{
        directory=args[1];
        System.setOut(new PrintStream(args[1]+"/cache-regression.txt"));
        try{
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);BundledTerminalFonts.initialize(assets);
            TerminalEmulator e=RenderComparison.emulator();e.resize(80,20,12,24);
            BeforeRenderer b=new BeforeRenderer(22,BundledTerminalFonts.typeface());TerminalRenderer f=new TerminalRenderer(22,BundledTerminalFonts.typeface());
            try(Target t=new Target(); Target ref=new Target()){
                reference=ref;
                for(int i=0;i<35;i++)RenderComparison.feed(e,"line "+i+" 中文  ✓ 😀 e\u0301 ▄ ● ○ • \033[1;3;4;9mstyles\033[0m\r\n");
                normal(t,e,b,f,"initial");normal(t,e,b,f,"unchanged");
                RenderComparison.feed(e,RenderComparison.shimmer(42,15));normal(t,e,b,f,"single animated row");
                RenderComparison.feed(e,RenderComparison.shimmer(43,15));normal(t,e,b,f,"color only");
                RenderComparison.feed(e,"\033[3;2H\033[1;3;4;9;44m bold italic underline strike \033[0m");normal(t,e,b,f,"styles and background");
                RenderComparison.feed(e,"\033[?25h\033[4;5H");normal(t,e,b,f,"cursor visible");
                RenderComparison.feed(e,"\033[5;6H");normal(t,e,b,f,"cursor moved");
                e.setCursorBlinkingEnabled(true);e.setCursorBlinkState(false);normal(t,e,b,f,"cursor blink off");
                e.setCursorBlinkState(true);normal(t,e,b,f,"cursor blink on");
                compare(t,e,b,f,"selection",0,2,5,3,24);normal(t,e,b,f,"selection cleared");
                e.mColors.mCurrentColors[2]=0xff2277ff;normal(t,e,b,f,"palette changed");
                RenderComparison.feed(e,"\033[?5h");normal(t,e,b,f,"reverse video on");RenderComparison.feed(e,"\033[?5l");normal(t,e,b,f,"reverse video off");
                compare(t,e,b,f,"scrollback",-6,-1,-1,-1,-1);normal(t,e,b,f,"return to bottom");
                RenderComparison.feed(e,"\033[?1049h\033[2J\033[Halternate buffer 中文");normal(t,e,b,f,"alternate screen");RenderComparison.feed(e,"\033[?1049l");normal(t,e,b,f,"main screen restored");
                RenderComparison.feed(e,"\033[2J\033[H\033[45m                   \033[0m");normal(t,e,b,f,"erase and colored spaces");
                e.resize(65,16,12,24);normal(t,e,b,f,"terminal resize");
                b=new BeforeRenderer(14,BundledTerminalFonts.typeface());f=new TerminalRenderer(14,BundledTerminalFonts.typeface());normal(t,e,b,f,"font size changed");normal(t,e,b,f,"unchanged");
                java.lang.reflect.Method clear=TerminalRenderer.class.getDeclaredMethod("clearRowCache");clear.setAccessible(true);clear.invoke(f);
                normal(t,e,b,f,"window lifecycle cache released");normal(t,e,b,f,"unchanged");
            }
            java.lang.reflect.Method clear=TerminalRenderer.class.getDeclaredMethod("clearRowCache");clear.setAccessible(true);clear.invoke(f);
            try(Target t=new Target(); Target ref=new Target()){
                reference=ref;
                normal(t,e,b,f,"hardware context recreated after cache release");normal(t,e,b,f,"unchanged");
            }
            System.out.println("PASS "+scenes+" hardware pixel comparisons");
        }catch(Throwable e){e.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
