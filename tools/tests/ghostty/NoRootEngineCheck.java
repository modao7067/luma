package io.github.modao7067.luma;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public final class NoRootEngineCheck {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static void initializeFactoryOnly(TerminalSession session)throws Exception{
        TerminalEmulatorFactory factory=(TerminalEmulatorFactory)get(session,"mEmulatorFactory");
        TerminalSessionClient client=(TerminalSessionClient)get(session,"mClient");
        TerminalEmulator emulator=java.util.Objects.requireNonNull(factory).create(session,40,8,12,24,1000,client);
        set(session,"mEmulator",emulator);
    }
    static void exited(TerminalSession session)throws Exception{set(session,"mShellPid",-1);}
    public static void main(String[]args){try{
        Looper.prepareMainLooper();GhosttyTerminalEmulator.loadLibrary(args[0]);
        File base=new File(args[1]);check(base.mkdirs(),"NoRoot fixture is fresh");
        Context context=new ContextWrapper(null){
            public File getFilesDir(){File f=new File(base,"data");f.mkdirs();return f;}
            public File getCacheDir(){File f=new File(base,"cache");f.mkdirs();return f;}
            public Object getSystemService(String name){return null;}
            public String getSystemServiceName(Class<?> type){return "fixture";}
            public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();i.nativeLibraryDir=new File(base,"lib").getPath();return i;}
        };
        ShellService service=new ShellService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);service.environmentReady=true;
        int[] firstEvents={0,0},secondEvents={0,0};Object firstOwner=new Object(),secondOwner=new Object();
        ShellService.Listener first=(ShellService.Listener)Proxy.newProxyInstance(NoRootEngineCheck.class.getClassLoader(),new Class[]{ShellService.Listener.class},(p,m,a)->{if(m.getName().equals("onScreenChanged"))firstEvents[0]++;if(m.getName().equals("onEnvironmentReady"))firstEvents[1]++;return null;});
        ShellService.Listener second=(ShellService.Listener)Proxy.newProxyInstance(NoRootEngineCheck.class.getClassLoader(),new Class[]{ShellService.Listener.class},(p,m,a)->{if(m.getName().equals("onScreenChanged"))secondEvents[0]++;if(m.getName().equals("onEnvironmentReady"))secondEvents[1]++;return null;});
        service.registerListener(firstOwner,first);service.registerListener(secondOwner,second);
        ShellService.Listener fanout=(ShellService.Listener)get(service,"listener");fanout.onScreenChanged(null);fanout.onEnvironmentReady();
        check(firstEvents[0]==1&&secondEvents[0]==1&&firstEvents[1]==1&&secondEvents[1]==1,"two NoRoot owners receive screen and environment-ready notifications");
        service.unregisterListener(firstOwner);service.unregisterListener(firstOwner);fanout.onScreenChanged(null);fanout.onEnvironmentReady();
        check(firstEvents[0]==1&&secondEvents[0]==2&&firstEvents[1]==1&&secondEvents[1]==2,"old NoRoot owner unbind cannot clear another owner's screen/ready subscriptions");
        service.unregisterListener(secondOwner);
        service.createSession();TerminalSession nativeSession=service.session;initializeFactoryOnly(nativeSession);
        service.createSession();TerminalSession javaSession=service.session;initializeFactoryOnly(javaSession);
        check(nativeSession.getEmulator() instanceof GhosttyTerminalEmulator&&javaSession.getEmulator() instanceof GhosttyTerminalEmulator,"all NoRoot window entry points use native cores");
        check(service.getSessions().size()==2,"native-only creation preserves separate window inventory");
        String[] nativeArgs=(String[])get(nativeSession,"mArgs"),javaArgs=(String[])get(javaSession,"mArgs");
        check(Arrays.equals(nativeArgs,javaArgs)&&nativeArgs[0].endsWith("/libproot_exec.so")&&Arrays.asList(nativeArgs).contains("--kill-on-exit"),"all windows retain the same LinuxEnvironment PRoot command and cleanup flags");
        check(Arrays.equals((String[])get(nativeSession,"mEnv"),(String[])get(javaSession,"mEnv")),"all windows retain identical PRoot loader/environment contract");
        service.selectSession(nativeSession);exited(javaSession);TerminalSession javaReplacement=service.reconnectSession(javaSession);initializeFactoryOnly(javaReplacement);
        check(javaReplacement!=javaSession&&javaReplacement.getEmulator() instanceof GhosttyTerminalEmulator,"target reconnect creates a native replacement");
        check(javaSession.isEmulatorDisposed()&&service.session==nativeSession&&!nativeSession.isEmulatorDisposed(),"replacing one window releases only its frontend and preserves the selected sibling");
        check(javaReplacement.mSessionName.equals(javaSession.mSessionName)&&service.getSessions().size()==2,"target reconnect preserves name and two-window inventory");
        exited(nativeSession);TerminalSession nativeReplacement=service.reconnectSession(nativeSession);initializeFactoryOnly(nativeReplacement);
        check(nativeReplacement.getEmulator() instanceof GhosttyTerminalEmulator&&nativeSession.isEmulatorDisposed(),"target reconnect preserves libghostty and frees prior native core");
        check(service.session==nativeReplacement&&service.getSessions().contains(javaReplacement),"selected native replacement and its sibling remain in inventory");
        exited(javaReplacement);service.closeSession(javaReplacement);
        check(javaReplacement.isEmulatorDisposed()&&!nativeReplacement.isEmulatorDisposed()&&service.session==nativeReplacement,"closing one window does not release or deselect the native sibling");
        exited(nativeReplacement);service.closeSession(nativeReplacement);
        check(nativeReplacement.isEmulatorDisposed()&&((GhosttyTerminalEmulator)nativeReplacement.getEmulator()).adapter()==null&&service.getSessions().isEmpty(),"closing native releases handle/timers and empties inventory");
        TerminalSessionClient client=(TerminalSessionClient)get(nativeReplacement,"mClient");
        TerminalSession legacy=LinuxEnvironment.session(context,client);initializeFactoryOnly(legacy);
        check(legacy.getEmulator() instanceof GhosttyTerminalEmulator&&get(legacy,"mEmulatorFactory")!=null,"all LinuxEnvironment/TerminalSession entry points require native");legacy.disposeEmulator();
        TerminalSession cleanup=new TerminalSession("unused","/",new String[0],new String[0],1000,client,pid->{throw new AssertionError("No real signal permitted");});
        initializeFactoryOnly(cleanup);
        int fd=ParcelFileDescriptor.open(new File(base,"closed-session-fd"),ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE).detachFd();
        set(cleanup,"mTerminalFileDescriptor",fd);set(cleanup,"mShellPid",12345);
        check(new File("/proc/self/fd/"+fd).exists(),"closed-session fixture owns a real disposable file descriptor");
        cleanup.disposeEmulator();Message exit=Message.obtain();exit.what=4;exit.obj=0;((Handler)get(cleanup,"mMainThreadHandler")).dispatchMessage(exit);
        check(!new File("/proc/self/fd/"+fd).exists()&&!cleanup.isRunning(),"disposed local session still consumes waitpid exit and closes actual FD through packaged libtermux");
        check(JniCwdCheck.run(base),"distinct cmd/cwd strings release correctly and isolated shell runs in requested directory");
        check(get(nativeReplacement,"mProcessTerminator")!=null,"factory sessions retain an explicit PRoot termination policy");
        System.out.println("NOROOT ENGINE OK checks="+checks+"; real service/command/factory, no PRoot process, keeper, Activity or installed data");System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
