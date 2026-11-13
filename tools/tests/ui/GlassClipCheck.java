import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import java.io.*;
import java.nio.ByteBuffer;

public final class GlassClipCheck {
    static final int W = 760, H = 400;
    static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static String shaderSource;
    static RenderNode node(String name, int x, int y, int w, int h) {
        RenderNode n = new RenderNode(name); n.setPosition(x, y, x+w, y+h); n.setClipToBounds(false); return n;
    }
    static Bitmap capture(HardwareRenderer renderer, ImageReader reader) throws Exception {
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
        Image image = null;
        for (int i=0; i<50 && image==null; i++) { image=reader.acquireLatestImage(); if(image==null) Thread.sleep(10); }
        if(image==null) throw new AssertionError("No HWUI frame");
        try {
            Image.Plane plane=image.getPlanes()[0]; ByteBuffer bytes=plane.getBuffer(); int[] pixels=new int[W*H];
            for(int y=0;y<H;y++) for(int x=0;x<W;x++) {
                int p=y*plane.getRowStride()+x*plane.getPixelStride();
                pixels[y*W+x]=Color.argb(bytes.get(p+3)&255,bytes.get(p)&255,bytes.get(p+1)&255,bytes.get(p+2)&255);
            }
            return Bitmap.createBitmap(pixels,W,H,Bitmap.Config.ARGB_8888);
        } finally {image.close();}
    }
    static RenderNode glass(RenderNode scene, int x,int y,int w,int h,int tint,boolean pathClip) {
        int r=Math.min(w,h)/2;
        RenderNode sample=node("sample",0,0,w,h);
        RecordingCanvas s=sample.beginRecording();s.translate(-x,-y);s.drawRenderNode(scene);sample.endRecording();
        RuntimeShader shader=new RuntimeShader(shaderSource);
        shader.setFloatUniform("dimensions",(float)w,(float)h);shader.setFloatUniform("radius",(float)r);shader.setFloatUniform("edgeWidth",r*.7f);
        sample.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader,"scene"),RenderEffect.createBlurEffect(14,14,Shader.TileMode.CLAMP)));
        RenderNode panel=node("panel",x,y,w,h);
        if(!pathClip) { Outline outline=new Outline();outline.setRoundRect(0,0,w,h,r);panel.setOutline(outline);panel.setClipToOutline(true); }
        RecordingCanvas c=panel.beginRecording();
        if(pathClip) {Path shape=new Path();shape.addRoundRect(0,0,w,h,r,r,Path.Direction.CW);c.save();c.clipPath(shape);}
        c.drawRenderNode(sample);paint.setColor(tint);paint.setStyle(Paint.Style.FILL);
        if(pathClip)c.drawRoundRect(0,0,w,h,r,r,paint);else c.drawRect(0,0,w,h,paint);
        paint.setColor(0xff888888);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
        c.drawRoundRect(pathClip?1:0,pathClip?1:0,pathClip?w-1:w,pathClip?h-1:h,pathClip?r-1:r,pathClip?r-1:r,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xffd50000);c.drawCircle(w/2f,h/2f,4,paint);
        if(pathClip)c.restore();panel.endRecording();return panel;
    }
    public static void main(String[] args)throws Exception {
        shaderSource=java.nio.file.Files.readString(java.nio.file.Path.of(args[2]));
        File out=new File(args[1]);out.mkdirs();System.setOut(new PrintStream(new File(out,"gpu-result.txt")));
        try(ImageReader reader=ImageReader.newInstance(W,H,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN|HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
            HardwareRenderer renderer=new HardwareRenderer();RenderNode root=node("root",0,0,W,H);renderer.setSurface(reader.getSurface());renderer.setContentRoot(root);renderer.setOpaque(true);
            for(boolean fixed:new boolean[]{false,true}) for(int frame=0;frame<12;frame++) {
                boolean light=frame%2==0;int bg=light?0xfffafafa:0xff111111,tint=light?0xffe5e5e5:0xff272727;
                int height=frame%3==0?112:88;
                RenderNode scene=node("session-"+frame,0,0,W,H);RecordingCanvas b=scene.beginRecording();b.drawColor(bg);scene.endRecording();
                RenderNode[] panels={glass(scene,16,16,height,height,tint,fixed),glass(scene,144,16,440,height,tint,fixed),glass(scene,620,16,height,height,tint,fixed),glass(scene,64,240,632,frame%3==0?120:96,tint,fixed)};
                for(int refresh=0;refresh<2;refresh++) {
                    b=scene.beginRecording();b.drawColor(bg);scene.endRecording();
                    RecordingCanvas c=root.beginRecording();c.drawColor(bg);for(RenderNode p:panels)c.drawRenderNode(p);root.endRecording();
                    Bitmap bitmap=capture(renderer,reader);
                    if(fixed) {
                        int[][] bounds={{16,16,height,height},{144,16,440,height},{620,16,height,height},{64,240,632,frame%3==0?120:96}};
                        for(int[] q:bounds){int x=q[0],y=q[1],w=q[2],h=q[3];
                            for(int[] pt:new int[][]{{x+2,y+2},{x+w-3,y+2},{x+2,y+h-3},{x+w-3,y+h-3}})
                                if(bitmap.getPixel(pt[0],pt[1])!=bg)throw new AssertionError("corner leaked "+frame+" "+java.util.Arrays.toString(pt));
                            if(bitmap.getPixel(x+w-5,y+h/2)!=tint)throw new AssertionError("right edge cut "+frame);
                            if(bitmap.getPixel(x+w/2,y+h/2)!=0xffd50000)throw new AssertionError("control lost "+frame);
                        }
                    }
                    if(frame<2&&refresh==1)try(FileOutputStream file=new FileOutputStream(new File(out,(fixed?"path-":"outline-")+frame+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}
                    bitmap.recycle();
                }
                for(RenderNode p:panels)p.discardDisplayList();scene.discardDisplayList();
            }
            renderer.destroy();System.out.println("PASS: 24 path-clipped HWUI frames, 4 panels/frame; day/night, new layers, redraw, resize, corners/right edges/control pixels");
            System.out.println("Scope: native compositing primitive, not full Compose session navigation or live app acceptance.");
        } catch(Throwable e){e.printStackTrace(System.out);System.exit(1);}
    }
}
