package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class FontCoverageCheck {
    public static void main(String[] args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/font-coverage-check.txt"));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
            BundledTerminalFonts.initialize(assets);
            Paint paint=new Paint();paint.setTypeface(BundledTerminalFonts.typeface());paint.setTextSize(22);
            int checked=0;List<String> failures=new ArrayList<>();
            for(String row:Files.readAllLines(Path.of(args[1],"..","font-codepoints.txt"))) {
                int cp=Integer.parseInt(row);char[] text=Character.toChars(cp);
                PositionedGlyphs raw=TextRunShaper.shapeTextRun(text,0,text.length,0,text.length,0,0,false,paint);
                boolean missing=raw.glyphCount()==0;
                for(int i=0;i<raw.glyphCount();i++)missing|=raw.getGlyphId(i)==0;
                try {new BundledTerminalFonts.PreparedGlyphs(raw);}catch(IllegalStateException e){missing=true;}
                if(missing)failures.add(String.format("U+%04X",cp));
                checked++;
            }
            if(!failures.isEmpty())throw new AssertionError("Missing/system fallback glyphs: "+failures);
            System.out.println("ALL PASS "+checked+" assigned characters shaped from bundled assets; no replacement/system fallback, including U+FF65");
        }catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
