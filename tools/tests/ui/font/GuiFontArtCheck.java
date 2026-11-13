package io.github.modao7067.luma.gui.ui;

import android.graphics.*;
import android.text.*;
import java.io.File;
import java.io.FileOutputStream;

public final class GuiFontArtCheck {
    public static void main(String[] args) {
        try { check(args); } catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    private static void check(String[] args) throws Exception {
        Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        File root = new File(args.length > 1 ? args[1] : ".");
        Typeface original = Typeface.createFromFile(new File(root, "runtime/noroot/src/main/assets/MapleMonoNL-NF-CN-Regular.ttf"));
        Typeface enlarged = Typeface.createFromFile(new File(root, "apps/gui/app/src/main/assets/LumaGuiHan-Regular.ttf"));
        TextPaint a = new TextPaint(Paint.ANTI_ALIAS_FLAG), b = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        a.setTypeface(original); b.setTypeface(enlarged); a.setTextSize(100); b.setTextSize(100);
        int checked = 0;
        for (int cp : "中文你好会话输入设置字体大小山川一口国测量".codePoints().toArray()) {
            String s = new String(Character.toChars(cp)); Rect x = new Rect(), y = new Rect();
            a.getTextBounds(s,0,s.length(),x); b.getTextBounds(s,0,s.length(),y);
            if (Math.abs(a.measureText(s)-b.measureText(s)) > .01 || Math.abs(y.width()-x.width()*1.12)>2.1 || Math.abs(y.height()-x.height()*1.12)>2.1)
                throw new AssertionError(s+" ink "+x+" -> "+y);
            System.out.println(s+" advance="+a.measureText(s)+" ink="+x+" -> "+y); checked++;
        }
        for (String s : new String[]{"English 123", "，。！？", "[x] => {}", "→★"}) {
            Rect x=new Rect(),y=new Rect();a.getTextBounds(s,0,s.length(),x);b.getTextBounds(s,0,s.length(),y);
            if (!x.equals(y)||Math.abs(a.measureText(s)-b.measureText(s))>.01) throw new AssertionError("non-Han changed: "+s);
        }
        String text="中文 English，标点。\n你好世界 abc 第二行 中文测试";
        a.setTextSize(28);b.setTextSize(28);
        StaticLayout x=StaticLayout.Builder.obtain(text,0,text.length(),a,210).setIncludePad(false).build();
        StaticLayout y=StaticLayout.Builder.obtain(text,0,text.length(),b,210).setIncludePad(false).build();
        if(x.getLineCount()!=y.getLineCount())throw new AssertionError("wrap changed");
        for(int i=0;i<=text.length();i++) if(x.getLineForOffset(i)!=y.getLineForOffset(i)||Math.abs(x.getPrimaryHorizontal(i)-y.getPrimaryHorizontal(i))>.01)throw new AssertionError("caret changed at "+i);
        Bitmap bitmap=Bitmap.createBitmap(1000,280,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);
        a.setTextSize(60);b.setTextSize(60);a.setColor(Color.BLACK);b.setColor(Color.BLACK);
        canvas.drawText("中文字体 English，。",20,95,a);canvas.drawText("中文字体 English，。",20,205,b);
        File outFile = new File(root, "verification/gui-snapshot-font-20261103/font-art.png");
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try(FileOutputStream out=new FileOutputStream(outFile)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("PASS Android: "+checked+" Han ink samples, unchanged non-Han/advance, "+text.length()+" caret offsets and "+x.getLineCount()+" wrapped lines");
    }
}
