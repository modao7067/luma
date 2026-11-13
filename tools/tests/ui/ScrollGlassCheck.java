import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import java.io.*;
import java.nio.file.*;

public final class ScrollGlassCheck {
    public static void main(String[] args) throws Exception {
        File out=new File(args[1]); out.mkdirs();
        System.setOut(new PrintStream(new File(out,"scroll-gpu.txt")));
        String shaderSource=Files.readString(java.nio.file.Path.of(args[2]));
        Paint highlightPaint=null;
        if(args.length>4) {
            java.util.Properties constants=new java.util.Properties();
            try(java.io.Reader input=Files.newBufferedReader(java.nio.file.Path.of(args[4]))) {constants.load(input);}
            RuntimeShader highlight=new RuntimeShader(Files.readString(java.nio.file.Path.of(args[3])));
            float inner=Float.parseFloat(constants.getProperty("highlightInnerBlurDp"));
            highlight.setFloatUniform("halfView",30f,30f);highlight.setFloatUniform("halfViewFloor",30f,30f);
            highlight.setFloatUniform("cornerRadii",30f,30f,30f,30f);
            highlight.setFloatUniform("strokeWidth",Float.parseFloat(constants.getProperty("highlightWidthDp")));
            highlight.setFloatUniform("innerBlurRadius",inner);highlight.setFloatUniform("innerBlurRadiusSq",inner*inner);
            highlight.setFloatUniform("highlightAlpha",Float.parseFloat(constants.getProperty("highlightAlpha")));
            highlight.setFloatUniform("strokeAlphaMul",Float.parseFloat(constants.getProperty("highlightStrokeAlpha")));
            highlight.setColorUniform("strokeColor",Color.WHITE);
            float[] xs={-0.70710677f,0f},ys={-0.70710677f,0.1f},zs={-0.05f,-0.5f};
            for(int i=0;i<2;i++) {float length=(float)Math.sqrt(xs[i]*xs[i]+ys[i]*ys[i]+zs[i]*zs[i]);String suffix=""+(i+1);
                highlight.setFloatUniform("lightDir"+suffix,xs[i]/length,ys[i]/length,zs[i]/length);
                highlight.setColorUniform("lightColor"+suffix,Color.WHITE);highlight.setFloatUniform("lightIntensity"+suffix,i==0?1f:0.4f);}
            highlightPaint=new Paint();highlightPaint.setShader(highlight);highlightPaint.setBlendMode(BlendMode.PLUS);
        }
        final int W=GlassClipCheck.W,H=GlassClipCheck.H,w=60,h=60,x=(W-w)/2,y=280,padding=40;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RenderNode scene=GlassClipCheck.node("reading-backdrop",0,0,W,H);
        RenderNode sample=GlassClipCheck.node("glass-sample",-padding,-padding,w+2*padding,h+2*padding);
        RenderNode overlay=GlassClipCheck.node("return-to-bottom-overlay",x,y,w,h);
        RenderNode root=GlassClipCheck.node("root",0,0,W,H);
        RuntimeShader shader=new RuntimeShader(shaderSource);
        shader.setFloatUniform("dimensions",w,h);shader.setFloatUniform("radius",30f);
        shader.setFloatUniform("refractionHeight",24f);shader.setFloatUniform("refractionAmount",24f);
        shader.setFloatUniform("samplePadding",(float)padding);
        float saturation=1.5f,r=(1-saturation)*0.2126f,g=(1-saturation)*0.7152f,blue=(1-saturation)*0.0722f;
        ColorMatrix vibrancy=new ColorMatrix(new float[]{r+saturation,g,blue,0,0,r,g+saturation,blue,0,0,r,g,blue+saturation,0,0,0,0,0,1,0});
        RenderEffect color=RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancy));
        float nativeBlurRadius=(4f*0.45f-0.5f)/0.57735f;
        RenderEffect blur=RenderEffect.createBlurEffect(nativeBlurRadius,nativeBlurRadius,Shader.TileMode.CLAMP);
        sample.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader,"scene"),RenderEffect.createChainEffect(blur,color)));
        try(ImageReader reader=ImageReader.newInstance(W,H,PixelFormat.RGBA_8888,3,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT|HardwareBuffer.USAGE_CPU_READ_OFTEN|HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
            HardwareRenderer renderer=new HardwareRenderer();renderer.setSurface(reader.getSurface());renderer.setContentRoot(root);renderer.setOpaque(true);
            for(int frame=0;frame<48;frame++) {
                RecordingCanvas c=scene.beginRecording();c.drawColor(0xff171421);
                paint.setColor(0xff4b557e);
                for(int row=0;row<12;row++) {float top=row*48-(frame*7%48);c.drawRect(12,top,W-12,top+15,paint);}
                scene.endRecording();
                c=root.beginRecording();c.drawRenderNode(scene);root.endRecording();
                Bitmap clean=GlassClipCheck.capture(renderer,reader);
                if(clean.getPixel(x+30,y+30)==0xffd50000)throw new AssertionError("Overlay leaked into backdrop");clean.recycle();
                c=sample.beginRecording();c.translate(padding-x,padding-y);c.drawRenderNode(scene);sample.endRecording();
                c=overlay.beginRecording();android.graphics.Path clip=new android.graphics.Path();clip.addCircle(30,30,30,android.graphics.Path.Direction.CW);c.clipPath(clip);c.drawRenderNode(sample);
                paint.setColor(0x66444050);c.drawCircle(30,30,30,paint);if(highlightPaint!=null)c.drawRect(0,0,w,h,highlightPaint);paint.setColor(0xffd50000);c.drawCircle(30,30,4,paint);overlay.endRecording();
                boolean visible=frame%4!=0;
                c=root.beginRecording();c.drawRenderNode(scene);if(visible)c.drawRenderNode(overlay);root.endRecording();
                Bitmap b=GlassClipCheck.capture(renderer,reader);
                if(visible && b.getPixel(x+30,y+30)!=0xffd50000)throw new AssertionError("Scroll control lost at frame "+frame+" pixel="+Integer.toHexString(b.getPixel(x+30,y+30)));
                if(visible && highlightPaint!=null && Color.red(b.getPixel(x+3,y+30))<12)throw new AssertionError("highlight edge became black at frame "+frame);
                if(frame==47)try(FileOutputStream file=new FileOutputStream(new File(out,"scroll-overlay-gpu.png"))){b.compress(Bitmap.CompressFormat.PNG,100,file);}
                b.recycle();
            }
            renderer.destroy();
            System.out.println("PASS: 96 HWUI frames; scrolling backdrop, overlay show/hide, production AGSL with SukiSU 1.5/4/24/24/40 parameters, isolated background and visible control pixels");
            if(highlightPaint!=null)System.out.println("PASS: current production dual-light RuntimeShader, cached uniforms, additive clipped edge and unchanged control across 48 scrolling frames");
            System.out.println("Scope: Android hardware render graph, not installed-App gestures or Compose lifecycle.");
        } catch(Throwable e){e.printStackTrace(System.out);System.exit(1);}
    }
}
