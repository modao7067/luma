package io.github.modao7067.luma;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.*;
import com.termux.terminal.*;
import java.util.ArrayList;
import java.util.List;
import android.os.PowerManager;

public final class ShellService extends Service {
    private static final int NOTIFICATION_ID=1;
    private static final String ACTION_ENABLE_WAKE_LOCK="io.github.modao7067.luma.ENABLE_WAKE_LOCK";
    private static final String ACTION_DISABLE_WAKE_LOCK="io.github.modao7067.luma.DISABLE_WAKE_LOCK";
    private static final String WAKE_LOCK_PREFS="background";
    private static final String WAKE_LOCK_ENABLED="wakeLockEnabled";
    private static String defaultStatusText(){return LumaLang.tr("后台环境运行中","Background environment running");}
    private static volatile ShellService activeInstance;
    private static volatile String sessionStatusText;
    public volatile String preparation=LumaLang.tr("准备 Debian 环境…","Preparing Debian environment…");
    public volatile String preparationError;
    public volatile boolean environmentReady;
    private Thread installer;
    private volatile boolean destroyed;
    private boolean cleanupStarted;
    private final java.util.concurrent.CountDownLatch cleanupDone=new java.util.concurrent.CountDownLatch(1);
    private static final int MAX_CLIPBOARD_CHARS=262_144;
    private PowerManager.WakeLock wakeLock;
    private boolean wakeLockEnabled;
    private NativeBridge nativeBridge;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean checkRequested;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable notifyStatusRunnable=()->{
        if(!destroyed && preparationError==null){
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null)nm.notify(NOTIFICATION_ID,buildNotification());
        }
    };
    public TerminalSession session;
    private final TerminalWorkspace<TerminalSession> windows=new TerminalWorkspace<>();
    private final List<TerminalSession> sessions=windows.all();
    private int nextWindowNumber=1;
    public List<TerminalSession> getSessions(){return sessions;}
    public List<TerminalSession> getOrdinarySessions(){return windows.ordinary();}
    boolean stopAfterLastWindow(ManagedTerminalRequest launch){
        return sessions.isEmpty()&&(launch==null||!launch.isOrdinaryTerminal());
    }
    boolean allowsSelection(ManagedTerminalRequest request,TerminalSession selected){
        return windows.allows(request!=null&&request.isSessionTerminal()?request.windowId:null,selected);
    }
    public boolean isClosing(){return destroyed;}
    public TaskRegistry tasks(){return nativeBridge==null?null:nativeBridge.taskRegistry;}
    public void selectSession(TerminalSession selected){
        windows.select(selected);
        session=selected;
    }
    public void closeDedicatedWindow(String windowId){
        if(windowId==null)return;
        TerminalSession closing=managedSessions.get(windowId);
        if(closing!=null&&allowsSelection(managedRequests.get(windowId),closing)){
            closeSession(closing);
        }
    }
    public void closeSession(TerminalSession closing){
        int index=sessions.indexOf(closing);
        if(index<0)return;
        windows.remove(closing);
        managedSessions.entrySet().removeIf(entry->{if(entry.getValue()!=closing)return false;managedRequests.remove(entry.getKey());return true;});
        session=windows.current();
        closing.finishIfRunning();
        closing.disposeEmulator();
    }
    public interface Listener {
        default void onEnvironmentReady(){}
        default void onTerminalError(String message){}
        void onScreenChanged(TerminalSession session);
        void onColorsChanged(TerminalSession session);
        void onCursorChanged();
        void onSessionStateChanged(TerminalSession session);
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
        public void onTerminalError(String message){for(Listener subscriber:listenerSnapshot())subscriber.onTerminalError(message);}
        public void onEnvironmentReady(){for(Listener subscriber:listenerSnapshot())subscriber.onEnvironmentReady();}
    };
    public final class LocalBinder extends Binder { public ShellService get(){return ShellService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Client client=new Client();
    @Override public void onCreate(){
        super.onCreate();
        TerminalTheme.initialize();
        if (!io.github.modao7067.luma.ghostty.GhosttyNative.configureFrameDirectory(getCacheDir()))
            android.util.Log.w("LumaFrames","Scoped file handoff unavailable; inline graphics remain enabled");
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("luma",LumaLang.tr("Luma 运行中","Luma running"),NotificationManager.IMPORTANCE_LOW));
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Luma:Terminal");
        wakeLock.setReferenceCounted(false);
        wakeLockEnabled=getSharedPreferences(WAKE_LOCK_PREFS,MODE_PRIVATE).getBoolean(WAKE_LOCK_ENABLED,false);
        if(wakeLockEnabled)wakeLock.acquire();
        activeInstance=this;
        startForeground(NOTIFICATION_ID,buildNotification());
        startInstaller();
    }
    private synchronized void startInstaller(){
        if(destroyed || environmentReady || (installer!=null && installer.isAlive()))return;
        preparationError=null;
        preparation=LumaLang.tr("准备 Debian 环境…","Preparing Debian environment…");
        installer=new Thread(()->{
            try {
                EnvironmentInstaller.install(this,message->preparation=message);
                if(!Thread.currentThread().isInterrupted())initializeRuntime();
            } catch(Exception e){
                android.util.Log.e("Luma","Environment initialization failed",e);
                preparationError=e.getMessage()==null?e.toString():e.getMessage();
                main.post(()->{if(!destroyed){listener.onTerminalError(LumaLang.tr("环境准备失败：","Environment setup failed: ")+preparationError);releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
            }
        },"LumaEnvironmentInstaller");
        installer.start();
    }
    private Notification buildNotification(){
        return buildKeepAliveNotification(this);
    }
    public static Notification buildKeepAliveNotification(Context context){
        Intent open=context.getPackageManager()!=null?context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()):null;
        if(open==null)open=new Intent().setClassName(context.getPackageName(),"io.github.modao7067.luma.gui.MainActivity");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String status=sessionStatusText;
        if(status==null||status.trim().isEmpty())status=defaultStatusText();
        return new Notification.Builder(context,"luma").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Luma").setContentText(status)
            .setContentIntent(pi).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .build();
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private void setWakeLockEnabled(boolean enabled){
        if(destroyed || preparationError!=null)return;
        if(enabled){if(!wakeLock.isHeld())wakeLock.acquire();}
        else releaseWakeLock();
        wakeLockEnabled=enabled;
        getSharedPreferences(WAKE_LOCK_PREFS,MODE_PRIVATE).edit().putBoolean(WAKE_LOCK_ENABLED,enabled).apply();
    }
    public static boolean isWakeLockEnabled(Context context){
        ShellService active=activeInstance;
        if(active!=null && !active.destroyed)return active.wakeLockEnabled;
        return context.getApplicationContext().getSharedPreferences(WAKE_LOCK_PREFS,MODE_PRIVATE).getBoolean(WAKE_LOCK_ENABLED,false);
    }
    public static void setWakeLockPreference(Context context,boolean enabled){
        context.getApplicationContext().getSharedPreferences(WAKE_LOCK_PREFS,MODE_PRIVATE).edit().putBoolean(WAKE_LOCK_ENABLED,enabled).commit();
        ShellService active=activeInstance;
        if(active!=null && !active.destroyed)active.main.post(()->active.setWakeLockEnabled(enabled));
    }
    public static void updateSessionStatus(String statusText){
        String normalized=(statusText==null||statusText.trim().isEmpty())?null:statusText.trim();
        if(java.util.Objects.equals(normalized,sessionStatusText))return;
        sessionStatusText=normalized;
        ShellService active=activeInstance;
        if(active!=null && !active.destroyed && active.preparationError==null){
            active.main.removeCallbacks(active.notifyStatusRunnable);
            active.main.postDelayed(active.notifyStatusRunnable,250L);
        }
    }
    private void initializeRuntime()throws Exception {
        RuntimeAssets.ensure(this);
        LinuxEnvironment.detectAcceleration(this);
        synchronized(this){
            if(destroyed || Thread.currentThread().isInterrupted())return;
            nativeBridge=new NativeBridge(this);environmentReady=true;
            registerNetworkCallback();
        }
        main.post(()->{if(!destroyed){listener.onEnvironmentReady();if(checkRequested)nativeBridge.checkToFile();}});
    }
    public void createSession(){
        if(!environmentReady || destroyed)return;
        session=newSession(LumaLang.tr("窗口 ","Window ")+nextWindowNumber++);
        windows.addOrdinary(session);
    }
    private final java.util.Map<String,TerminalSession> managedSessions=new java.util.HashMap<>();
    private final java.util.Map<String,ManagedTerminalRequest> managedRequests=new java.util.HashMap<>();
    TerminalSession openManagedSession(ManagedTerminalRequest request){
        if(!environmentReady||destroyed)return null;
        if(request.isOrdinaryTerminal())return openOrdinarySession(request);
        TerminalSession existing=managedSessions.get(request.windowId);
        if(existing!=null&&sessions.contains(existing)){
            managedRequests.put(request.windowId,request);
            TerminalSession selected=existing.isRunning()?existing:reconnectSession(existing);
            selectSession(selected);return selected;
        }
        TerminalSession created=newManagedSession(request);
        if(request.isSessionTerminal())windows.addDedicated(request.windowId,created);else windows.addOrdinary(created);
        managedSessions.put(request.windowId,created);managedRequests.put(request.windowId,request);session=created;
        return created;
    }
    TerminalSession openOrdinarySession(ManagedTerminalRequest request){
        if(!environmentReady||destroyed)return null;
        TerminalSession selected=windows.ordinarySelection();
        if(selected!=null){selectSession(selected);return selected;}
        if(request==null){createSession();return session;}
        TerminalSession created=newManagedSession(request);
        windows.addOrdinary(created);
        managedSessions.put(request.windowId,created);managedRequests.put(request.windowId,request);
        session=created;return created;
    }
    private TerminalSession newManagedSession(ManagedTerminalRequest request){
        String[] args=LinuxEnvironment.command(this,java.util.Arrays.asList("/bin/bash","--login","-c",request.script),request.cwd);
        TerminalSession created=new TerminalSession(args[0],getFilesDir().getAbsolutePath(),args,LinuxEnvironment.environment(this),10000,client,
            pid->{if(pid>0)android.system.Os.kill(pid,android.system.OsConstants.SIGQUIT);},TerminalEngines.factory());
        created.mSessionName=request.title;
        return created;
    }
    public void reconnectSession(){
        reconnectSession(session);
    }
    public TerminalSession reconnectSession(TerminalSession target){
        if(destroyed || target==null || target.isRunning())return target;
        int index=sessions.indexOf(target);
        if(index<0)return target;
        String managedId=null;
        for(java.util.Map.Entry<String,TerminalSession> entry:managedSessions.entrySet())if(entry.getValue()==target){managedId=entry.getKey();break;}
        ManagedTerminalRequest request=managedId==null?null:managedRequests.get(managedId);
        TerminalSession replacement=request==null?newSession(target.mSessionName):newManagedSession(request);
        if(managedId!=null)managedSessions.put(managedId,replacement);
        target.disposeEmulator();
        windows.replace(target,replacement);
        session=windows.current();
        return replacement;
    }
    private TerminalSession newSession(String name){
        TerminalSession created=LinuxEnvironment.session(this,client,TerminalEngines.factory());
        created.mSessionName=name;
        return created;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(!destroyed){
            startForeground(NOTIFICATION_ID,buildNotification());
            if(!environmentReady)startInstaller();
        }
        if(intent!=null && (ACTION_ENABLE_WAKE_LOCK.equals(intent.getAction()) || ACTION_DISABLE_WAKE_LOCK.equals(intent.getAction()))){
            setWakeLockEnabled(ACTION_ENABLE_WAKE_LOCK.equals(intent.getAction()));
        }
        if(intent!=null && "io.github.modao7067.luma.COMPUTE_CHECK".equals(intent.getAction())){
            if(environmentReady && nativeBridge!=null)nativeBridge.checkToFile();else checkRequested=true;
        }
        return START_NOT_STICKY;
    }
    private void registerNetworkCallback(){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            if(cm==null || networkCallback!=null)return;
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){if(environmentReady)LinuxEnvironment.updateDns(ShellService.this);}
                @Override public void onLinkPropertiesChanged(Network network,LinkProperties lp){if(environmentReady)LinuxEnvironment.updateDns(ShellService.this);}
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        }catch(Exception ignored){}
    }
    private void unregisterNetworkCallback(){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            if(cm!=null && networkCallback!=null)cm.unregisterNetworkCallback(networkCallback);
        }catch(Exception ignored){}
        networkCallback=null;
    }
    @Override public void onTaskRemoved(Intent rootIntent){
        shutdown();super.onTaskRemoved(rootIntent);
    }
    private void releaseWakeLock(){if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();}
    static void stopIfRunning(Context context){
        ShellService current=activeInstance;
        if(current!=null)current.shutdown();
        try{context.stopService(new Intent(context,ShellService.class));}catch(Exception ignored){}
    }
    static boolean stopIfRunningAndAwait(Context context,long timeoutMs){
        ShellService current=activeInstance;
        if(current!=null)current.shutdown();
        try{context.stopService(new Intent(context,ShellService.class));}catch(Exception ignored){}
        if(current==null)return true;
        try{return current.cleanupDone.await(Math.max(0L,timeoutMs),java.util.concurrent.TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }
    private void shutdown(){
        final NativeBridge bridge;
        final ArrayList<TerminalSession> closing;
        synchronized(this){
            if(cleanupStarted)return;
            cleanupStarted=true;
            destroyed=true;
            environmentReady=false;
            bridge=nativeBridge;
            closing=new ArrayList<>(sessions);
        }
        for(TerminalSession window:closing)window.disposeEmulator();
        unregisterNetworkCallback();
        if(installer!=null)installer.interrupt();
        ServiceCleanup.start(()->{
            try{
                if(bridge!=null)bridge.close();
            }finally{
                try{
                    for(TerminalSession window:closing)window.finishIfRunning();
                }finally{
                    cleanupDone.countDown();
                }
            }
        },()->{releaseWakeLock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();},main::post);
    }
    @Override public void onDestroy(){
        if(activeInstance==this)activeInstance=null;
        main.removeCallbacks(notifyStatusRunnable);
        shutdown();
        listeners.clear();
        windows.clear();
        session=null;
        super.onDestroy();
    }
    private static String clampClipboardText(CharSequence input){
        if(input==null)return "";
        int len=input.length();
        if(len<=MAX_CLIPBOARD_CHARS)return input.toString();
        int end=MAX_CLIPBOARD_CHARS;
        if(end>0 && Character.isHighSurrogate(input.charAt(end-1)))end--;
        return input.subSequence(0,end).toString();
    }
    private String readBoundedClipboardUri(android.net.Uri uri){
        try(android.content.res.AssetFileDescriptor afd=getContentResolver().openTypedAssetFileDescriptor(uri,"text/*",null)){
            if(afd==null)return null;
            try(java.io.Reader reader=new java.io.InputStreamReader(afd.createInputStream(),java.nio.charset.StandardCharsets.UTF_8)){
                StringBuilder out=new StringBuilder();
                char[] buf=new char[8192];
                int n;
                while(out.length()<MAX_CLIPBOARD_CHARS && (n=reader.read(buf,0,Math.min(buf.length,MAX_CLIPBOARD_CHARS-out.length())))!=-1){
                    out.append(buf,0,n);
                }
                if(out.length()>0 && Character.isHighSurrogate(out.charAt(out.length()-1)))out.setLength(out.length()-1);
                return out.toString();
            }
        }catch(Exception ignored){
            return null;
        }
    }
    private final class Client extends LogCallbacks implements TerminalSessionClient {
        public void onEmulatorError(TerminalSession s,String message){if(listener!=null)listener.onTerminalError(message);}
        public void onInputBufferFull(TerminalSession s,int rejectedBytes){if(listener!=null)listener.onTerminalError(LumaLang.tr("终端输入缓冲区已满，本次输入未写入","Terminal input buffer is full; input was not written"));}
        public void onTextChanged(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onScreenChanged(s);}
        public void onTitleChanged(TerminalSession s){}
        public void onCommandStateChanged(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onSessionStateChanged(s);}
        public void onSessionFinished(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onSessionStateChanged(s);}
        public void onColorsChanged(TerminalSession s){if(listener!=null && sessions.contains(s))listener.onColorsChanged(s);}
        public void onTerminalCursorStateChange(boolean b){if(listener!=null)listener.onCursorChanged();}
        public void setTerminalShellPid(TerminalSession s,int pid){android.util.Log.i("Luma","PTY shell pid="+pid);}
        public Integer getTerminalCursorStyle(){return 1;}
        public void onBell(TerminalSession s){}
        public void onCopyTextToClipboard(TerminalSession s,String text){
            if(text==null)return;
            ClipboardManager cm=getSystemService(ClipboardManager.class);
            if(cm==null)return;
            try{
                cm.setPrimaryClip(ClipData.newPlainText("Terminal",clampClipboardText(text)));
            }catch(RuntimeException ignored){}
        }
        public void onPasteTextFromClipboard(TerminalSession s){
            if(s==null || s.getEmulator()==null)return;
            ClipboardManager cm=getSystemService(ClipboardManager.class);
            if(cm==null || !cm.hasPrimaryClip())return;
            ClipData clip;
            try{clip=cm.getPrimaryClip();}catch(RuntimeException ignored){return;}
            if(clip==null || clip.getItemCount()==0)return;
            ClipData.Item item=clip.getItemAt(0);
            if(item==null)return;
            CharSequence raw=item.getText();
            String pasted=null;
            if(raw!=null){
                pasted=clampClipboardText(raw);
            }else if(item.getUri()!=null){
                pasted=readBoundedClipboardUri(item.getUri());
            }else{
                Intent intent=item.getIntent();
                if(intent!=null)pasted=clampClipboardText(intent.toUri(Intent.URI_INTENT_SCHEME));
            }
            if(pasted!=null && !pasted.isEmpty() && s.getEmulator()!=null)s.getEmulator().paste(pasted);
        }
    }
}
