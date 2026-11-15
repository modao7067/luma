package io.github.modao7067.luma.root;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

final class RootBackend implements AutoCloseable {
    interface Listener {
        void onEvent(JSONObject event);
        void onDisconnected(String message);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final File tokenFile;
    private final String token, socketName;
    private final int uid;
    private final Context app;
    private final boolean privateKeeper;
    private final Object outputLock = new Object();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256), r -> { Thread t=new Thread(r,"LumaRootWrite");t.setDaemon(true);return t; });
    private volatile LocalSocket socket;
    private volatile BufferedWriter output;
    private volatile boolean closed;
    private volatile boolean bootstrapAllowed=true;

    RootBackend(Context context, Listener listener) throws IOException {this(context,listener,false);}
    RootBackend(Context context, Listener listener, boolean privateKeeper) throws IOException {
        this.listener=listener;
        app=context;this.privateKeeper=privateKeeper;
        uid=context.getApplicationInfo().uid;
        tokenFile=new File(context.getFilesDir(),"root-backend.token");
        if(!tokenFile.isFile()) {
            String value=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
            File temp=File.createTempFile("root-token-",".tmp",context.getFilesDir());
            try {
                Files.write(temp.toPath(),value.getBytes(StandardCharsets.UTF_8));
                try {Os.chmod(temp.getAbsolutePath(),0600);} catch(Exception e){throw new IOException(e);}
                if(!temp.renameTo(tokenFile))throw new IOException(com.termux.terminal.LumaLang.tr("无法保存后端认证文件","Failed to save backend auth token"));
            } finally {temp.delete();}
        }
        token=new String(Files.readAllBytes(tokenFile.toPath()),StandardCharsets.UTF_8).trim();
        if(!token.matches("[a-f0-9]{64}"))throw new IOException(com.termux.terminal.LumaLang.tr("后端认证文件无效","Invalid backend auth token"));
        socketName="luma.root.direct."+uid+"."+token.substring(0,12);
    }

    void start() {
        Thread thread=new Thread(this::connectLoop,"LumaRootBackend");thread.setDaemon(true);thread.start();
    }
    void retryStartup(){bootstrapAllowed=true;disconnectSocket();}

    void send(JSONObject value) {
        if(closed)return;
        try {writer.execute(()->{
            try {write(value);} catch(IOException e){disconnectSocket();}
        });} catch(RejectedExecutionException ignored) {
            disconnectSocket();
            main.post(()->{if(!closed)listener.onDisconnected(com.termux.terminal.LumaLang.tr("连接繁忙，正在重新连接","Connection busy; reconnecting"));});
        }
    }

    private void write(JSONObject value) throws IOException {
        synchronized(outputLock) {
            BufferedWriter current=output;
            if(current==null)throw new IOException("Backend not connected");
            current.write(value.toString());current.newLine();current.flush();
        }
    }

    private LocalSocket connect() throws IOException {
        LocalSocket current=new LocalSocket();
        try {
            current.connect(new LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT));
            if(current.getPeerCredentials().getUid()!=0)throw new IOException(com.termux.terminal.LumaLang.tr("后端连接身份不是 Root","Backend peer UID is not root"));
            return current;
        }
        catch(IOException e){current.close();throw e;}
    }

    interface PtyListener { void onPty(DirectPty pty, JSONObject info); void onError(String message); }
    private static JSONObject readPacket(LocalSocket socket,java.util.List<FileDescriptor> descriptors)throws Exception {
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        InputStream input=socket.getInputStream();
        while(true){
            int value=input.read();
            FileDescriptor[] received=socket.getAncillaryFileDescriptors();
            if(received!=null)for(FileDescriptor fd:received)descriptors.add(fd);
            if(value<0)throw new EOFException(com.termux.terminal.LumaLang.tr("PTY 连接已断开","PTY connection closed"));
            if(value=='\n')return new JSONObject(data.toString("UTF-8"));
            if(data.size()>=128*1024)throw new IOException(com.termux.terminal.LumaLang.tr("PTY 响应过大","PTY response too large"));
            data.write(value);
        }
    }
    void openPty(String id,TerminalModeStore modes,PtyListener target){
        new Thread(()->{
            LocalSocket current=null;DirectPty pty=null;
            java.util.List<FileDescriptor> descriptors=new java.util.ArrayList<>();
            try{
                current=connect();current.setSoTimeout(15000);
                OutputStream out=current.getOutputStream();
                out.write((new JSONObject().put("op","hello").put("token",token).put("role","lease").put("lang",com.termux.terminal.LumaLang.code()).toString()+"\n").getBytes(StandardCharsets.UTF_8));
                JSONObject greeting=readPacket(current,descriptors);
                if(greeting.optInt("version")!=3)throw new IOException(com.termux.terminal.LumaLang.tr("Ubuntu 后端版本不匹配","Ubuntu backend version mismatch"));
                boolean sharedModes=greeting.optBoolean("modeMetadataFd");
                if(sharedModes)current.setFileDescriptorsForSend(new FileDescriptor[]{modes.descriptor()});
                out.write((new JSONObject().put("op","lease").put("id",id).put("modeMetadataFd",sharedModes).toString()+"\n").getBytes(StandardCharsets.UTF_8));
                current.setFileDescriptorsForSend(null);
                JSONObject response=readPacket(current,descriptors);
                if(!response.optString("event").equals("leased"))throw new IOException(response.optString("message",com.termux.terminal.LumaLang.tr("无法接回窗口","Failed to reattach window")));
                if(response.optBoolean("hasFd")){
                    if(descriptors.size()!=1)throw new IOException(com.termux.terminal.LumaLang.tr("未收到 PTY 文件描述符","Did not receive PTY file descriptor"));
                    pty=new DirectPty(current,descriptors.remove(0));current=null;
                }
                response.put("modeStateCaps",greeting.optInt("modeStateCaps",0));
                DirectPty adopted=pty;
                main.post(()->{if(closed){if(adopted!=null)adopted.release();}else target.onPty(adopted,response);});
            }catch(Exception e){
                if(pty!=null)pty.release();
                String message=e.getMessage()==null?com.termux.terminal.LumaLang.tr("PTY 连接失败","PTY connection failed"):e.getMessage();
                main.post(()->{if(!closed)target.onError(message);});
            }finally{
                for(FileDescriptor fd:descriptors)try{Os.close(fd);}catch(Exception ignored){}
                if(current!=null)try{current.close();}catch(IOException ignored){}
            }
        },"LumaRootLease").start();
    }

    private static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}

    private String installPrivateKeeper() throws Exception {
        File directory=new File(app.getFilesDir(),"root-keeper-v3");
        if(!directory.isDirectory()){
            File staging=new File(app.getFilesDir(),"root-keeper-"+UUID.randomUUID());
            if(!staging.mkdir())throw new IOException(com.termux.terminal.LumaLang.tr("无法创建私有 Root 后端目录","Failed to create private Root backend directory"));
            Os.chmod(staging.getAbsolutePath(),0700);
            try {
                for(String name:new String[]{"daemon.py","mode_state.py"}){
                    File target=new File(staging,name);
                    try(InputStream input=app.getAssets().open("root-keeper/"+name);OutputStream out=new FileOutputStream(target)){input.transferTo(out);}
                    Os.chmod(target.getAbsolutePath(),0600);
                }
                if(!staging.renameTo(directory))throw new IOException(com.termux.terminal.LumaLang.tr("无法发布私有 Root 后端","Failed to publish private Root backend"));
            }finally{for(File file:staging.listFiles()==null?new File[0]:staging.listFiles())file.delete();staging.delete();}
        }
        return "/proc/1/root"+new File(directory,"daemon.py").getAbsolutePath();
    }

    private void launch() throws Exception {
        String daemon="/opt/luma-root/daemon.py";
        if(privateKeeper)daemon=installPrivateKeeper();
        boolean builtin=RootEntry.usesBuiltinChroot(app);
        String entry=RootEntry.launcher(app);
        String command="exec "+(builtin?quote(entry):entry)+" /usr/bin/env LUMA_LANG="+quote(com.termux.terminal.LumaLang.code())+" /usr/bin/python3 -I "+(privateKeeper?"-B ":"")+quote(daemon)+" --daemonize --socket "
            +quote(socketName)+" --uid "+uid+" --token-file "+quote("/proc/1/root"+tokenFile.getAbsolutePath());
        Process process;
        try {
            process=new ProcessBuilder(RootEntry.suExecutable(),"-c",command).redirectErrorStream(true).start();
        } catch(IOException startError) {
            if(builtin)throw new IOException(com.termux.terminal.LumaLang.tr("没拿到 Root 权限。用临时 Root 的话，重新激活后再试","Root permission denied. If using temporary Root, re-activate it and try again"),startError);
            throw startError;
        }
        ByteArrayOutputStream errors=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{
            try(InputStream in=process.getInputStream()){
                byte[] data=new byte[1024];int n;
                while((n=in.read(data))!=-1)if(errors.size()<8192)errors.write(data,0,Math.min(n,8192-errors.size()));
            }catch(IOException ignored){}
        },"LumaRootBootstrap");drain.setDaemon(true);drain.start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroy();throw new IOException(com.termux.terminal.LumaLang.tr("Root 后端启动超时","Root backend startup timed out"));}
        drain.join(500);
        if(process.exitValue()!=0){
            if(builtin){
                String detail=errors.toString("UTF-8").trim();
                throw new IOException(detail.isEmpty()?com.termux.terminal.LumaLang.tr("Root 模式后端没启动起来。用临时 Root 的话，确认 Root 还在后再试","Root backend failed to start. If using temporary Root, confirm Root is active and try again"):detail);
            }
            throw new IOException(privateKeeper?com.termux.terminal.LumaLang.tr("Ubuntu Root 后端启动失败，请确认 Root 授权与 /data/local/linux/bin/ubuntu 可用","Failed to start Ubuntu Root backend; check Root access and /data/local/linux/bin/ubuntu"):com.termux.terminal.LumaLang.tr("Ubuntu 后端不可用，请确认 /opt/luma-root/daemon.py 已安装且 Root 授权可用","Ubuntu backend unavailable; check /opt/luma-root/daemon.py and Root access"));
        }
    }

    private void connectLoop() {
        String lastError=null;
        while(!closed) {
            try {
                LocalSocket current;
                try {current=connect();}
                catch(IOException first){
                    if(!bootstrapAllowed)throw new IOException(com.termux.terminal.LumaLang.tr("Ubuntu 后端未连接，请在窗口列表中重试后端连接","Ubuntu backend not connected; retry connection from the windows list"));
                    bootstrapAllowed=false;launch();current=connect();
                }
                if(closed){current.close();return;}
                socket=current;
                lastError=null;
                synchronized(outputLock){
                    output=new BufferedWriter(new OutputStreamWriter(current.getOutputStream(),StandardCharsets.UTF_8));
                    write(new JSONObject().put("op","hello").put("token",token).put("lang",com.termux.terminal.LumaLang.code()));
                }
                try(BufferedReader input=new BufferedReader(new InputStreamReader(current.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while(!closed && (line=input.readLine())!=null) {
                        if(line.length()>128*1024)throw new IOException(com.termux.terminal.LumaLang.tr("后端响应过大","Backend response too large"));
                        JSONObject event=new JSONObject(line);
                        deliver(()->listener.onEvent(event));
                    }
                }
                if(!closed)throw new IOException(com.termux.terminal.LumaLang.tr("连接已断开，正在重连原窗口","Connection lost; reconnecting to window"));
            }catch(Exception e){
                String message=e.getMessage()==null?com.termux.terminal.LumaLang.tr("后端连接失败","Backend connection failed"):e.getMessage();
                if(!closed&&!message.equals(lastError)){lastError=message;deliver(()->listener.onDisconnected(message));}
            }
            finally {disconnectSocket();}
            if(!closed)try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
    }

    private void deliver(Runnable work) {
        if(closed)return;
        CountDownLatch done=new CountDownLatch(1);
        main.post(()->{try{if(!closed)work.run();}finally{done.countDown();}});
        try {done.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }

    private void disconnectSocket() {
        LocalSocket current=socket;socket=null;
        writer.getQueue().clear();
        if(current!=null)try{current.close();}catch(IOException ignored){}
        output=null;
    }
    @Override public void close(){closed=true;disconnectSocket();writer.shutdownNow();}
}
