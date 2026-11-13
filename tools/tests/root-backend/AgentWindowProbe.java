package io.github.modao7067.luma.root;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import com.termux.terminal.TerminalSession;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

public final class AgentWindowProbe {
    private static final Method dispatch;
    private static final String VALID="A1B2C3D4";
    private static final String VALID_LOWER="a1b2c3d4";
    private static final String TITLE="Claude 测试窗口";
    static {
        try {dispatch=ShellService.class.getDeclaredMethod("handleEvent",JSONObject.class);dispatch.setAccessible(true);}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }

    private static ShellService service;
    private static RootBackend backend;
    private static Handler main;
    private static FakeKeeper keeper;
    private static int checks,stage,windowsChanged,stateChanges;
    private static boolean attachSelected=true,listSent;
    private static TerminalSession announced;
    private static final List<String> errors=new ArrayList<>();
    private static final List<String> preferenceWrites=new ArrayList<>();
    private static long deadline;
    private static boolean validationDone,queued,deduped,limitChecked,boundaryChecked;

    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static void fail(Throwable error){
        error.printStackTrace();
        if(backend!=null)backend.close();
        if(keeper!=null)keeper.close();
        System.exit(1);
    }
    private static Object field(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void setField(Object object,String name,Object value)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static Map<?,?> windows()throws Exception{return (Map<?,?>)field(service,"windows");}
    private static int pendingCount()throws Exception{return ((Map<?,?>)field(service,"pendingClaudeWindows")).size();}
    private static JSONObject create(String id)throws Exception{
        synchronized(keeper.creates){for(JSONObject request:keeper.creates)if(request.getString("id").equals(id))return request;}
        return null;
    }
    private static JSONObject createNamed(String name)throws Exception{
        synchronized(keeper.creates){for(JSONObject request:keeper.creates)if(request.getString("name").equals(name))return request;}
        return null;
    }
    private static Intent intent(String action,String session,String title){
        Intent value=new Intent();if(action!=null)value.setAction(action);if(session!=null)value.putExtra(AgentWindowRequest.EXTRA_SESSION,session);
        if(title!=null)value.putExtra(AgentWindowRequest.EXTRA_TITLE,title);return value;
    }
    private static String expectedScript(String session)throws Exception{
        return (String)AgentWindowRequest.class.getDeclaredMethod("startupScript").invoke(AgentWindowRequest.parse(session,"t"));
    }
    private static SharedPreferences preferences(){
        return (SharedPreferences)Proxy.newProxyInstance(AgentWindowProbe.class.getClassLoader(),
            new Class[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("getString"))return args[1];
                if(method.getName().equals("contains"))return false;
                if(method.getName().equals("edit")){
                    SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(AgentWindowProbe.class.getClassLoader(),
                        new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
                            if(m.getName().equals("putString")){if ("selected".equals(a[0])) preferenceWrites.add(a[0]+"="+a[1]);return p;}
                            if(m.getName().equals("apply")||m.getName().equals("commit"))return true;
                            if(m.getName().equals("putBoolean")||m.getName().equals("putInt")||m.getName().equals("putLong")
                                ||m.getName().equals("putFloat")||m.getName().equals("putStringSet")||m.getName().equals("remove")
                                ||m.getName().equals("clear"))return p;
                            throw new AssertionError("Unexpected preference editor call: "+m.getName());
                        });
                    return editor;
                }
                throw new AssertionError("Unexpected preference access: "+method.getName());
            });
    }

    public static void main(String[] args)throws Exception {
        boolean unselected=args[1].endsWith("case-unselected");
        io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator.loadLibrary(args[2]);
        Looper.prepareMainLooper();main=new Handler(Looper.getMainLooper());
        File data=new File(args[1],"data");if(!data.mkdirs())throw new AssertionError("fixture must be new");
        String token="0123456789abcdef".repeat(4);
        Files.write(new File(data,"root-backend.token").toPath(),token.getBytes(StandardCharsets.UTF_8));
        String address="luma.root.direct."+android.system.Os.getuid()+"."+token.substring(0,12);
        keeper=new FakeKeeper(address);
        SharedPreferences prefs=preferences();
        Context context=new ContextWrapper(null){
            public File getFilesDir(){return data;}public File getCacheDir(){return new File(data,"cache");}
            public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=android.system.Os.getuid();return info;}
            public SharedPreferences getSharedPreferences(String name,int flags){return prefs;}
        };
        service=new ShellService();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);
        service.registerListener(service,new ShellService.Listener(){
            public void onScreenChanged(TerminalSession s){}
            public void onColorsChanged(TerminalSession s){}
            public void onCursorChanged(){}
            public void onSessionStateChanged(TerminalSession s){stateChanges++;announced=s;}
            public void onWindowsChanged(){windowsChanged++;if(attachSelected&&service.session!=null)service.session.updateSize(80,24,12,24);}
            public void onBackendError(String message){errors.add(message);}
        });
        backend=new RootBackend(context,new RootBackend.Listener(){
            public void onEvent(JSONObject event){try{dispatch.invoke(service,event);}catch(Exception e){fail(e);}}
            public void onDisconnected(String message){fail(new AssertionError(message));}
        });
        setField(service,"backend",backend);
        deadline=System.currentTimeMillis()+20000;
        backend.start();
        if(unselected)main.postDelayed(AgentWindowProbe::unselected,20);
        else main.postDelayed(AgentWindowProbe::poll,20);
        Looper.loop();
    }

    private static boolean unselectedIssued;
    private static void unselected(){
        try{
            if(!keeper.controlReady){tick(AgentWindowProbe::unselected);return;}
            if(!unselectedIssued){
                setField(service,"backendConnected",true);
                check(service.session==null&&service.getSessions().isEmpty(),"unselected service starts with no window");
                check(AgentWindowRequest.parse(VALID,TITLE)!=null,"mixed-case session id is accepted");
                service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,TITLE),0,1);
                unselectedIssued=true;
            }
            if(keeper.creates.isEmpty()){tick(AgentWindowProbe::unselected);return;}
            String id=AgentWindowRequest.windowId(VALID_LOWER);
            JSONObject request=create(id);
            check(request!=null&&request.getString("script").equals(expectedScript(VALID_LOWER)),"first unselected request sends the fixed attach script");
            check(service.session!=null&&service.session==service.getSessions().get(0)&&service.getSessions().size()==1,"unselected request becomes the first window");
            check(windowsChanged==1&&announced==null,"first window reports one windows-changed and no background-only state change");
            check(service.session.getEmulator()!=null,"background PTY is started before selection");
            finish("unselected");
        }catch(Throwable e){fail(e);}
    }

    private static void poll(){
        try{
            if(System.currentTimeMillis()>deadline)throw new AssertionError("Agent window integration timed out at stage "+stage+": "+errors);
            if(!keeper.controlReady){tick(AgentWindowProbe::poll);return;}
            if(stage==0){
                check(errors.isEmpty(),"no backend error before the first list");
                String[][] invalid={
                    {null,VALID,TITLE},{AgentWindowRequest.ACTION_OPEN_CLAUDE,null,TITLE},
                    {AgentWindowRequest.ACTION_OPEN_CLAUDE,"","t"},{AgentWindowRequest.ACTION_OPEN_CLAUDE,"a1b2c3d","t"},
                    {AgentWindowRequest.ACTION_OPEN_CLAUDE,"a1b2c3d4e","t"},{AgentWindowRequest.ACTION_OPEN_CLAUDE,"a1b2c3dz","t"},
                    {AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,""},{AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,"x".repeat(129)},
                    {AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,"bad\nname"}};
                for(String[] value:invalid)service.onStartCommand(intent(value[0],value[1],value[2]),0,1);
                check(pendingCount()==0&&service.getSessions().isEmpty()&&keeper.creates.isEmpty(),"malformed Intents create no window or queued request");
                service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,TITLE),0,1);
                service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,TITLE),0,1);
                check(pendingCount()==1&&service.getSessions().isEmpty()&&keeper.creates.isEmpty(),"disconnected duplicate request is queued exactly once");
                for(int i=0;i<15;i++)service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,String.format("%08x",i),"queued "+i),0,1);
                service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,"ffffffff","overflow"),0,1);
                Map<?,?> pending=(Map<?,?>)field(service,"pendingClaudeWindows");
                check(pending.size()==16&&pending.containsKey(AgentWindowRequest.windowId(VALID_LOWER))
                    &&!pending.containsKey(AgentWindowRequest.windowId("ffffffff")),"full queue preserves accepted requests and rejects the overflow request");
                java.util.Iterator<?> ids=pending.keySet().iterator();
                while(ids.hasNext())if(!ids.next().equals(AgentWindowRequest.windowId(VALID_LOWER)))ids.remove();
                validationDone=true;stage=1;
            }
            if(stage==1){
                if(!listSent){listSent=true;keeper.sendList(new JSONArray());}
                if(keeper.creates.size()<2){tick(AgentWindowProbe::poll);return;}
                String agentId=AgentWindowRequest.windowId(VALID_LOWER);
                JSONObject normal=createNamed("窗口 1"),agent=create(agentId);
                check(normal!=null&&agent!=null,"queued Claude request lands after the backend list creates the first window");
                check(normal.getString("script").equals(RootShellIntegration.startupScript(service.getCacheDir(),service.getApplicationInfo().uid)),"ordinary window retains Root startup script and scoped graphics environment");
                check(agent.getString("script").equals(expectedScript(VALID_LOWER))
                    &&agent.getString("script").equals("/bin/bash --login -c 'cd /workspace && exec claude attach "+VALID_LOWER+"'"),
                    "background window sends the fixed login attach script with the normalized id");
                check(agent.getString("name").equals(TITLE)&&agent.getJSONArray("size").getInt(1)==24,"background window keeps its title and 80x24 start size");
                check(agentId.matches("[a-f0-9]{32}")&&windows().containsKey(agentId),"background window id is a stable 32-digit hex id");
                Object agentWindow=windows().get(agentId),normalWindow=windows().get(normal.getString("id"));
                check(field(agentWindow,"startupScript").equals(expectedScript(VALID_LOWER))&&field(normalWindow,"startupScript")==null,
                    "only the background window carries its own startup script");
                check(service.session!=null&&service.session==service.getSessions().get(0)&&service.session.mSessionName.equals("窗口 1"),
                    "existing selected window object is preserved");
                check(preferenceWrites.isEmpty(),"background window never rewrites the selected-window preference");
                check(windowsChanged==1,"background window does not re-select through windows-changed");
                check(stateChanges==1&&announced==service.getSessions().get(1),"background window reports a state change for the unselected terminal");
                check(service.getSessions().get(1).getEmulator()!=null,"background PTY starts without being selected");
                stage=2;
            }
            if(stage==2){
                service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,VALID,TITLE),0,1);
                main.postDelayed(AgentWindowProbe::dedupe,300);stage=3;return;
            }
            tick(AgentWindowProbe::poll);
        }catch(Throwable e){fail(e);}
    }
    private static void dedupe(){
        try{
            check(keeper.creates.size()==2&&service.getSessions().size()==2&&pendingCount()==0,"repeated Intent for an existing session creates no second window");
            attachSelected=false;
            JSONArray full=new JSONArray();
            for(int i=0;i<16;i++)full.put(new JSONObject().put("id",String.format("%032x",i+1)).put("name","窗口 "+(i+1)).put("running",true).put("size",new JSONArray().put(80).put(24).put(0).put(0)));
            keeper.sendList(full);
            main.postDelayed(AgentWindowProbe::limit,300);
        }catch(Throwable e){fail(e);}
    }
    private static void limit(){
        try{
            check(service.getSessions().size()==16,"sixteen backend windows are restored");
            service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,"deadbeef","Claude 上限"),0,1);
            main.postDelayed(AgentWindowProbe::boundary,300);
        }catch(Throwable e){fail(e);}
    }
    private static void boundary(){
        try{
            check(keeper.creates.size()==2&&service.getSessions().size()==16,"request at the window limit is dropped without a create");
            JSONArray room=new JSONArray();
            for(int i=0;i<15;i++)room.put(new JSONObject().put("id",String.format("%032x",i+100)).put("name","窗口 "+(i+1)).put("running",true).put("size",new JSONArray().put(80).put(24).put(0).put(0)));
            keeper.sendList(room);
            main.postDelayed(AgentWindowProbe::boundaryRequest,300);
        }catch(Throwable e){fail(e);}
    }
    private static void boundaryRequest(){
        try{
            service.onStartCommand(intent(AgentWindowRequest.ACTION_OPEN_CLAUDE,"deadbeef","Claude 上限"),0,1);
            if(keeper.creates.size()<3){if(System.currentTimeMillis()>deadline)throw new AssertionError("boundary create did not arrive");main.postDelayed(AgentWindowProbe::boundaryRequest,20);return;}
            check(service.getSessions().size()==16&&create(AgentWindowRequest.windowId("deadbeef"))!=null,"request fills the last free window slot");
            finish("selected");
        }catch(Throwable e){fail(e);}
    }
    private static void finish(String scope){
        System.out.println("ALL PASS "+checks+" agent-window checks ("+scope+"); no su, real keeper, PTY, Activity, or installed app data used");
        if(backend!=null)backend.close();
        if(keeper!=null)keeper.close();
        System.exit(0);
    }
    private static void tick(Runnable next){main.postDelayed(next,20);}

    static final class FakeKeeper implements Closeable {
        final LocalServerSocket server;
        final List<JSONObject> creates=Collections.synchronizedList(new ArrayList<>());
        final Map<String,JSONObject> windows=Collections.synchronizedMap(new LinkedHashMap<>());
        final List<Thread> threads=Collections.synchronizedList(new ArrayList<>());
        volatile boolean controlReady,closed;
        volatile String inspectError;
        boolean provideDescriptors;
        final List<String> leases=Collections.synchronizedList(new ArrayList<>());
        private volatile OutputStream control;
        private volatile LocalSocket controlSocket;
        synchronized void disconnectControl()throws IOException {
            controlReady=false;controlSocket.shutdownOutput();controlSocket.shutdownInput();controlSocket.close();control=null;
        }

        FakeKeeper(String address)throws IOException{
            server=new LocalServerSocket(address);
            Thread accept=new Thread(()->{
                while(!closed){
                    try{
                        LocalSocket socket=server.accept();
                        Thread worker=new Thread(()->serve(socket),"FakeKeeperClient");worker.setDaemon(true);worker.start();
                    }catch(IOException e){return;}
                }
            },"FakeKeeperAccept");accept.setDaemon(true);accept.start();
        }

        synchronized void sendList(JSONArray entries)throws Exception{
            JSONArray array=new JSONArray();
            for(int i=0;i<entries.length();i++)array.put(entries.getJSONObject(i));
            emit(control,new JSONObject().put("event","list").put("version",3).put("windows",array)
                .put("serverPid",1).put("modeMetadataFd",false));
        }

        private static void emit(OutputStream out,JSONObject value)throws IOException{
            if(out==null)return;
            synchronized(out){out.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
        }

        private void serve(LocalSocket socket){
            boolean controlRole=false;
            try(BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8))){
                OutputStream output=socket.getOutputStream();
                String line;
                while((line=input.readLine())!=null){
                    JSONObject request=new JSONObject(line);
                    String op=request.optString("op");
                    if(op.equals("hello")){
                        if(request.optString("role","").equals("lease")){
                            emit(output,new JSONObject().put("event","list").put("version",3).put("windows",snapshot())
                                .put("serverPid",1).put("modeMetadataFd",false));
                        }else{
                            synchronized(this){control=output;controlSocket=socket;controlReady=true;}
                        }
                        continue;
                    }
                    String id=request.optString("id");
                    if(op.equals("create")){
                        synchronized(this){
                            creates.add(request);
                            windows.put(request.getString("id"),new JSONObject().put("id",request.getString("id"))
                                .put("pid",32000+creates.size()).put("name",request.getString("name")).put("running",true).put("size",request.getJSONArray("size")));
                        }
                        emit(output,new JSONObject().put("event","created").put("id",id)
                            .put("window",windows.get(id)).put("request",0));
                    }else if(op.equals("lease")){
                        JSONObject window=windows.get(id);
                        if(window==null)window=new JSONObject().put("id",id).put("name","claude").put("running",true)
                            .put("size",new JSONArray().put(80).put(24).put(0).put(0));
                        leases.add(id);
                        java.io.FileDescriptor descriptor=null;
                        if(provideDescriptors&&window.optBoolean("running")){
                            descriptor=android.system.Os.open("/dev/null",android.system.OsConstants.O_RDWR,0);
                            socket.setFileDescriptorsForSend(new java.io.FileDescriptor[]{descriptor});
                        }
                        emit(output,new JSONObject().put("event","leased").put("id",id).put("hasFd",descriptor!=null)
                            .put("reattached",false).put("window",window));
                        socket.setFileDescriptorsForSend(null);if(descriptor!=null)android.system.Os.close(descriptor);
                    }else if(op.equals("close")){
                        windows.remove(id);
                        emit(output,new JSONObject().put("event","closed").put("id",id));
                    }else if(op.equals("inspect")){
                        JSONObject window=windows.get(id);
                        if(inspectError!=null)emit(output,new JSONObject().put("event","error").put("id",id).put("message",inspectError));
                        else if(window!=null)emit(output,new JSONObject().put("event","info").put("id",id).put("window",window));
                        else emit(output,new JSONObject().put("event","error").put("id",id).put("message","Window no longer exists (backend restarted or window closed)"));
                    }else if(op.equals("rename")){
                        JSONObject window=windows.get(id);
                        if(window!=null){window.put("name",request.optString("name"));}
                    }
                }
            }catch(Exception ignored){
            }finally{
                try{socket.close();}catch(IOException ignored){}
            }
        }
        private JSONArray snapshot(){
            JSONArray array=new JSONArray();
            synchronized(this){for(JSONObject window:windows.values())array.put(window);}
            return array;
        }
        public void close(){
            closed=true;
            try{server.close();}catch(IOException ignored){}
        }
    }
}
