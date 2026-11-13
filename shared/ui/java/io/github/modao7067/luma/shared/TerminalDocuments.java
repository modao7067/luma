package io.github.modao7067.luma.shared;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TerminalDocuments {
    static final int MAX_TEXT_BYTES=1024*1024;
    static boolean isText(String name){
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|markdown|log|json|jsonl|ya?ml|csv|tsv|xml|html?|css|js|ts|java|py|sh|toml|ini|conf|c|h|cpp|rs|go|sql)$");
    }
    static String text(File file)throws IOException {
        byte[] bytes;
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[]buffer=new byte[8192];int total=0,n;
            while(total<MAX_TEXT_BYTES&&(n=in.read(buffer,0,Math.min(buffer.length,MAX_TEXT_BYTES-total)))!=-1){out.write(buffer,0,n);total+=n;}
            bytes=out.toByteArray();
        }
        for(byte b:bytes)if(b==0)throw new IOException("该文件不是可预览的文本");
        String text=new String(bytes,StandardCharsets.UTF_8);
        return file.length()>MAX_TEXT_BYTES ? text+"\n\n（内容较长，仅预览前 1 MiB）" : text;
    }
    static final class PdfPage {
        final Bitmap bitmap;final int count;
        PdfPage(Bitmap bitmap,int count){this.bitmap=bitmap;this.count=count;}
    }
    static PdfPage pdf(File file,int page,int width,int height)throws IOException {
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            int count=renderer.getPageCount();if(page<0||page>=count)throw new IOException("PDF 页码无效");
            try(PdfRenderer.Page current=renderer.openPage(page)){
                float scale=Math.min((float)Math.max(1,Math.min(2048,width))/current.getWidth(),(float)Math.max(1,Math.min(2048,height))/current.getHeight());
                int w=Math.max(1,(int)(current.getWidth()*scale)),h=Math.max(1,(int)(current.getHeight()*scale));
                Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                try{bitmap.eraseColor(Color.WHITE);current.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return new PdfPage(bitmap,count);}
                catch(RuntimeException e){bitmap.recycle();throw e;}
            }
        }
    }
}
