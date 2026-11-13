package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.*;
import com.termux.terminal.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class NativePreviewCheck {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static Bitmap draw(TerminalPreviewScreen screen){Bitmap b=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xff202020);screen.draw(c);return b;}
    public static void main(String[]args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/native-preview-check.txt"));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager am=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[0]);BundledTerminalFonts.initialize(am);
            Method fixture=Class.forName("RenderComparison").getDeclaredMethod("emulator");fixture.setAccessible(true);TerminalEmulator main=(TerminalEmulator)fixture.invoke(null);
            byte[] original="主会话不变 main buffer\r\n".getBytes(StandardCharsets.UTF_8);main.append(original,original.length);String transcript=main.getScreen().getTranscriptText();
            int[] palette=main.mColors.mCurrentColors.clone();TerminalRenderer mainRenderer=new TerminalRenderer(28,BundledTerminalFonts.typeface());
            Bitmap before=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888);Canvas bc=new Canvas(before);bc.drawColor(0xff202020);mainRenderer.render(main,bc,0,-1,-1,-1,-1);
            TerminalPreviewScreen text=new TerminalPreviewScreen(28,palette);text.text("\033[38;2;255;0;0mdef preview(x):\033[0m\r\n    return x + 1\r\n\033[1;3m中文粗斜体\033[0m".getBytes(StandardCharsets.UTF_8));text.layout(1100,850);
            check(text.renderer.mTypeface==BundledTerminalFonts.typeface()&&text.renderer.mTextSize==28,"preview uses existing terminal Typeface and exact size");
            check(TextStyle.decodeForeColor(text.emulator.getScreen().getStyleAt(0,0))==0xffff0000,"ANSI syntax colors reach terminal cells");
            check(text.bounds().height()<500&&text.bounds().width()>text.bounds().height(),"short source file gets a compact landscape frame");
            float gap=text.body.left-text.frame.left;
            check(gap>10&&Math.abs(gap-(text.body.top-text.frame.top))<.01&&Math.abs(gap-(text.frame.right-text.body.right))<.01&&Math.abs(gap-(text.frame.bottom-text.body.bottom))<.01,"equal real padding separates content from every frame edge");
            check(text.body.height()==3*text.renderer.mFontLineSpacing,"short text has no phantom extra bottom row");
            Bitmap rendered=draw(text);int red=0;for(int y=0;y<850;y++)for(int x=0;x<1100;x++){int color=rendered.getPixel(x,y);if(Color.red(color)>180&&Color.green(color)<80&&Color.blue(color)<80)red++;}
            check(red>30,"terminal renderer actually draws syntax colors");
            int borderX=(int)Math.ceil(text.frame.left),borderY=(int)text.frame.centerY();
            check(rendered.getPixel(borderX,borderY)==0xff808080&&rendered.getPixel(borderX+1,borderY)==0xff808080,"gray frame is visibly thicker than one pixel");
            TerminalPreviewScreen ending=new TerminalPreviewScreen(28,palette);ending.text("single line\r\n\r\n\033[0m".getBytes(StandardCharsets.UTF_8));ending.layout(1100,850);
            check(ending.body.height()==ending.renderer.mFontLineSpacing&&ending.plainText().contains("single line"),"trailing newlines and reset do not masquerade as bottom padding");ending.close();
            try(FileOutputStream out=new FileOutputStream(args[1]+"/native-preview-code.png")){rendered.compress(Bitmap.CompressFormat.PNG,100,out);}rendered.recycle();
            text.close();
            File markdownFile=new File(args[1],"markdown.ansi");
            if(markdownFile.isFile()){
                TerminalPreviewScreen md=new TerminalPreviewScreen(28,palette);md.text(java.nio.file.Files.readAllBytes(markdownFile.toPath()));md.layout(1100,850);
                check(md.plainText().contains("排版标题")&&!md.plainText().contains("```")&&!md.plainText().contains("**"),"real guest Markdown is formatted in native terminal buffer");
                int styledCells=0;for(int row=-md.emulator.getScreen().getActiveTranscriptRows();row<md.emulator.mRows;row++)for(int col=0;col<md.emulator.mColumns;col++)if(TextStyle.decodeEffect(md.emulator.getScreen().getStyleAt(row,col))!=0||TextStyle.decodeForeColor(md.emulator.getScreen().getStyleAt(row,col))!=TextStyle.COLOR_INDEX_FOREGROUND)styledCells++;
                check(styledCells>30,"Markdown heading/emphasis/code styles survive native parsing");
                check(md.renderer.mTypeface==BundledTerminalFonts.typeface(),"Markdown retains the terminal font");
                Bitmap markdownPixels=draw(md);try(FileOutputStream out=new FileOutputStream(args[1]+"/native-preview-markdown.png")){markdownPixels.compress(Bitmap.CompressFormat.PNG,100,out);}markdownPixels.recycle();md.close();
            }
            for(int[] dims:new int[][]{{160,90},{90,160},{100,100}}){
                Bitmap source=Bitmap.createBitmap(dims[0],dims[1],Bitmap.Config.ARGB_8888);Canvas imageCanvas=new Canvas(source);imageCanvas.drawColor(0xff00ff00);Paint blue=new Paint();blue.setColor(0xff0000ff);imageCanvas.drawRect(dims[0]/2f,0,dims[0],dims[1],blue);
                TerminalPreviewScreen image=new TerminalPreviewScreen(28,palette);image.image(source);image.layout(1100,850);RectF frame=image.bounds();
                check(Math.abs(image.body.width()/image.body.height()-(double)dims[0]/dims[1])<.01,"adaptive image aspect "+dims[0]+"x"+dims[1]);
                check(image.emulator.getScreen().getTerminalBitmapCount()==1,"preview uses existing terminal image placement/cache");
                check(image.emulator.getScreen().getTerminalBitmapBytes()<=TerminalBuffer.MAX_IMAGE_CACHE_BYTES,"normal terminal bitmap budget enforced");
                Bitmap pixels=draw(image);int left=pixels.getPixel((int)(frame.left+frame.width()*.25),(int)frame.centerY()),right=pixels.getPixel((int)(frame.left+frame.width()*.75),(int)frame.centerY());
                check(Color.green(left)>200&&Color.blue(right)>200,"whole image appears in frame without incorrect crop");
                Class<?> cls=Class.forName("CacheRegression$Target");Constructor<?> ctor=cls.getDeclaredConstructor();ctor.setAccessible(true);Object target=ctor.newInstance();
                try{
                    Field nodeField=cls.getDeclaredField("node");nodeField.setAccessible(true);RenderNode node=(RenderNode)nodeField.get(target);
                    Method read=cls.getDeclaredMethod("image");read.setAccessible(true);
                    RecordingCanvas c=node.beginRecording(1100,850);c.drawColor(0xff202020);image.draw(c);node.endRecording();byte[] gpu=(byte[])read.invoke(target);
                    Bitmap gb=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888);gb.copyPixelsFromBuffer(ByteBuffer.wrap(gpu));
                    check(Color.green(gb.getPixel((int)(frame.left+frame.width()*.25),(int)frame.centerY()))>200&&Color.blue(gb.getPixel((int)(frame.left+frame.width()*.75),(int)frame.centerY()))>200,"GPU also displays native image preview");gb.recycle();
                }finally{((AutoCloseable)target).close();}
                if(dims[0]==160)try(FileOutputStream out=new FileOutputStream(args[1]+"/native-preview-image.png")){pixels.compress(Bitmap.CompressFormat.PNG,100,out);}pixels.recycle();
                image.close();check(image.emulator.getScreen().getTerminalBitmapCount()==0,"closing preview releases its image cache");
            }
            Bitmap after=Bitmap.createBitmap(1100,850,Bitmap.Config.ARGB_8888);Canvas ac=new Canvas(after);ac.drawColor(0xff202020);mainRenderer.render(main,ac,0,-1,-1,-1,-1);
            check(before.sameAs(after)&&transcript.equals(main.getScreen().getTranscriptText()),"main screen pixels/text survive opening and closing previews");before.recycle();after.recycle();
            check(java.util.Arrays.equals(palette,main.mColors.mCurrentColors),"main terminal palette unchanged");
            System.out.println("ALL PASS "+checks+" native preview font/color/geometry/software/GPU/image-cache/isolation checks");
        }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
