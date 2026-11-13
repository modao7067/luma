package io.github.modao7067.luma;

import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class NativeBridge implements AutoCloseable {
    private final Context context;
    private final LocalServerSocket server;
    final TaskRegistry taskRegistry;
    private final Set<Job> jobs=ConcurrentHashMap.newKeySet();
    private final Set<LocalSocket> clients=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    NativeBridge(Context c)throws IOException {
        context=c;
        taskRegistry=new TaskRegistry(c);
        server=new LocalServerSocket(LinuxEnvironment.socketName(c));
        new Thread(()->{
            while(!closed)try{
                LocalSocket socket=server.accept();
                if(socket.getPeerCredentials().getUid()!=android.os.Process.myUid()){socket.close();continue;}
                clients.add(socket);new Thread(()->serve(socket),"LumaNativeRequest").start();
            }catch(IOException e){if(!closed)android.util.Log.w("Luma","Native socket",e);}
        },"LumaNativeBridge").start();
    }
    private static final class Job {
        final java.lang.Process process;final File pidFile;final boolean linux;
        final ShizukuBridge.RelaySession relay;
        Job(java.lang.Process process,File pidFile,boolean linux){this(process,pidFile,linux,null);}
        Job(java.lang.Process process,File pidFile,boolean linux,ShizukuBridge.RelaySession relay){this.process=process;this.pidFile=pidFile;this.linux=linux;this.relay=relay;}
        int pid(){if(pidFile==null)return -1;try{return Integer.parseInt(new String(Files.readAllBytes(pidFile.toPath()),StandardCharsets.UTF_8).trim());}catch(Exception e){return -1;}}
        void interrupt(){int pid=pid();if(pid>0)try{Os.kill(-pid,OsConstants.SIGINT);}catch(Exception ignored){}else process.destroy();}
        void stop(){
            int pid=pid();
            if(pid>0)try{if(linux)Os.kill(pid,OsConstants.SIGQUIT);else Os.kill(-pid,OsConstants.SIGTERM);}catch(Exception ignored){}
            else process.destroy();
            try{if(process.waitFor(250,java.util.concurrent.TimeUnit.MILLISECONDS))return;}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(pid>0)try{Os.kill(-pid,OsConstants.SIGKILL);}catch(Exception ignored){}
            process.destroyForcibly();
        }
        void finish()throws IOException{if(relay!=null)relay.syncBack();}
        void cleanup(){if(pidFile!=null)pidFile.delete();if(relay!=null)relay.close();}
    }
    private static String line(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1 && value!='\n'){
            if(bytes.size()>=1024*1024)throw new IOException("请求过长");bytes.write(value);
        }
        return bytes.size()==0 && value==-1?null:bytes.toString(StandardCharsets.UTF_8.name());
    }
    static void sendEvent(OutputStream output,JSONObject value)throws IOException {
        event(output,value);
    }
    private static void event(OutputStream output,JSONObject value)throws IOException {
        synchronized(output){output.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));output.flush();}
    }
    private Thread pump(InputStream input,OutputStream output,String channel){
        Thread thread=new Thread(()->{
            try{byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)event(output,new JSONObject().put(channel,Base64.encodeToString(b,0,n,Base64.NO_WRAP)));}
            catch(Exception ignored){}
        },"LumaNativeOutput");thread.start();return thread;
    }
    private boolean handleServiceOp(String op,JSONObject request,OutputStream output,TaskRegistry.Cancellation cancellation)throws Exception {
        switch(op){
            case "svc.start":{
                JSONObject res=taskRegistry.startTask(request);
                if(request.optBoolean("wait",false)){
                    res=taskRegistry.waitTask(res.getString("id"),request.optString("waitFor","ready"),
                        request.optLong("waitTimeoutMs",request.optLong("startupTimeoutMs",60_000L)),cancellation);
                }
                event(output,new JSONObject().put("result",res).put("exit",0));
                return true;
            }
            case "svc.list":
                event(output,new JSONObject().put("result",taskRegistry.listTasks()).put("exit",0));
                return true;
            case "svc.status":
                event(output,new JSONObject().put("result",taskRegistry.getStatus(request.getString("id"))).put("exit",0));
                return true;
            case "svc.stop":
                event(output,new JSONObject().put("result",taskRegistry.stopTask(request.getString("id"),request.optLong("gracePeriodMs",-1L),false)).put("exit",0));
                return true;
            case "svc.wait":
                event(output,new JSONObject().put("result",taskRegistry.waitTask(request.getString("id"),request.optString("waitFor","ready"),request.optLong("timeoutMs",60_000L),cancellation)).put("exit",0));
                return true;
            case "svc.logs":
                taskRegistry.streamLogs(request.getString("id"),request.optLong("offset",0L),request.optLong("tail",0L),request.optBoolean("follow",false),output,cancellation);
                return true;
            case "svc.netinfo":{
                LinuxEnvironment.updateDns(context);
                JSONObject info=new JSONObject()
                    .put("uid",android.os.Process.myUid())
                    .put("dnsServers",new JSONArray(LinuxEnvironment.dnsServers(context)))
                    .put("lanIpv4",new JSONArray(LinuxEnvironment.lanIpv4Addresses(context)));
                event(output,new JSONObject().put("result",info).put("exit",0));
                return true;
            }
            case "svc.open":{
                String target=request.optString("target","").trim();
                String url=target;
                if(!target.startsWith("http://") && !target.startsWith("https://")){
                    JSONObject st=taskRegistry.getStatus(target);
                    url=st.optString("baseUrl","");
                }
                if(url.isEmpty())throw new IOException("该服务没有可打开的 URL："+target);
                String finalUrl=url;
                new Handler(Looper.getMainLooper()).post(()->{
                    try{
                        Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(finalUrl));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(i);
                    }catch(Exception ignored){}
                });
                event(output,new JSONObject().put("result",new JSONObject().put("opened",finalUrl)).put("exit",0));
                return true;
            }
            default:
                return false;
        }
    }
    private void serve(LocalSocket socket){
        Job job=null;
        TaskRegistry.Cancellation cancellation=new TaskRegistry.Cancellation();
        try {
            InputStream input=new BufferedInputStream(socket.getInputStream(),16384);OutputStream output=socket.getOutputStream();
            String first=line(input);if(first==null)return;
            JSONObject request=new JSONObject(first);
            String op=request.optString("op","exec");
            if("svc.wait".equals(op) || ("svc.logs".equals(op) && request.optBoolean("follow",false))
                    || ("svc.start".equals(op) && request.optBoolean("wait",false))){
                new Thread(()->{
                    try{while(input.read()!=-1){}}catch(IOException ignored){}
                    finally{
                        cancellation.cancel();
                        try{socket.close();}catch(IOException ignored){}
                    }
                },"LumaServiceDisconnect").start();
            }
            if(handleServiceOp(op,request,output,cancellation))return;
            JSONArray array=request.optJSONArray("args");
            ArrayList<String> arguments=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)arguments.add(array.getString(i));
            String cmdName=request.getString("command");
            if("adb-shell".equals(cmdName) && handleAdbShellBuiltin(arguments,output))return;
            job=start(cmdName,arguments,request.optString("cwd","/workspace"));
            Job active=job;
            Thread stdout=pump(job.process.getInputStream(),output,"stdout"),stderr=pump(job.process.getErrorStream(),output,"stderr");
            new Thread(()->{
                try{String text;while((text=line(input))!=null){
                    JSONObject control=new JSONObject(text);
                    if(control.has("stdin")){byte[] data=Base64.decode(control.getString("stdin"),Base64.DEFAULT);active.process.getOutputStream().write(data);active.process.getOutputStream().flush();}
                    if(control.optBoolean("eof"))active.process.getOutputStream().close();
                    if("INT".equals(control.optString("signal")))active.interrupt();
                    if("TERM".equals(control.optString("signal")))active.stop();
                }}catch(Exception ignored){}finally{if(active.process.isAlive())active.stop();}
            },"LumaNativeInput").start();
            int exit=job.process.waitFor();stdout.join();stderr.join();job.finish();event(output,new JSONObject().put("exit",exit));
        }catch(Exception e){
            try{event(socket.getOutputStream(),new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()).put("exit",1));}catch(Exception ignored){}
        }finally{
            cancellation.cancel();
            if(job!=null){if(job.process.isAlive())job.stop();jobs.remove(job);job.cleanup();}
            clients.remove(socket);
            try{socket.shutdownOutput();}catch(Exception ignored){}
            try{socket.close();}catch(IOException ignored){}
        }
    }
    static File mapGuestPath(Context context,String path)throws IOException {
        if(!path.startsWith("/"))throw new IOException("需要绝对路径");
        File base;String relative;
        if(path.equals("/workspace") || path.startsWith("/workspace/")){base=LinuxEnvironment.workspace(context);relative=path.substring(10);}
        else if(path.equals("/sdcard") || path.startsWith("/sdcard/")){
            if(!LinuxEnvironment.storageAvailable())throw new IOException("未授权共享存储");
            base=LinuxEnvironment.sharedStorage();relative=path.substring(7);
        }else{base=LinuxEnvironment.root(context);relative=path.substring(1);}
        while(relative.startsWith("/"))relative=relative.substring(1);
        File file=new File(base,relative).getCanonicalFile();String boundary=base.getCanonicalPath();
        if(!file.getPath().equals(boundary) && !file.getPath().startsWith(boundary+"/"))throw new IOException("路径越界："+path);
        return file;
    }
    static String mapGuestArgument(Context context,String arg)throws IOException {
        if(arg.startsWith("/workspace/") || arg.equals("/workspace") || arg.startsWith("/sdcard/") || arg.equals("/sdcard") || arg.startsWith("/root/") || arg.startsWith("/tmp/"))return mapGuestPath(context,arg).getPath();
        int equal=arg.indexOf('=');if(arg.startsWith("--") && equal>0)return arg.substring(0,equal+1)+mapGuestArgument(context,arg.substring(equal+1));
        return arg;
    }
    private File mapped(String path)throws IOException {return mapGuestPath(context,path);}
    private String argument(String arg)throws IOException {return mapGuestArgument(context,arg);}
    private boolean handleAdbShellBuiltin(List<String> arguments,OutputStream output)throws IOException {
        if(arguments.size()==1 && ("--help".equals(arguments.get(0)) || "-h".equals(arguments.get(0)))){
            String help=
                "用法：\n"+
                "  adb-shell <command> [args...]   通过 Shizuku 在宿主 Android 执行 ADB Shell 命令\n"+
                "  adb-shell -c \"<script>\"         执行包含管道或复合语句的 Shell 脚本\n"+
                "  adb-shell --status              查看 Shizuku 安装、运行与授权状态\n"+
                "  adb-shell --help                显示此帮助信息\n\n"+
                "路径说明：\n"+
                "  命令中引用的 /workspace、/root、/tmp 路径会自动中转并同步回 Debian 环境；\n"+
                "  /sdcard 路径将直接访问手机共享存储。\n";
            byte[] bytes=help.getBytes(StandardCharsets.UTF_8);
            try{event(output,new JSONObject().put("stdout",Base64.encodeToString(bytes,Base64.NO_WRAP)));event(output,new JSONObject().put("exit",0));}
            catch(Exception e){throw new IOException(e);}
            return true;
        }
        if(arguments.size()==1 && "--status".equals(arguments.get(0))){
            boolean installed=ShizukuBridge.isInstalled(context);
            boolean running=ShizukuBridge.isRunning(context);
            boolean authorized=running && ShizukuBridge.isAuthorized(context);
            int uid=authorized?ShizukuBridge.serverUid(context):-1;
            int version=authorized?ShizukuBridge.serverVersion(context):-1;
            String selinux=authorized?ShizukuBridge.seLinuxContext(context):null;
            StringBuilder sb=new StringBuilder();
            sb.append("installed=").append(installed?"yes":"no").append('\n');
            sb.append("running=").append(running?"yes":"no").append('\n');
            sb.append("authorized=").append(authorized?"yes":"no").append('\n');
            if(authorized){
                String role=uid==0?"root":uid==2000?"shell":"uid-"+uid;
                sb.append("uid=").append(uid).append(" (").append(role).append(")\n");
                if(version>0)sb.append("version=").append(version).append('\n');
                if(selinux!=null)sb.append("selinux=").append(selinux).append('\n');
            }
            byte[] bytes=sb.toString().getBytes(StandardCharsets.UTF_8);
            try{event(output,new JSONObject().put("stdout",Base64.encodeToString(bytes,Base64.NO_WRAP)));event(output,new JSONObject().put("exit",authorized?0:1));}
            catch(Exception e){throw new IOException(e);}
            return true;
        }
        return false;
    }
    private Job start(String command,List<String> arguments,String cwd)throws Exception {
        if(closed)throw new IOException("Luma 已停止");
        if("adb-shell".equals(command)){
            ShizukuBridge.RelaySession relay=new ShizukuBridge.RelaySession(context);
            try {
                String remoteDir=relay.prepareWorkingDirectory(cwd);
                List<String> rewritten=relay.rewriteArguments(arguments,cwd);
                String[] shellCmd=ShizukuBridge.buildShellCommand(rewritten);
                java.lang.Process proc=ShizukuBridge.newProcess(context,shellCmd,ShizukuBridge.defaultAdbEnvironment(),remoteDir);
                Job job=new Job(proc,null,false,relay);jobs.add(job);
                if(closed){job.stop();job.cleanup();throw new IOException("Luma 已停止");}
                return job;
            }catch(Exception e){relay.close();throw e;}
        }
        String nativeDir=context.getApplicationInfo().nativeLibraryDir;
        ArrayList<String> launch=new ArrayList<>();File pidFile=File.createTempFile("native-",".pid",context.getCacheDir());
        launch.add(nativeDir+"/libluma_exec.so");launch.add(pidFile.getPath());
        boolean linux=command.equals("linux");
        if(linux)Collections.addAll(launch,LinuxEnvironment.command(context,arguments,cwd));
        else {
            String executable;
            if(command.equals("compute-check"))executable=nativeDir+"/libluma_compute.so";
            else if(command.equals("android-run") && !arguments.isEmpty()){
                String name=arguments.get(0);if(!name.startsWith("/"))name=cwd+"/"+name;
                executable=mapped(name).getPath();arguments=new ArrayList<>(arguments.subList(1,arguments.size()));
            } else throw new IOException("未知原生命令");
            launch.add("/system/bin/linker64");launch.add(executable);
            for(String arg:arguments)launch.add(argument(arg));
        }
        ProcessBuilder builder=new ProcessBuilder(launch);builder.directory(mapped(cwd));
        Map<String,String> environment=builder.environment();environment.clear();
        environment.put("PATH","/system/bin");environment.put("HOME",new File(LinuxEnvironment.root(context),"root").getPath());
        environment.put("TMPDIR",new File(LinuxEnvironment.root(context),"tmp").getPath());
        if(linux)for(String entry:LinuxEnvironment.environment(context)){int eq=entry.indexOf('=');environment.put(entry.substring(0,eq),entry.substring(eq+1));}
        else environment.put("LD_LIBRARY_PATH",nativeDir);
        try {
            Job job=new Job(builder.start(),pidFile,linux);jobs.add(job);
            if(closed){job.stop();throw new IOException("Luma 已停止");}return job;
        }catch(Exception e){pidFile.delete();throw e;}
    }
    void checkToFile(){
        new Thread(()->{
            StringBuilder text=new StringBuilder("Luma compute check\n");
            try {
                for(String command:new String[]{"compute-check","linux"}){
                    List<String> args=command.equals("linux")?Arrays.asList("/usr/local/bin/node","-e","let s=0;for(let i=0;i<100000;i++)s+=i;if(s!==4999950000)process.exit(1);console.log('DEBIAN_CPU_OK')"):Collections.emptyList();
                    Job job=start(command,args,"/workspace");job.process.getOutputStream().close();
                    ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
                    Thread err=new Thread(()->{try{byte[] b=new byte[8192];int n;while((n=job.process.getErrorStream().read(b))!=-1)stderr.write(b,0,n);}catch(IOException ignored){}});err.start();
                    try(InputStream in=job.process.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)stdout.write(b,0,n);}
                    int exit=job.process.waitFor();err.join();jobs.remove(job);job.pidFile.delete();
                    text.append("\nCOMMAND ").append(command).append(" EXIT ").append(exit).append('\n').append(stdout.toString("UTF-8")).append(stderr.toString("UTF-8"));
                }
            }catch(Exception e){text.append("ERROR ").append(e.toString());}
            try{RuntimeAssets.write(new File(context.getFilesDir(),"compute-check.txt"),text.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
        },"LumaComputeCheck").start();
    }
    @Override public void close(){
        closed=true;try{server.close();}catch(IOException ignored){}
        try{taskRegistry.close();}catch(Exception ignored){}
        for(Job job:jobs)job.stop();
        for(LocalSocket socket:clients)try{socket.close();}catch(IOException ignored){}
    }
}
