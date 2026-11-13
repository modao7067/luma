import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import java.io.*;

public class MenuShadowCheck {
    static final int X=100,Y=100,PW=220,PH=160;
    static RenderNode panel() {
        RenderNode n=GlassClipCheck.node("surface",0,0,PW,PH);
        Outline outline=new Outline();outline.setRoundRect(0,0,PW,PH,24);outline.setAlpha(1f);n.setOutline(outline);
        n.setElevation(12);n.setClipToOutline(true);
        RecordingCanvas c=n.beginRecording();c.drawColor(0xfff4f4f4);n.endRecording();return n;
    }
    static int frame(HardwareRenderer renderer,ImageReader reader,RenderNode root,boolean fixed,float alpha,File out)throws Exception {
        int halo=fixed?48:0;
        RenderNode panel=panel();panel.setPosition(halo,halo,halo+PW,halo+PH);
        RenderNode layer=GlassClipCheck.node("fade",X-halo,Y-halo,PW+halo*2,PH+halo*2);
        layer.setHasOverlappingRendering(true);layer.setAlpha(alpha);layer.setUseCompositingLayer(fixed,null);
        RecordingCanvas c=layer.beginRecording();c.enableZ();c.drawRenderNode(panel);c.disableZ();layer.endRecording();
        c=root.beginRecording();c.drawColor(Color.WHITE);c.drawRenderNode(layer);root.endRecording();
        Bitmap b=GlassClipCheck.capture(renderer,reader);
        int shadow=255-Color.red(b.getPixel(X+PW/2,Y+PH+8));
        try(FileOutputStream f=new FileOutputStream(new File(out,(fixed?"padded-":"legacy-")+alpha+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}
        b.recycle();layer.discardDisplayList();panel.discardDisplayList();return shadow;
    }
    public static void main(String[] args)throws Exception {
        File out=new File(args[1]);out.mkdirs();System.setOut(new PrintStream(new File(out,"shadow-result.txt")));
        try(ImageReader reader=ImageReader.newInstance(GlassClipCheck.W,GlassClipCheck.H,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN|HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
            HardwareRenderer renderer=new HardwareRenderer();RenderNode root=GlassClipCheck.node("root",0,0,GlassClipCheck.W,GlassClipCheck.H);
            renderer.setSurface(reader.getSurface());renderer.setOpaque(true);renderer.setContentRoot(root);
            renderer.setLightSourceAlpha(.2f,.3f);renderer.setLightSourceGeometry(380,0,600,800);
            int oldHalf=frame(renderer,reader,root,false,.5f,out),oldFull=frame(renderer,reader,root,false,1f,out);
            int half=frame(renderer,reader,root,true,.5f,out),near=frame(renderer,reader,root,true,.999f,out),full=frame(renderer,reader,root,true,1f,out);
            System.out.println("Outside-surface shadow darkness legacy .5="+oldHalf+" 1="+oldFull+"; padded .5="+half+" .999="+near+" 1="+full);
            if(oldHalf!=0 || oldFull<3)throw new AssertionError("negative control did not reproduce delayed shadow");
            if(half<=0 || full<half || Math.abs(full-near)>1)throw new AssertionError("padded shadow missing/jumps on final frame");
            renderer.destroy();System.out.println("PASS: reproduced old delayed shadow; padded fixed offscreen layer retains it during fade, no final-frame jump");
        }catch(Throwable e){e.printStackTrace(System.out);System.exit(1);}
    }
}
