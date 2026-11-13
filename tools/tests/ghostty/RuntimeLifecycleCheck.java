package io.github.modao7067.luma.root;

import android.content.*;
import android.os.Looper;
import com.termux.terminal.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public final class RuntimeLifecycleCheck {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static void event(ShellService s,JSONObject value)throws Exception{Method m=ShellService.class.getDeclaredMethod("handleEvent",JSONObject.class);m.setAccessible(true);m.invoke(s,value);}
    static class Subscriber implements ShellService.Listener {
        int screen,states,windows,errors;
        public void onScreenChanged(TerminalSession s){screen++;}
        public void onColorsChanged(TerminalSession s){}
        public void onCursorChanged(){}
        public void onSessionStateChanged(TerminalSession s){states++;}
        public void onWindowsChanged(){windows++;}
        public void onBackendError(String message){errors++;}
    }
    static class HeldInput extends InputStream {
        boolean closed;
        public synchronized int read()throws IOException{while(!closed)try{wait();}catch(InterruptedException e){throw new IOException(e);}return -1;}
        public synchronized void close(){closed=true;notifyAll();}
    }
    static class Bytes extends OutputStream {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        public synchronized void write(int b){out.write(b);}
        public synchronized void write(byte[] b,int o,int n){out.write(b,o,n);}
        synchronized String text()throws Exception{return out.toString("UTF-8");}
    }
    static JSONObject inventory(String id,boolean alive)throws Exception{return new JSONObject().put("event","list").put("version",3).put("windows",new JSONArray().put(new JSONObject().put("id",id).put("name","window").put("running",alive)));}
    static void waitFor(Bytes bytes,String expected)throws Exception{long end=System.currentTimeMillis()+2000;while(!bytes.text().equals(expected)&&System.currentTimeMillis()<end)Thread.sleep(5);check(bytes.text().equals(expected),"independent PTY consumes input: "+expected);}
    public static void main(String[] args){try{
        Looper.prepareMainLooper();io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator.loadLibrary(args[0]);
        File dir=new File(args[1]);ShellService service=new ShellService();
        Map<String,String> prefs=new HashMap<>();
        SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(RuntimeLifecycleCheck.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return prefs.getOrDefault((String)a[0],(String)a[1]);
            if(m.getName().equals("edit"))return Proxy.newProxyInstance(RuntimeLifecycleCheck.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(ep,em,ea)->{
                if(em.getName().equals("putString")){prefs.put((String)ea[0],(String)ea[1]);return ep;}
                if(em.getName().equals("remove")){prefs.remove(ea[0]);return ep;}
                if(em.getName().equals("apply"))return null;if(em.getName().equals("commit"))return true;throw new AssertionError(em.getName());});
            throw new AssertionError(m.getName());});
        Context context=new ContextWrapper(null){public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}public File getFilesDir(){return dir;}public android.content.pm.ApplicationInfo getApplicationInfo(){android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();info.uid=android.system.Os.getuid();return info;}};
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);
        Subscriber first=new Subscriber(),second=new Subscriber(),replacement=new Subscriber();Object a=new Object(),b=new Object();
        service.registerListener(a,first);service.registerListener(b,second);
        ShellService.Listener fanout=(ShellService.Listener)get(service,"listener");fanout.onScreenChanged(null);
        check(first.screen==1&&second.screen==1,"two Activity owners receive the same screen notification");
        service.registerListener(a,replacement);service.unregisterListener(b);service.unregisterListener(b);fanout.onScreenChanged(null);
        check(first.screen==1&&second.screen==1&&replacement.screen==1,"owner replacement and old-owner unbind cannot clear another subscriber");
        service.registerListener(b,second);
        RootBackend backend=new RootBackend(context,new RootBackend.Listener(){public void onEvent(JSONObject value){throw new AssertionError("no real backend");}public void onDisconnected(String message){throw new AssertionError("no real control socket");}});
        StringWriter requests=new StringWriter();set(backend,"output",new BufferedWriter(requests));set(service,"backend",backend);
        String id="12345678901234567890123456789012";event(service,inventory(id,true));
        TerminalSession session=service.session;Object window=((Map<?,?>)get(service,"windows")).get(id);
        HeldInput input=new HeldInput();Bytes bytes=new Bytes();session.attachExternalPty(4321,input,bytes,()->{});set(window,"ready",true);
        session.write("before");waitFor(bytes,"before");
        Method disconnected=ShellService.class.getDeclaredMethod("backendDisconnected",String.class);disconnected.setAccessible(true);disconnected.invoke(service,"synthetic control outage");
        check(!service.isBackendConnected()&&session.getPid()==4321&&session.isRunning(),"control outage never reports a live independent PTY as exited");
        session.write("-during");waitFor(bytes,"before-during");
        set(window,"created",false);set(window,"createSent",true);
        event(service,inventory(id,true));check(Boolean.TRUE.equals(get(window,"created")),"inventory confirms a create even when its ACK was lost");check(service.session==session&&!session.isEmulatorDisposed(),"list reconciliation preserves the same session/lease rather than replacing its streams");
        session.write("-after");waitFor(bytes,"before-during-after");
        check(replacement.errors==1&&second.errors==1,"control error reaches both current owners");
        final int[] completion={0};service.closeSession(session,()->completion[0]++);
        disconnected.invoke(service,"second synthetic outage");event(service,inventory(id,true));
        long deadline=System.currentTimeMillis()+2000;
        while(requests.toString().split("close",-1).length<3&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        Map<String,Runnable> pending=(Map<String,Runnable>)get(service,"pendingCloses");
        check(requests.toString().split("close",-1).length>=3&&pending.containsKey(id)&&completion[0]==0,"pending close is retried by same ID on present inventory without premature completion");
        event(service,new JSONObject().put("event","exit").put("id",id).put("exit",7));
        check(!session.isRunning()&&session.getExitStatus()==7,"real exit event settles the PTY terminal state");
        event(service,new JSONObject().put("event","list").put("version",3).put("windows",new JSONArray()));
        check(completion[0]==1&&!pending.containsKey(id)&&session.isEmulatorDisposed(),"lost close ACK settles once on reconnect inventory absence");
        event(service,new JSONObject().put("event","closed").put("id",id));check(completion[0]==1,"late close ACK cannot repeat completion");
        backend.close();System.out.println("RUNTIME LIFECYCLE OK checks="+checks);System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
