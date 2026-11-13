import android.content.res.AssetManager;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ImageCheck {
    static int checks;
    static String directory;
    static void check(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
        checks++; System.out.println("PASS " + text); System.out.flush();
    }
    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        final List<Integer> commands = new ArrayList<>();
        public void write(byte[] b,int o,int n){replies.write(b,o,n);}
        public void titleChanged(String a,String b){}
        public void onCommandState(int status){commands.add(status);}
        public void onCopyTextToClipboard(String s){}
        public void onPasteTextFromClipboard(){}
        public void onBell(){}
        public void onColorsChanged(){}
    }
    static TerminalEmulator emulator(Output output) {
        TerminalSessionClient client=(TerminalSessionClient)Proxy.newProxyInstance(ImageCheck.class.getClassLoader(),
            new Class[]{TerminalSessionClient.class},(p,m,v)->m.getName().equals("getTerminalCursorStyle")?1:null);
        TerminalEmulator e=new TerminalEmulator(output,80,24,12,24,100,client);
        feed(e,"\033[?25l"); return e;
    }
    static TerminalEmulator emulator(){return emulator(new Output());}
    static void feed(TerminalEmulator e,String text){byte[] b=text.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}
    static byte[] png(int w,int h){
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);c.drawColor(Color.RED);
        Paint paint=new Paint();paint.setColor(Color.GREEN);c.drawRect(w/2,0,w,h,paint);
        ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        bitmap.recycle();return out.toByteArray();
    }
    static String encoded(byte[] png){return android.util.Base64.encodeToString(png,android.util.Base64.NO_WRAP);}
    static String image(byte[] png,String parameters){return "\033]1337;File=inline=1;"+parameters+":"+encoded(png)+"\007";}
    static TerminalBitmap first(TerminalEmulator e){
        TerminalBuffer b=e.getScreen();
        for(int row=-b.getActiveTranscriptRows();row<e.mRows;row++)for(int col=0;col<e.mColumns;col++){
            TerminalBitmap image=b.getTerminalBitmap(b.getStyleAt(row,col));if(image!=null)return image;
        }return null;
    }
    static String sixel(){return "\033Pq\"1;1;24;12#1;2;100;0;0!12~#2;2;0;100;0!12~-#1!12~#2!12~\033\\";}
    static void protocolChecks()throws Exception{
        byte[] small=png(24,24);TerminalEmulator e=emulator();
        feed(e,image(small,""));TerminalBitmap picture=first(e);
        check(picture!=null&&picture.getBitmap().getWidth()==24,"singlepart PNG decoded and placed");
        check(picture.getBitmap().getPixel(2,2)==Color.RED&&picture.getBitmap().getPixel(20,2)==Color.GREEN,"PNG pixels preserved");
        Bitmap jpeg=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);jpeg.eraseColor(Color.RED);
        ByteArrayOutputStream jpegData=new ByteArrayOutputStream();jpeg.compress(Bitmap.CompressFormat.JPEG,90,jpegData);jpeg.recycle();
        e=emulator();feed(e,image(jpegData.toByteArray(),""));
        check(first(e)!=null&&Color.red(first(e).getBitmap().getPixel(20,20))>240,"JPEG uses bounded Android image decoding");
        e=emulator();byte[] helper=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(directory,"show-image-output.bin"));e.append(helper,helper.length);
        check(first(e)!=null&&first(e).getBitmap().getWidth()==480,"actual preview-helper PTY output renders successfully");
        e=emulator();String osc=image(small,"").replace("\007","\033\\");
        for(byte b:osc.getBytes(StandardCharsets.UTF_8))e.append(new byte[]{b},1);
        check(first(e)!=null,"single-byte fragmentation and OSC ST termination");
        e=emulator();feed(e,"\033]1337;MultipartFile=inline=1;width=24px;height=24px\007");
        String data=encoded(small);for(int start=0;start<data.length();start+=13)
            feed(e,"\033]1337;FilePart="+data.substring(start,Math.min(start+13,data.length()))+"\007");
        feed(e,"\033]1337;FileEnd\007");
        check(first(e)!=null&&first(e).getBitmap().getPixel(20,2)==Color.GREEN,"multipart iTerm transfer including unaligned base64 chunks");
        e=emulator();feed(e,image(small,"width=4;height=2;preserveAspectRatio=0"));
        check(first(e)!=null&&first(e).getBitmap().getWidth()==48&&first(e).getBitmap().getHeight()==48,"cell dimensions and independent scaling");
        e=emulator();feed(e,image(small,"width=50%;height=25%;preserveAspectRatio=0"));
        check(first(e)!=null&&first(e).getBitmap().getWidth()==480&&first(e).getBitmap().getHeight()==144,"percentage dimensions");
        e=emulator();feed(e,sixel());picture=first(e);
        check(picture!=null&&picture.getBitmap().getPixel(1,1)==Color.RED&&picture.getBitmap().getPixel(20,10)==Color.GREEN,"Sixel raster, RGB palette, repeats and newline");
        e=emulator();for(byte b:sixel().getBytes(StandardCharsets.UTF_8))e.append(new byte[]{b},1);
        check(first(e)!=null&&first(e).getBitmap().getPixel(20,10)==Color.GREEN,"Sixel single-byte fragmentation");
        e=emulator();feed(e,"\033Pq#1;2;100;0;0!10~$#2;2;0;100;0!5~\033\\");
        check(first(e)!=null&&first(e).getBitmap().getPixel(2,2)==Color.GREEN&&first(e).getBitmap().getPixel(8,2)==Color.RED,"Sixel carriage return and overpaint");
        e=emulator();feed(e,image(png(2048,2048),"width=40;height=20"));
        check(first(e)!=null&&first(e).getBitmap().getAllocationByteCount()<=8*1024*1024,"large compressed source subsampled before pixel allocation");
        e=emulator();feed(e,image(small,"width=4096px;height=4096px;preserveAspectRatio=0")+"AFTER");
        check(first(e)==null&&e.getScreen().getTranscriptText().contains("AFTER"),"oversized requested image rejected without swallowing following text");
        e=emulator();feed(e,"\033]1337;File=inline=1:garbage%%\007OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().contains("OK"),"invalid base64 recovery");
        e=emulator();feed(e,"\033Pq#999999999999999;2;100;0;0~~~~~\033\\OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"malformed Sixel discarded through ST without printing payload");
        e=emulator();feed(e,"\033Pq\"1;1;999999;999999#1~\033\\OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"oversized Sixel raster rejected");
        e=emulator();feed(e,"\033Pq#1~\030OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"cancelled Sixel releases in-flight image and restores parser");
        e=emulator();feed(e,"\033]1337;File=inline=1:");
        byte[] chunk=new byte[8192];Arrays.fill(chunk,(byte)'A');
        for(int n=0;n<1030;n++)e.append(chunk,chunk.length);
        feed(e,"\007OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"oversized singlepart payload bounded and discarded through BEL");
        e=emulator();feed(e,"\033]1337;MultipartFile=inline=1\007");
        String part="\033]1337;FilePart="+"A".repeat(8192)+"\007";
        for(int n=0;n<1025;n++)feed(e,part);
        feed(e,"\033]1337;FileEnd\007OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"multipart aggregate payload limit");
        e=emulator();feed(e,"\033Pq~");e.reset();feed(e,"OK");
        check(first(e)==null&&e.getScreen().getTranscriptText().equals("OK"),"reset cancels incomplete image");
        Output out=new Output();e=emulator(out);feed(e,"\033[c\033]1337;ReportCellSize\007\033]133;C\007\033]133;D;0\007");
        check(out.replies.toString("UTF-8").contains(";4;")||out.replies.toString("UTF-8").contains(";4c"),"device attributes advertise Sixel");
        check(out.replies.toString("UTF-8").contains("\033]1337;ReportCellSize=24;12\007"),"iTerm cell-size report has correct OSC prefix");
        check(out.commands.equals(Arrays.asList(-1,0)),"Root command-state markers preserved");
    }
    static void lifetimeChecks()throws Exception{
        byte[] small=png(24,24);TerminalEmulator e=emulator();feed(e,image(small,""));
        long style=e.getScreen().getStyleAt(0,0);feed(e,"\033[3J");
        check(e.getScreen().getTerminalBitmap(style)!=null,"clear scrollback retains visible image");
        feed(e,"\033[2J");check(e.getScreen().getTerminalBitmapCount()==0&&e.getScreen().getTerminalBitmapBytes()==0,"erase releases unreachable images on same output batch");
        e=emulator();feed(e,image(small,""));style=e.getScreen().getStyleAt(0,0);
        for(int n=0;n<30;n++)feed(e,"line\r\n");
        check(e.getScreen().getTerminalBitmap(style)!=null,"image retained while in scrollback");
        for(int n=0;n<120;n++)feed(e,"line\r\n");
        check(e.getScreen().getTerminalBitmapCount()==0,"image released after scrolling out of history ring");
        e=emulator();feed(e,image(small,""));style=e.getScreen().getStyleAt(0,0);
        e.getScreen().blockCopy(0,0,2,1,8,3);feed(e,"\033[1;1H\033[2K");
        check(e.getScreen().getTerminalBitmap(style)!=null,"copied image cells retain resource after original erased");
        feed(e,"\033[2J");check(e.getScreen().getTerminalBitmapCount()==0,"copied image resource eventually collected");
        e=emulator();for(int n=0;n<35;n++)feed(e,image(png(12,24),""));
        check(e.getScreen().getTerminalBitmapCount()<=24&&e.getScreen().getTerminalBitmapBytes()<=8*1024*1024,"image-count cache bound");
        check(e.getScreen().getTerminalBitmap(e.getScreen().getStyleAt(0,0))==null,"oldest image evicted without reusing its placement ID");
        byte[] large=png(900,900);e=emulator();for(int n=0;n<12;n++)feed(e,image(large,"")+"\r\n");
        check(e.getScreen().getTerminalBitmapBytes()<=8*1024*1024,"pixel-memory cache bound under repeated large images");
        check(TextStyle.getTerminalBitmapNum(TextStyle.encodeTerminalBitmap(65535,3,7))==65535,"full 16-bit image number roundtrip");
        e=emulator();feed(e,image(small,""));
        Field next=TerminalBuffer.class.getDeclaredField("mNextBitmapNum");next.setAccessible(true);next.setInt(e.getScreen(),65536);
        feed(e,"\033[4;1H"+image(small,""));
        check(!TextStyle.isTerminalBitmap(e.getScreen().getStyleAt(0,0))&&first(e)!=null,"number wrap clears stale placements before reuse");
        e=emulator();feed(e,image(small,""));e.resize(60,18,16,30);feed(e,"text");
        check(first(e)!=null,"image survives terminal reflow and changed cell dimensions");
    }
    static final class Target implements AutoCloseable {
        static final int W=1000,H=700;
        final ImageReader reader=ImageReader.newInstance(W,H,PixelFormat.RGBA_8888,3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN);
        final HardwareRenderer hw=new HardwareRenderer();final RenderNode node=new RenderNode("image test");
        Target(){node.setPosition(0,0,W,H);hw.setSurface(reader.getSurface());hw.setOpaque(true);hw.setContentRoot(node);}
        byte[] frame(TerminalRenderer renderer,TerminalEmulator e,int top){
            RecordingCanvas canvas=node.beginRecording(W,H);canvas.drawColor(Color.BLACK);renderer.render(e,canvas,top,-1,-1,-1,-1);node.endRecording();
            hw.createRenderRequest().setVsyncTime(System.nanoTime()).setWaitForPresent(true).syncAndDraw();
            try(Image image=reader.acquireLatestImage()){
                if(image==null)throw new AssertionError("missing GPU frame");
                Image.Plane plane=image.getPlanes()[0];byte[] bytes=new byte[W*H*4];ByteBuffer b=plane.getBuffer();
                for(int y=0;y<H;y++){b.position(y*plane.getRowStride());b.get(bytes,y*W*4,W*4);}return bytes;
            }
        }
        public void close(){hw.destroy();reader.close();}
    }
    static byte[] software(TerminalRenderer renderer,TerminalEmulator e,int top)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(Target.W,Target.H,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);renderer.render(e,canvas,top,-1,-1,-1,-1);
        ByteBuffer pixels=ByteBuffer.allocate(Target.W*Target.H*4);bitmap.copyPixelsToBuffer(pixels);bitmap.recycle();return pixels.array();
    }
    static void compare(Target target,TerminalRenderer cached,TerminalEmulator e,int top,String name)throws Exception{
        byte[] actual=target.frame(cached,e,top),fresh;
        try(Target reference=new Target()){
            fresh=reference.frame(new TerminalRenderer(22,BundledTerminalFonts.typeface()),e,top);
        }
        check(Arrays.equals(actual,fresh),name+" cached GPU pixels match a fresh renderer");
        int imagePixels=0;for(int i=0;i<actual.length;i+=4)
            if((fresh[i]==(byte)255&&fresh[i+1]==0&&fresh[i+2]==0)||(fresh[i]==0&&fresh[i+1]==(byte)255&&fresh[i+2]==0))imagePixels++;
        check(imagePixels>20,name+" image is visible");
    }
    static void checkPixel(byte[] actual,int offset,byte[] expected){for(int n=0;n<4;n++)if(actual[offset+n]!=expected[offset+n])throw new AssertionError("image pixel mismatch at "+offset);}
    static void renderChecks()throws Exception{
        TerminalRenderer renderer=new TerminalRenderer(22,BundledTerminalFonts.typeface());TerminalEmulator e=emulator();
        feed(e,"BEFORE "+image(png(48,48),"")+" AFTER\r\nChinese 中文  😀\r\n"+sixel());
        try(Target target=new Target()){
            compare(target,renderer,e,0,"mixed text and images");
            byte[] initial=target.frame(renderer,e,0),second=target.frame(renderer,e,0);
            check(Arrays.equals(initial,second),"unchanged image frame uses identical cached output");
            Method clear=TerminalRenderer.class.getDeclaredMethod("clearRowCache");clear.setAccessible(true);clear.invoke(renderer);
            check(Arrays.equals(initial,target.frame(renderer,e,0)),"cache release and rebuild preserve pixels");
            feed(e,"\033[?1049halternate"+image(png(36,24),""));compare(target,renderer,e,0,"alternate buffer");
            feed(e,"\033[?1049l");check(Arrays.equals(initial,target.frame(renderer,e,0)),"main-buffer image restored after alternate screen");
            for(int n=0;n<28;n++)feed(e,"row "+n+"\r\n");
            compare(target,renderer,e,-e.getScreen().getActiveTranscriptRows(),"scrollback");
        }
        e=emulator();renderer=new TerminalRenderer(22,BundledTerminalFonts.typeface());feed(e,image(png(12,24),""));
        try(Target target=new Target()){
            target.frame(renderer,e,0);
            for(int n=0;n<28;n++)feed(e,image(png(12,24),""));
            byte[] evicted=target.frame(renderer,e,0);Method clear=TerminalRenderer.class.getDeclaredMethod("clearRowCache");clear.setAccessible(true);clear.invoke(renderer);
            check(Arrays.equals(evicted,target.frame(renderer,e,0)),"evicted images cannot survive in old RenderNode display lists");
        }
        e=emulator();feed(e,"Sixel image\r\n"+sixel()+"\r\niTerm PNG / 中文 / \r\n"+image(png(384,192),""));
        Bitmap scene=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(scene);canvas.drawColor(0xff151a25);
        new TerminalRenderer(22,BundledTerminalFonts.typeface()).render(e,canvas,0,-1,-1,-1,-1);
        try(OutputStream out=new FileOutputStream(directory+"/image-scene.png")){scene.compress(Bitmap.CompressFormat.PNG,100,out);}scene.recycle();
        final class CountCanvas extends Canvas {
            int calls;
            CountCanvas(Bitmap bitmap){super(bitmap);}
            @Override public void drawBitmap(Bitmap b,Rect from,RectF to,Paint paint){calls++;super.drawBitmap(b,from,to,paint);}
        }
        e=emulator();feed(e,image(png(384,192),""));
        Bitmap countImage=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888);CountCanvas countCanvas=new CountCanvas(countImage);
        new TerminalRenderer(22,BundledTerminalFonts.typeface()).render(e,countCanvas,0,-1,-1,-1,-1);
        check(countCanvas.calls==8,"384x192 image drawn in 8 row spans instead of 256 individual cells");countImage.recycle();
        e=emulator();TerminalEmulator text=emulator();
        feed(e,"BEFORE"+image(png(48,24),"")+"AFTER");feed(text,"BEFORE    AFTER");
        try(Target a=new Target();Target b=new Target()){
            byte[] mixed=a.frame(new TerminalRenderer(22,BundledTerminalFonts.typeface()),e,0);
            byte[] plain=b.frame(new TerminalRenderer(22,BundledTerminalFonts.typeface()),text,0);
            float width=new TerminalRenderer(22,BundledTerminalFonts.typeface()).getFontWidth();
            int left=(int)Math.floor(6*width),right=(int)Math.ceil(10*width),differences=0;
            for(int i=0;i<mixed.length;i++){
                int x=(i/4)%Target.W;if(x>=left&&x<=right)continue;
                if(mixed[i]!=plain[i])differences++;
            }
            check(differences==0,"text before and after inline image preserves exact GPU pixels");
        }
    }
    public static void main(String[] args)throws Exception{
        directory=args[1];System.setOut(new PrintStream(directory+"/image-check.txt"));
        try{
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);BundledTerminalFonts.initialize(assets);
            protocolChecks();lifetimeChecks();renderChecks();System.out.println("ALL PASS checks="+checks);
        }catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
