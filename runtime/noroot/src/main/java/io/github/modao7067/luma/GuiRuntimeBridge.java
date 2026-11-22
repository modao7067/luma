package io.github.modao7067.luma;

import android.content.*;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        ShellService.stopIfRunningAndAwait(app,5_000L);
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
        Intent intent=new Intent(context,ShellService.class);
        try{
            context.startForegroundService(intent);
        }catch(android.app.ForegroundServiceStartNotAllowedException e){
            throw new IOException(com.termux.terminal.LumaLang.tr("后台无法启动 Debian 前台服务，请先回到 Luma 前台后重试","Cannot start Debian foreground service from background; return to Luma foreground and retry"),e);
        }
        boolean bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
        if(!bound)throw new IOException(com.termux.terminal.LumaLang.tr("无法连接 Debian 服务，请先打开 NoRoot 终端","Failed to connect to Debian service; open NoRoot terminal first"));
        try {
            if(!connected.await(15,TimeUnit.SECONDS))throw new IOException(com.termux.terminal.LumaLang.tr("Debian 服务连接超时，请重试","Debian service connection timed out; please retry"));
            ShellService service=result.get();if(service==null)throw new IOException(com.termux.terminal.LumaLang.tr("Debian 服务已断开","Debian service disconnected"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!service.environmentReady){
                if(service.preparationError!=null)throw new IOException(com.termux.terminal.LumaLang.tr("Debian 准备失败：","Debian setup failed: ")+service.preparationError);
                if(service.isClosing())throw new IOException(com.termux.terminal.LumaLang.tr("Debian 服务正在关闭，请重新打开终端后重试","Debian service is closing; reopen terminal and retry"));
                if(System.nanoTime()>deadline)throw new IOException(com.termux.terminal.LumaLang.tr("Debian 仍在准备，请打开 NoRoot 终端查看进度后重试","Debian is still preparing; open NoRoot terminal to check progress and retry"));
                Thread.sleep(100);
            }
            return action.run(service);
        }finally{context.unbindService(connection);}
    }
    private static <T>T withRunningService(Context input,Operation<T> action)throws Exception {
        if(input==null)return null;
        Context context=input.getApplicationContext();
        if(context==null)context=input;
        CountDownLatch connected=new CountDownLatch(1);
        AtomicReference<ShellService> result=new AtomicReference<>();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){
                result.set(binder instanceof ShellService.LocalBinder ? ((ShellService.LocalBinder)binder).get() : null);
                connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name){result.set(null);}
        };
        Intent intent=new Intent(context,ShellService.class);
        boolean bound=false;
        try{
            bound=context.bindService(intent,connection,0);
            if(!bound||!connected.await(500,TimeUnit.MILLISECONDS))return null;
            ShellService service=result.get();
            if(service==null||!service.environmentReady||service.isClosing())return null;
            return action.run(service);
        }finally{
            if(bound){
                try{context.unbindService(connection);}catch(Exception ignored){}
            }
        }
    }
    public static String connect(Context context,String tokenPath)throws Exception {
        return withService(context,service->{
            TaskRegistry tasks=service.tasks();
            JSONObject status=null;
            try{status=tasks.getStatus(SERVER_ID);}catch(IOException ignored){}
            if(status==null||"exited".equals(status.optString("phase"))){
                String script="command -v codex >/dev/null 2>&1 || { if [[ \"${LUMA_LANG:-zh}\" == zh* ]]; then printf '请在 NoRoot 终端安装并登录 Codex 0.166.0 或兼容版本\\n' >&2; else printf 'Install and sign in to Codex 0.166.0 or compatible in the NoRoot terminal\\n' >&2; fi; exit 127; }; exec codex app-server --listen ws://127.0.0.1:{port} --ws-auth capability-token --ws-token-file "+quote(tokenPath);
                JSONObject request=new JSONObject().put("id",SERVER_ID).put("name","Luma Codex")
                    .put("backend","linux").put("cwd","/workspace")
                    .put("args",new JSONArray(Arrays.asList("/bin/bash","--login","-c",script)))
                    .put("listen",new JSONObject().put("host","127.0.0.1").put("port",0))
                    .put("probe",new JSONObject().put("type","http").put("path","/readyz").put("okStatus","200"))
                    .put("restartPolicy","never");
                status=tasks.startTask(request);
            }
            status=tasks.waitTask(SERVER_ID,"ready",30_000L);
            if(!"yes".equals(status.optString("ready")))throw new IOException(com.termux.terminal.LumaLang.tr("Codex 服务未就绪，请在 NoRoot 终端确认 codex 版本及登录状态，再重试","Codex server not ready; verify codex version and sign-in state in NoRoot terminal, then retry"));
            return "ws://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static void stopCodexIfIdle(Context context)throws Exception {
        if(context==null||!isReady(context))return;
        Context app=context.getApplicationContext();
        if(app==null)app=context;
        Context target=app;
        withRunningService(target,service->{
            stopTaskIfIdle(target,service.tasks(),SERVER_ID,false);
            return null;
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
                String script="if ! command -v opencode >/dev/null 2>&1; then export PATH=\"$PATH:$HOME/.opencode/bin\"; fi; command -v opencode >/dev/null 2>&1 || { if [[ \"${LUMA_LANG:-zh}\" == zh* ]]; then printf '未找到 OpenCode 可执行文件（PATH 或 ~/.opencode/bin），请在 NoRoot 终端检查安装\\n' >&2; else printf 'OpenCode executable not found (PATH or ~/.opencode/bin); check installation in NoRoot terminal\\n' >&2; fi; exit 127; }; exec /usr/bin/python3 "+quote(launcherPath)+" {port} "+quote(passwordPath);
                JSONObject request=new JSONObject().put("id",id).put("name","Luma OpenCode")
                    .put("backend","linux").put("cwd","/workspace")
                    .put("args",new JSONArray(Arrays.asList("/bin/bash","--login","-c",script)))
                    .put("listen",new JSONObject().put("host","127.0.0.1").put("port",0))
                    .put("probe",new JSONObject().put("type","http").put("path","/api/info").put("okStatus","401"))
                    .put("restartPolicy","never");
                status=tasks.startTask(request);
            }
            status=tasks.waitTask(id,"ready",30_000L);
            if(!"yes".equals(status.optString("ready")))throw new IOException(com.termux.terminal.LumaLang.tr("OpenCode 服务未就绪，请查看 NoRoot 任务日志中的启动错误","OpenCode server not ready; check startup errors in NoRoot task logs"));
            return "http://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static void stopOpenCodeIfIdle(Context context)throws Exception {
        if(context==null||!isReady(context))return;
        Context app=context.getApplicationContext();
        if(app==null)app=context;
        Context target=app;
        withRunningService(target,service->{
            stopTaskIfIdle(target,service.tasks(),"luma-opencode-gui",true);
            return null;
        });
    }
    private static void stopTaskIfIdle(Context context,TaskRegistry tasks,String id,boolean checkOpenCodeActive)throws Exception {
        if(tasks==null)return;
        JSONObject status=null;
        try{status=tasks.getStatus(id);}catch(IOException ignored){}
        if(status==null||!"running".equals(status.optString("phase")))return;
        JSONObject listen=status.optJSONObject("listen");
        int port=listen!=null?listen.optInt("port",-1):-1;
        int pid=status.optInt("pid",-1);
        if(port<=0||port>65535||pid<2)return;
        if(hasEstablishedClients(port)||hasNonMcpBackgroundChild(pid,new File("/proc")))return;
        if(checkOpenCodeActive&&!isOpenCodeSessionIdle(context,port))return;
        if(hasEstablishedClients(port)||hasNonMcpBackgroundChild(pid,new File("/proc")))return;
        tasks.stopTask(id,1_500L,false);
    }
    static boolean hasEstablishedClients(int port){
        return hasEstablishedClients(port,new File("/proc/net/tcp"),new File("/proc/net/tcp6"));
    }
    static boolean hasEstablishedClients(int port,File tcp4,File tcp6){
        if(port<=0||port>65535)return false;
        String hexPort=String.format(Locale.ROOT,"%04X",port);
        Set<String> v4Targets=Collections.singleton("0100007F:"+hexPort);
        Set<String> v6Targets=new HashSet<>(Arrays.asList(
            "0000000000000000FFFF00000100007F:"+hexPort,
            "00000000000000000000000001000000:"+hexPort));
        return hasEstablishedSocketInFile(tcp4,v4Targets)||hasEstablishedSocketInFile(tcp6,v6Targets);
    }
    private static boolean hasEstablishedSocketInFile(File procFile,Set<String> targets){
        if(procFile==null||!procFile.isFile())return false;
        try{
            List<String> lines=Files.readAllLines(procFile.toPath(),StandardCharsets.UTF_8);
            for(int i=1;i<lines.size();i++){
                String[] parts=lines.get(i).trim().split("\\s+");
                if(parts.length>=4&&"01".equals(parts[3])&&targets.contains(parts[1].toUpperCase(Locale.ROOT))){
                    return true;
                }
            }
        }catch(Exception ignored){}
        return false;
    }
    static boolean hasNonMcpBackgroundChild(int pid,File procDir){
        if(pid<2||procDir==null||!procDir.isDirectory())return false;
        File[] entries=procDir.listFiles();
        if(entries==null)return false;
        Map<Integer,List<Integer>> byPpid=new HashMap<>();
        for(File entry:entries){
            String name=entry.getName();
            if(!isDigits(name))continue;
            int childPid;
            try{childPid=Integer.parseInt(name);}catch(NumberFormatException ignored){continue;}
            if(childPid==pid)continue;
            Integer ppid=readAlivePpid(entry);
            if(ppid!=null)byPpid.computeIfAbsent(ppid,k->new ArrayList<>()).add(childPid);
        }
        String rootCmd=readCmdline(new File(procDir,String.valueOf(pid)));
        boolean isProot=rootCmd.contains("proot")||rootCmd.contains("libluma_exec");
        Set<Integer> visited=new HashSet<>();
        visited.add(pid);
        return hasNonMcpDescendant(pid,procDir,byPpid,visited,isProot);
    }
    private static boolean hasNonMcpDescendant(int parentPid,File procDir,Map<Integer,List<Integer>> byPpid,Set<Integer> visited,boolean unwrapProotDaemon){
        List<Integer> children=byPpid.get(parentPid);
        if(children==null||children.isEmpty())return false;
        Set<String> holderTargets=null;
        boolean unwrapped=false;
        for(int childPid:children){
            if(!visited.add(childPid))continue;
            File childDir=new File(procDir,String.valueOf(childPid));
            String cmd=readCmdline(childDir);
            if(unwrapProotDaemon&&!unwrapped&&isPrimaryGuestDaemon(cmd)){
                unwrapped=true;
                if(hasNonMcpDescendant(childPid,procDir,byPpid,visited,cmd.contains("opencode-launch.py")))return true;
                continue;
            }
            boolean isNamedHelper=cmd.contains("mcp")||cmd.contains("supervisor.mjs")||cmd.contains("localharness");
            boolean isStdioHelper=false;
            if(!isNamedHelper){
                if(holderTargets==null)holderTargets=readParentStdioTargets(new File(procDir,String.valueOf(parentPid)));
                String stdinTarget=readLinkTarget(new File(childDir,"fd/0"));
                isStdioHelper=(stdinTarget.startsWith("pipe:")||stdinTarget.startsWith("socket:"))&&holderTargets.contains(stdinTarget);
            }
            if(!isNamedHelper&&!isStdioHelper)return true;
            if(hasNonMcpDescendant(childPid,procDir,byPpid,visited,false))return true;
        }
        return false;
    }
    private static boolean isPrimaryGuestDaemon(String cmd){
        return (cmd.contains("codex")&&cmd.contains("app-server"))
            ||(cmd.contains("opencode")&&cmd.contains("serve"))
            ||cmd.contains("opencode-launch.py");
    }
    private static Integer readAlivePpid(File procPidDir){
        String stat=readTrimmedText(new File(procPidDir,"stat"),65_536);
        int rparen=stat.lastIndexOf(')');
        if(rparen<0||rparen+1>=stat.length())return null;
        String[] fields=stat.substring(rparen+1).trim().split("\\s+");
        if(fields.length<2||"Z".equals(fields[0])||"X".equals(fields[0]))return null;
        try{return Integer.valueOf(fields[1]);}catch(NumberFormatException ignored){return null;}
    }
    private static String readCmdline(File procPidDir){
        byte[] raw=readFileBytes(new File(procPidDir,"cmdline"),65_536);
        if(raw.length==0)return "";
        for(int i=0;i<raw.length;i++){
            if(raw[i]==0)raw[i]=' ';
        }
        return new String(raw,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
    private static Set<String> readParentStdioTargets(File parentDir){
        Set<String> targets=new HashSet<>();
        File fdDir=new File(parentDir,"fd");
        File[] fds=fdDir.listFiles();
        if(fds==null)return targets;
        File fdInfoDir=new File(parentDir,"fdinfo");
        boolean checkFdInfo=fdInfoDir.isDirectory()&&fdInfoDir.canRead();
        for(File fd:fds){
            String target=readLinkTarget(fd);
            if(target.startsWith("socket:")){
                targets.add(target);
            }else if(target.startsWith("pipe:")){
                if(!checkFdInfo||isWritablePipeFd(new File(fdInfoDir,fd.getName()))){
                    targets.add(target);
                }
            }
        }
        return targets;
    }
    private static boolean isWritablePipeFd(File fdInfoFile){
        String text=readTrimmedText(fdInfoFile,8_192);
        if(text.isEmpty())return false;
        for(String line:text.split("\\R")){
            String trimmed=line.trim();
            if(trimmed.startsWith("flags:")){
                String octal=trimmed.substring(6).trim();
                try{
                    int flags=Integer.parseInt(octal,8);
                    return (flags&3)!=0;
                }catch(NumberFormatException ignored){
                    return false;
                }
            }
        }
        return false;
    }
    private static String readLinkTarget(File link){
        try{return Files.readSymbolicLink(link.toPath()).toString();}catch(Exception ignored){return "";}
    }
    private static boolean isDigits(String s){
        if(s.isEmpty())return false;
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c<'0'||c>'9')return false;
        }
        return true;
    }
    static boolean isOpenCodeSessionIdle(Context context,int port){
        File filesDir=context==null?null:context.getFilesDir();
        if(filesDir==null)return false;
        File passwordFile=new File(filesDir,"runtime/agent/opencode-password");
        if(!passwordFile.isFile())passwordFile=new File(filesDir,"runtime/agent/noroot/opencode-password");
        return isOpenCodeSessionIdle(passwordFile,"http://127.0.0.1:"+port);
    }
    static boolean isOpenCodeSessionIdle(File passwordFile,String baseUrl){
        String password=readTrimmedText(passwordFile,8_192);
        if(password.isEmpty())return false;
        HttpURLConnection conn=null;
        try{
            URL url=new URL(baseUrl+"/api/session/active");
            conn=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(2_500);
            conn.setReadTimeout(2_500);
            conn.setInstanceFollowRedirects(false);
            String auth=Base64.getEncoder().encodeToString(("opencode:"+password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization","Basic "+auth);
            conn.setRequestProperty("Connection","close");
            if(conn.getResponseCode()!=200)return false;
            String body=readStreamText(conn.getInputStream(),1_048_576);
            if(body.isEmpty())return false;
            JSONObject json=new JSONObject(body);
            JSONObject data=json.optJSONObject("data");
            if(data!=null)return data.length()==0;
            JSONArray array=json.optJSONArray("data");
            return array!=null&&array.length()==0;
        }catch(Exception ignored){
            return false;
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }
    private static String readTrimmedText(File file,int maxBytes){
        if(file==null||!file.isFile())return "";
        return new String(readFileBytes(file,maxBytes),StandardCharsets.UTF_8).trim();
    }
    private static byte[] readFileBytes(File file,int maxBytes){
        try(InputStream in=new FileInputStream(file)){
            return readStreamBytes(in,maxBytes);
        }catch(Exception ignored){
            return new byte[0];
        }
    }
    private static String readStreamText(InputStream in,int maxBytes)throws IOException {
        return new String(readStreamBytes(in,maxBytes),StandardCharsets.UTF_8).trim();
    }
    private static byte[] readStreamBytes(InputStream in,int maxBytes)throws IOException {
        try(InputStream source=in){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[4096];
            int total=0;
            int n;
            while((n=source.read(buf))!=-1){
                int take=Math.min(n,maxBytes-total);
                if(take>0){
                    out.write(buf,0,take);
                    total+=take;
                }
                if(total>=maxBytes)break;
            }
            return out.toByteArray();
        }
    }
    public static String connectNativeCli(Context context,String launcher,String token,String directory)throws Exception {
        return connectNativeCli(context,launcher,token,directory,"luma-native-cli-gui");
    }
    public static String connectNativeCli(Context context,String launcher,String token,String directory,String taskId)throws Exception {
        if(!"luma-native-cli-gui".equals(taskId)&&!"luma-native-cli-gui-v3".equals(taskId)&&!"luma-antigravity-gui".equals(taskId)&&!"luma-acp-cli-gui".equals(taskId)&&!"luma-codebuddy-gui-v1".equals(taskId))throw new IOException(com.termux.terminal.LumaLang.tr("无效 CLI 任务标识","Invalid CLI task ID"));
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
            if(!"yes".equals(status.optString("ready")))throw new IOException(com.termux.terminal.LumaLang.tr("CLI 桥接服务未就绪，请查看 NoRoot 任务日志","CLI bridge service not ready; check NoRoot task logs"));
            return "http://127.0.0.1:"+status.getJSONObject("listen").getInt("port");
        });
    }
    public static void stopNativeCli(Context context,String taskId)throws Exception {
        withService(context,service->{
            JSONObject status=service.tasks().stopOwnedCliBridge(taskId);
            if(!"exited".equals(status.optString("phase")))throw new IOException(com.termux.terminal.LumaLang.tr("CLI 桥服务尚未退出；未启动新服务，可稍后重试更新","CLI bridge service has not exited yet; new service not started, retry update shortly"));
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
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroy();throw new IOException(com.termux.terminal.LumaLang.tr("Debian 文件操作超时","Debian file operation timed out"));}
            reader.join(2000);String text=output.toString(StandardCharsets.UTF_8.name());
            if(process.exitValue()!=0)throw new IOException(text.isBlank()?com.termux.terminal.LumaLang.tr("Debian 文件操作失败","Debian file operation failed"):text.trim());
            return text;
        });
    }
    public static void copyFile(Context context,String path,File destination,long limit)throws Exception {
        withService(context,service->{TerminalLinkReader.copy(context,path,destination,limit);return null;});
    }
    private static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
}
