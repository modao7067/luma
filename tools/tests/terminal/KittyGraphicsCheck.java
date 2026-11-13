import android.content.res.AssetManager;
import android.graphics.*;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

public final class KittyGraphicsCheck {
    private static int checks;
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);System.out.flush();}
    private static void feed(TerminalEmulator e,String text){ImageCheck.feed(e,text);}
    private static String packet(String controls,String payload){return "\033_G"+controls+(payload==null?"":";"+payload)+"\033\\";}
    private static String encoded(byte[] bytes){return ImageCheck.encoded(bytes);}
    private static byte[] compressed(byte[] bytes)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(DeflaterOutputStream deflate=new DeflaterOutputStream(out)){deflate.write(bytes);}return out.toByteArray();}
    private static void send(TerminalEmulator e,String controls,byte[] data){
        String encoded=encoded(data);
        for(int i=0;i<encoded.length();i+=4096)feed(e,packet((i==0?controls+",":"")+"m="+(i+4096<encoded.length()?1:0),encoded.substring(i,Math.min(i+4096,encoded.length()))));
    }
    private static KittyGraphics graphics(TerminalEmulator e){return e.getScreen().getKittyGraphics();}
    private static List<KittyGraphics.Draw> visible(TerminalEmulator e){return graphics(e).visible(0,e.mRows,12,24,0);}
    private static String replies(ImageCheck.Output out)throws Exception{return out.replies.toString("UTF-8");}
    private static TerminalEmulator fresh(){return ImageCheck.emulator();}
    private static void png(TerminalEmulator e,long id){send(e,"a=T,f=100,C=1,i="+id,ImageCheck.png(24,24));}
    private static int countColor(byte[] rgba,int red,int green,int blue){int count=0;for(int i=0;i<rgba.length;i+=4)if((rgba[i]&255)==red&&(rgba[i+1]&255)==green&&(rgba[i+2]&255)==blue)count++;return count;}

    private static void protocol()throws Exception{
        byte[] picture=ImageCheck.png(24,24);ImageCheck.Output out=new ImageCheck.Output();TerminalEmulator e=ImageCheck.emulator(out);
        feed(e,packet("a=q,t=d,f=24,s=1,v=1,i=31","AAAA")+"\033[c");
        check(replies(out).startsWith("\033_Gi=31;OK\033\\")&&replies(out).indexOf("\033[?")>0,"support query replies immediately before device attributes");
        check(graphics(e).getImageCount()==0&&graphics(e).getPlacementCount()==0,"query does not retain or display an image");
        out.replies.reset();send(e,"a=t,f=100,i=7",picture);
        check(graphics(e).getImageCount()==1&&visible(e).isEmpty()&&e.getCursorRow()==0&&e.getCursorCol()==0,"transmit-only stores PNG without changing text or cursor");
        feed(e,packet("a=p,i=7,p=1,C=1",null));KittyGraphics.Draw draw=visible(e).get(0);
        check(draw.bitmap.getPixel(1,1)==Color.RED&&draw.bitmap.getPixel(20,1)==Color.GREEN,"PNG source pixels preserved through APC decoding");
        check(replies(out).contains("i=7,p=1;OK"),"placement acknowledgement includes image and placement IDs");
        feed(e,"HELLO");check(e.getScreen().getTranscriptText().equals("HELLO")&&visible(e).size()==1,"text writes do not overwrite a Kitty image placement");
        feed(e,packet("a=d,d=i,i=7",null));
        check(visible(e).isEmpty()&&graphics(e).getImageCount()==1&&e.getScreen().getTranscriptText().equals("HELLO"),"soft deletion preserves stored pixels and underlying text");
        feed(e,packet("a=p,i=7,p=2,C=1",null)+"\033[4;5H"+packet("a=p,i=7,p=3,C=1",null));
        check(visible(e).size()==2&&graphics(e).getImageCount()==1,"one image can be placed repeatedly without duplicating pixel storage");
        feed(e,"\033[6;7H"+packet("a=p,i=7,p=2,C=1",null));
        check(visible(e).size()==2&&visible(e).stream().anyMatch(d->d.destination.left==72&&d.destination.top==120),"same nonzero placement ID moves the existing placement");
        feed(e,packet("a=d,d=I,i=7,p=2",null));check(graphics(e).getImageCount()==1&&visible(e).size()==1,"hard-delete keeps source pixels still used by another placement");
        feed(e,packet("a=d,d=I,i=7,p=3",null));check(graphics(e).getImageCount()==0&&visible(e).isEmpty(),"last hard-deleted placement releases the stored image");
        e=fresh();String full=packet("a=T,f=100,i=9,C=1",encoded(picture));for(byte b:full.getBytes(StandardCharsets.UTF_8))e.append(new byte[]{b},1);
        check(visible(e).size()==1,"APC image and ST can arrive one byte at a time");
        e=fresh();String data=encoded(picture);feed(e,packet("a=T,f=100,i=4,m=1,C=1",data.substring(0,16)));
        check(graphics(e).hasPendingTransfer()&&graphics(e).getImageCount()==0,"partial multipart transfer remains invisible");
        feed(e,"\033[3;4H");for(int i=16;i<data.length();i+=16)feed(e,packet("m="+(i+16<data.length()?1:0),data.substring(i,Math.min(i+16,data.length()))));
        draw=visible(e).get(0);check(draw.destination.left==36&&draw.destination.top==48,"multipart placement uses cursor position at the final chunk");
        out=new ImageCheck.Output();e=ImageCheck.emulator(out);
        send(e,"a=T,f=24,s=2,v=1,i=10,C=1",new byte[]{(byte)255,0,0,0,(byte)255,0});draw=visible(e).get(0);
        check(draw.bitmap.getPixel(0,0)==Color.RED&&draw.bitmap.getPixel(1,0)==Color.GREEN,"packed RGB upload decoded exactly");
        send(e,"a=T,f=32,s=1,v=1,i=11,C=1",new byte[]{(byte)255,0,0,(byte)128});draw=visible(e).get(1);
        check(Color.alpha(draw.bitmap.getPixel(0,0))==128,"RGBA upload preserves alpha");
        send(e,"a=T,f=24,s=2,v=1,o=z,i=12,C=1",compressed(new byte[]{0,0,(byte)255,(byte)255,0,0}));
        check(visible(e).get(2).bitmap.getPixel(0,0)==Color.BLUE,"zlib-compressed RGB decoded with the exact size budget");
        send(e,"a=T,f=100,o=z,S="+picture.length+",i=13,C=1",compressed(picture));
        check(graphics(e).getImageCount()==4&&visible(e).get(3).bitmap.getPixel(20,1)==Color.GREEN,"compressed PNG uses declared decompressed size");
        send(e,"a=q,f=100,i=10",picture);check(visible(e).size()==4&&visible(e).get(0).bitmap.getWidth()==2,"query using an existing ID never replaces its data or placements");
        send(e,"a=t,f=100,i=10",picture);check(visible(e).size()==3,"retransmission of an image ID removes its old placements");
        feed(e,packet("a=p,i=10,C=1",null));check(visible(e).get(0).bitmap.getWidth()==24,"retransmitted pixels replace the old source");
        e=fresh();png(e,4294967295L);check(visible(e).size()==1,"full unsigned 32-bit image IDs are supported");
        feed(e,packet("a=d,d=R,x=4294967295,y=4294967295",null));check(graphics(e).getImageCount()==0,"unsigned image ID ranges can be hard-deleted");
        for(String mode:new String[]{"a", "c", "p,x=1,y=1", "q,x=1,y=1,z=0", "x,x=1", "y,y=1", "z,z=0"}){
            e=fresh();png(e,1);feed(e,packet("a=d,d="+mode,null));
            check(visible(e).isEmpty()&&graphics(e).getImageCount()==1,"deletion selector removes placement without freeing reusable pixels: "+mode);
        }
        out=new ImageCheck.Output();e=ImageCheck.emulator(out);send(e,"a=t,f=100,I=55",picture);send(e,"a=t,f=100,I=55",picture);
        check(graphics(e).getImageCount()==2&&replies(out).contains("I=55;OK"),"image numbers allocate distinct IDs and acknowledge the number");
        feed(e,packet("a=p,I=55,C=1",null));check(visible(e).size()==1,"put by image number selects only the newest image");
        feed(e,packet("a=d,d=N,I=55",null));check(graphics(e).getImageCount()==1,"hard-delete by image number removes only its newest image");
        e=fresh();png(e,1);feed(e,packet("a=p,i=1,C=1",null)+packet("a=p,i=1,C=1",null));check(visible(e).size()==3,"omitting placement IDs creates separate placements");
        e=fresh();send(e,"a=T,f=100,i=1,c=4,r=2,C=1",picture);draw=visible(e).get(0);
        check(draw.destination.width()==48&&draw.destination.height()==48&&e.getCursorRow()==0,"cell rectangle scales an image and C=1 keeps the cursor");
        e=fresh();send(e,"a=T,f=100,i=1,c=4,r=4,C=1",picture);draw=visible(e).get(0);
        check(draw.destination.width()==48&&draw.destination.height()==48&&draw.destination.top==24,"fitting both axes letterboxes without distorting aspect ratio");
        draw=graphics(e).visible(0,e.mRows,20,30,0).get(0);
        check(draw.destination.width()==draw.destination.height(),"different renderer cell metrics preserve the image aspect ratio");
        e=fresh();send(e,"a=T,f=100,i=1,x=12,y=0,w=99,h=24,c=1,C=1,X=2,Y=3",picture);draw=visible(e).get(0);
        check(draw.source.equals(new Rect(12,0,24,24))&&draw.destination.left==2&&draw.destination.top==3,"crop intersects the source and honors sub-cell pixel offsets");
        e=fresh();send(e,"a=T,f=100,i=1",picture);check(e.getCursorRow()==1&&e.getCursorCol()==2,"default placement advances the cursor by its cell rectangle");
    }

    private static void errors()throws Exception{
        ImageCheck.Output out=new ImageCheck.Output();TerminalEmulator e=ImageCheck.emulator(out);png(e,1);out.replies.reset();
        String[] bad={"a=T,f=24,s=2,v=2,i=2", "a=T,f=100,t=f,i=2", "a=T,f=100,t=s,i=2", "a=T,f=100,U=1,i=2",
            "a=p,i=1,P=1,Q=1", "a=f,i=1", "a=T,f=25,i=2", "a=t,i=2,I=3", "a=p,i=999", "a=p,i=1,X=12",
            "a=p,i=1,c=4097", "a=p,i=1,x=999", "a=t,f=24,s=4096,v=4096,i=2", "a=t,f=24,s=1,v=1,o=z,i=2"};
        for(String controls:bad){out.replies.reset();feed(e,packet(controls,"AAAA"));check(replies(out).contains(";E")&&graphics(e).getImageCount()==1,"invalid/unsupported request rejected without replacing existing images: "+controls);}
        out.replies.reset();feed(e,packet("a=t,f=100,i=2,q=1",encoded(ImageCheck.png(24,24))));check(replies(out).isEmpty(),"q=1 suppresses success responses");
        feed(e,packet("a=p,i=999,q=1",null));check(replies(out).contains("ENOENT"),"q=1 still reports failures");
        out.replies.reset();feed(e,packet("a=p,i=999,q=2",null));check(replies(out).isEmpty(),"q=2 suppresses failure responses");
        out.replies.reset();feed(e,packet("a=t,f=24,s=1,v=1,i=3,m=1","AAAA")+packet("m=0,q=2",""));check(replies(out).isEmpty(),"continuation packet can suppress the final acknowledgement");
        e=fresh();feed(e,packet("a=T,f=100,i=7,m=1", "AAAA")+packet("m=0","%%%="));feed(e,"OK");
        check(graphics(e).getImageCount()==0&&!graphics(e).hasPendingTransfer()&&e.getScreen().getTranscriptText().equals("OK"),"invalid base64 aborts multipart state and never prints payload");
        e=fresh();feed(e,"\033_Ga=T,f=100,i=7;AAAA\030OK");check(e.getScreen().getTranscriptText().equals("OK"),"CAN aborts an incomplete APC and restores text parsing");
        e=fresh();feed(e,"\033_Ga=T,f=100,i=7;AAAA\032OK");check(e.getScreen().getTranscriptText().equals("OK"),"SUB aborts an incomplete APC and restores text parsing");
        e=fresh();feed(e,"\033_Ga=T,f=100,i=7;"+"A".repeat(20000)+"\033\\OK");check(e.getScreen().getTranscriptText().equals("OK"),"oversized APC drains through ST without leaking base64 text");
        e=fresh();feed(e,packet("a=T,f=24,s=1,v=1,i=7", "A".repeat(4100))+"OK");check(graphics(e).getImageCount()==0&&e.getScreen().getTranscriptText().equals("OK"),"per-packet 4096-character limit is enforced");
        out=new ImageCheck.Output();e=ImageCheck.emulator(out);
        feed(e,packet("a=t,f=24,s=1,v=1,i=6,m=1","AAAA"));for(int i=0;i<2731;i++)feed(e,packet("m=1","A".repeat(4096)));
        check(!graphics(e).hasPendingTransfer()&&graphics(e).getImageBytes()==0&&replies(out).contains("E2BIG"),"aggregate decoded upload bytes are bounded before bitmap allocation");
        out=new ImageCheck.Output();e=ImageCheck.emulator(out);byte[] bomb=new byte[4096];send(e,"a=t,f=24,s=1,v=1,o=z,i=7",compressed(bomb));
        check(replies(out).contains("EINVAL")&&graphics(e).getImageCount()==0,"zlib expansion beyond declared dimensions is rejected");
        byte[] png=ImageCheck.png(24,24);out.replies.reset();send(e,"a=t,f=100,o=z,S="+(png.length+1)+",i=7",compressed(png));
        check(replies(out).contains("EINVAL")&&graphics(e).getImageCount()==0,"truncated compressed PNG is rejected");
        out.replies.reset();send(e,"a=t,f=100,i=7",ImageCheck.png(4096,2048));
        check(replies(out).contains("E2BIG")&&graphics(e).getImageCount()==0,"PNG pixel dimensions are checked before decode allocation");
        e=fresh();png(e,1);for(int i=1;i<KittyGraphics.MAX_PLACEMENTS;i++)feed(e,packet("a=p,i=1,C=1",null));
        feed(e,packet("a=p,i=1,C=1",null));check(graphics(e).getPlacementCount()==KittyGraphics.MAX_PLACEMENTS,"placement metadata has a fixed bound");
    }

    private static void lifetime(String directory)throws Exception{
        TerminalEmulator e=fresh();png(e,1);String original=e.getScreen().getTranscriptText();feed(e,"\033[?1049h");
        check(e.getScreen().getKittyGraphics()==null||graphics(e).getPlacementCount()==0,"alternate screen starts with no Kitty placements");
        png(e,1);feed(e,"\033[?1049l");check(graphics(e).getImageCount()==1&&visible(e).size()==1&&e.getScreen().getTranscriptText().equals(original),"main buffer restores its own image namespace and placements");
        feed(e,"\033[?1049h");check(graphics(e).getImageCount()==0,"entering alternate screen clears its previous image data");feed(e,"\033[?1049l");
        feed(e,"\033[2J");check(visible(e).isEmpty()&&graphics(e).getImageCount()==1,"erase display removes visible placements but retains reusable source data");
        feed(e,packet("a=p,i=1,C=1",null));e.reset();check(graphics(e).getImageCount()==0&&graphics(e).getPlacementCount()==0,"terminal reset clears Kitty images and placements");
        feed(e,packet("a=T,f=100,i=2,m=1","AAAA"));e.reset();feed(e,"OK");check(!graphics(e).hasPendingTransfer()&&e.getScreen().getTranscriptText().contains("OK"),"reset cancels a partial transfer");
        e=fresh();png(e,1);feed(e,"\033[3J");check(visible(e).size()==1,"clear scrollback preserves visible Kitty placement");
        for(int i=0;i<30;i++)feed(e,"line\r\n");
        check(graphics(e).visible(-e.getScreen().getActiveTranscriptRows(),e.mRows,12,24,0).size()==1,"image anchor scrolls into retained history");
        for(int i=0;i<120;i++)feed(e,"line\r\n");check(graphics(e).getPlacementCount()==0,"reusing a history row cannot resurrect an old image anchor");
        e=fresh();send(e,"a=T,f=100,i=1,C=1",ImageCheck.png(24,2400));for(int i=0;i<125;i++)feed(e,"line\r\n");
        check(graphics(e).getPlacementCount()==1&&!graphics(e).visible(-e.getScreen().getActiveTranscriptRows(),e.mRows,12,24,0).isEmpty(),"a tall image retains its visible tail after its original anchor leaves the history ring");
        for(int i=0;i<125;i++)feed(e,"line\r\n");check(graphics(e).getPlacementCount()==0,"tall image placement is released when its last row leaves history");
        e=fresh();png(e,1);e.resize(60,18,16,30);check(graphics(e).getPlacementCount()==1&&!graphics(e).visible(-e.getScreen().getActiveTranscriptRows(),e.mRows,16,30,0).isEmpty(),"resize/reflow preserves an image anchored on otherwise blank text");
        e=fresh();feed(e,"\033[3;1H");png(e,1);feed(e,"\033[1;1H\033[L");check(visible(e).get(0).destination.top==72,"insert-line scrolling moves the independent image anchor");
        e=fresh();feed(e,ImageCheck.image(ImageCheck.png(24,24),""));for(int i=1;i<=30;i++)png(e,i);
        check(e.getScreen().getTerminalBitmapCount()<=24&&e.getScreen().getTerminalBitmapBytes()<=8*1024*1024,"Kitty and iTerm images share one count/byte quota");
        e=fresh();byte[] large=ImageCheck.png(900,900);for(int i=1;i<=4;i++)send(e,"a=T,f=100,C=1,i="+i,large);
        check(e.getScreen().getTerminalBitmapBytes()<=8*1024*1024&&graphics(e).getImageCount()==2,"repeated large Kitty images evict old pixels within the shared budget");
        e=fresh();byte[] helper=Files.readAllBytes(Path.of(directory,"show-kitty-output.bin"));e.append(helper,helper.length);
        check(graphics(e).getImageCount()==1&&graphics(e).getPlacementCount()==1&&visible(e).get(0).destination.width()==480,"real helper PTY output uses the Kitty parser and cell sizing");
    }

    private static void render(String directory)throws Exception{
        TerminalRenderer renderer=new TerminalRenderer(22,BundledTerminalFonts.typeface());TerminalEmulator e=fresh();
        send(e,"a=T,f=100,i=1,c=4,r=2,C=1",ImageCheck.png(48,48));
        try(ImageCheck.Target target=new ImageCheck.Target()){
            byte[] first=target.frame(renderer,e,0);check(countColor(first,255,0,0)>100&&countColor(first,0,255,0)>100,"GPU draws actual Kitty image pixels");
            check(Arrays.equals(first,target.frame(renderer,e,0)),"unchanged Kitty frame reuses text row caches with identical GPU output");
            byte[] soft=ImageCheck.software(renderer,e,0);check(countColor(soft,255,0,0)>100&&countColor(soft,0,255,0)>100,"software canvas draws the same Kitty source colors");
            feed(e,"HELLO");byte[] overlay=target.frame(renderer,e,0);check(countColor(overlay,255,0,0)>100,"positive-z Kitty images remain over subsequently written text");
            feed(e,"\033[1;1H");send(e,"a=T,f=24,s=1,v=1,i=2,c=4,r=2,z=1,C=1",new byte[]{0,0,(byte)255});
            byte[] top=target.frame(renderer,e,0);check(countColor(top,0,0,255)>100&&countColor(top,255,0,0)==0,"higher z-index image overlays lower image");
            feed(e,packet("a=d,d=I,i=2",null));check(Arrays.equals(overlay,target.frame(renderer,e,0)),"deleting the top layer immediately reveals cached lower image and text");
            send(e,"a=T,f=32,s=1,v=1,i=3,c=4,r=2,z=1,C=1",new byte[]{0,0,(byte)255,(byte)128});
            byte[] alpha=target.frame(renderer,e,0);int blended=0;
            for(int i=0;i<alpha.length;i+=4)if((alpha[i]&255)>100&&(alpha[i]&255)<155&&(alpha[i+2]&255)>100&&(alpha[i+2]&255)<155&&(alpha[i+1]&255)==0)blended++;
            check(blended>100,"GPU alpha-blends RGBA placement over an existing image");
            feed(e,packet("a=d,d=I,i=3",null));check(Arrays.equals(overlay,target.frame(renderer,e,0)),"deleting an alpha layer restores exact previous pixels");
            feed(e,packet("a=d,d=I,i=1",null));byte[] deleted=target.frame(renderer,e,0);check(countColor(deleted,255,0,0)==0&&countColor(deleted,0,255,0)==0,"deleted Kitty image cannot survive inside cached RenderNodes");
        }
        for(int z:new int[]{-1,Integer.MIN_VALUE}){
            e=fresh();feed(e,"\033[44m \033[0m \033[1;1H");send(e,"a=T,f=24,s=1,v=1,i=1,c=2,r=1,z="+z+",C=1",new byte[]{(byte)255,0,0});
            try(ImageCheck.Target target=new ImageCheck.Target()){
                byte[] pixels=target.frame(renderer,e,0);
                Paint metrics=new Paint();metrics.setTypeface(BundledTerminalFonts.typeface());metrics.setTextSize(22);
                int cellHeight=renderer.getFontLineSpacing();float textTop=cellHeight+(float)Math.ceil(metrics.ascent());
                RectF rectangle=graphics(e).visible(0,e.mRows,renderer.getFontWidth(),cellHeight,textTop).get(0).destination;
                int red=0,blue=0;
                for(int yy=(int)Math.ceil(rectangle.top)+1;yy<(int)Math.floor(rectangle.bottom)-1;yy++)
                    for(int xx=Math.max(1,(int)Math.ceil(rectangle.left)+1);xx<Math.min((int)renderer.getFontWidth()-1,(int)Math.floor(rectangle.right)-1);xx++){
                    int at=(yy*ImageCheck.Target.W+xx)*4;if((pixels[at]&255)>200)red++;if((pixels[at+2]&255)>200)blue++;
                }
                check(z==-1?red>0&&blue==0:blue>0&&red==0,"negative-z layer ordering relative to colored cell backgrounds: "+z);
                check(Arrays.equals(pixels,target.frame(renderer,e,0)),"negative-z cached GPU frame remains stable: "+z);
                feed(e,packet("a=d,d=I,i=1",null));byte[] gone=target.frame(renderer,e,0);
                check(countColor(gone,255,0,0)==0,"negative-z image deletion invalidates its recorded rows: "+z);
            }
        }
        e=fresh();send(e,"a=T,f=100,i=1,c=20,C=1",ImageCheck.png(240,120));feed(e,"\033[12;1HKitty PNG / RGB / RGBA\r\nIndependent image layer\r\n");
        Bitmap scene=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(scene);canvas.drawColor(Color.BLACK);renderer.render(e,canvas,0,-1,-1,-1,-1);
        try(OutputStream out=new FileOutputStream(directory+"/kitty-scene.png")){scene.compress(Bitmap.CompressFormat.PNG,100,out);}scene.recycle();
    }
    public static void main(String[] args)throws Exception{
        System.setOut(new PrintStream(args[1]+"/kitty-graphics-check.txt"));
        try{
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);BundledTerminalFonts.initialize(assets);
            protocol();errors();lifetime(args[1]);render(args[1]);System.out.println("ALL PASS checks="+checks+" Kitty direct static graphics protocol/software/GPU");
        }catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
