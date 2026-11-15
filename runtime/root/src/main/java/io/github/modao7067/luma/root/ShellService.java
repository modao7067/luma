package io.github.modao7067.luma.root;

import android.app.*;
import android.content.*;
import android.os.*;
import com.termux.terminal.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShellService extends Service {
    public TerminalSession session;
    private final ArrayList<TerminalSession> sessions=new ArrayList<>();
    private int nextWindowNumber=1;
    public List<TerminalSession> getSessions(){return Collections.unmodifiableList(sessions);}
    public void selectSession(TerminalSession selected){
        if(!sessions.contains(selected))throw new IllegalArgumentException("Unknown window");
        session=selected;
        RemoteWindow window=find(selected);
        if(window!=null){
            android.content.SharedPreferences.Editor edit=getSharedPreferences("root-windows",MODE_PRIVATE).edit().putString("selected",window.id);
            if(managedWorkspace()&&!dedicated(window))edit.putString("ordinarySelected",window.id);
            edit.apply();
        }
    }
    private boolean managedWorkspace;
    boolean managedWorkspace(){return managedWorkspace;}
    private ManagedTerminalRequest savedRequest(String id){
        if(!managedWorkspace())return null;
        try{return ManagedTerminalRequest.restore(getSharedPreferences("root-managed-windows",MODE_PRIVATE).getString(id,null),"root");}
        catch(java.io.IOException failure){throw new IllegalStateException(com.termux.terminal.LumaLang.tr("无法读取窗口归属，原记录已保留","Failed to read window ownership; existing record preserved"),failure);}
    }
    private boolean dedicated(RemoteWindow window){
        ManagedTerminalRequest request=savedRequest(window.id);
        return request!=null&&request.isSessionTerminal();
    }
    public List<TerminalSession> getOrdinarySessions(){
        ArrayList<TerminalSession> result=new ArrayList<>();
        for(RemoteWindow window:windows.values())if(!dedicated(window))result.add(window.terminal);
        return Collections.unmodifiableList(result);
    }
    boolean allowsSelection(ManagedTerminalRequest request,TerminalSession selected){
        if(!managedWorkspace())return true;
        RemoteWindow window=find(selected);if(window==null)return false;
        return request!=null&&request.isSessionTerminal()?request.windowId.equals(window.id):!dedicated(window);
    }
    private TerminalSession ordinarySelection(){
        String selected=getSharedPreferences("root-windows",MODE_PRIVATE).getString("ordinarySelected","");
        List<TerminalSession> ordinary=getOrdinarySessions();
        for(TerminalSession candidate:ordinary)if(find(candidate).id.equals(selected))return candidate;
        return ordinary.isEmpty()?null:ordinary.get(0);
    }
    TerminalSession openOrdinarySession(ManagedTerminalRequest request){
        if(!backendConnected)return null;
        TerminalSession selected=ordinarySelection();
        if(selected!=null){selectSession(selected);return selected;}
        return request==null?createSession():createManagedSession(request);
    }
    private void rememberRequest(ManagedTerminalRequest request){
        if(managedWorkspace()&&!getSharedPreferences("root-managed-windows",MODE_PRIVATE).edit().putString(request.windowId,request.save()).commit())
            throw new IllegalStateException(com.termux.terminal.LumaLang.tr("无法保存窗口归属，未启动终端","Failed to save window ownership; terminal not started"));
    }
    private RootBackend backend;
    private boolean backendConnected;
    public boolean isBackendConnected(){return backendConnected;}
    private final java.util.Map<String,RemoteWindow> windows=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Runnable> pendingCloses=new java.util.HashMap<>();
    private final java.util.Map<String,AgentWindowRequest> checkingClaudeWindows=new java.util.HashMap<>();
    private static final String MISSING_WINDOW="Window no longer exists (backend restarted or window closed)";
    static final int MAX_WINDOWS=16;
    private static final int MAX_PENDING_CLAUDE_REQUESTS=16;
    private final java.util.LinkedHashMap<String,AgentWindowRequest> pendingClaudeWindows=new java.util.LinkedHashMap<>();
    public String backendError;
    public void closeDedicatedWindow(String windowId){
        if(!managedWorkspace()||windowId==null)return;
        RemoteWindow window=windows.get(windowId);
        if(window!=null&&dedicated(window)){
            closeSession(window.terminal,()->{if(listener!=null)listener.onWindowsChanged();});
        }
    }
    public void closeSession(TerminalSession closing,Runnable completion){
        RemoteWindow window=find(closing);if(window==null||backend==null)return;
        if(pendingCloses.containsKey(window.id))return;
        if(!backendConnected){if(listener!=null)listener.onBackendError(com.termux.terminal.LumaLang.tr("连接尚未恢复，窗口已保留，请稍后关闭","Connection not restored yet; window kept, try closing later"));return;}
        pendingCloses.put(window.id,completion);
        window.repaint.cancel();window.ready=false;
        if(!window.created&&!window.createSent){completeClose(window);return;}
        send("close",window.id,null,null);
    }
    private void completeClose(RemoteWindow window){
        int index=sessions.indexOf(window.terminal);
        if(index<0)return;
        boolean wasDedicated=managedWorkspace()&&dedicated(window);
        windows.remove(window.id);sessions.remove(window.terminal);window.terminal.detachExternalPty();
        if(managedWorkspace())getSharedPreferences("root-managed-windows",MODE_PRIVATE).edit().remove(window.id).apply();
        getSharedPreferences("root-engines",MODE_PRIVATE).edit().remove(window.id).apply();
        if(window.modeStore!=null)window.modeStore.file.delete();
        if(session==window.terminal)session=managedWorkspace()?(wasDedicated?null:ordinarySelection()):(sessions.isEmpty()?null:sessions.get(Math.min(index,sessions.size()-1)));
        Runnable completion=pendingCloses.remove(window.id);if(completion!=null)completion.run();
    }
    public void renameSession(TerminalSession target,String name){
        RemoteWindow window=find(target);if(window==null)return;
        if(!backendConnected){if(listener!=null)listener.onBackendError(com.termux.terminal.LumaLang.tr("连接尚未恢复，请稍后重命名","Connection not restored yet; try renaming later"));return;}
        target.mSessionName=name;if(window.created)send("rename",window.id,"name",name);
    }
    private RemoteWindow find(TerminalSession target){
        for(RemoteWindow window:windows.values())if(window.terminal==target)return window;
        return null;
    }
    private void send(String op,String id,String key,Object value){
        if(backend==null)return;
        try{
            org.json.JSONObject request=new org.json.JSONObject().put("op",op).put("id",id);
            if(key!=null)request.put(key,value);backend.send(request);
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    public interface Listener {
        void onScreenChanged(TerminalSession session);
        void onColorsChanged(TerminalSession session);
        void onCursorChanged();
        void onSessionStateChanged(TerminalSession session);
        void onWindowsChanged();
        void onBackendError(String message);
    }
    private final java.util.IdentityHashMap<Object,Listener> listeners=new java.util.IdentityHashMap<>();
    public void registerListener(Object owner,Listener subscriber){
        listeners.put(java.util.Objects.requireNonNull(owner),java.util.Objects.requireNonNull(subscriber));
    }
    public void unregisterListener(Object owner){listeners.remove(owner);}
    private java.util.List<Listener> listenerSnapshot(){return new ArrayList<>(listeners.values());}
    private final Listener listener=new Listener(){
        public void onScreenChanged(TerminalSession session){for(Listener subscriber:listenerSnapshot())subscriber.onScreenChanged(session);}
        public void onColorsChanged(TerminalSession session){for(Listener subscriber:listenerSnapshot())subscriber.onColorsChanged(session);}
        public void onCursorChanged(){for(Listener subscriber:listenerSnapshot())subscriber.onCursorChanged();}
        public void onSessionStateChanged(TerminalSession session){for(Listener subscriber:listenerSnapshot())subscriber.onSessionStateChanged(session);}
        public void onBackendError(String message){for(Listener subscriber:listenerSnapshot())subscriber.onBackendError(message);}
        public void onWindowsChanged(){for(Listener subscriber:listenerSnapshot())subscriber.onWindowsChanged();}
    };
    public final class LocalBinder extends Binder { public ShellService get(){return ShellService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Client client=new Client();
    @Override public void onCreate(){
        super.onCreate();
        managedWorkspace=getResources().getBoolean(R.bool.root_gui_managed);
        TerminalTheme.initialize();
        if (!io.github.modao7067.luma.ghostty.GhosttyNative.configureFrameDirectory(getCacheDir()))
            android.util.Log.w("LumaFrames","Scoped file handoff unavailable; inline graphics remain enabled");
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null)nm.deleteNotificationChannel("ubuntu");
        startBackend();
    }
    private void startBackend(){
        try {
            backend=new RootBackend(this,new RootBackend.Listener(){
                public void onEvent(org.json.JSONObject event){handleEvent(event);}
                public void onDisconnected(String message){
                    backendDisconnected(message);
                }
            },managedWorkspace());
            backend.start();
        }catch(java.io.IOException e){
            backendError=e.getMessage();
            if(listener!=null)listener.onBackendError(backendError);
        }
    }
    private void backendDisconnected(String message){
        backendConnected=false;
        backendError=message;
        for(RemoteWindow window:windows.values())window.repaint.cancel();
        if(listener!=null)listener.onBackendError(message);
    }
    public void retryBackend(){
        if(backendConnected)return;
        backendError=null;
        if(backend==null)startBackend();else backend.retryStartup();
    }
    public TerminalSession createSession(){
        if(backend==null||!backendConnected){
            if(listener!=null)listener.onBackendError(backendError==null?com.termux.terminal.LumaLang.tr("后端连接中，请稍候","Connecting to backend, please wait"):backendError);
            return null;
        }
        if(windows.size()>=MAX_WINDOWS){if(listener!=null)listener.onBackendError(com.termux.terminal.LumaLang.tr("终端窗口已达上限，请先关闭不用的窗口","Maximum terminal windows reached; close unused windows first"));return null;}
        String id=java.util.UUID.randomUUID().toString().replace("-","");
        getSharedPreferences("root-engines",MODE_PRIVATE).edit().putString(id,TerminalEngines.GHOSTTY).apply();
        RemoteWindow window=addWindow(id,com.termux.terminal.LumaLang.tr("窗口 ","Window ")+nextWindowNumber++);
        window.created=false;
        session=window.terminal;
        return session;
    }
    TerminalSession openManagedSession(ManagedTerminalRequest request){
        if(backend==null||!backendConnected)return null;
        if(managedWorkspace()&&request.isOrdinaryTerminal())return openOrdinarySession(request);
        RemoteWindow existing=windows.get(request.windowId);
        if(existing!=null){
            if(managedWorkspace()){
                ManagedTerminalRequest old=savedRequest(existing.id);
                if(old!=null&&old.isSessionTerminal()!=request.isSessionTerminal())throw new IllegalStateException(com.termux.terminal.LumaLang.tr("窗口归属不匹配","Window ownership mismatch"));
                rememberRequest(request);existing.startupScript=managedScript(request);
            }
            if(pendingCloses.containsKey(existing.id)){
                pendingCloses.put(existing.id,()->{
                    if(managedWorkspace())createManagedSession(request);
                    if(listener!=null)listener.onWindowsChanged();
                });
                return null;
            }
            if(existing.alive){selectSession(existing.terminal);return existing.terminal;}
            closeSession(existing.terminal,()->{
                if(managedWorkspace())createManagedSession(request);
                if(listener!=null)listener.onWindowsChanged();
            });
            return null;
        }
        return createManagedSession(request);
    }
    private String managedScript(ManagedTerminalRequest request){
        return managedWorkspace()?RootShellIntegration.managedScript(getCacheDir(),getApplicationInfo().uid,request.script,request.isOrdinaryTerminal()):request.script;
    }
    private TerminalSession createManagedSession(ManagedTerminalRequest request){
        if(windows.size()>=MAX_WINDOWS)throw new IllegalStateException(com.termux.terminal.LumaLang.tr("终端窗口已达上限，请先关闭不用的窗口","Maximum terminal windows reached; close unused windows first"));
        rememberRequest(request);
        rememberEngine(request.windowId,TerminalEngines.GHOSTTY);
        RemoteWindow window=addWindow(request.windowId,request.title);
        window.startupScript=managedScript(request);window.created=false;
        selectSession(window.terminal);
        return window.terminal;
    }
    private void requestClaudeWindow(AgentWindowRequest request){
        if(pendingClaudeWindows.containsKey(request.windowId)||checkingClaudeWindows.containsKey(request.windowId))return;
        RemoteWindow existing=windows.get(request.windowId);
        if(existing!=null&&backendConnected){
            if(!existing.alive)reopenClaudeWindow(existing,request);
            else {checkingClaudeWindows.put(request.windowId,request);send("inspect",request.windowId,null,null);}
            return;
        }
        if(!backendConnected){
            if(pendingClaudeWindows.size()>=MAX_PENDING_CLAUDE_REQUESTS){
                android.util.Log.w("UbuntuGlass","Claude window request queue is full; existing requests retained");
                return;
            }
            pendingClaudeWindows.put(request.windowId,request);
            return;
        }
        openClaudeWindow(request);
    }
    private void reopenClaudeWindow(RemoteWindow old,AgentWindowRequest request){
        pendingClaudeWindows.put(request.windowId,request);
        closeSession(old.terminal,()->{
            AgentWindowRequest next=pendingClaudeWindows.remove(request.windowId);
            if(next!=null)openClaudeWindow(next);
            if(listener!=null)listener.onWindowsChanged();
        });
    }
    private void closeClaudeWindow(AgentWindowRequest request){
        pendingClaudeWindows.remove(request.windowId);checkingClaudeWindows.remove(request.windowId);
        RemoteWindow window=windows.get(request.windowId);
        if(window!=null)closeSession(window.terminal,()->{if(listener!=null)listener.onWindowsChanged();});
    }
    private void flushClaudeWindows(){
        if(!backendConnected||pendingClaudeWindows.isEmpty())return;
        for(AgentWindowRequest request:new ArrayList<>(pendingClaudeWindows.values())){
            pendingClaudeWindows.remove(request.windowId);
            openClaudeWindow(request);
        }
    }
    private void openClaudeWindow(AgentWindowRequest request){
        if(!backendConnected||windows.size()>=MAX_WINDOWS||windows.containsKey(request.windowId))return;
        if(!getSharedPreferences("root-engines",MODE_PRIVATE).contains(request.windowId))
            rememberEngine(request.windowId,TerminalEngines.GHOSTTY);
        RemoteWindow window=addWindow(request.windowId,request.title);
        window.startupScript=request.startupScript();
        window.created=false;
        window.terminal.updateSize(80,24,0,0);
        if(session==null){
            session=window.terminal;
            if(listener!=null)listener.onWindowsChanged();
        }else if(listener!=null)listener.onSessionStateChanged(window.terminal);
    }
    private void rememberEngine(String id,String engine){
        getSharedPreferences("root-engines",MODE_PRIVATE).edit().putString(id,TerminalEngines.GHOSTTY).apply();
    }
    private RemoteWindow addWindow(String id,String name){
        RemoteWindow window=new RemoteWindow(id);
        window.terminal=new TerminalSession(window,10000,client,TerminalEngines.factory());
        window.terminal.mSessionName=name;
        windows.put(id,window);sessions.add(window.terminal);return window;
    }
    public void reconnectSession(){
        if(!backendConnected){retryBackend();return;}
        RemoteWindow window=find(session);
        if(window!=null){
            if(window.alive){
                if(window.terminal.getEmulator()==null)window.terminal.updateSize(window.cols,window.rows,window.cw,window.ch);
                else if(!window.ready)window.attach();
            }
            else {
                String name=window.terminal.mSessionName;
                ManagedTerminalRequest request=savedRequest(window.id);
                closeSession(window.terminal,()->{
                    if(request!=null)createManagedSession(request);else {createSession();renameSession(session,name);}
                    if(listener!=null)listener.onWindowsChanged();
                });
            }
        }
    }
    private void handleEvent(org.json.JSONObject event){
        try{
            String kind=event.getString("event");
            if(kind.equals("list")){
                if(event.getInt("version")!=3)throw new org.json.JSONException(com.termux.terminal.LumaLang.tr("后端版本不匹配","Backend version mismatch"));
                backendConnected=true;
                String selected=getSharedPreferences("root-windows",MODE_PRIVATE).getString("selected","");
                org.json.JSONArray entries=event.getJSONArray("windows");
                java.util.Set<String> present=new java.util.HashSet<>();
                checkingClaudeWindows.clear();nextWindowNumber=1;
                for(int i=0;i<entries.length();i++){
                    org.json.JSONObject entry=entries.getJSONObject(i);
                    String id=entry.getString("id");present.add(id);
                    RemoteWindow window=windows.get(id);
                    if(window==null){
                        window=addWindow(id,entry.getString("name"));
                        ManagedTerminalRequest request=savedRequest(id);
                        if(request!=null)window.startupScript=managedScript(request);
                    }else window.terminal.mSessionName=entry.getString("name");
                    window.created=true;
                    window.alive=entry.optBoolean("running");
                    if(!window.alive){window.ready=false;window.terminal.externalProcessExited(entry.optInt("exit",-1));}
                    if(session==null||id.equals(selected))session=window.terminal;
                    if(window.terminal.mSessionName.matches("(窗口|Window) [0-9]+"))try{
                        int number=Integer.parseInt(window.terminal.mSessionName.substring(window.terminal.mSessionName.indexOf(' ')+1));
                        if(number<Integer.MAX_VALUE)nextWindowNumber=Math.max(nextWindowNumber,number+1);
                    }catch(NumberFormatException ignored){}
                }
                for(RemoteWindow old:new ArrayList<>(windows.values())){
                    if(!present.contains(old.id))completeClose(old);
                }
                for(String id:new ArrayList<>(pendingCloses.keySet()))if(windows.containsKey(id))send("close",id,null,null);
                for(RemoteWindow window:new ArrayList<>(windows.values())){
                    if(window.alive&&!pendingCloses.containsKey(window.id)){
                        if(window.ready)window.sendSize(window.cols,window.rows,window.cw,window.ch);
                        else window.attach();
                    }else if(!window.alive&&managedWorkspace()&&dedicated(window)&&!pendingCloses.containsKey(window.id)){
                        closeSession(window.terminal,null);
                    }
                }
                backendError=null;
                if(sessions.isEmpty()&&!managedWorkspace())createSession();
                if(listener!=null)listener.onWindowsChanged();
                flushClaudeWindows();
                return;
            }
            String id=event.optString("id","");RemoteWindow window=windows.get(id);
            if(kind.equals("error")){
                String message=event.optString("message",com.termux.terminal.LumaLang.tr("后端操作失败","Backend operation failed"));
                AgentWindowRequest check=checkingClaudeWindows.remove(id);
                if(window!=null&&MISSING_WINDOW.equals(message)&&(check!=null||pendingCloses.containsKey(id))){
                    completeClose(window);
                    if(check!=null)openClaudeWindow(check);
                    if(listener!=null)listener.onWindowsChanged();
                    return;
                }
                if(window!=null&&!window.created){
                    completeClose(window);
                    if(listener!=null)listener.onWindowsChanged();
                }
                if(pendingCloses.containsKey(id)){pendingCloses.remove(id);if(window!=null)window.ready=window.alive;}
                backendError=event.optString("message",com.termux.terminal.LumaLang.tr("后端操作失败","Backend operation failed"));
                if(listener!=null)listener.onBackendError(backendError);return;
            }
            if(window==null)return;
            if(kind.equals("created")){
                window.created=true;
                if(!window.terminal.mSessionName.equals(event.getJSONObject("window").optString("name")))
                    send("rename",id,"name",window.terminal.mSessionName);
                window.attach();return;
            }
            if(kind.equals("info")){
                AgentWindowRequest check=checkingClaudeWindows.remove(id);
                if(check!=null&&!event.getJSONObject("window").optBoolean("running")){
                    window.alive=false;reopenClaudeWindow(window,check);return;
                }
                org.json.JSONArray size=event.getJSONObject("window").optJSONArray("size");
                if(size!=null&&size.length()==4)window.repaint.sizeConfirmed(size.getInt(0),size.getInt(1),size.getInt(2),size.getInt(3));
            }else if(kind.equals("exit")){
                window.repaint.cancel();window.ready=false;window.alive=false;window.terminal.externalProcessExited(event.getInt("exit"));
                if(managedWorkspace()&&dedicated(window))closeSession(window.terminal,()->{if(listener!=null)listener.onWindowsChanged();});
            }else if(kind.equals("closed")){
                completeClose(window);
            }
        }catch(org.json.JSONException|IllegalArgumentException e){backendError=e.getMessage();if(listener!=null)listener.onBackendError(backendError);}
    }
    private final class RemoteWindow implements TerminalSession.PtyProvider {
        final String id;TerminalSession terminal;boolean ready,started,created=true,alive=true,createSent,opening,detached;
        String startupScript;
        int cols=80,rows=24,cw=0,ch=0;
        TerminalModeStore modeStore;
        final Handler repaintHandler=new Handler(Looper.getMainLooper());
        final ColdStartRepaint repaint=new ColdStartRepaint(new ColdStartRepaint.Host(){
            public long now(){return SystemClock.uptimeMillis();}
            public void later(Runnable task,long delay){repaintHandler.postDelayed(task,delay);}
            public void remove(Runnable task){repaintHandler.removeCallbacks(task);}
            public void resize(int columns,int lines,int width,int height){sendSize(columns,lines,width,height);}
            public void inspect(){send("inspect",id,null,null);}
            public void redraw(){send("redraw",id,null,null);}
        });
        int keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_UNAVAILABLE;
        boolean keeperKeyboardTracking,keyboardConfident;
        void checkpointModes(){
            if(modeStore==null||detached||terminal.getEmulator()==null)return;
            int[] values=terminal.getEmulator().exportModeState();values[70]=terminal.getCommandState();
            int[] keyboard=terminal.getEmulator().exportKeyboardState();
            if(keyboard!=null)modeStore.save(values,keyboard,keyboardConfident);
        }
        private static String keyboardStatusName(int status){
            switch(status){
                case TerminalEmulator.KEYBOARD_RESTORE_FRESH:return "FRESH";
                case TerminalEmulator.KEYBOARD_RESTORE_FULL:return "FULL";
                case TerminalEmulator.KEYBOARD_RESTORE_STALE:return "STALE";
                default:return "UNAVAILABLE";
            }
        }
        RemoteWindow(String id){this.id=id;}
        void attach(){
            if(!started||!created||opening||ready||detached)return;
            try{if(modeStore==null)modeStore=new TerminalModeStore(getFilesDir(),id);}
            catch(java.io.IOException e){backendError=e.getMessage();if(listener!=null)listener.onBackendError(backendError);return;}
            opening=true;
            backend.openPty(id,modeStore,new RootBackend.PtyListener(){
                public void onPty(DirectPty pty,org.json.JSONObject info){
                    opening=false;
                    if(detached||windows.get(id)!=RemoteWindow.this){if(pty!=null)pty.release();return;}
                    try{
                        org.json.JSONObject entry=info.getJSONObject("window");
                        alive=entry.optBoolean("running");
                        int keeperCaps=info.optInt("modeStateCaps",0);
                        keeperKeyboardTracking=(keeperCaps&2)!=0;
                        boolean reattached=info.optBoolean("reattached");
                        TerminalModeStore.Checkpoint saved=modeStore.load();
                        keyboardConfident=false;
                        String keyboardReason;
                        if(saved.modes!=null){
                            int partial=saved.keyboard!=null&&saved.keyboard.length>20?(saved.keyboard[20]&0xff):0;
                            terminal.getEmulator().restoreModeState(saved.modes,partial);
                            terminal.restoreCommandState(saved.modes[70]);
                            if(saved.keyboard==null){
                                keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_UNAVAILABLE;
                                keyboardReason=saved.orphan?"no-bound-annex(orphan)":"no-bound-annex";
                            }else if(terminal.getEmulator().restoreKeyboardState(saved.keyboard)==0){
                                if(reattached&&saved.confident&&keeperKeyboardTracking){
                                    keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_FULL;
                                    keyboardReason="bound+confident+keeper";
                                    keyboardConfident=true;
                                }else{
                                    keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_STALE;
                                    keyboardReason=!reattached?"first-lease-bound"
                                        :!saved.confident?"unconfident":"legacy-keeper";
                                }
                            }else{
                                keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_UNAVAILABLE;
                                keyboardReason="native-restore-failed";
                            }
                        }else if(!reattached&&saved.empty){
                            keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_FRESH;
                            keyboardConfident=true;
                            keyboardReason="empty-start";
                        }else{
                            keyboardRestoreStatus=TerminalEmulator.KEYBOARD_RESTORE_UNAVAILABLE;
                            keyboardConfident=false;
                            keyboardReason=reattached
                                ? (saved.orphan?"no-checkpoint(orphan)":"no-checkpoint")
                                : (saved.orphan?"residue-annex":"residue-no-v1");
                        }
                        terminal.getEmulator().setKeyboardRestoreState(keyboardRestoreStatus,keeperKeyboardTracking);
                        android.util.Log.i("UbuntuGlass","Keyboard restore="+keyboardStatusName(keyboardRestoreStatus)
                            +" reason="+keyboardReason+" keeperTracking="+keeperKeyboardTracking);
                        terminal.getEmulator().setModeChangeListener(RemoteWindow.this::checkpointModes);
                        checkpointModes();
                        rememberEngine(id,TerminalEngines.GHOSTTY);
                        if(pty!=null){
                            terminal.attachExternalPty(entry.getInt("pid"),pty.input,pty.output,pty::release);
                            ready=alive;
                            if(ready){
                                resize(cols,rows,cw,ch);
                                if(info.optBoolean("reattached"))repaint.start(cols,rows,cw,ch);
                            }
                        }
                        if(!alive)terminal.externalProcessExited(entry.optInt("exit",0));
                    }catch(org.json.JSONException|IllegalStateException|IllegalArgumentException e){
                        if(pty!=null)pty.release();
                        if(listener!=null)listener.onBackendError(e.getMessage());
                    }
                }
                public void onError(String message){
                    opening=false;backendError=message;
                    if(listener!=null&&!detached)listener.onBackendError(message);
                }
            });
        }
        public void start(TerminalSession terminal,int columns,int lines,int width,int height){
            started=true;cols=columns;rows=lines;cw=width;ch=height;
            if(!created&&!createSent){
                createSent=true;
                try{
                    backend.send(new org.json.JSONObject().put("op","create").put("id",id)
                        .put("name",terminal.mSessionName).put("script",startupScript!=null?startupScript:RootShellIntegration.startupScript(getCacheDir(),getApplicationInfo().uid))
                        .put("size",new org.json.JSONArray().put(cols).put(rows).put(cw).put(ch)));
                }catch(org.json.JSONException e){throw new IllegalStateException(e);}
            }else attach();
        }
        public void resize(int columns,int lines,int width,int height){
            if(columns==cols&&lines==rows&&width==cw&&height==ch&&repaint.isActive())return;
            repaint.cancel();cols=columns;rows=lines;cw=width;ch=height;
            sendSize(cols,rows,cw,ch);
        }
        void sendSize(int columns,int lines,int width,int height){
            if(backendConnected&&ready&&!detached)send("resize",id,"size",new org.json.JSONArray().put(columns).put(lines).put(width).put(height));
        }
        public void closeWindow(){repaint.cancel();send("close",id,null,null);}
        public void detach(){
            repaint.cancel();detached=true;ready=false;
            if(terminal.getEmulator()!=null)terminal.getEmulator().setModeChangeListener(null);
            if(modeStore!=null)modeStore.close();
        }
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&AgentWindowRequest.ACTION_CLOSE_CLAUDE.equals(intent.getAction())){
            AgentWindowRequest request=AgentWindowRequest.parseClose(intent.getStringExtra(AgentWindowRequest.EXTRA_SESSION));
            if(request!=null)closeClaudeWindow(request);
            else android.util.Log.w("UbuntuGlass","Ignored malformed Claude close request");
        }else if(intent!=null&&AgentWindowRequest.ACTION_OPEN_CLAUDE.equals(intent.getAction())){
            AgentWindowRequest request=AgentWindowRequest.parse(
                intent.getStringExtra(AgentWindowRequest.EXTRA_SESSION),intent.getStringExtra(AgentWindowRequest.EXTRA_TITLE));
            if(request==null)android.util.Log.w("UbuntuGlass","Ignored malformed Claude window request");
            else requestClaudeWindow(request);
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){
        listeners.clear();
        if(backend!=null)backend.close();
        for(TerminalSession window:new ArrayList<>(sessions))window.detachExternalPty();
        windows.clear();pendingCloses.clear();pendingClaudeWindows.clear();checkingClaudeWindows.clear();
        sessions.clear();
        session=null;
        super.onDestroy();
    }
    private final class Client extends LogCallbacks implements TerminalSessionClient {
        public void onEmulatorError(TerminalSession s,String message){backendError=message;if(listener!=null)listener.onBackendError(message);}
        public void onTextChanged(TerminalSession s){RemoteWindow window=find(s);if(window!=null)window.repaint.output();if(listener!=null && sessions.contains(s))listener.onScreenChanged(s);}
        public void onTitleChanged(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onSessionStateChanged(s);}
        public void onCommandStateChanged(TerminalSession s){RemoteWindow window=find(s);if(window!=null)window.checkpointModes();if(listener!=null && sessions.contains(s))listener.onSessionStateChanged(s);}
        public void onSessionFinished(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onSessionStateChanged(s);}
        public void onColorsChanged(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onColorsChanged(s);}
        public void onTerminalCursorStateChange(boolean b){if(listener!=null)listener.onCursorChanged();}
        public void setTerminalShellPid(TerminalSession s,int pid){android.util.Log.i("UbuntuGlass","PTY shell pid="+pid);}
        public Integer getTerminalCursorStyle(){return 1;}
        public void onBell(TerminalSession s){}
        public void onCopyTextToClipboard(TerminalSession s,String text){
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Terminal",text));
        }
        public void onPasteTextFromClipboard(TerminalSession s){
            ClipboardManager cm=getSystemService(ClipboardManager.class);
            if(cm.hasPrimaryClip()){
                CharSequence t=cm.getPrimaryClip().getItemAt(0).coerceToText(ShellService.this);
                if(t!=null && s.getEmulator()!=null)s.getEmulator().paste(t.toString());
            }
        }
    }
}
