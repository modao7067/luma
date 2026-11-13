package io.github.modao7067.luma.root;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import com.termux.terminal.TerminalSession;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class RootServiceProbe {
    private static RootBackend backend;
    private static ShellService service;
    private static Handler main;
    private static int stage,serverPid;
    private static boolean keeperStopped;
    private static boolean nameConfirmed;
    private static String windowId;
    private static long deadline;
    private static String mode, directory, tuiScript;
    private static boolean offlineExit,coldRepaint;
    private static int probeSizes;
    private static boolean finalSize;
    private static final Method dispatch;
    static {
        try {dispatch=ShellService.class.getDeclaredMethod("handleEvent",JSONObject.class);dispatch.setAccessible(true);}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static void fail(Throwable error){
        error.printStackTrace();
        try{if(keeperStopped)android.system.Os.kill(serverPid,android.system.OsConstants.SIGCONT);}catch(Exception ignored){}
        if(backend!=null)backend.close();System.exit(1);
    }
    private static SharedPreferences preferences(String namespace)throws Exception {
        java.util.Properties values=new java.util.Properties();File file=new File(directory,namespace+".properties");
        if(file.isFile())try(FileInputStream in=new FileInputStream(file)){values.load(in);}
        return (SharedPreferences)Proxy.newProxyInstance(RootServiceProbe.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return values.getProperty((String)a[0],(String)a[1]);
            if(m.getName().equals("contains"))return values.containsKey(a[0]);
            if(m.getName().equals("edit"))return Proxy.newProxyInstance(RootServiceProbe.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(ep,em,ea)->{
                if(em.getName().equals("putString")){values.setProperty((String)ea[0],(String)ea[1]);return ep;}
                if(em.getName().equals("remove")){values.remove(ea[0]);return ep;}
                if(em.getName().equals("apply")||em.getName().equals("commit")){try(FileOutputStream out=new FileOutputStream(file)){values.store(out,"isolated fixture");}return em.getName().equals("commit")?true:null;}
                throw new AssertionError("Unexpected preference editor: "+em.getName());
            });
            throw new AssertionError("Unexpected preference access: "+m.getName());
        });
    }
    private static void field(String name,Object value)throws Exception{
        Field f=ShellService.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);
    }
    private static void poll(){
        try {
            if(System.currentTimeMillis()>deadline)throw new AssertionError("Service integration timed out at "+mode+" stage "+stage+": "+service.backendError+" screen="+(service.session.getEmulator()==null?"null":service.session.getEmulator().getScreen().getTranscriptText()));
            TerminalSession terminal=service.session;
            if(terminal==null){main.postDelayed(RootServiceProbe::poll,20);return;}
            String screen=terminal.getEmulator()==null?"":terminal.getEmulator().getScreen().getTranscriptText();
            if(stage==0&&terminal.isRunning()&&terminal.getPid()>1){
                if(coldRepaint&&mode.equals("restore")){
                    if(!screen.contains("STATIC_REPAINT:24")){
                        if(System.currentTimeMillis()>deadline-8000)throw new AssertionError("Cold repaint missing before any input; "+screen);
                        main.postDelayed(RootServiceProbe::poll,20);return;
                    }
                    if(!finalSize){backend.send(new JSONObject().put("op","inspect").put("id",windowId));main.postDelayed(RootServiceProbe::poll,20);return;}
                    if(probeSizes!=1)throw new AssertionError("Expected one row probe: "+probeSizes);
                    System.out.println("COLD_REPAINT_OK static frame restored before input, size=80x24, probes="+probeSizes);
                }
                if(android.system.Os.getuid()==0){android.system.Os.kill(serverPid,android.system.OsConstants.SIGSTOP);keeperStopped=true;}
                if(mode.equals("restore")){
                    int oldPid=Integer.parseInt(new String(java.nio.file.Files.readAllBytes(new File(directory,"pid").toPath()),StandardCharsets.UTF_8));
                    if(terminal.getPid()!=oldPid)throw new AssertionError("Reopen replaced the shell");
                    if(!terminal.mSessionName.equals("service persistent"))throw new AssertionError("Window name not restored");
                    if(terminal.getEmulator().isAlternateBufferActive()==offlineExit)throw new AssertionError("Alternate screen not restored");
                    int flags=terminal.getEmulator().exportModeState()[3];
                    int expected=1|64|512|1024;
                    if(!offlineExit&&((flags&expected)!=expected||(flags&16)!=0||terminal.getCommandState()!=1))
                        throw new AssertionError("Input modes or running command state not restored: "+flags+":"+terminal.getCommandState());
                    if(offlineExit){
                        if(terminal.getCommandState()!=2)throw new AssertionError("Offline command completion not restored");
                        byte[] command="printf '\\nTUI_RESTORED:original\\n'\n".getBytes(StandardCharsets.UTF_8);terminal.write(command,0,command.length);
                    }else terminal.write(new byte[]{'R'},0,1);
                }else{
                    service.renameSession(terminal,"service persistent");
                    byte[] command=("HISTFILE=/dev/null; stty -echo; export KEEP_SERVICE=original; "+(coldRepaint ? "export LUMA_TEST_RESIZE_ONLY=1; " : "")+"python3 '"+tuiScript+"'\n").getBytes(StandardCharsets.UTF_8);
                    terminal.write(command,0,command.length);
                }
                stage=1;
            }else if(stage==1&&("\n"+screen+"\n").contains(mode.equals("restore")?"\nTUI_RESTORED:original\n":"\nTUI_READY\n")){
                if(keeperStopped){android.system.Os.kill(serverPid,android.system.OsConstants.SIGCONT);keeperStopped=false;}
                if(mode.equals("fresh")){
                    stage=3;
                    backend.send(new JSONObject().put("op","inspect").put("id",windowId));
                    main.postDelayed(RootServiceProbe::poll,20);return;
                }
                stage=2;
                service.closeSession(terminal,()->{
                    if(!service.getSessions().isEmpty())fail(new AssertionError("Closed window retained"));
                    backend.close();System.out.println("SERVICE_RESTORE_CLOSE_OK");System.exit(0);
                });
            }
            if(stage==3&&nameConfirmed){
                if(!terminal.getEmulator().isAlternateBufferActive()||terminal.getCommandState()!=1)
                    throw new AssertionError("Fixture did not enter fullscreen/running mode");
                if(offlineExit){terminal.write(new byte[]{'E'},0,1);stage=4;main.postDelayed(RootServiceProbe::poll,20);return;}
                finishFresh(terminal);
            }
            if(stage==4&&screen.contains("TUI_EXIT_PENDING"))finishFresh(terminal);
            main.postDelayed(RootServiceProbe::poll,20);
        }catch(Throwable e){fail(e);}
    }
    private static void finishFresh(TerminalSession terminal)throws Exception{
                java.nio.file.Files.write(new File(directory,"pid").toPath(),String.valueOf(terminal.getPid()).getBytes(StandardCharsets.UTF_8));
                System.out.println("SERVICE_FRESH_OK pid="+terminal.getPid());
                System.exit(0);
    }
    public static void main(String[] args)throws Exception {
        directory=args[0];mode=args[1];tuiScript=args[2];offlineExit=args.length>3&&args[3].equals("offline-exit");coldRepaint=args.length>3&&args[3].equals("cold-repaint");Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());
        io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator.loadLibrary(new File(directory,"libluma_vt_jni.so").getPath());
        java.util.Map<String,SharedPreferences> prefs=new java.util.HashMap<>();
        Context context=new ContextWrapper(null){
            @Override public File getFilesDir(){return new File(directory);}
            @Override public File getCacheDir(){return new File(directory,"cache");}
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=android.system.Os.getuid();return info;}
            @Override public SharedPreferences getSharedPreferences(String name,int flags){try{if(!prefs.containsKey(name))prefs.put(name,preferences(name));return prefs.get(name);}catch(Exception e){throw new IllegalStateException(e);}}
        };
        service=new ShellService();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);
        service.registerListener(service,new ShellService.Listener(){
            public void onScreenChanged(TerminalSession s){}
            public void onColorsChanged(TerminalSession s){}
            public void onCursorChanged(){}
            public void onSessionStateChanged(TerminalSession s){}
            public void onBackendError(String message){fail(new AssertionError(message));}
            public void onWindowsChanged(){if(service.session!=null)service.session.updateSize(80,24,12,24);}
        });
        backend=new RootBackend(context,new RootBackend.Listener(){
            public void onEvent(JSONObject event){try{
                String kind=event.optString("event");
                if(kind.equals("list")){
                    serverPid=event.getInt("serverPid");
                    if(event.getJSONArray("windows").length()>0)windowId=event.getJSONArray("windows").getJSONObject(0).getString("id");
                }
                if(kind.equals("created"))windowId=event.getString("id");
                if(kind.equals("info")){
                    nameConfirmed=event.getJSONObject("window").getString("name").equals("service persistent");
                    org.json.JSONArray size=event.getJSONObject("window").getJSONArray("size");
                    if(mode.equals("restore")&&size.getInt(1)==25)probeSizes++;
                    if(probeSizes>0&&size.getInt(1)==24)finalSize=true;
                }
                dispatch.invoke(service,event);
            }catch(Exception e){fail(e);}}
            public void onDisconnected(String message){fail(new AssertionError(message));}
        });
        field("backend",backend);deadline=System.currentTimeMillis()+12000;
        backend.start();main.postDelayed(RootServiceProbe::poll,20);Looper.loop();
    }
}
