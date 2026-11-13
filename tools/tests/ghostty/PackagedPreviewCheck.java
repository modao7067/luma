import android.content.res.AssetManager;import android.graphics.*;import android.os.*;
import java.lang.reflect.*;import java.nio.charset.StandardCharsets;import java.io.*;
public final class PackagedPreviewCheck {
    static Class<?> preview;static Object screen;static Handler main;static int checks;static String out;
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    static Object invoke(Object target,String name,Class<?>[]types,Object...args)throws Exception{return preview.getMethod(name,types).invoke(target,args);}
    static Object make()throws Exception{int[]palette=new int[259];for(int i=0;i<256;i++)palette[i]=0xffcccccc;palette[257]=0xff000000;return preview.getConstructor(int.class,int[].class).newInstance(24,palette);}
    static void text(Object target,String text)throws Exception{invoke(target,"text",new Class[]{byte[].class},text.getBytes(StandardCharsets.UTF_8));}
    static void layout(Object target,int w,int h)throws Exception{invoke(target,"layout",new Class[]{int.class,int.class},w,h);}
    static void dispose(Object target)throws Exception{invoke(target,"close",new Class[]{});}
    static void fail(Throwable t){t.printStackTrace();System.exit(1);}
    public static void main(String[]args){try{
        Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());out=args[2];Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
        Class.forName("com.termux.view.BundledTerminalFonts").getMethod("initialize",AssetManager.class).invoke(null,assets);
        Class.forName("io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator").getMethod("loadLibrary",String.class).invoke(null,args[1]);
        preview=Class.forName("com.termux.view.TerminalPreviewScreen");screen=make();text(screen,"\033[38;2;255;0;0mR8 中文 native preview\033[0m\r\nnext line");layout(screen,800,600);
        check((Boolean)invoke(screen,"isLoading",new Class[]{}),"actual APK preview schedules bounded native feed on its owner Looper");
        main.postDelayed(PackagedPreviewCheck::complete,50);Looper.loop();
    }catch(Throwable t){fail(t);}}
    static void complete(){try{
        if((Boolean)invoke(screen,"isLoading",new Class[]{})){main.postDelayed(PackagedPreviewCheck::complete,20);return;}
        check(((String)invoke(screen,"plainText",new Class[]{})).contains("R8 中文 native preview"),"actual signed R8 preview parses ANSI/Unicode with the packaged native core");
        Bitmap bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);invoke(screen,"draw",new Class[]{Canvas.class},new Canvas(bitmap));
        try(FileOutputStream f=new FileOutputStream(out+"/packaged-preview.png")){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}
        final boolean[] rejected={false};Thread wrong=new Thread(()->{try{dispose(screen);}catch(Exception e){rejected[0]=e instanceof InvocationTargetException&&e.getCause() instanceof IllegalStateException;}});wrong.start();wrong.join();check(rejected[0],"actual R8 preview rejects wrong-thread release");
        dispose(screen);
        for(int i=0;i<80;i++){Object cancelled=make();text(cancelled,"cancel ".repeat(3000));layout(cancelled,500,350);dispose(cancelled);}
        check(true,"actual R8 preview survives 80 queued-open/cancel cycles without native handle exhaustion");
        Object image=make();Bitmap pixels=Bitmap.createBitmap(40,20,Bitmap.Config.ARGB_8888);pixels.eraseColor(Color.GREEN);invoke(image,"image",new Class[]{Bitmap.class},pixels);layout(image,800,600);
        Bitmap painted=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);invoke(image,"draw",new Class[]{Canvas.class},new Canvas(painted));
        check(Color.green(painted.getPixel(400,300))>200,"actual R8 image preview draws a Bitmap without legacy image slices");dispose(image);check(!pixels.isRecycled(),"actual R8 preview close preserves GPU Bitmap lifetime");
        System.out.println("PACKAGED PREVIEW OK checks="+checks+"; actual APK classes only, no Activity/PTY");System.exit(0);
    }catch(Throwable t){fail(t);}}
}
