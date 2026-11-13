package com.termux.view;
import android.content.res.AssetManager;
import android.graphics.*;
import android.os.*;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public final class NativeImageCheck {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static String kitty(String options,byte[] payload){return "\033_G"+options+(payload==null?"":";"+Base64.getEncoder().encodeToString(payload))+"\033\\";}
    static void feed(TerminalSession session,String text){ViewViewportCheck.feed(session,text);}
    static GhosttyTerminalEmulator core(TerminalSession session){return(GhosttyTerminalEmulator)session.getEmulator();}
    static byte[] rgba(int...colors){byte[]data=new byte[colors.length*4];for(int i=0;i<colors.length;i++){int c=colors[i];data[i*4]=(byte)(c>>16);data[i*4+1]=(byte)(c>>8);data[i*4+2]=(byte)c;data[i*4+3]=(byte)(c>>24);}return data;}
    static int pixel(byte[]pixels,int x,int y){int p=(y*ViewViewportCheck.VW+x)*4;return (pixels[p+3]&255)<<24|(pixels[p]&255)<<16|(pixels[p+1]&255)<<8|(pixels[p+2]&255);}
    static byte[] png(int...colors){Bitmap bitmap=Bitmap.createBitmap(colors,2,2,Bitmap.Config.ARGB_8888);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();return out.toByteArray();}
    public static void main(String[]args)throws Exception{
        try{
            Looper.prepareMainLooper();Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[1]);BundledTerminalFonts.initialize(assets);
            GhosttyTerminalEmulator.loadLibrary(args[0]);ViewViewportCheck.context=ViewViewportCheck.headlessContext(args[3]);
            TerminalSession session=ViewViewportCheck.session(true);TerminalView view=ViewViewportCheck.makeView(session);
            feed(session,"\033[?25l"+kitty("a=T,f=32,s=2,v=2,c=4,r=2,i=1,p=1,C=1",rgba(0xffff0000,0xff00ff00,0xff0000ff,0xffffffff)));
            TerminalRenderFrame frame=core(session).getRenderFrame();
            check(frame.images.size()==1,"native RGBA image becomes an Android frame placement");TerminalImage image=frame.images.get(0);
            check(image.bitmap.getPixel(0,0)==0xffff0000&&image.bitmap.getPixel(1,1)==0xffffffff,"RGBA pixels preserve exact channels");
            check(image.cells.left==0&&image.cells.top==0&&Math.abs(image.cells.width()-4)<0.01f&&Math.abs(image.cells.height()-2)<0.01f,"native placement geometry uses terminal cell metrics");
            byte[]software=ViewViewportCheck.draw(view),hardware=ViewViewportCheck.hardware(view);
            int x=Math.round(view.mRenderer.getFontWidth()*0.65f),y=view.mRenderer.mFontLineSpacingAndAscent+Math.round(view.mRenderer.getFontLineSpacing()*0.3f);
            check((pixel(software,x,y)&0xffffff)==0xff0000,"real View software Canvas draws the image at the native origin");
            check((pixel(hardware,x,y)&0xffffff)==0xff0000,"real View GPU draws the image at the native origin");
            Bitmap shot=Bitmap.createBitmap(ViewViewportCheck.VW,ViewViewportCheck.VH,Bitmap.Config.ARGB_8888);Canvas shotCanvas=new Canvas(shot);shotCanvas.drawColor(Color.BLACK);view.draw(shotCanvas);
            try(FileOutputStream out=new FileOutputStream(args[2]+"/native-image.png")){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            feed(session,kitty("a=t,f=32,s=1,v=1,i=7",rgba(0xff0000ff))+kitty("a=p,i=7,p=2,P=1,Q=1,H=1,V=1,c=2,r=1",null));
            long[]placements=GhosttyNative.imagePlacements(core(session).adapter().handle());boolean child=false;
            for(int i=0;i<placements[1];i++){int p=2+i*16;if(placements[p]==7)child=placements[p+3]==1&&placements[p+4]==1;}
            check(child,"relative placement resolves against the parent image");
            feed(session,kitty("a=d,d=A",null));
            feed(session,kitty("a=T,f=100,i=2,p=1,c=4,r=2,C=1",png(0xff00ffff,0xffff00ff,0xffffff00,0xff404040)));
            frame=core(session).getRenderFrame();check(frame.images.size()==1&&frame.images.get(0).bitmap.getPixel(0,0)==0xff00ffff,"PNG uses bounded Android decoding and exact native pixels");
            check(GhosttyImageDecoder.decodePng(new byte[]{1,2,3})==null,"malformed PNG rejected");
            byte[]large=png(0xff000000,0xff000000,0xff000000,0xff000000);ByteBuffer.wrap(large,16,8).putInt(100000).putInt(100000);
            check(GhosttyImageDecoder.decodePng(large)==null,"PNG dimensions are rejected before full pixel allocation");
            feed(session,kitty("a=d,d=i,i=2",null));check(core(session).getRenderFrame().images.isEmpty(),"placement-only delete removes the displayed image");
            feed(session,kitty("a=p,i=2,p=1,c=4,r=2,C=1",null));check(core(session).getRenderFrame().images.size()==1,"placement-only delete retains pixels for redisplay");
            TerminalSession frozen=ViewViewportCheck.session(true);TerminalView frozenView=ViewViewportCheck.makeView(frozen);
            feed(frozen,"\033[?25l"+kitty("a=T,f=32,s=1,v=1,c=4,r=3,i=11,C=1",rgba(0xffff0000))+"\033[?2026h"+kitty("a=d,d=I,i=11",null));
            frozenView.onScreenUpdated();
            check(core(frozen).adapter().queryMode(2026)==1,"ordinary View output updates do not cancel the protocol hold");
            TerminalRenderFrame held=core(frozen).getRenderFrame();check(held.images.size()==1&&held.images.get(0).bitmap.getPixel(0,0)==0xffff0000,"hold captures pictures before their first paint");
            Bitmap heldBitmap=held.images.get(0).bitmap;check((pixel(ViewViewportCheck.draw(frozenView),x,y)&0xffffff)==0xff0000,"deleted live image remains visible while the frame is held");
            feed(frozen,"\033[?2026l");check(core(frozen).getRenderFrame().images.isEmpty(),"release drops held image references and reflects deletion");check(!heldBitmap.isRecycled(),"previous display-list bitmaps are never manually recycled");
            TerminalSession partial=ViewViewportCheck.session(true);TerminalView partialView=ViewViewportCheck.makeView(partial);
            feed(partial,kitty("a=T,f=32,s=1,v=1,c=4,r=8,i=12,C=1",rgba(0xff00ff00)));
            StringBuilder output=new StringBuilder();for(int i=0;i<core(partial).mRows+3;i++)output.append("line\r\n");feed(partial,output.toString());partialView.onScreenUpdated();
            TerminalRenderFrame partly=core(partial).getRenderFrame();System.out.println("PARTIAL rows="+core(partial).mRows+" history="+core(partial).getViewportHistoryRows()+" images="+partly.images.size()+" placements="+java.util.Arrays.toString(GhosttyNative.imagePlacements(core(partial).adapter().handle())));check(partly.images.size()==1&&partly.images.get(0).cells.top<0&&partly.images.get(0).cells.bottom>0,"partially visible history-anchored picture retains negative row geometry");
            feed(partial,"\033[?2026h"+kitty("a=d,d=I,i=12",null));check(core(partial).getRenderFrame().images.size()==1,"hold retains the partially visible picture");feed(partial,"\033[?2026l");
            TerminalSession inline=ViewViewportCheck.session(true);TerminalView inlineView=ViewViewportCheck.makeView(inline);
            String encoded=Base64.getEncoder().encodeToString(png(0xffff0000,0xff00ff00,0xff0000ff,0xffffffff));
            feed(inline,"\033[?25l\033]1337;File=inline=1;width=52px:"+encoded+"\007AFTER");
            TerminalRenderFrame inlineFrame=core(inline).getRenderFrame();
            check(inlineFrame.images.size()==1&&inlineFrame.images.get(0).bitmap.getWidth()==52&&inlineFrame.images.get(0).bitmap.getHeight()==52,"single iTerm2 inline PNG uses requested pixel dimensions and native storage");
            check(core(inline).getSelectedText(0,0,core(inline).mColumns-1,core(inline).mRows-1).contains("AFTER")&&core(inline).getCursorRow()>0,"inline-image cursor advancement precedes following bytes in the same feed");
            feed(inline,"\033c\033[?25l");
            String packet="\033]1337;MultipartFile=inline=1;width=4;preserveAspectRatio=1\007";
            for(int i=0;i<encoded.length();i+=19)packet+="\033]1337;FilePart="+encoded.substring(i,Math.min(encoded.length(),i+19))+"\007";
            packet+="\033]1337;FileEnd\007";
            for(int i=0;i<packet.length();i+=7)feed(inline,packet.substring(i,Math.min(packet.length(),i+7)));
            inlineView.onScreenUpdated();inlineFrame=core(inline).getRenderFrame();
            check(inlineFrame.images.size()==1&&Math.abs(inlineFrame.images.get(0).cells.width()-4)<0.01f,"multipart iTerm2 survives seven-byte PTY fragmentation and cell sizing");
            check((pixel(ViewViewportCheck.hardware(inlineView),x,y)&0xffffff)==0xff0000,"iTerm2 images share the actual GPU rendering path");
            feed(inline,"\033c\033]1337;MultipartFile=inline=1;width=100%\007\033]1337;FilePart="+encoded+"\007\033c\033]1337;FileEnd\007");
            check(core(inline).getRenderFrame().images.isEmpty(),"RIS aborts a pending multipart image");
            feed(inline,"\033]1337;File=inline=0:"+encoded+"\007");
            check(core(inline).getRenderFrame().images.isEmpty(),"non-inline iTerm2 file transfers do not create images or files");
            Bitmap noise=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Random random=new Random(17);
            for(int ny=0;ny<64;ny++)for(int nx=0;nx<64;nx++)noise.setPixel(nx,ny,0xff000000|random.nextInt(0x1000000));
            ByteArrayOutputStream pngOut=new ByteArrayOutputStream();noise.compress(Bitmap.CompressFormat.PNG,100,pngOut);noise.recycle();
            String big=Base64.getEncoder().encodeToString(pngOut.toByteArray());check(big.length()>4096,"multipart fixture exceeds the real tool chunk size");
            StringBuilder multi=new StringBuilder("\033]1337;MultipartFile=inline=1;width=100%;preserveAspectRatio=1\007");
            for(int i=0;i<big.length();i+=4096)multi.append("\033]1337;FilePart=").append(big,i,Math.min(big.length(),i+4096)).append('\007');
            multi.append("\033]1337;FileEnd\007");feed(inline,multi.toString());
            inlineFrame=core(inline).getRenderFrame();check(inlineFrame.images.size()==1&&Math.abs(inlineFrame.images.get(0).cells.width()-core(inline).mColumns)<0.1f,"default 100-percent MultipartFile with 4096-character parts is displayed");
            TerminalSession animation=ViewViewportCheck.session(true);TerminalView animationView=ViewViewportCheck.makeView(animation);
            feed(animation,kitty("a=T,f=32,s=1,v=1,c=4,r=2,i=20,C=1",rgba(0xffff0000))+
                kitty("a=f,f=32,s=1,v=1,i=20,z=500",rgba(0xff0000ff))+kitty("a=a,i=20,r=1,z=500",null)+kitty("a=a,i=20,s=3",null));
            TerminalRenderFrame initial=core(animation).getRenderFrame();check(initial.images.size()==1&&initial.images.get(0).bitmap.getPixel(0,0)==0xffff0000,"animation starts on the root frame");
            new Handler().postDelayed(()->{try{
                TerminalRenderFrame next=core(animation).getRenderFrame();check(next.images.get(0).bitmap.getPixel(0,0)==0xff0000ff,"real timer advances the composited Kitty animation to frame two");
                check((pixel(ViewViewportCheck.hardware(animationView),x,y)&0xffffff)==0x0000ff,"GPU image cache refreshes on animation generation");
                for(TerminalSession s:new TerminalSession[]{session,frozen,partial,inline,animation})s.disposeEmulator();
                check(core(animation).adapter()==null,"dispose releases image storage and animation timer");
                System.out.println("NATIVE IMAGE OK checks="+checks);System.exit(0);
            }catch(Throwable failure){failure.printStackTrace();System.exit(1);}},600);
            Looper.loop();
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
