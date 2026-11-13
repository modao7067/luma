package io.github.modao7067.luma;

import android.content.*;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class GuiRuntimeBridge {
    public interface ProgressListener { void onProgress(String message); }
    private static final String SERVER_ID="luma-codex-gui";
    private GuiRuntimeBridge(){}
    public static boolean isReady(Context context){return LinuxEnvironment.ready(context);}
    public static File debianRoot(Context context){return LinuxEnvironment.root(context);}
    public static void syncDns(Context context){LinuxEnvironment.updateDns(context.getApplicationContext());}
    public static void stageForRoot(Context context)throws Exception{RuntimeAssets.stageForRoot(context.getApplicationContext());}
    public static void stopBackgroundService(Context context){ShellService.stopIfRunning(context.getApplicationContext());}
    public static void prepareEnvironmentHeadless(Context context,ProgressListener progress)throws Exception {
        Context app=context.getApplicationContext();
        ShellService.stopIfRunning(app);
        EnvironmentInstaller.install(app,message->{if(progress!=null)progress.onProgress(message);});
        RuntimeAssets.ensure(app);
        LinuxEnvironment.updateDns(app);
    }
    public static List<String> lanIpv4Addresses(Context context){return LinuxEnvironment.lanIpv4Addresses(context);}
    public static boolean isWakeLockEnabled(Context context){return ShellService.isWakeLockEnabled(context);}
    public static void setWakeLockEnabled(Context context,boolean enabled){ShellService.setWakeLockPreference(context,enabled);}
    public static void updateSessionStatus(String statusText){ShellService.updateSessionStatus(statusText);}
    private interface Operation<T>{T run(ShellService service)throws Exception;}
    private static <T>T withService(Context input,Operation<T> action)throws Exception {
        Context context=input.getApplicationContext();
        CountDownLatch connected=new CountDownLatch(1);AtomicReference<ShellService> result=new AtomicReference<>();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){
                result.set(binder instanceof ShellService.LocalBinder ? ((ShellService.LocalBinder)binder).get() : null);
                connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name){result.set(null);}
        };
        Intent intent=new Intent(context,ShellService.class);context.startForegroundService(intent);
        boolean bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
        if(!bound)throw new IOException("无法连接 Debian 服务，请先打开 NoRoot 终端");
        try {
            if(!connected.await(15,TimeUnit.SECONDS))throw new IOException("Debian 服务连接超时，请重试");
            ShellService service=result.get();if(service==null)throw new IOException("Debian 服务已断开");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!service.environmentReady){
                if(service.preparationError!=null)throw new IOException("Debian 准备失败："+service.preparationError);
                if(service.isClosing())throw new IOException("Debian 服务正在关闭，请重新打开终端后重试");
                if(System.nanoTime()>deadline)throw new IOException("Debian 仍在准备，请打开 NoRoot 终端查看进度后重试");
                Thread.sleep(100);
            }
            return action.run(service);
        }finally{context.unbindService(connection);}
    }
    public static String connect(Context context,String tokenPath)throws Exception {
        return withService(context,service->{
            TaskRegistry tasks=service.tasks();
            JSONObject status=null;
            try{status=tasks.getStatus(SERVER_ID);}catch(IOException ignored){}
            if(status==null||"exited".equals(status.optString("phase"))){
                String script="command -v codex >/dev/null 2>&1 || { printf '请在 NoRoot 终端安装并登录 Codex 0.166.0 或兼容版本\\n' >&2; exit 127; }; exec codex app-server --listen ws://127.0.0.1:{port} --ws-auth capability-token --ws-token-file "+quote(tokenPath);
                JSONObject request=new JSONObject().put("id",SERVER_ID).put("name","Luma Codex")
                    .put("backend","linux").put("cwd","/workspace")
                    .put("args",new JSONArray(Arrays.asList("/bin/bash","--login","-c",script)))
                    .put("listen",new JSONObject().put("host","127.0.0.1").put("port",0))
                    .put("probe",new JSONObject().put("type","http").put("path","/readyz").put("okStatus","200"))
                    .put("restartPolicy","never");
                status=tasks.startTask(request);
            }
            status=tasks.waitTask(SERVER_ID,"ready",30_000L);
            if(!"yes".equals(status.optString("ready")))throw new IOException("Codex 服务未就绪，请在 NoRoot 终端确认 codex 版本及登录状态，再重试");
            return "ws://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static String connectOpenCode(Context context,String passwordPath,String launcherPath)throws Exception {
        return withService(context,service->{
            String id="luma-opencode-gui";
            TaskRegistry tasks=service.tasks(); JSONObject status=null;
            try{status=tasks.getStatus(id);}catch(IOException ignored){}
            if(status!=null&&!"exited".equals(status.optString("phase"))&&!tasks.isTaskHealthy(id,3_000L)){
                status=tasks.stopTask(id,1_500L,false);
            }
            if(status==null||"exited".equals(status.optString("phase"))){
                String script="if ! command -v opencode >/dev/null 2>&1; then export PATH=\"$PATH:$HOME/.opencode/bin\"; fi; command -v opencode >/dev/null 2>&1 || { printf '未找到 OpenCode 可执行文件（PATH 或 ~/.opencode/bin），请在 NoRoot 终端检查安装\\n' >&2; exit 127; }; exec /usr/bin/python3 "+quote(launcherPath)+" {port} "+quote(passwordPath);
                JSONObject request=new JSONObject().put("id",id).put("name","Luma OpenCode")
                    .put("backend","linux").put("cwd","/workspace")
                    .put("args",new JSONArray(Arrays.asList("/bin/bash","--login","-c",script)))
                    .put("listen",new JSONObject().put("host","127.0.0.1").put("port",0))
                    .put("probe",new JSONObject().put("type","http").put("path","/api/info").put("okStatus","401"))
                    .put("restartPolicy","never");
                status=tasks.startTask(request);
            }
            status=tasks.waitTask(id,"ready",30_000L);
            if(!"yes".equals(status.optString("ready")))throw new IOException("OpenCode 服务未就绪，请查看 NoRoot 任务日志中的启动错误");
            return "http://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static void stopOpenCodeIfIdle(Context context)throws Exception {
        if(!isReady(context))return;
        withService(context,service->{
            String id="luma-opencode-gui";
            TaskRegistry tasks=service.tasks(); JSONObject status=null;
            try{status=tasks.getStatus(id);}catch(IOException ignored){}
            if(status==null||"exited".equals(status.optString("phase")))return null;
            tasks.stopTask(id,1_500L,false);
            return null;
        });
    }
    public static String connectNativeCli(Context context,String launcher,String token,String directory)throws Exception {
        return connectNativeCli(context,launcher,token,directory,"luma-native-cli-gui");
    }
    public static String connectNativeCli(Context context,String launcher,String token,String directory,String taskId)throws Exception {
        if(!"luma-native-cli-gui".equals(taskId)&&!"luma-native-cli-gui-v3".equals(taskId)&&!"luma-antigravity-gui".equals(taskId)&&!"luma-acp-cli-gui".equals(taskId)&&!"luma-codebuddy-gui-v1".equals(taskId))throw new IOException("无效 CLI 任务标识");
        return withService(context,service->{
            String id=taskId;
            TaskRegistry tasks=service.tasks(); JSONObject status=null;
            try{status=tasks.getStatus(id);}catch(IOException ignored){}
            if(status==null||"exited".equals(status.optString("phase"))){
                String script="exec /usr/bin/python3 -I -B "+quote(launcher)+" {port} "+quote(token)+" "+quote(directory);
                JSONObject request=new JSONObject().put("id",id).put("name","Luma Native CLI")
                    .put("backend","linux").put("cwd","/workspace")
                    .put("args",new JSONArray(Arrays.asList("/bin/bash","--login","-c",script)))
                    .put("listen",new JSONObject().put("host","127.0.0.1").put("port",0))
                    .put("probe",new JSONObject().put("type","http").put("path","/health").put("okStatus","401"))
                    .put("restartPolicy","never");
                status=tasks.startTask(request);
            }
            status=tasks.waitTask(id,"ready",30_000L);
            if(!"yes".equals(status.optString("ready")))throw new IOException("CLI 桥接服务未就绪，请查看 NoRoot 任务日志");
            return "http://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static void stopNativeCli(Context context,String taskId)throws Exception {
        withService(context,service->{
            JSONObject status=service.tasks().stopOwnedCliBridge(taskId);
            if(!"exited".equals(status.optString("phase")))throw new IOException("CLI 桥服务尚未退出；未启动新服务，可稍后重试更新");
            return null;
        });
    }
    public static String execute(Context context,List<String> command)throws Exception {
        return withService(context,service->{
            ProcessBuilder builder=new ProcessBuilder(LinuxEnvironment.command(context,command,"/workspace"));
            builder.environment().clear();
            for(String pair:LinuxEnvironment.environment(context)){int at=pair.indexOf('=');builder.environment().put(pair.substring(0,at),pair.substring(at+1));}
            builder.redirectErrorStream(true);
            Process process=builder.start();process.getOutputStream().close();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            Thread reader=new Thread(()->{try(InputStream in=process.getInputStream()){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(output.size()+n>2*1024*1024){process.destroy();break;}output.write(block,0,n);}}catch(IOException ignored){}},"LumaGuiFileRead");reader.start();
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroy();throw new IOException("Debian 文件操作超时");}
            reader.join(2000);String text=output.toString(StandardCharsets.UTF_8.name());
            if(process.exitValue()!=0)throw new IOException(text.isBlank()?"Debian 文件操作失败":text.trim());
            return text;
        });
    }
    public static void copyFile(Context context,String path,File destination,long limit)throws Exception {
        withService(context,service->{TerminalLinkReader.copy(context,path,destination,limit);return null;});
    }
    private static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
}
