package io.github.modao7067.luma.root;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public final class RootStartupProbe {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static void set(Object object,String name,Object value)throws Exception{
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static boolean bootstrapAllowed(RootBackend backend)throws Exception{
        Field field=RootBackend.class.getDeclaredField("bootstrapAllowed");field.setAccessible(true);return field.getBoolean(backend);
    }
    public static void main(String[] args)throws Exception{
        io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator.loadLibrary(args[2]);
        Looper.prepareMainLooper();
        File data=new File(args[1],"data");if(!data.mkdirs())throw new AssertionError("fixture must be new");
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(RootStartupProbe.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return a[1];
            if(m.getName().equals("edit"))return Proxy.newProxyInstance(RootStartupProbe.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(ep,em,ea)->{
                if(em.getName().equals("apply"))return null;if(em.getName().equals("commit"))return true;return ep;
            });
            throw new AssertionError("unexpected preference access: "+m.getName());
        });
        Context context=new ContextWrapper(null){
            public File getFilesDir(){return data;}
            public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=android.system.Os.getuid();return info;}
            public SharedPreferences getSharedPreferences(String name,int flags){return prefs;}
        };
        ShellService service=new ShellService();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);
        List<String> errors=new ArrayList<>();
        service.registerListener(service,new ShellService.Listener(){
            public void onScreenChanged(TerminalSession s){}public void onColorsChanged(TerminalSession s){}
            public void onCursorChanged(){}public void onSessionStateChanged(TerminalSession s){}public void onWindowsChanged(){}
            public void onBackendError(String message){errors.add(message);}
        });
        check(service.createSession()==null&&service.session==null&&service.getSessions().isEmpty(),"new-window request before backend initialization safely returns no session");
        check(errors.size()==1&&errors.get(0).contains("连接中"),"unavailable creation reports backend status");
        RootBackend backend=new RootBackend(context,new RootBackend.Listener(){
            public void onEvent(JSONObject event){throw new AssertionError("no client thread should be started");}
            public void onDisconnected(String message){throw new AssertionError(message);}
        });
        try{
            set(service,"backend",backend);set(backend,"bootstrapAllowed",false);service.backendError="Root 授权失败";
            service.retryBackend();
            check(bootstrapAllowed(backend)&&service.backendError==null&&service.getSessions().isEmpty(),"backend retry re-enables bootstrap even with zero windows");
            set(backend,"bootstrapAllowed",false);service.reconnectSession();
            check(bootstrapAllowed(backend),"session reconnect shares the explicit backend retry path");
            Method event=ShellService.class.getDeclaredMethod("handleEvent",JSONObject.class);event.setAccessible(true);
            event.invoke(service,new JSONObject().put("event","list").put("version",3).put("windows",new JSONArray()));
            check(service.isBackendConnected()&&service.session!=null&&service.getSessions().size()==1,"successful backend listing creates the first window");
            TerminalSession first=service.session,second=service.createSession();
            check(second!=null&&second!=first&&service.session==second&&service.getSessions().size()==2,"connected creation returns the newly created session");
            set(service,"backendConnected",false);service.backendError="后端断开";
            check(service.createSession()==null&&service.session==second&&service.getSessions().size()==2,"disconnected creation never reports an old session as newly created");
            check(errors.get(errors.size()-1).equals("后端断开"),"disconnected creation reports the stored error");
            set(backend,"bootstrapAllowed",false);service.retryBackend();
            check(bootstrapAllowed(backend)&&service.getSessions().size()==2,"backend retry preserves existing window records");
        }finally{backend.close();}
        System.out.println("ALL PASS "+checks+" Root startup/retry checks; no su, PTY, Activity, or installed app data used");
    }
}
