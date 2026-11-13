package com.termux.view;
import android.app.Activity;import android.content.*;import android.content.res.*;import android.graphics.*;import android.graphics.pdf.PdfDocument;import android.os.*;import android.view.*;import android.widget.FrameLayout;
import com.termux.terminal.*;import io.github.modao7067.luma.ghostty.*;
import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.atomic.*;
public final class NativePreviewLifecycleCheck {
    static int checks,stage,heartbeats;static Handler main;static long deadline;static File out,pdf,cache;static Context context;
    static TerminalSession ownerSession;static TerminalView ownerView;static String originalText;static byte[] originalPixels;
    static TerminalPreviewScreen screen,large,scrolling;static GhosttyTerminalEmulator oldCore;static Object opener;static Method open,dismiss,close,changePage;static Class<?> openerClass;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void call(Method m,Object...args)throws Exception{m.invoke(opener,args);}
    static TerminalPreviewScreen activeScreen()throws Exception{Object view=get(opener,"active");return view==null?null:(TerminalPreviewScreen)get(view,"screen");}
    static boolean currentText(String text)throws Exception{TerminalPreviewScreen s=activeScreen();return s!=null&&!s.isImage()&&!s.isLoading()&&s.plainText().contains(text);}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static Bitmap draw(TerminalPreviewScreen s,String name)throws Exception{Bitmap b=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);s.draw(c);if(name!=null)try(FileOutputStream f=new FileOutputStream(new File(out,name))){b.compress(Bitmap.CompressFormat.PNG,100,f);}return b;}
    static void fail(Throwable t){t.printStackTrace();System.exit(1);}
    static final class FixtureActivity extends Activity {
        final FrameLayout content;
        FixtureActivity(Context c)throws Exception{Field base=ContextWrapper.class.getDeclaredField("mBase");base.setAccessible(true);base.set(this,c);content=new FrameLayout(c);content.layout(0,0,800,600);}
        @SuppressWarnings("unchecked") @Override public <T extends View>T findViewById(int id){return(T)content;}
        @Override public File getCacheDir(){return cache;}
        @Override public boolean isDestroyed(){return false;}
    }
    public static void main(String[]args){try{
        Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());out=new File(args[2]);cache=new File(out,"preview-cache");cache.mkdirs();
        Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);AssetManager a=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(a,args[1]);BundledTerminalFonts.initialize(a);
        GhosttyTerminalEmulator.loadLibrary(args[0]);context=ViewViewportCheck.headlessContext(args[3]);ViewViewportCheck.context=context;
        ownerSession=ViewViewportCheck.session(true);ownerView=ViewViewportCheck.makeView(ownerSession);ViewViewportCheck.feed(ownerSession,"MAIN WINDOW UNCHANGED");originalText=ownerSession.getEmulator().getViewportText(0);originalPixels=ViewViewportCheck.draw(ownerView);
        screen=new TerminalPreviewScreen(28,ownerView.getTerminalPalette());screen.text(bytes("\033[38;2;255;0;0m中文 syntax\033[0m\r\nsecond line\r\nthird\r\n"));screen.layout(800,600);
        check(screen.emulator instanceof GhosttyTerminalEmulator,"ANSI preview creates an isolated native core, not a Java parser");
        check(screen.isLoading(),"text is queued for bounded owner-Looper batches rather than fed synchronously");
        deadline=SystemClock.uptimeMillis()+25000;main.post(NativePreviewLifecycleCheck::poll);Looper.loop();
    }catch(Throwable t){fail(t);}}
    static void poll(){try{
        if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("preview timeout stage="+stage);
        switch(stage){
        case 0:
            if(screen.isLoading())break;
            check(screen.plainText().contains("中文 syntax")&&!screen.plainText().contains("\033"),"native preview plain copy removes ANSI and preserves Unicode");
            check(TextStyle.decodeForeColor(screen.emulator.getRenderFrame().screen.getStyleAt(0,0))==0xffff0000,"syntax truecolor foreground survives native parsing");
            check(screen.body.height()==3*screen.lineHeight(),"preview retains three-row compact sizing without extra bottom row");
            float gap=screen.body.left-screen.frame.left;check(gap>10&&Math.abs(gap-(screen.frame.bottom-screen.body.bottom))<.01,"preview retains equal frame padding");
            draw(screen,"preview-ansi.png");AtomicBoolean rejected=new AtomicBoolean();Thread wrong=new Thread(()->{try{screen.close();}catch(IllegalStateException expected){rejected.set(true);}});wrong.start();wrong.join();check(rejected.get()&&screen.emulator.adapter()!=null,"wrong-thread close rejects before releasing owner resources");screen.close();
            large=new TerminalPreviewScreen(22,ownerView.getTerminalPalette());
            final TerminalPreviewScreen.PreparedText[]prepared={null};Thread prep=new Thread(()->prepared[0]=TerminalPreviewScreen.prepareText(bytes("long preview text 中文\n".repeat(30000))));prep.start();prep.join();
            check(prepared[0].widths.length==1&&prepared[0].counts[0]==30000,"large repeated-line layout keeps a compact width histogram rather than per-line UI work");
            large.text(prepared[0]);large.layout(800,600);oldCore=large.emulator;
            main.post(()->{try{heartbeats++;check(large.isLoading(),"owner Looper heartbeat runs while a large ANSI preview is still feeding");large.layout(620,440);check(oldCore.adapter()==null&&large.emulator!=oldCore,"resize cancels old feed and frees old native instance before replay");oldCore=large.emulator;large.close();check(oldCore.adapter()==null,"closing an in-progress preview cancels feed/timers and frees native");}catch(Throwable t){fail(t);}});stage=1;break;
        case 1:
            if(heartbeats==0)break;
            scrolling=new TerminalPreviewScreen(22,ownerView.getTerminalPalette());StringBuilder lines=new StringBuilder();for(int i=0;i<240;i++)lines.append(String.format("LINE%03d 中文\r\n",i));scrolling.text(bytes(lines.toString()));scrolling.layout(800,600);stage=10;break;
        case 10:
            if(scrolling.isLoading())break;
            int initialTop=scrolling.topRow;check(initialTop<0,"long text preview initially shows native history top");
            scrolling.scroll(10);check(scrolling.topRow==initialTop+10&&scrolling.emulator.getViewportTopRow()==scrolling.topRow,"preview scrolling updates the native viewport");
            String copied=scrolling.plainText();check(copied.contains("LINE000 中文")&&copied.contains("LINE239 中文")&&!copied.contains("\033"),"plain preview copy spans native history without ANSI escapes");scrolling.close();
            TerminalPreviewScreen single=new TerminalPreviewScreen(22,ownerView.getTerminalPalette());single.text(bytes("single line\r\n\033[0m"));single.layout(800,600);check(single.body.height()==single.lineHeight(),"single-line preview has no phantom bottom row");single.close();
            for(int i=0;i<80;i++){TerminalPreviewScreen s=new TerminalPreviewScreen(20,ownerView.getTerminalPalette());s.text(bytes("cancel"));s.layout(400,300);s.close();}
            check(true,"80 rapid open/cancel cycles exceed native handle limit without leakage");
            Bitmap image=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888);Canvas ic=new Canvas(image);ic.drawColor(Color.GREEN);Paint blue=new Paint();blue.setColor(Color.BLUE);ic.drawRect(50,0,100,50,blue);
            screen=new TerminalPreviewScreen(22,ownerView.getTerminalPalette());screen.image(image);screen.layout(800,600);check(screen.emulator==null&&screen.isImage(),"image preview draws Bitmap directly without any VT instance");
            Bitmap shot=draw(screen,"preview-image.png");check(Color.green(shot.getPixel((int)(screen.body.left+screen.body.width()*.25f),(int)screen.body.centerY()))>200&&Color.blue(shot.getPixel((int)(screen.body.left+screen.body.width()*.75f),(int)screen.body.centerY()))>200,"image aspect and full two-color content are preserved");
            View surface=new View(context){@Override protected void onDraw(Canvas c){screen.draw(c);}};surface.layout(0,0,800,600);check(ViewViewportCheck.nonBlack(ViewViewportCheck.hardware(surface))>1000,"direct Bitmap preview renders on actual GPU RenderNode");screen.close();check(!image.isRecycled(),"close does not recycle Bitmap possibly referenced by a GPU display list");
            pdf=new File(out,"fixture.pdf");PdfDocument doc=new PdfDocument();try{for(int i=0;i<3;i++){PdfDocument.Page p=doc.startPage(new PdfDocument.PageInfo.Builder(200,100,i+1).create());p.getCanvas().drawColor(new int[]{Color.RED,Color.GREEN,Color.BLUE}[i]);doc.finishPage(p);}try(FileOutputStream f=new FileOutputStream(pdf)){doc.writeTo(f);}}finally{doc.close();}
            openerClass=Class.forName("io.github.modao7067.luma.root.TerminalLinkOpener");Class<?> reader=Class.forName("io.github.modao7067.luma.root.TerminalLinkOpener$Reader");
            Object copy=Proxy.newProxyInstance(reader.getClassLoader(),new Class[]{reader},(p,m,a)->{check(Looper.myLooper()==null,"file IO/decoding work enters background worker");String path=(String)a[0];if(path.contains("slow"))try{Thread.sleep(150);}catch(InterruptedException e){throw new IOException("cancelled");}if(path.endsWith(".pdf"))Files.copy(pdf.toPath(),((File)a[2]).toPath(),StandardCopyOption.REPLACE_EXISTING);else Files.write(((File)a[2]).toPath(),bytes(path.contains("latest")?"LATEST 中文":"stale"));return null;});
            Constructor<?> ctor=openerClass.getDeclaredConstructor(Activity.class,reader);ctor.setAccessible(true);opener=ctor.newInstance(new FixtureActivity(context),copy);
            open=openerClass.getDeclaredMethod("open",String.class,TerminalView.class);dismiss=openerClass.getDeclaredMethod("dismiss");close=openerClass.getDeclaredMethod("close");changePage=openerClass.getDeclaredMethod("changePage",int.class);for(Method m:new Method[]{open,dismiss,close,changePage})m.setAccessible(true);
            call(open,"/slow.txt",ownerView);call(open,"/latest.txt",ownerView);stage=2;break;
        case 2:
            if(!currentText("LATEST 中文"))break;check(true,"rapid opener replacement discards cancelled/late results and shows latest text");
            call(open,"/fixture.pdf",ownerView);stage=3;break;
        case 3:
            if(!(get(opener,"pdf") instanceof File))break;screen=activeScreen();check(screen.isImage()&&screen.emulator==null,"production PDF preview uses decoded Bitmap, not terminal bitmap slices");
            Bitmap first=draw(screen,"preview-pdf-first.png");check(Color.red(first.getPixel((int)screen.body.centerX(),(int)screen.body.centerY()))>200,"actual PDF decoder displays first page");call(changePage,1);call(changePage,1);stage=4;break;
        case 4:
            if((Boolean)get(opener,"pageBusy")||(Integer)get(opener,"page")!=2)break;screen=activeScreen();Bitmap last=draw(screen,"preview-pdf-last.png");check(Color.blue(last.getPixel((int)screen.body.centerX(),(int)screen.body.centerY()))>200,"rapid next-page requests land on the final requested PDF page");
            call(changePage,-1);call(dismiss);stage=5;break;
        case 5:
            check(activeScreen()==null,"dismiss during PDF decoding leaves no active preview");call(close);
            check(ownerSession.getEmulator().getViewportText(0).equals(originalText)&&java.util.Arrays.equals(originalPixels,ViewViewportCheck.draw(ownerView)),"opening/scrolling/cancelling previews never changes owner terminal bytes, text or pixels");
            ownerSession.disposeEmulator();System.out.println("NATIVE PREVIEW OK checks="+checks);System.exit(0);return;
        }main.postDelayed(NativePreviewLifecycleCheck::poll,20);
    }catch(Throwable t){fail(t);}}
}
