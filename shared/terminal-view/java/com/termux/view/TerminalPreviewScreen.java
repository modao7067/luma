package com.termux.view;

import android.graphics.*;
import android.os.*;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TerminalPreviewScreen implements AutoCloseable {
    final TerminalRenderer renderer;
    final RectF frame=new RectF(),body=new RectF();
    GhosttyTerminalEmulator emulator;
    private final Looper owner;
    private final Handler handler;
    private final int[] palette;
    private final Paint border=new Paint(),fill=new Paint(),imagePaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float stroke,inset;
    private PreparedText text;
    private Bitmap image;
    private int layoutWidth,layoutHeight,feedOffset;
    private static final long LOAD_INVALIDATE_INTERVAL_MS=100L;
    private final byte[] chunk=new byte[32768];
    private boolean closed,loading,firstChunkNotified;
    private long lastLoadInvalidatedAt;
    private String initializationError;
    private Runnable invalidator=()->{};
    int topRow;

    public static final class PreparedText {
        final byte[] ansi;final int[] widths,counts;final int longest;
        private PreparedText(byte[] ansi,int[] widths,int[] counts,int longest){this.ansi=ansi;this.widths=widths;this.counts=counts;this.longest=longest;}
    }
    public static PreparedText prepareText(byte[] data){
        int end=data.length;boolean newline=false;
        while(end>0){
            int c=data[end-1];if(c=='\r'||c=='\n'){newline=true;end--;continue;}
            if(c=='m'){int start=end-2;while(start>=0&&((data[start]>='0'&&data[start]<='9')||data[start]==';'||data[start]==':'))start--;
                if(start>=1&&data[start]=='['&&data[start-1]==27){end=start-1;continue;}}
            break;
        }
        byte[] ansi=newline?Arrays.copyOf(data,end+4):data.clone();
        if(newline){ansi[end]=27;ansi[end+1]='[';ansi[end+2]='0';ansi[end+3]='m';}
        String plain=new String(ansi,StandardCharsets.UTF_8).replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]","");
        Map<Integer,Integer> lineCounts=new HashMap<>();int width=0,longest=12;
        for(int i=0;i<plain.length();){int cp=plain.codePointAt(i);i+=Character.charCount(cp);
            if(cp=='\n'){lineCounts.merge(width,1,Integer::sum);longest=Math.max(longest,width);width=0;}
            else if(cp=='\r'){}else if(cp=='\t')width+=8-width%8;else width+=Math.max(0,WcWidth.width(cp));
        }
        lineCounts.merge(width,1,Integer::sum);longest=Math.max(longest,width);
        int[] widths=new int[lineCounts.size()],counts=new int[lineCounts.size()];int at=0;
        for(Map.Entry<Integer,Integer> line:lineCounts.entrySet()){widths[at]=line.getKey();counts[at++]=line.getValue();}
        return new PreparedText(ansi,widths,counts,longest);
    }
    public TerminalPreviewScreen(int textSize,int[] palette){
        owner=Looper.myLooper();if(owner==null)throw new IllegalStateException("Preview requires an owner Looper");handler=new Handler(owner);
        renderer=new TerminalRenderer(textSize,BundledTerminalFonts.typeface());this.palette=palette.clone();
        stroke=borderWidth(renderer);inset=contentInset(renderer);border.setColor(0xff808080);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(stroke);
    }
    private void checkOwner(){if(Looper.myLooper()!=owner)throw new IllegalStateException("Preview access requires its owner Looper");}
    private void usable(){checkOwner();if(closed)throw new IllegalStateException("Preview closed");}
    static float borderWidth(TerminalRenderer r){return Math.max(2,Math.round(r.mTextSize/10f));}
    static float contentInset(TerminalRenderer r){return borderWidth(r)+Math.max(6,Math.round(r.mFontLineSpacing*.75f));}
    static int contentColumns(int width,TerminalRenderer r){return Math.max(2,(int)((width-2*r.mFontWidth-2*contentInset(r))/r.mFontWidth));}
    public void setInvalidator(Runnable callback){usable();invalidator=callback;}
    public void text(byte[] data){text(prepareText(data));}
    public void text(PreparedText prepared){usable();text=prepared;image=null;layoutWidth=layoutHeight=0;}
    public void image(Bitmap bitmap){usable();image=Objects.requireNonNull(bitmap);text=null;layoutWidth=layoutHeight=0;}
    public boolean isImage(){checkOwner();return image!=null;}
    public boolean isLoading(){checkOwner();return loading;}
    private void releaseCore(){handler.removeCallbacks(feedMore);loading=false;if(emulator!=null){emulator.dispose();emulator=null;}renderer.clearRowCache();}
    public void layout(int width,int height){
        usable();if(width==layoutWidth&&height==layoutHeight)return;
        if(width<8||height<8)return;releaseCore();layoutWidth=width;layoutHeight=height;
        float maxW=Math.max(4,width-2*renderer.mFontWidth-2*inset),maxH=Math.max(4,height-2*renderer.mFontLineSpacing-2*inset),w,h;
        int cols=2,rows=2;
        if(image!=null){float scale=Math.min(maxW/image.getWidth(),maxH/image.getHeight());w=Math.max(1,image.getWidth()*scale);h=Math.max(1,image.getHeight()*scale);}
        else{
            cols=Math.min(contentColumns(width,renderer),text==null?12:Math.max(2,text.longest));w=cols*renderer.mFontWidth;
            long lines=0;if(text!=null)for(int i=0;i<text.widths.length;i++)lines+=Math.max(1,(text.widths[i]+cols-1L)/cols)*text.counts[i];else lines=1;
            int visibleRows=(int)Math.min(Math.max(1,lines),Math.max(1,(int)(maxH/renderer.mFontLineSpacing)));rows=Math.max(2,visibleRows);h=visibleRows*renderer.mFontLineSpacing;
        }
        frame.set((width-w)/2-inset,(height-h)/2-inset,(width+w)/2+inset,(height+h)/2+inset);
        body.set(frame.left+inset,frame.top+inset,frame.right-inset,frame.bottom-inset);topRow=0;
        if(image!=null)return;
        initializationError=null;
        TerminalOutput output=new TerminalOutput(){
            public void write(byte[]b,int o,int n){}public void titleChanged(String a,String b){}public void onCopyTextToClipboard(String s){}public void onPasteTextFromClipboard(){}public void onBell(){}public void onColorsChanged(){}public void onScreenChanged(){if(!loading)invalidator.run();}
        };
        try {emulator=GhosttyTerminalEmulator.forOutput(output,cols,rows,Math.max(1,Math.round(renderer.mFontWidth)),renderer.mFontLineSpacing,4096,CLIENT,palette);}
        catch(RuntimeException | LinkageError failure){initializationError=com.termux.terminal.LumaLang.tr("libghostty 预览初始化失败，请重新打开","libghostty preview initialization failed; please reopen");invalidator.run();return;}
        byte[] hide="\033[?25l".getBytes(StandardCharsets.UTF_8);emulator.append(hide,hide.length);feedOffset=0;
        firstChunkNotified=false;lastLoadInvalidatedAt=0L;
        loading=text!=null&&text.ansi.length>0;if(loading)handler.post(feedMore);
    }
    private final Runnable feedMore=()->{
        if(closed||!loading||emulator==null)return;int count=Math.min(chunk.length,text.ansi.length-feedOffset);
        System.arraycopy(text.ansi,feedOffset,chunk,0,count);emulator.append(chunk,count);feedOffset+=count;
        if(feedOffset<text.ansi.length){
            TerminalPreviewScreen.this.handler.post(TerminalPreviewScreen.this.feedMore);
            long now=SystemClock.uptimeMillis();
            if(!firstChunkNotified||now-lastLoadInvalidatedAt>=LOAD_INVALIDATE_INTERVAL_MS){
                firstChunkNotified=true;lastLoadInvalidatedAt=now;invalidator.run();
            }
        }else{
            loading=false;topRow=-emulator.getViewportHistoryRows();emulator.setViewportTopRow(topRow);
            firstChunkNotified=true;lastLoadInvalidatedAt=SystemClock.uptimeMillis();invalidator.run();
        }
    };
    public RectF bounds(){checkOwner();return new RectF(frame);}
    public int lineHeight(){checkOwner();return renderer.mFontLineSpacing;}
    public void scroll(int rows){usable();if(emulator!=null){topRow=Math.max(-emulator.getViewportHistoryRows(),Math.min(0,topRow+rows));emulator.setViewportTopRow(topRow);}}
    public String plainText(){usable();return emulator==null?"":emulator.getSelectedText(0,-emulator.getViewportHistoryRows(),emulator.mColumns-1,emulator.mRows-1).trim();}
    public void draw(Canvas canvas){
        usable();if(layoutWidth==0)return;
        canvas.drawColor(0x22000000);fill.setColor(palette[TextStyle.COLOR_INDEX_BACKGROUND]|0xff000000);canvas.drawRect(frame,fill);
        canvas.save();canvas.clipRect(body);
        if(image!=null)canvas.drawBitmap(image,null,body,imagePaint);
        else if(initializationError!=null){fill.setColor(0xffeeeeee);fill.setTextSize(renderer.mTextSize);canvas.drawText(initializationError,body.left,body.top+renderer.mFontLineSpacing,fill);}
        else if(emulator!=null){canvas.translate(body.left,body.top-renderer.mFontLineSpacingAndAscent);renderer.render(emulator,canvas,topRow,-1,-1,-1,-1);}
        canvas.restore();canvas.drawRect(frame.left+stroke/2,frame.top+stroke/2,frame.right-stroke/2,frame.bottom-stroke/2,border);
    }
    @Override public void close(){checkOwner();if(closed)return;releaseCore();closed=true;image=null;text=null;invalidator=()->{};}
    private static final TerminalSessionClient CLIENT=new TerminalSessionClient(){
        public void onTextChanged(TerminalSession s){}public void onTitleChanged(TerminalSession s){}public void onSessionFinished(TerminalSession s){}public void onCopyTextToClipboard(TerminalSession s,String t){}public void onPasteTextFromClipboard(TerminalSession s){}public void onBell(TerminalSession s){}public void onColorsChanged(TerminalSession s){}public void onTerminalCursorStateChange(boolean s){}public void setTerminalShellPid(TerminalSession s,int p){}public Integer getTerminalCursorStyle(){return 1;}
        public void logError(String t,String m){}public void logWarn(String t,String m){}public void logInfo(String t,String m){}public void logDebug(String t,String m){}public void logVerbose(String t,String m){}public void logStackTraceWithMessage(String t,String m,Exception e){}public void logStackTrace(String t,Exception e){}
    };
}
