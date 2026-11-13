package io.github.modao7067.luma.shared;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TerminalFileProvider extends ContentProvider {
    private static final ScheduledExecutorService CLEANER=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"LumaTerminalFileCleaner");t.setDaemon(true);return t;
    });
    @Override public boolean onCreate(){return true;}
    static String mime(String name){
        int dot=name.lastIndexOf('.');String extension=dot<0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT);
        if(extension.equals("apk"))return "application/vnd.android.package-archive";
        String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime==null ? "application/octet-stream" : mime;
    }
    private File file(Uri uri)throws FileNotFoundException {
        if(uri.getPathSegments().size()!=2)throw new FileNotFoundException();
        String token=uri.getPathSegments().get(0);
        if(!token.matches("open-[A-Za-z0-9.-]+"))throw new FileNotFoundException();
        File f=new File(new File(getContext().getCacheDir(),"terminal-open"),token);
        if(!f.isFile())throw new FileNotFoundException("文件预览已过期，请重新点击链接");
        return f;
    }
    @Override public String getType(Uri uri){return mime(uri.getLastPathSegment()==null ? "" : uri.getLastPathSegment());}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Read only");
        File f=file(uri);
        ParcelFileDescriptor pfd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
        CLEANER.schedule(()->{
            if(f.isFile()&&System.currentTimeMillis()-f.lastModified()>=55_000L)f.delete();
        },60,TimeUnit.SECONDS);
        return pfd;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{
            File f=file(uri);String[]columns=projection==null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
            MatrixCursor cursor=new MatrixCursor(columns);Object[]values=new Object[columns.length];
            for(int i=0;i<columns.length;i++)if(columns[i].equals(OpenableColumns.DISPLAY_NAME))values[i]=uri.getLastPathSegment();else if(columns[i].equals(OpenableColumns.SIZE))values[i]=f.length();
            cursor.addRow(values);return cursor;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
