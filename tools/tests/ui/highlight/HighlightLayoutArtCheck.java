package io.github.modao7067.luma.gui.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

public final class HighlightLayoutArtCheck {
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(System.out); System.exit(1); });
        Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        String code = "val x = \"text\"; return 123 // comment\n".repeat(10000) + "最后一行🙂";
        long before = System.nanoTime();
        SpannableString styled = new SpannableString(code);
        CodeTokens.scan(code, false, CodeTokens.DISPLAY_SPAN_LIMIT,
                (start, end, kind) -> styled.setSpan(new ForegroundColorSpan(0xffabcdef), start, end, 33), () -> {});
        long styledAt = System.nanoTime();
        TextPaint paint = new TextPaint(); paint.setTextSize(42); paint.setTypeface(Typeface.MONOSPACE);
        StaticLayout layout = StaticLayout.Builder.obtain(styled, 0, styled.length(), paint, 8192)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setIncludePad(false).build();
        long layoutAt = System.nanoTime();
        Bitmap bitmap = Bitmap.createBitmap(1080, 738, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); layout.draw(canvas);
        long drawAt = System.nanoTime();
        if (!code.equals(styled.toString()) || layout.getLineEnd(layout.getLineCount()-1) != code.length())
            throw new AssertionError("lost output tail");
        System.out.printf("chars=%d lines=%d spans=%d styleMs=%.3f layoutMs=%.3f visibleDrawMs=%.3f%n",
                code.length(), layout.getLineCount(), styled.getSpans(0, styled.length(), ForegroundColorSpan.class).length,
                (styledAt-before)/1e6, (layoutAt-styledAt)/1e6, (drawAt-layoutAt)/1e6);
        bitmap.recycle();
        System.out.println("PASS Android native full text layout and clipped offscreen draw");
    }
}
