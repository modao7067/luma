package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.IOException;
import java.util.ArrayList;
import android.util.LongSparseArray;

/** The terminal's font sources are APK assets, including the missing-glyph box. */
public final class BundledTerminalFonts {
    /** Selected Maple variants, shared with the GUI's Maple-derived font. */
    public static final String FEATURE_SETTINGS =
        "'cv01' 1, 'cv02' 1, 'cv05' 1, 'cv61' 1, 'cv62' 1, 'cv96' 1, 'cv97' 1, 'cv98' 1";

    private static final ArrayList<Font> fonts=new ArrayList<>();
    private static final LongSparseArray<Font> assetSources=new LongSparseArray<>();
    private static Typeface typeface;
    private static final char[] REPLACEMENT={'\ufffd'};
    private static final String[] FILES={
        "MapleMonoNL-NF-CN-Regular.ttf",
        "JetBrainsMonoNLNerdFontMono-Regular.ttf",
        "NotoSansMono-Regular.ttf", "NotoSansSymbols-Regular.ttf",
        "NotoSansMath-Regular.ttf", "NotoMusic-Regular.ttf",
        "NotoColorEmoji.ttf", "NotoSansSymbols2-Regular.ttf",
        "LumaCjkForms-Regular.otf"
    };

    public static synchronized void initialize(AssetManager assets){
        if(typeface!=null)return;
        ArrayList<Font> loaded=new ArrayList<>();
        Typeface.CustomFallbackBuilder chain=null;
        try{
            for(String file:FILES){
                Font font=new Font.Builder(assets,file).setWeight(400).build();
                loaded.add(font);
                FontFamily family=new FontFamily.Builder(font).build();
                if(chain==null)chain=new Typeface.CustomFallbackBuilder(family);
                else chain.addCustomFallback(family);
            }
            Typeface built=chain.build();
            fonts.addAll(loaded);
            for(Font font:loaded)assetSources.put(sourceKey(font),font);
            typeface=built;
        }catch(IOException e){throw new IllegalStateException("Unable to load bundled terminal fonts",e);}
    }
    public static Typeface typeface(){
        if(typeface==null)throw new IllegalStateException("Terminal fonts not initialized");
        return typeface;
    }
    private static long sourceKey(Font font){return ((long)font.getSourceIdentifier()<<32)|(font.getTtcIndex()&0xffffffffL);}
    private static Font assetSource(Font shapedFont){return assetSources.get(sourceKey(shapedFont));}
    /** Android always appends system fallback; reject it before measuring or drawing. */
    static PositionedGlyphs shape(char[] text,int start,int count,Paint paint){
        PositionedGlyphs glyphs=TextRunShaper.shapeTextRun(text,start,count,start,count,0,0,false,paint);
        for(int i=0;i<glyphs.glyphCount();i++){
            if(glyphs.getGlyphId(i)==0 || assetSource(glyphs.getFont(i))==null){
                // Non-ASCII terminal cells are isolated by the renderer. A missing
                // character or combining cluster gets our own replacement glyph.
                return TextRunShaper.shapeTextRun(REPLACEMENT,0,1,0,1,0,0,false,paint);
            }
        }
        return glyphs;
    }
    /** Draw only explicit asset Font objects; Canvas cannot select system fonts here. */
    static void draw(Canvas canvas,PositionedGlyphs glyphs,Paint paint){
        new PreparedGlyphs(glyphs).draw(canvas,paint);
    }
    /** Cached shaping and draw arrays; colors and decorations remain draw-time state. */
    static final class PreparedGlyphs {
        final PositionedGlyphs glyphs;
        final int[] ids;
        final float[] positions;
        final Font[] sources;
        android.graphics.Rect hanBounds;
        android.graphics.Rect connectorBounds;
        PreparedGlyphs(PositionedGlyphs glyphs){
            this.glyphs=glyphs;int count=glyphs.glyphCount();
            ids=new int[count];positions=new float[count*2];sources=new Font[count];
            for(int i=0;i<count;i++){
                ids[i]=glyphs.getGlyphId(i);positions[i*2]=glyphs.getGlyphX(i);positions[i*2+1]=glyphs.getGlyphY(i);
                sources[i]=assetSource(glyphs.getFont(i));
                if(sources[i]==null)throw new IllegalStateException("Unexpected terminal font source");
            }
        }
        int bytes(){return 64+ids.length*24;}
        void draw(Canvas canvas,Paint paint){
        int count=glyphs.glyphCount();
        int start=0;
        while(start<count){
            Font asset=sources[start];
            int end=start+1;
            while(end<count && asset==sources[end])end++;
            canvas.drawGlyphs(ids,start,positions,start*2,end-start,asset,paint);
            start=end;
        }
        }
    }
}
