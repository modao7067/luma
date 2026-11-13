import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import java.io.*;
import io.github.modao7067.luma.gui.ui.MediaDecoderKt;
import io.github.modao7067.luma.gui.ui.MediaFrame;
import io.github.modao7067.luma.gui.session.ResolvedMedia;

public final class MediaDecoderCheck {
  public static void main(String[] args) throws Exception {
    File out=new File(args[1]);out.mkdirs();System.setOut(new PrintStream(new File(out,"decoder-art.txt")));
    try {
      Looper.prepareMainLooper();
      Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
      Class<?> memory=Class.forName("com.android.internal.os.ApplicationSharedMemory");
      memory.getMethod("setInstance",memory).invoke(null,memory.getMethod("create").invoke(null));
      Class<?> thread=Class.forName("android.app.ActivityThread");Object main=thread.getMethod("systemMain").invoke(null);
      Context context=(Context)thread.getMethod("getSystemContext").invoke(main);
      Bitmap original=Bitmap.createBitmap(1080,10000,Bitmap.Config.ARGB_8888);
      Canvas c=new Canvas(original);c.drawColor(Color.WHITE);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xff17212b);p.setTextSize(22);
      for(int y=40;y<10000;y+=40)c.drawText(String.format("%04d  Read the small details: 1080 x 10000, bounded decode, readable text.",y/40),24,y,p);
      File source=new File(out,"long-screenshot-source.png");save(original,source);original.recycle();
      MediaFrame frame=MediaDecoderKt.decodeMediaFrame(context,new ResolvedMedia(source.toURI().toString(),"image/png","long-screenshot.png",source.length()),2048,0);
      Bitmap decoded=frame.getBitmap();
      if(decoded.getWidth()<950 || (long)decoded.getWidth()*decoded.getHeight()>8388608L || decoded.getAllocationByteCount()>33554432)throw new AssertionError("decode budget/detail");
      save(decoded,new File(out,"long-screenshot-decoded.png"));
      Bitmap viewport=Bitmap.createBitmap(decoded,0,decoded.getHeight()/2-700,decoded.getWidth(),1400);save(viewport,new File(out,"long-screenshot-viewport.png"));viewport.recycle();
      System.out.println("PASS production ImageDecoder: source1080x10000 -> "+decoded.getWidth()+"x"+decoded.getHeight()+"; allocation="+decoded.getAllocationByteCount()+" bytes; full-screen budget8388608 pixels/33554432 bytes; sRGB; small22px source text retained");decoded.recycle();
      File svg=new File(out,"fixture.svg");try(FileWriter w=new FileWriter(svg)){w.write("<svg xmlns='http://www.w3.org/2000/svg' width='100' height='200'><rect width='100' height='200' fill='#287a60'/></svg>");}
      MediaFrame vector=MediaDecoderKt.decodeMediaFrame(context,new ResolvedMedia(svg.toURI().toString(),"image/svg+xml","fixture.svg",svg.length()),2048,0);
      if(vector.getBitmap().getPixel(500,1000)!=0xff287a60)throw new AssertionError("SVG pixel");vector.getBitmap().recycle();
      File pdf=new File(out,"fixture.pdf");android.graphics.pdf.PdfDocument doc=new android.graphics.pdf.PdfDocument();
      for(int page=0;page<2;page++){android.graphics.pdf.PdfDocument.Page sheet=doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(200,300,page+1).create());sheet.getCanvas().drawColor(page==0?Color.RED:Color.BLUE);doc.finishPage(sheet);}
      try(FileOutputStream f=new FileOutputStream(pdf)){doc.writeTo(f);}doc.close();
      MediaFrame second=MediaDecoderKt.decodeMediaFrame(context,new ResolvedMedia(pdf.toURI().toString(),"application/pdf","fixture.pdf",pdf.length()),2048,1);
      if(second.getPageCount()!=2 || second.getBitmap().getPixel(100,100)!=Color.BLUE)throw new AssertionError("PDF second page");second.getBitmap().recycle();
      System.out.println("PASS production AndroidSVG raster pixels and native PDF second-page pixels/count; no WebView or external resolver");
      System.exit(0);
    }catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}
  }
  static void save(Bitmap bitmap,File file)throws Exception{try(FileOutputStream f=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}}
}
