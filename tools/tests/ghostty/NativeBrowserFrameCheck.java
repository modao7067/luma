package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.*;
import android.os.Looper;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Deflater;

public final class NativeBrowserFrameCheck {
    static final int W=1254,H=2091;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
    static byte[] packet(int id,int w,int h,byte[]rgba){
        Deflater d=new Deflater(1);d.setInput(rgba);d.finish();ByteArrayOutputStream z=new ByteArrayOutputStream();byte[]tmp=new byte[65536];
        while(!d.finished()){int n=d.deflate(tmp);z.write(tmp,0,n);}d.end();
        String b64=Base64.getEncoder().encodeToString(z.toByteArray());StringBuilder b=new StringBuilder();
        for(int i=0;i<b64.length();i+=4096){int end=Math.min(i+4096,b64.length());
            b.append("\033_G");if(i==0)b.append("a=T,f=32,o=z,s=").append(w).append(",v=").append(h).append(",i=").append(id).append(",C=1,q=2,");
            b.append("m=").append(end<b64.length()?1:0).append(';').append(b64,i,end).append("\033\\");}
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }
    static void feed(GhosttyTerminalEmulator e,byte[]b){for(int i=0;i<b.length;i+=65536)e.append(Arrays.copyOfRange(b,i,Math.min(i+65536,b.length)),Math.min(65536,b.length-i));}
    static long ns(){return System.nanoTime();}
    static void times(String stage,long[]v){Arrays.sort(v);System.out.printf(Locale.ROOT,"TIMING %s n=%d median_ms=%.3f p95_ms=%.3f min_ms=%.3f%n",stage,v.length,v[v.length/2]/1e6,v[(int)(v.length*.95)]/1e6,v[0]/1e6);}
    public static void main(String[]a)throws Exception{
        Looper.prepareMainLooper();Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,a[1]);BundledTerminalFonts.initialize(assets);
        GhosttyTerminalEmulator.loadLibrary(a[0]);ViewViewportCheck.context=ViewViewportCheck.headlessContext(a[3]);
        TerminalSession session=ViewViewportCheck.session(true);TerminalView view=ViewViewportCheck.makeView(session);
        GhosttyTerminalEmulator e=(GhosttyTerminalEmulator)session.getEmulator();long handle=e.adapter().handle();
        byte[]rgba=new byte[W*H*4];for(int y=0;y<H;y++)for(int x=0;x<W;x++){int p=(y*W+x)*4;rgba[p]=(byte)(x*255/W);rgba[p+1]=(byte)(y*255/H);rgba[p+2]=80;rgba[p+3]=(byte)255;}
        byte[]full=packet(91,W,H,rgba);System.out.println("FRAME width="+W+" height="+H+" rgba_bytes="+rgba.length+" wire_bytes="+full.length);
        feed(e,full);TerminalRenderFrame first=e.getRenderFrame();check(first.images.size()==1,"1254x2091 frame survives native storage and Bitmap export");
        Bitmap old=first.images.get(0).bitmap;check(old.getWidth()==W&&old.getHeight()==H&&old.getPixel(W-1,H-1)==0xfffefe50,"full-frame corner channels and dimensions");
        check(!GhosttyNative.copyImage(handle,91,Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)),"mismatched target rejected");
        long[]parse=new long[16],copy=new long[16],legacy=new long[16];
        for(int i=0;i<16;i++){
            long t=ns();feed(e,full);parse[i]=ns()-t;
            t=ns();Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);check(GhosttyNative.copyImage(handle,91,b),"direct pixel copy "+i);copy[i]=ns()-t;b.recycle();
            t=ns();int[]argb=GhosttyNative.imagePixels(handle,91);b=Bitmap.createBitmap(argb,2,W,W,H,Bitmap.Config.ARGB_8888);legacy[i]=ns()-t;b.recycle();
        }
        times("inline_parse_inflate",parse);times("fresh_bitmap_direct_copy",copy);times("legacy_malloc_intarray_bitmap",legacy);
        byte[]patch=packet(92,128,64,NativeImageCheck.rgba(new int[128*64]));feed(e,patch);check(e.getRenderFrame().images.size()==2,"base and patch coexist within bounded storage");
        byte[]blue=new byte[4*W*H];for(int p=0;p<blue.length;p+=4){blue[p+2]=(byte)255;blue[p+3]=(byte)255;}
        feed(e,packet(91,W,H,blue));Bitmap next=e.getRenderFrame().images.get(0).bitmap;
        check(old.getPixel(W-1,H-1)==0xfffefe50&&!old.isRecycled(),"replacement never mutates or recycles a previously published Bitmap");
        view.onScreenUpdated();byte[]hw=ViewViewportCheck.hardware(view);check(hw.length>0,"content-sized frame reaches actual offscreen GPU View renderer");
        Bitmap shot=Bitmap.createBitmap(ViewViewportCheck.VW,ViewViewportCheck.VH,Bitmap.Config.ARGB_8888);view.draw(new Canvas(shot));
        try(FileOutputStream out=new FileOutputStream(a[2]+"/browser-frame.png")){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
        if(a.length>4){feed(e,"\033c".getBytes(StandardCharsets.US_ASCII));byte[]captured=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[4]));feed(e,captured);TerminalRenderFrame frame=e.getRenderFrame();check(!frame.images.isEmpty(),"captured real inline frame output renders in Luma");Bitmap rendered=Bitmap.createBitmap(1254,2091,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(rendered);c.drawColor(Color.BLACK);for(TerminalImage image:frame.images){RectF dst=new RectF(image.cells);dst.left*=19;dst.right*=19;dst.top*=41;dst.bottom*=41;c.drawBitmap(image.bitmap,image.source,dst,null);}try(FileOutputStream out=new FileOutputStream(a[2]+"/browser-real.png")){rendered.compress(Bitmap.CompressFormat.PNG,100,out);} }
        session.disposeEmulator();System.out.println("BROWSER FRAME OK (offscreen, not panel FPS)");System.exit(0);
    }
}
