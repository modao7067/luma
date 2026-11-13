package io.github.modao7067.luma;

import android.content.Context;
import android.os.Environment;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TerminalEmulatorFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

final class LinuxEnvironment {
    private static volatile boolean syscallAcceleration;
    private static boolean accelerationChecked;
    static synchronized void detectAcceleration(Context c)throws InterruptedException {
        if(accelerationChecked)return;
        Process probe=null;
        boolean supported=false;
        try {
            String script="new(require('node:worker_threads').Worker)(`require('node:worker_threads').parentPort.postMessage(1)`,{eval:true}).on('message',()=>{require('node:child_process').execFileSync('/bin/true');process.stdout.write('LUMA_SECCOMP_OK')})";
            ProcessBuilder builder=new ProcessBuilder(command(c,Arrays.asList("/usr/local/bin/node","-e",script),"/workspace"));
            builder.environment().clear();
            for(String pair:environment(c,true)){int at=pair.indexOf('=');builder.environment().put(pair.substring(0,at),pair.substring(at+1));}
            builder.redirectErrorStream(true);
            probe=builder.start();probe.getOutputStream().close();
            if(probe.waitFor(2,TimeUnit.SECONDS)&&probe.exitValue()==0){
                byte[] buffer=new byte[4096];int n=probe.getInputStream().read(buffer);
                supported=n>0&&new String(buffer,0,n,StandardCharsets.UTF_8).contains("LUMA_SECCOMP_OK");
            }
        }catch(IOException|RuntimeException e){
            android.util.Log.i("Luma","PRoot acceleration probe unavailable; using compatibility mode");
        }finally{
            if(probe!=null){
                if(probe.isAlive())probe.destroyForcibly();
                try{probe.getInputStream().close();}catch(IOException ignored){}
            }
        }
        syscallAcceleration=supported;accelerationChecked=true;
        android.util.Log.i("Luma","PRoot syscall acceleration: "+(supported?"enabled":"compatibility"));
    }
    static File root(Context c){return new File(c.getFilesDir(),"linux/debian");}
    static File workspace(Context c){return new File(c.getFilesDir(),"workspace");}
    static boolean ready(Context c){return new File(root(c),".luma-ready").isFile();}
    static boolean storageGranted(){return Environment.isExternalStorageManager();}
    static File sharedStorage(){return Environment.getExternalStorageDirectory();}
    static boolean storageAvailable(){return storageGranted() && sharedStorage().isDirectory() && sharedStorage().canRead();}
    static String socketName(Context c){return "luma.native."+c.getApplicationInfo().uid;}
    static String timezoneId(){
        TimeZone tz=TimeZone.getDefault();
        String id=tz==null?"":tz.getID();
        if(id==null||id.isEmpty()||id.startsWith("/")||id.contains("..")||id.startsWith("GMT"))return "";
        return id;
    }
    static TerminalSession session(Context c,TerminalSessionClient client){
        return session(c,client,TerminalEmulatorFactory.nativeFactory());
    }
    static TerminalSession session(Context c,TerminalSessionClient client,TerminalEmulatorFactory factory){
        String[] args=command(c,Arrays.asList("/bin/bash","--login"),"/workspace");
        return new TerminalSession(args[0],c.getFilesDir().getAbsolutePath(),args,environment(c),10000,client,
            pid -> { if(pid>0)android.system.Os.kill(pid,android.system.OsConstants.SIGQUIT); },factory);
    }
    static String[] command(Context c,List<String> guestCommand,String cwd){
        return command(c,guestCommand,cwd,Collections.emptyMap());
    }
    static String[] command(Context c,List<String> guestCommand,String cwd,Map<String,String> extraEnv){
        File temp=new File(c.getCacheDir(),"proot");
        if(!temp.isDirectory() && !temp.mkdirs())throw new IllegalStateException("无法创建临时目录");
        String nativeDir=c.getApplicationInfo().nativeLibraryDir;
        updateDns(c);
        String proot=new File(nativeDir,"libproot_exec.so").getAbsolutePath();
        ArrayList<String> args=new ArrayList<>();
        Collections.addAll(args,proot,"--root-id","--link2symlink","--kill-on-exit","--sysvipc",
            "-r",root(c).getAbsolutePath(),"-w",cwd);
        for(String path:new String[]{"/dev","/proc","/sys"}){
            if(new File(path).isDirectory())Collections.addAll(args,"-b",path);
        }
        Collections.addAll(args,"-b",workspace(c).getAbsolutePath()+":/workspace","-b",temp.getAbsolutePath()+":/dev/shm");
        File frames=new File(c.getCacheDir(),"kitty-frames");
        if(frames.isDirectory() || frames.mkdirs()){
            Collections.addAll(args,"-b",frames.getAbsolutePath()+":"+frames.getAbsolutePath());
        }
        File runtime=new File(c.getFilesDir(),"runtime");
        if(runtime.isDirectory())Collections.addAll(args,"-b",runtime.getAbsolutePath()+":/opt/luma");
        if(storageAvailable())Collections.addAll(args,"-b",sharedStorage().getAbsolutePath()+":/sdcard");
        Map<String,String> guestEnv=new LinkedHashMap<>();
        guestEnv.put("HOME","/root");
        guestEnv.put("USER","root");
        guestEnv.put("LOGNAME","root");
        guestEnv.put("SHELL","/bin/bash");
        guestEnv.put("TERM","xterm-256color");
        guestEnv.put("COLORTERM","truecolor");
        guestEnv.put("LANG","C.UTF-8");
        guestEnv.put("LC_ALL","C.UTF-8");
        guestEnv.put("TMPDIR","/tmp");
        guestEnv.put("PNPM_HOME","/root/.local/share/pnpm");
        String tz=timezoneId();
        if(!tz.isEmpty() && new File(root(c),"usr/share/zoneinfo/"+tz).isFile())guestEnv.put("TZ",tz);
        guestEnv.put("SSL_CERT_FILE","/etc/ssl/certs/ca-certificates.crt");
        guestEnv.put("NO_PROXY","127.0.0.1,localhost,::1");
        guestEnv.put("no_proxy","127.0.0.1,localhost,::1");
        guestEnv.put("PATH","/opt/luma/bin:/root/.local/share/pnpm/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        guestEnv.put("LUMA_ENV","debian-proot");
        guestEnv.put("LUMA_WORKSPACE","/workspace");
        guestEnv.put("LUMA_NATIVE_SOCKET",socketName(c));
        if(frames.isDirectory()){
            guestEnv.put("TERMINAL_BROWSER_FRAME_DIR",frames.getAbsolutePath());
            guestEnv.put("TERMINAL_BROWSER_FRAME_HOST_DIR",frames.getAbsolutePath());
        }
        if(extraEnv!=null)for(Map.Entry<String,String> entry:extraEnv.entrySet()){
            if(entry.getKey()!=null && entry.getValue()!=null)guestEnv.put(entry.getKey(),entry.getValue());
        }
        Collections.addAll(args,"/usr/bin/env","-i");
        for(Map.Entry<String,String> entry:guestEnv.entrySet())args.add(entry.getKey()+"="+entry.getValue());
        args.addAll(guestCommand);
        return args.toArray(new String[0]);
    }
    static String[] environment(Context c){return environment(c,syscallAcceleration);}
    private static String[] environment(Context c,boolean accelerated){
        File temp=new File(c.getCacheDir(),"proot");temp.mkdirs();
        String nativeDir=c.getApplicationInfo().nativeLibraryDir;
        ArrayList<String> env=new ArrayList<>(Arrays.asList("PATH=/system/bin","HOME="+c.getFilesDir(),"TMPDIR="+temp,
            "PROOT_TMP_DIR="+temp,"PROOT_LOADER="+nativeDir+"/libproot_loader.so"));
        if(!accelerated)env.add("PROOT_NO_SECCOMP=1");
        return env.toArray(new String[0]);
    }
    static void updateDns(Context c){
        File resolv=new File(root(c),"etc/resolv.conf");
        if(!resolv.getParentFile().isDirectory())return;
        List<String> servers=dnsServers(c);
        if(servers.isEmpty()){
            if(resolv.isFile() && resolv.length()>0)return;
            servers=Arrays.asList("223.5.5.5","1.1.1.1");
        }
        StringBuilder text=new StringBuilder("# Generated by Luma from the active Android network\n");
        for(String address:servers)text.append("nameserver ").append(address).append('\n');
        text.append("options timeout:2 attempts:2\n");
        try(FileOutputStream out=new FileOutputStream(resolv)){out.write(text.toString().getBytes(StandardCharsets.UTF_8));}
        catch(IOException e){android.util.Log.w("Luma","Unable to update guest DNS",e);}
    }
    static List<String> dnsServers(Context c){
        ArrayList<String> list=new ArrayList<>();
        ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);
        if(manager==null)return list;
        Network active=manager.getActiveNetwork();if(active==null)return list;
        LinkProperties properties=manager.getLinkProperties(active);
        if(properties==null)return list;
        for(InetAddress address:properties.getDnsServers())list.add(address.getHostAddress());
        return list;
    }
    static List<String> lanIpv4Addresses(Context c){
        ArrayList<String> primary=new ArrayList<>(),fallback=new ArrayList<>();
        ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);
        if(manager==null)return primary;
        for(Network net:manager.getAllNetworks()){
            LinkProperties lp=manager.getLinkProperties(net);
            if(lp==null)continue;
            String iface=lp.getInterfaceName()==null?"":lp.getInterfaceName().toLowerCase();
            boolean vpnOrCell=iface.startsWith("tun") || iface.startsWith("vgate") || iface.startsWith("ppp") || iface.startsWith("ccmni") || iface.startsWith("rmnet");
            for(LinkAddress la:lp.getLinkAddresses()){
                InetAddress addr=la.getAddress();
                if(!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress())continue;
                String ip=addr.getHostAddress();
                if(vpnOrCell){if(!fallback.contains(ip))fallback.add(ip);}
                else{if(!primary.contains(ip))primary.add(ip);}
            }
        }
        primary.addAll(fallback);
        return primary;
    }
}
