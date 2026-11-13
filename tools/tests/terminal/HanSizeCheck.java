import android.content.res.AssetManager;
import android.graphics.*;
import com.termux.terminal.*;
import com.termux.view.*;
import java.io.*;

public final class HanSizeCheck {
    static int checks;
    static final int W=400,H=250;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
    static Bitmap draw(String text,String style,int size,boolean before){
        TerminalEmulator e=RenderComparison.emulator();e.resize(12,4,12,24);
        RenderComparison.feed(e,"\033[2;5H\033["+style+"m"+text+"\033[0m");
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);
        if(before)new HanSizeBeforeRenderer(size,BundledTerminalFonts.typeface()).render(e,c,0,-1,-1,-1,-1);
        else new TerminalRenderer(size,BundledTerminalFonts.typeface()).render(e,c,0,-1,-1,-1,-1);
        check(e.getScreen().getSelectedText(4,1,5,1).equals(text),"glyph rendering preserves copy text");
        return b;
    }
    static Rect ink(Bitmap b){
        Rect r=new Rect(W,H,0,0);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)if((b.getPixel(x,y)&0xffffff)!=0){r.left=Math.min(r.left,x);r.top=Math.min(r.top,y);r.right=Math.max(r.right,x+1);r.bottom=Math.max(r.bottom,y+1);}
        return r;
    }
    public static void main(String[]args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/han-size-check.txt"));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager am=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[0]);BundledTerminalFonts.initialize(am);
            for(int size:new int[]{18,28,40}){
                TerminalRenderer r=new TerminalRenderer(size,BundledTerminalFonts.typeface());
                for(String text:new String[]{"中","文","终","端","国","田","器","㐀"})for(String style:new String[]{"0","1","3","1;3"}){
                    Bitmap a=draw(text,style,size,true),b=draw(text,style,size,false);Rect old=ink(a),now=ink(b);
                    float left=4*r.getFontWidth(),right=6*r.getFontWidth();
                    check(now.left>=Math.floor(left)-1&&now.right<=Math.ceil(right)+1,"Han ink stays in its two columns: "+text+" style="+style+" size="+size+" "+now);
                    Paint metrics=new Paint();metrics.setTypeface(BundledTerminalFonts.typeface());metrics.setTextSize(size);
                    float rowTop=2*r.getFontLineSpacing()+(float)Math.ceil(metrics.ascent());
                    check(now.top>=rowTop-1&&now.bottom<=rowTop+r.getFontLineSpacing()+1,"Han ink stays in its row: "+text+" "+now);
                    if(style.equals("0")&&!text.equals("㐀")){
                        check(now.width()>old.width()&&now.height()>old.height(),"Maple Han actually enlarged: "+text+" size="+size+" "+old+" -> "+now);
                        System.out.println("PASS size="+size+" "+text+" ink "+old.width()+"x"+old.height()+" -> "+now.width()+"x"+now.height());
                    }
                    a.recycle();b.recycle();
                }
                for(String style:new String[]{"0","1","3","1;3"}){
                    TerminalEmulator table=RenderComparison.emulator();table.resize(18,4,12,24);
                    RenderComparison.feed(table,"\033[2;2H\033["+style+"m|中|文|终|端|\033[0m");
                    Bitmap a=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);Canvas ca=new Canvas(a),cb=new Canvas(b);ca.drawColor(Color.BLACK);cb.drawColor(Color.BLACK);
                    new HanSizeBeforeRenderer(size,BundledTerminalFonts.typeface()).render(table,ca,0,-1,-1,-1,-1);r.render(table,cb,0,-1,-1,-1,-1);
                    for(int column:new int[]{1,4,7,10,13}){
                        int from=(int)Math.ceil((column+.25f)*r.getFontWidth()),to=(int)Math.floor((column+.75f)*r.getFontWidth());
                        boolean same=true;for(int x=from;x<to;x++)for(int y=0;y<H;y++)same&=a.getPixel(x,y)==b.getPixel(x,y);
                        check(same,"ASCII table border untouched next to Han: column="+column+" style="+style+" size="+size);
                    }
                    a.recycle();b.recycle();
                }
                for(String control:new String[]{"AB","，","😀","\uf120","\u23be"}){
                    TerminalEmulator e=RenderComparison.emulator();e.resize(12,4,12,24);RenderComparison.feed(e,"\033[2;5H"+control);
                    Bitmap a=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);Canvas ca=new Canvas(a),cb=new Canvas(b);ca.drawColor(Color.BLACK);cb.drawColor(Color.BLACK);
                    new HanSizeBeforeRenderer(size,BundledTerminalFonts.typeface()).render(e,ca,0,-1,-1,-1,-1);r.render(e,cb,0,-1,-1,-1,-1);
                    check(a.sameAs(b),"non-Han pixels unchanged: "+control+" size="+size);a.recycle();b.recycle();
                }
            }
            System.out.println("ALL PASS "+checks+" Han enlargement, normal/bold/italic/bold-italic containment, copy and non-Han controls");
        }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
