package io.github.modao7067.luma.root;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import com.termux.terminal.*;
import io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator;
import java.io.*;import java.lang.reflect.*;import java.nio.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;
public final class NativeRootServiceCheck {
    static int checks,stage;static long deadline;static boolean listed,disconnected,done,retried,failureInjected;static File data;static String oracle;
    static ShellService service;static RootBackend backend;static Handler main;static AgentWindowProbe.FakeKeeper keeper;
    static final String OLD="10000000000000000000000000000001",UNMARKED="10000000000000000000000000000002",NATIVE="10000000000000000000000000000003",SHORT="b1b2c3d4",VIEWER=AgentWindowRequest.windowId(SHORT);
    static final Map<String,Map<String,String>> stored=new HashMap<>();static final List<String> errors=new ArrayList<>();static TerminalSession oldViewer,selected;
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
    static Map<?,?> windows()throws Exception{return(Map<?,?>)get(service,"windows");}static TerminalSession terminal(String id)throws Exception{Object w=windows().get(id);return w==null?null:(TerminalSession)get(w,"terminal");}
    static SharedPreferences prefs(String name){Map<String,String>d=stored.computeIfAbsent(name,k->new HashMap<>());return(SharedPreferences)Proxy.newProxyInstance(NativeRootServiceCheck.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
        if(m.getName().equals("getString"))return d.getOrDefault((String)a[0],(String)a[1]);if(m.getName().equals("contains"))return d.containsKey(a[0]);
        if(m.getName().equals("edit"))return Proxy.newProxyInstance(NativeRootServiceCheck.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(ep,em,ea)->{if(em.getName().equals("putString")){d.put((String)ea[0],(String)ea[1]);return ep;}if(em.getName().equals("remove")){d.remove(ea[0]);return ep;}if(em.getName().equals("apply"))return null;if(em.getName().equals("commit"))return true;throw new AssertionError(em.getName());});throw new AssertionError(m.getName());});}
    static JSONObject entry(String id,int pid)throws Exception{return new JSONObject().put("id",id).put("pid",pid).put("name",id.equals(VIEWER)?"旧Java Claude":id).put("running",true).put("size",new JSONArray().put(80).put(24).put(12).put(24));}
    static JSONArray inventory()throws Exception{JSONArray a=new JSONArray();synchronized(keeper.windows){for(JSONObject w:keeper.windows.values())a.put(new JSONObject(w.toString()));}return a;}
    static void dispatch(JSONObject e)throws Exception{Method m=ShellService.class.getDeclaredMethod("handleEvent",JSONObject.class);m.setAccessible(true);m.invoke(service,e);}
    static void open(){service.onStartCommand(new Intent(AgentWindowRequest.ACTION_OPEN_CLAUDE).putExtra(AgentWindowRequest.EXTRA_SESSION,SHORT).putExtra(AgentWindowRequest.EXTRA_TITLE,"旧Java Claude"),0,1);}
    static int[] words(String name)throws Exception{ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(Path.of(oracle,name))).order(ByteOrder.LITTLE_ENDIAN);int[]v=new int[80];for(int i=0;i<80;i++)v[i]=b.getInt();return v;}
    static void fail(Throwable t){t.printStackTrace();done=true;if(backend!=null)backend.close();if(keeper!=null)keeper.close();System.exit(1);}
    public static void main(String[]args)throws Exception{
        Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());GhosttyTerminalEmulator.loadLibrary(args[0]);data=new File(args[1],"data");if(!data.mkdirs())throw new AssertionError("fresh fixture");oracle=args[2];
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");Files.writeString(new File(data,"root-backend.token").toPath(),token);
        keeper=new AgentWindowProbe.FakeKeeper("luma.root.direct."+android.system.Os.getuid()+"."+token.substring(0,12));keeper.provideDescriptors=true;
        keeper.windows.put(OLD,entry(OLD,31001));keeper.windows.put(UNMARKED,entry(UNMARKED,31002));keeper.windows.put(NATIVE,entry(NATIVE,31003));keeper.windows.put(VIEWER,entry(VIEWER,31004));
        prefs("root-engines").edit().putString(OLD,"java").putString(NATIVE,"ghostty").putString(VIEWER,"java").apply();prefs("root-windows").edit().putString("selected",OLD).apply();
        new TerminalModeStore(data,OLD).save(words("legacy-full.bin"));
        File modeDir=new File(data,"terminal-modes");if(!modeDir.isDirectory()&&!modeDir.mkdirs())throw new AssertionError("fresh mode fixture");
        byte[] residue=new byte[4096];residue[0]=1;
        Files.write(new File(modeDir,NATIVE+".modes").toPath(),residue);
        Context c=new ContextWrapper(null){public File getFilesDir(){return data;}public File getCacheDir(){return new File(data,"cache");}public String getPackageName(){return "io.github.modao7067.luma.root";}public SharedPreferences getSharedPreferences(String n,int f){return prefs(n);}public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();return i;}};
        service=new ShellService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,c);
        service.registerListener(service,new ShellService.Listener(){public void onScreenChanged(TerminalSession s){}public void onColorsChanged(TerminalSession s){}public void onCursorChanged(){}public void onSessionStateChanged(TerminalSession s){}public void onBackendError(String m){errors.add(m);}public void onWindowsChanged(){for(TerminalSession s:service.getSessions()){
            if(!failureInjected&&s.mSessionName.equals(OLD)){failureInjected=true;try{set(s,"mEmulatorFactory",(TerminalEmulatorFactory)(ss,cx,rx,cw,ch,h,cl)->{throw new IllegalStateException("injected native construction failure");});}catch(Exception ex){throw new RuntimeException(ex);}}
            s.updateSize(80,24,12,24);
        }}});
        backend=new RootBackend(c,new RootBackend.Listener(){public void onEvent(JSONObject e){try{dispatch(e);}catch(Throwable t){fail(t);}}public void onDisconnected(String m){if(!done)try{Method method=ShellService.class.getDeclaredMethod("backendDisconnected",String.class);method.setAccessible(true);method.invoke(service,m);disconnected=true;}catch(Throwable t){fail(t);}}});set(service,"backend",backend);backend.start();deadline=SystemClock.uptimeMillis()+20000;main.postDelayed(NativeRootServiceCheck::poll,20);Looper.loop();
    }
    static void poll(){try{
        if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("migration timeout stage="+stage+" errors="+errors);
        if(!keeper.controlReady){later();return;}
        switch(stage){
        case 0:if(!listed){listed=true;keeper.sendList(inventory());}stage=1;break;
        case 1:
            if(!retried){
                if(terminal(OLD)==null||terminal(OLD).getInitializationError()==null)break;
                check(terminal(OLD).getEmulator()==null&&"java".equals(stored.get("root-engines").get(OLD)),"failed native migration retains old identity and has no Java fallback");
                check(keeper.windows.containsKey(OLD)&&keeper.creates.isEmpty(),"failed migration keeps original backend ID and process");
                set(terminal(OLD),"mEmulatorFactory",TerminalEmulatorFactory.nativeFactory());service.reconnectSession();retried=true;break;
            }
            if(service.getSessions().size()!=4||terminal(OLD).getPid()!=31001||terminal(UNMARKED).getPid()!=31002||terminal(NATIVE).getPid()!=31003)break;
            for(String id:List.of(OLD,UNMARKED,NATIVE,VIEWER))check(terminal(id).getEmulator() instanceof GhosttyTerminalEmulator,"old/java/unmarked/native ID uses native: "+id);
            check(keeper.creates.isEmpty()&&keeper.windows.size()==4,"migration issued no create/close and retained four original IDs");
            check(terminal(OLD).getPid()==31001&&terminal(UNMARKED).getPid()==31002&&terminal(NATIVE).getPid()==31003,"lease migration retained original protocol PIDs (substitute FDs, no real PTY)");
            check(service.session==terminal(OLD),"selected original Java ID remains selected");
            for(String id:List.of(OLD,UNMARKED,NATIVE,VIEWER))check("ghostty".equals(stored.get("root-engines").get(id)),"successful native restore commits migrated identity: "+id);
            check(terminal(OLD).getEmulator().getKeyboardRestoreStatus()==TerminalEmulator.KEYBOARD_RESTORE_STALE
                && !terminal(OLD).getEmulator().isKeeperKeyboardTracking(),
                "legacy v1+unconfident annex exposes STALE, never FULL");
            check(terminal(UNMARKED).getEmulator().getKeyboardRestoreStatus()==TerminalEmulator.KEYBOARD_RESTORE_FRESH,
                "empty first lease exposes FRESH");
            check(terminal(NATIVE).getEmulator().getKeyboardRestoreStatus()==TerminalEmulator.KEYBOARD_RESTORE_UNAVAILABLE,
                "first lease with residual/non-empty but invalid v1 is UNAVAILABLE, not FRESH");
            TerminalEmulator e=terminal(OLD).getEmulator();check(e.isAlternateBufferActive()&&e.isMouseTrackingActive()&&e.isCursorKeysApplicationMode()&&!e.isCursorEnabled(),"actual old Java 80-word checkpoint restores alt/input/mouse/cursor modes");
            check(terminal(OLD).getCommandState()==TerminalSession.COMMAND_RUNNING,"legacy OSC133 command state survives migration");
            feed(e,"\0338");int[]v=e.exportModeState();check(v[74]==4&&v[75]==3,"old alternate-screen saved cursor restored");
            feed(e,"\033[?1049l");v=e.exportModeState();check(v[74]==3&&v[75]==2,"old primary saved cursor restored when leaving alternate screen; actual="+v[74]+","+v[75]);
            e.restoreModeState(words("legacy-csi.bin"));feed(e,"49h");check(e.isAlternateBufferActive(),"old Java CSI half-packet resumes natively");
            int[] oldUtf=words("legacy-utf8.bin");check(oldUtf[76]==0&&oldUtf[77]==0&&oldUtf[78]==0&&oldUtf[79]==0,"actual old Java oracle never persisted its UTF8 decoder prefix");
            e.restoreModeState(oldUtf);e.append(new byte[]{(byte)0x98,(byte)0x80},2);feed(e," resync 中文");check(e.getViewportText(0).contains("resync 中文"),"old missing UTF8 prefix is not fabricated and subsequent stream resynchronizes");
            int[] extended=oldUtf.clone();extended[76]=(0x82<<24)|(0xf0<<16)|(0x9f<<8);e.restoreModeState(extended);e.append(new byte[]{(byte)0x98,(byte)0x80},2);check(e.getSelectedText(0,0,1,0).equals("😀"),"80-word native/keeper UTF8 extension restores the complete split scalar");
            selected=service.session;oldViewer=terminal(VIEWER);open();stage=2;break;
        case 2:
            if(!((Map<?,?>)get(service,"checkingClaudeWindows")).isEmpty())break;
            check(terminal(VIEWER)==oldViewer&&keeper.creates.isEmpty(),"duplicate OPEN after migration is idempotent");keeper.disconnectControl();stage=3;break;
        case 3:if(!disconnected||!keeper.controlReady)break;check(!service.isBackendConnected()&&service.getSessions().size()==4,"actual control disconnect preserves inventory");keeper.sendList(inventory());stage=4;break;
        case 4:
            if(!service.isBackendConnected()||terminal(OLD).getPid()!=31001)break;
            check(service.session==selected&&service.session==terminal(OLD)&&!selected.isEmulatorDisposed(),"control reconnect retains selected original session and independent PTY lease");
            check(keeper.creates.isEmpty()&&service.getSessions().size()==4,"native reconnect preserves IDs with no process creation");
            for(TerminalSession s:service.getSessions())check(s.getEmulator() instanceof GhosttyTerminalEmulator,"reconnect has only native windows");
            oldViewer=terminal(VIEWER);keeper.windows.remove(VIEWER);open();stage=5;break;
        case 5:
            if(keeper.creates.size()<1||terminal(VIEWER)==oldViewer||terminal(VIEWER)==null)break;
            check(oldViewer.isEmulatorDisposed()&&terminal(VIEWER).getEmulator() instanceof GhosttyTerminalEmulator,"missing agent reopens natively and frees stale frontend");
            oldViewer=terminal(VIEWER);keeper.windows.get(VIEWER).put("running",false);open();stage=6;break;
        case 6:
            if(keeper.creates.size()<2||terminal(VIEWER)==oldViewer||terminal(VIEWER)==null)break;
            check(oldViewer.isEmulatorDisposed()&&terminal(VIEWER).getEmulator() instanceof GhosttyTerminalEmulator,"exited agent reopens with the sole native engine");
            selected=terminal(NATIVE);service.selectSession(selected);service.closeSession(terminal(UNMARKED),()->{});stage=7;break;
        case 7:
            if(terminal(UNMARKED)!=null)break;check(service.session==selected&&!selected.isEmulatorDisposed(),"closing migrated window leaves other selected window intact");
            for(TerminalSession s:new ArrayList<>(service.getSessions()))s.detachExternalPty();done=true;backend.close();keeper.close();System.out.println("NATIVE ROOT MIGRATION OK checks="+checks);System.exit(0);return;
        }later();
    }catch(Throwable t){fail(t);}}
    static void feed(TerminalEmulator e,String s){byte[]b=s.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}static void later(){main.postDelayed(NativeRootServiceCheck::poll,20);}
}
