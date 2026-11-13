package io.github.modao7067.luma.shared;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.termux.view.*;
import com.termux.terminal.TerminalLinks;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

final class TerminalLinkOpener implements AutoCloseable {
    static final int INSTALL_PERMISSION_REQUEST=7342;
    interface Reader {void copy(String path,int pid,File destination,long limit,boolean highlight,int columns)throws IOException;}
    private final Activity activity;
    private final Reader reader;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
            r->{Thread t=new Thread(r,"LumaFilePreview");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private TerminalPreviewView active;
    private TerminalView owner;
    private Future<?> pending;
    private File pdf,pendingInstall;
    private String pendingInstallName;
    private int generation,fontSize,page,pageCount,pageGeneration,requestedPage;
    private int[] palette;
    private boolean closed,pageBusy;
    TerminalLinkOpener(Activity activity,Reader reader){this.activity=activity;this.reader=reader;}
    boolean isShowing(){return active!=null;}
    boolean dismiss(){
        if(active==null)return false;
        generation++;pageGeneration++;if(pending!=null)pending.cancel(true);
        if(owner!=null)owner.setFilePreview(null);
        active.clear();if(active.getParent() instanceof ViewGroup)((ViewGroup)active.getParent()).removeView(active);
        active=null;owner=null;if(pdf!=null){pdf.delete();pdf=null;}pageBusy=false;return true;
    }
    boolean open(String target,TerminalView source){
        if(TerminalLinks.isSystemUri(target)){openSystem(Uri.parse(target));return true;}
        String path=target;Uri uri=Uri.parse(target);
        if("file".equalsIgnoreCase(uri.getScheme())){
            String host=uri.getHost();if(host!=null&&!host.isEmpty()&&!host.equalsIgnoreCase("localhost")){message("链接指向其他主机");return true;}path=uri.getPath();
        }
        if(path==null||path.isEmpty()||path.indexOf('\0')>=0)return false;
        path=path.replaceFirst("(?::[0-9]+(?::[0-9]+)?(?:[-–][0-9]+(?::[0-9]+)?)?|#L[0-9]+(?:C[0-9]+)?(?:-L?[0-9]+(?:C[0-9]+)?)?)$","");
        if(closed||activity.isDestroyed())return true;
        dismiss();owner=source;fontSize=source.getTextSize();palette=source.getTerminalPalette();
        active=new TerminalPreviewView(activity,source,this::dismiss);owner.setFilePreview(active);
        ((FrameLayout)activity.findViewById(android.R.id.content)).addView(active,new FrameLayout.LayoutParams(-1,-1));
        showMessage("正在打开…");final int ticket=++generation;
        final String requested=path,name=new File(path).getName(),lower=name.toLowerCase(Locale.ROOT);
        final boolean image=lower.matches(".*\\.(png|jpe?g|webp|gif|bmp|avif|heic|heif)$"),document=TerminalDocuments.isText(name),isPdf=lower.endsWith(".pdf");
        final int pid=source.getCurrentSession()==null ? 0 : source.getCurrentSession().getPid();
        final int width=source.getWidth(),height=source.getHeight(),textSize=fontSize,columns=source.getFilePreviewColumns();final int[] colors=palette.clone();
        pending=worker.submit(()->{
            File staged=null;
            try{
                File dir=new File(activity.getCacheDir(),"terminal-open");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建预览缓存");
                staged=File.createTempFile("open-",".data",dir);
                reader.copy(requested,pid,staged,image ? WallpaperImages.MAX_SOURCE_BYTES : document ? 9L*1024*1024 : 512L*1024*1024,document,columns);trim(dir,staged);
                Bitmap bitmap=null;TerminalPreviewScreen.PreparedText text=null;int pages=0;
                if(image)bitmap=WallpaperImages.decode(staged,width,height);
                else if(document)text=TerminalPreviewScreen.prepareText(Files.readAllBytes(staged.toPath()));
                else if(isPdf){TerminalDocuments.PdfPage first=TerminalDocuments.pdf(staged,0,width,height);pages=first.count;bitmap=first.bitmap;}
                final File result=staged;final Bitmap decoded=bitmap;final TerminalPreviewScreen.PreparedText prepared=text;final int count=pages;
                activity.runOnUiThread(()->{
                    if(!current(ticket)){result.delete();return;}
                    if(decoded!=null||prepared!=null){
                        TerminalPreviewScreen screen=new TerminalPreviewScreen(textSize,colors);
                        if(decoded!=null)screen.image(decoded);else screen.text(prepared);
                        active.show(screen);if(isPdf){pdf=result;page=requestedPage=0;pageCount=count;active.pageHandler(this::changePage);}else result.delete();
                    }else{dismiss();openExternal(result,name);}
                });
            }catch(Exception e){
                if(staged!=null)staged.delete();
                activity.runOnUiThread(()->{if(current(ticket))showMessage("\033[31m"+(e.getMessage()==null ? "无法打开文件" : e.getMessage())+"\033[0m");});
            }
        });return true;
    }
    private boolean current(int ticket){return !closed&&!activity.isDestroyed()&&active!=null&&ticket==generation;}
    private void showMessage(String message){
        if(active==null)return;TerminalPreviewScreen screen=new TerminalPreviewScreen(fontSize,palette);
        screen.text(message.replace("\n","\r\n").getBytes(StandardCharsets.UTF_8));active.show(screen);
    }
    private void changePage(int delta){
        int index=(pageBusy?requestedPage:page)+delta;if(pdf==null||index<0||index>=pageCount)return;
        requestedPage=index;pageBusy=true;if(pending!=null)pending.cancel(true);
        final int ticket=generation,pageTicket=++pageGeneration,size=fontSize,width=owner.getWidth(),height=owner.getHeight();final File file=pdf;final int[]colors=palette.clone();
        pending=worker.submit(()->{
            try{TerminalDocuments.PdfPage result=TerminalDocuments.pdf(file,index,width,height);
                activity.runOnUiThread(()->{if(!current(ticket)||pageTicket!=pageGeneration)return;
                    TerminalPreviewScreen screen=new TerminalPreviewScreen(size,colors);screen.image(result.bitmap);page=index;pageBusy=false;active.show(screen);
                });
            }catch(Exception e){activity.runOnUiThread(()->{if(current(ticket)&&pageTicket==pageGeneration){pageBusy=false;requestedPage=page;showMessage("无法预览此页");}});}
        });
    }
    private void openSystem(Uri uri){
        Intent intent=TerminalSystemLinks.intent(uri);
        try{activity.startActivity(intent);}catch(ActivityNotFoundException|SecurityException e){message("没有可打开此链接的应用，或系统未允许访问");}
    }
    private void openExternal(File file,String name){
        if(name.toLowerCase(Locale.ROOT).endsWith(".apk")&&!activity.getPackageManager().canRequestPackageInstalls()){
            if(pendingInstall!=null)pendingInstall.delete();pendingInstall=file;pendingInstallName=name;
            message("请允许 Luma 安装应用，返回后继续打开安装器");
            try{activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),INSTALL_PERMISSION_REQUEST);}
            catch(ActivityNotFoundException e){message("无法打开安装权限设置");pendingInstall=null;pendingInstallName=null;file.delete();}return;
        }
        Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".terminal-files").appendPath(file.getName()).appendPath(name).build();
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,TerminalFileProvider.mime(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(name,uri));
        try{activity.startActivity(intent);}catch(ActivityNotFoundException|SecurityException e){message("没有可打开此类文件的应用，或系统未允许访问");}
    }
    boolean onActivityResult(int request,int result,Intent data){
        if(request!=INSTALL_PERMISSION_REQUEST)return false;File file=pendingInstall;String name=pendingInstallName;pendingInstall=null;pendingInstallName=null;
        if(file!=null){if(activity.getPackageManager().canRequestPackageInstalls())openExternal(file,name);else{file.delete();message("未允许安装应用");}}return true;
    }
    private static void trim(File dir,File keep){
        File[]files=dir.listFiles((d,n)->n.startsWith("open-")&&!n.startsWith("open-media-"));
        if(files==null)return;
        for(File f:files)if(!f.equals(keep))f.delete();
    }
    private void message(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    @Override public void close(){
        closed=true;dismiss();if(pendingInstall!=null)pendingInstall.delete();
        File[]files=new File(activity.getCacheDir(),"terminal-open").listFiles((d,n)->n.startsWith("open-")&&!n.startsWith("open-media-"));
        if(files!=null)for(File f:files)f.delete();
        worker.shutdownNow();
    }
}
