package io.github.modao7067.luma.root;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.termux.terminal.*;
import com.termux.view.*;
import io.github.modao7067.luma.root.keys.*;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class MainActivity extends Activity {
    private TerminalView terminal;
    private RootTerminalHeader terminalHeader;
    private TerminalLinkOpener linkOpener;
    private final LinkedHashMap<TerminalSession, TerminalView> terminalViews = new LinkedHashMap<>();
    private ImageButton windowsButton;
    private ToolbarIcon windowsIcon;
    private boolean resumed;
    private ShellService service;
    private boolean bound;
    private ImageView wallpaper;
    private View tint;
    private FrameLayout terminalArea;
    private int terminalBackdropColor = Color.TRANSPARENT;
    private TextView title;
    private StatusDot titleDot;
    private final LinkedHashMap<TerminalSession,StatusDot> windowDots = new LinkedHashMap<>();
    private boolean statusPending;
    private final java.util.concurrent.ExecutorService wallpaperWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r,"LumaRootWallpaper"); t.setDaemon(true); return t;
    });
    private final Object wallpaperLock = new Object();
    private volatile int wallpaperGeneration;
    private VirtualKeysView extraKeys;
    private SharedPreferences prefs;
    private float fontSize;
    private boolean backdropPending;
    private LumaUi.Sheet activeSheet;
    private final TerminalPaste.Pending pendingPaste = new TerminalPaste.Pending();
    private float terminalFrameRate = 60f;
    private ManagedTerminalRequest managedTerminalRequest;
    private boolean terminalClosing;
    private android.window.OnBackInvokedCallback backCallback;
    private final String foregroundToken = java.util.UUID.randomUUID().toString();
    private boolean activityStarted;
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundHeartbeat = new Runnable() {
        public void run() {
            if (activityStarted && isSessionTerminal() && !terminalClosing && !isFinishing()) {
                ForegroundTerminalTracker.record(MainActivity.this, foregroundToken, managedTerminalRequest);
                foregroundHandler.postDelayed(this, 60_000L);
            }
        }
    };
    private void syncForegroundTerminal() {
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        if (activityStarted && isSessionTerminal() && !terminalClosing && !isFinishing()) {
            ForegroundTerminalTracker.record(this, foregroundToken, managedTerminalRequest);
            foregroundHandler.postDelayed(foregroundHeartbeat, 60_000L);
        } else {
            ForegroundTerminalTracker.clear(this, foregroundToken);
        }
    }
    private boolean managedWorkspace(){return getResources().getBoolean(R.bool.root_gui_managed);}
    private boolean isSessionTerminal(){return managedWorkspace()&&managedTerminalRequest!=null&&managedTerminalRequest.isSessionTerminal();}
    private void updateDomainControls(){
        if(terminalHeader!=null)terminalHeader.setSessionLocked(isSessionTerminal());
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ShellService.LocalBinder) binder).get();
            service.registerListener(MainActivity.this, new ShellService.Listener() {
                public void onScreenChanged(TerminalSession session) {
                    TerminalView view = terminalViews.get(session);
                    if (view != null) view.onScreenUpdated();
                    if (session == displayedSession() && titleDot != null) titleDot.onOutput(session);
                    StatusDot dot = windowDots.get(session);
                    if (dot != null) dot.onOutput(session);
                    if (session == displayedSession()) scheduleBackdropUpdate();
                }
                public void onColorsChanged(TerminalSession session) {
                    TerminalView view = terminalViews.get(session);
                    if (view != null) view.invalidate();
                    if (session == displayedSession()) scheduleBackdropUpdate();
                }
                public void onCursorChanged() { if (terminal != null) terminal.invalidate(); }
                public void onSessionStateChanged(TerminalSession session) { scheduleStatusUpdate(); }
                public void onWindowsChanged() {
                    dismissActiveSheet();windowDots.clear();
                    for(TerminalSession old:new java.util.ArrayList<>(terminalViews.keySet())){
                        if(!service.getSessions().contains(old)){
                            TerminalView view=terminalViews.remove(old);
                            view.setTerminalCursorBlinkerState(false,true);terminalArea.removeView(view);
                        }
                    }
                    if(displayedSession()==null){openManagedTerminalIfReady();selectWindow(service.session);}
                }
                public void onBackendError(String message) {
                    if (!isFinishing()) terminalClosing = false;
                    Toast.makeText(MainActivity.this,message,Toast.LENGTH_LONG).show();
                    scheduleStatusUpdate();
                }
            });
            openManagedTerminalIfReady();
            if(displayedSession()==null)selectWindow(service.session);
            if(service.backendError!=null)Toast.makeText(MainActivity.this,service.backendError,Toast.LENGTH_LONG).show();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    private void takeManagedTerminalRequest(Intent intent){
        try {ManagedTerminalRequest request=ManagedTerminalRequest.take(this,intent,"root");if(request!=null)managedTerminalRequest=request;}
        catch(java.io.IOException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();if(managedWorkspace()){terminalClosing=true;finish();}}
    }
    private void openManagedTerminalIfReady(){
        if(service==null||terminalClosing)return;
        TerminalSession displayed=displayedSession();
        if(managedWorkspace()&&displayed!=null&&service.allowsSelection(managedTerminalRequest,displayed)&&terminal.getVisibility()==View.VISIBLE)return;
        if(!managedWorkspace()&&managedTerminalRequest==null)return;
        try {
            TerminalSession opened=managedTerminalRequest==null?service.openOrdinarySession(null):service.openManagedSession(managedTerminalRequest);
            if(opened!=null){
                if(!managedWorkspace())managedTerminalRequest=null;
                selectWindow(opened);
            }
            updateDomainControls();
            syncForegroundTerminal();
        }catch(IllegalStateException error){
            Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();
            if(managedWorkspace()){terminalClosing=true;leaveTerminal();}else managedTerminalRequest=null;
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){
        if(managedTerminalRequest!=null)state.putString("lumaManagedTerminal",managedTerminalRequest.save());
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        dismissActiveSheet();pendingPaste.cancel();
        if(managedWorkspace()){managedTerminalRequest=null;terminalClosing=false;if(terminal!=null)terminal.setVisibility(View.GONE);}
        takeManagedTerminalRequest(intent);updateDomainControls();
        syncForegroundTerminal();
        openManagedTerminalIfReady();if(service!=null)selectWindow(service.session);
    }

    private int dp(float n) {
        return LumaUi.dp(this, n);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if(managedWorkspace()&&Build.VERSION.SDK_INT>=33){backCallback=this::handleTerminalBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        if(state!=null)try{managedTerminalRequest=ManagedTerminalRequest.restore(state.getString("lumaManagedTerminal"),"root");}
        catch(java.io.IOException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();if(managedWorkspace()){finish();return;}}
        takeManagedTerminalRequest(getIntent());
        if(isFinishing())return;
        BundledTerminalFonts.initialize(getAssets());
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (getDisplay() != null) {
            Display.Mode current = getDisplay().getMode(), best = current;
            for (Display.Mode mode : getDisplay().getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && mode.getRefreshRate() > best.getRefreshRate()) {
                    best = mode;
                }
            }
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            terminalFrameRate = best.getRefreshRate();
            attributes.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(attributes);
        }
        prefs = getSharedPreferences(getString(R.string.root_appearance_preferences), MODE_PRIVATE);
        fontSize = TerminalFontSize.clamp(prefs.getFloat("font", TerminalFontSize.DEFAULT),
                getResources().getDisplayMetrics().density);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        if(!managedWorkspace()){
            wallpaper = new ImageView(this);
            wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
            tint = new View(this);
            root.addView(tint, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        RootTerminalHeader header=new RootTerminalHeader(this,managedWorkspace());terminalHeader=header;
        title=header.title;titleDot=header.status;windowsIcon=header.windowsIcon;windowsButton=header.windows;
        if(header.back!=null)header.back.setOnClickListener(v->handleTerminalBack());
        title.setOnClickListener(v->showWindows());
        title.setOnLongClickListener(v->{
            if(!isSessionTerminal()&&displayedSession()!=null){v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);renameWindow(displayedSession());}
            return true;
        });
        header.keyboard.setOnClickListener(v->toggleKeyboard());
        windowsButton.setOnClickListener(v->showWindows());
        windowsButton.setOnLongClickListener(v->{
            if(!isSessionTerminal()&&service!=null){
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                TerminalSession created=service.createSession();
                if(created!=null){selectWindow(created);Toast.makeText(this,com.termux.terminal.LumaLang.tr("已新建 ","Created ")+created.mSessionName,Toast.LENGTH_SHORT).show();}
            }
            return true;
        });
        if(header.settings!=null)header.settings.setOnClickListener(v->settings());
        updateDomainControls();

        content.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        View line = new View(this);
        line.setBackgroundColor(0x18ffffff);
        content.addView(line, new LinearLayout.LayoutParams(-1, 1));

        terminal = createTerminalView();
        terminalArea = new FrameLayout(this);
        terminalArea.setPadding(0, 0, 0, 0);
        terminalArea.addView(terminal, new FrameLayout.LayoutParams(-1, -1));
        content.addView(terminalArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        View keysDivider = new View(this);
        keysDivider.setBackgroundColor(0x18ffffff);
        content.addView(keysDivider, new LinearLayout.LayoutParams(-1, 1));

        extraKeys = new VirtualKeysView(this, null);
        extraKeys.setBackgroundColor(0x0affffff);
        extraKeys.setButtonColors(0xffe0e8f2, 0xffabefdc, Color.TRANSPARENT, 0x26abefdc);
        extraKeys.setVirtualKeysViewClient(new VirtualKeysView.IVirtualKeysView() {
            public void onVirtualKeyButtonClick(View v, VirtualKeyButton info, Button b) {
                if (displayedSession() == null || displayedSession().getEmulator() == null) return;
                String key = info.getKey();
                if(linkOpener!=null&&linkOpener.isShowing()){
                    Integer previewCode=VirtualKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
                    if(previewCode!=null)terminal.onKeyDown(previewCode,new KeyEvent(KeyEvent.ACTION_DOWN,previewCode));
                    return;
                }
                Integer code = key.equals("BACKTAB") ? Integer.valueOf(KeyEvent.KEYCODE_TAB)
                    : VirtualKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
                if (code != null) {
                    int mods = key.equals("BACKTAB") ? KeyHandler.KEYMOD_SHIFT : 0;
                    if (Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.CTRL, true))) mods |= KeyHandler.KEYMOD_CTRL;
                    if (Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.ALT, true))) mods |= KeyHandler.KEYMOD_ALT;
                    if (Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.SHIFT, true))) mods |= KeyHandler.KEYMOD_SHIFT;
                    terminal.handleKeyCode(code, mods);
                } else {
                    key.codePoints().forEach(cp -> terminal.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, cp, false, false));
                }
                terminal.requestFocus();
            }
            public boolean performVirtualKeyButtonHapticFeedback(View v, VirtualKeyButton i, Button b) {
                return false;
            }
        });
        try {
            extraKeys.reload(new VirtualKeysInfo(
                "[[{key:'ESC',popup:'`'},{key:'/',popup:'~'},{key:'-',popup:'|'},{key:'ENTER',display:'↲'},'HOME','UP','END','PGUP']," +
                "[{key:'TAB',popup:{key:'BACKTAB',display:'⇤'}},'CTRL','ALT','SHIFT','LEFT','DOWN','RIGHT','PGDN']]",
                "arrows-only",
                VirtualKeysConstants.CONTROL_CHARS_ALIASES
            ));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        content.addView(extraKeys, new LinearLayout.LayoutParams(-1, dp(80)));

        setContentView(root);
        applyAppearance();
        loadWallpaper();
        root.post(() -> {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        });
        Intent intent = new Intent(this, ShellService.class);
        startService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
        terminal.requestFocus();
    }

    private TerminalView createTerminalView() {
        TerminalView view = new TerminalView(this, null);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setTerminalViewClient(new ViewClient(view));
        view.setTextSize(TerminalFontSize.pixels(fontSize, getResources().getDisplayMetrics().density));
        view.setTypeface(BundledTerminalFonts.typeface());
        view.setBackgroundColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(terminalFrameRate);
        }
        return view;
    }

    private TerminalSession displayedSession() {
        if(service==null)return null;
        TerminalSession current=terminal==null?null:terminal.getCurrentSession();
        return current!=null&&service.getSessions().contains(current)?current:null;
    }

    private void selectWindow(TerminalSession session) {
        if (service == null || session == null || (managedWorkspace()&&!service.allowsSelection(managedTerminalRequest,session))) return;
        pendingPaste.selectionChanged(session);
        if (extraKeys != null) extraKeys.cancelPendingPresses();
        if (terminal != null) {
            terminal.stopTextSelectionMode();
            terminal.setTerminalCursorBlinkerState(false, true);
            terminal.setVisibility(View.GONE);
        }
        service.selectSession(session);
        TerminalView selected = terminalViews.get(session);
        if (selected == null) {
            if (terminalViews.isEmpty() && terminal != null && terminal.getCurrentSession() == null) {
                selected = terminal;
            } else {
                selected = createTerminalView();
                terminalArea.addView(selected, new FrameLayout.LayoutParams(-1, -1));
            }
            terminalViews.put(session, selected);
        }
        terminal = selected;
        terminal.updateAccessibilityState();
        terminal.setVisibility(View.VISIBLE);
        terminal.setTextSize(TerminalFontSize.pixels(fontSize, getResources().getDisplayMetrics().density));
        terminal.attachSession(session);
        terminal.setTerminalCursorBlinkerState(resumed, true);
        terminal.requestFocus();
        for (SpecialButtonState state : extraKeys.getSpecialButtons().values()) {
            state.setIsLocked(false);
            state.setIsActive(false);
        }
        getSystemService(InputMethodManager.class).restartInput(terminal);
        updateTerminalBackdrop();
        updateTitle();
    }

    private static String formatWindowTitle(TerminalSession s) {
        if (s == null) return "";
        return s.mSessionName + (s.isRunning() ? "" : com.termux.terminal.LumaLang.tr(" · 已结束", " · Ended"));
    }

    private void dismissActiveSheet() {
        if (activeSheet != null) {
            activeSheet.dismiss();
            activeSheet = null;
        }
        windowDots.clear();
    }

    private void showWindows() {
        if (isSessionTerminal() || service == null) return;
        dismissActiveSheet();
        ArrayList<TerminalSession> windows = new ArrayList<>(managedWorkspace()?service.getOrdinarySessions():service.getSessions());
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("终端窗口", "Terminal windows"), service.isBackendConnected()?null:
            (service.backendError==null?com.termux.terminal.LumaLang.tr("正在连接 Ubuntu 后端…", "Connecting to Ubuntu backend…"):service.backendError));

        if(!service.isBackendConnected()){
            TextView retryBtn=LumaUi.primaryButton(this,com.termux.terminal.LumaLang.tr("重试后端连接","Retry backend connection"),v->{
                sheet.dismiss();
                Toast.makeText(this,com.termux.terminal.LumaLang.tr("正在重新连接 Ubuntu 后端…","Reconnecting to Ubuntu backend…"),Toast.LENGTH_SHORT).show();
                service.retryBackend();
            });
            sheet.body.addView(retryBtn,LumaUi.vSpace(this,0));
        }

        for (int i = 0; i < windows.size(); i++) {
            TerminalSession window = windows.get(i);
            boolean isCurrent = (window == displayedSession());
            boolean running = window.isRunning();

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(13), dp(12), dp(13));
            row.setBackground(LumaUi.rippleSurface(
                isCurrent ? LumaUi.BG_CARD_ACTIVE : LumaUi.BG_CARD,
                0x22ffffff,
                dp(16),
                1,
                isCurrent ? LumaUi.STROKE_ACCENT : 0x12ffffff
            ));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameView = new TextView(this);
            nameView.setText(window.mSessionName);
            nameView.setTextColor(isCurrent ? LumaUi.ACCENT : LumaUi.TEXT_PRIMARY);
            nameView.setTextSize(15.5f);
            nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            StatusDot dot = new StatusDot(this);
            dot.showSession(window);
            windowDots.put(window, dot);

            infoCol.addView(LumaUi.windowNameRow(this, nameView, dot));
            row.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));

            if (!running) {
                TextView reconnectChip = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("重连", "Reconnect"), true, v -> {
                    sheet.dismiss();
                    TerminalView oldView = terminalViews.remove(window);
                    if (oldView != null) {
                        oldView.setTerminalCursorBlinkerState(false, true);
                        terminalArea.removeView(oldView);
                    }
                    service.selectSession(window);
                    service.reconnectSession();
                    selectWindow(service.session);
                });
                LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(-2, -2);
                rcLp.rightMargin = dp(6);
                row.addView(reconnectChip, rcLp);
            }

            TextView closeChip = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("关闭", "Close"), false, v -> {
                sheet.dismiss();
                confirmCloseWindow(window);
            });
            closeChip.setTextColor(LumaUi.DANGER);
            row.addView(closeChip, new LinearLayout.LayoutParams(-2, -2));

            row.setOnClickListener(v -> {
                sheet.dismiss();
                selectWindow(window);
            });

            sheet.body.addView(row, LumaUi.vSpace(this, i == 0 ? 0 : 8));
        }

        TextView newWinBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("+ 新建窗口", "+ New window"), v -> {
            sheet.dismiss();
            TerminalSession created=service.createSession();
            if(created!=null)selectWindow(created);
        });
        sheet.body.addView(newWinBtn, LumaUi.vSpace(this, 14));

        activeSheet = sheet;
        sheet.dialog.setOnDismissListener(dialog -> { if (activeSheet == sheet) windowDots.clear(); });
        sheet.show();
    }

    private void renameWindow(TerminalSession target) {
        if (isSessionTerminal() || service == null || target == null || (managedWorkspace()&&!service.allowsSelection(null,target))) return;
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("重命名窗口", "Rename window"),
            com.termux.terminal.LumaLang.isZh() ? ("为「" + target.mSessionName + "」设置便于识别的窗口名称") : ("Set a display name for \"" + target.mSessionName + "\""));
        EditText input = LumaUi.inputField(this, target.mSessionName, com.termux.terminal.LumaLang.tr("输入窗口名称", "Enter window name"));
        sheet.body.addView(input, LumaUi.vSpace(this, 2));

        TextView cancelBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("取消", "Cancel"), v -> sheet.dismiss());
        TextView saveBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("保存", "Save"), v -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                service.renameSession(target,name);
                updateTitle();
            }
            sheet.dismiss();
        });
        sheet.body.addView(LumaUi.buttonRow(this, cancelBtn, saveBtn), LumaUi.vSpace(this, 14));
        activeSheet = sheet;
        sheet.show();
    }

    private void confirmCloseWindow(TerminalSession closing) {
        if (isSessionTerminal() || service == null || closing == null || (managedWorkspace()&&!service.allowsSelection(null,closing))) return;
        dismissActiveSheet();
        boolean isLast = service.getSessions().size() == 1;
        boolean lastOrdinary=managedWorkspace()&&service.getOrdinarySessions().size()==1;
        String desc = (com.termux.terminal.LumaLang.isZh()
            ? ("关闭后，「" + closing.mSessionName + "」内的前台进程与终端会话将立即结束。")
            : ("Closing \"" + closing.mSessionName + "\" immediately terminates its foreground processes and terminal session."))
            + (managedWorkspace()&&lastOrdinary
                ? com.termux.terminal.LumaLang.tr("\n关闭后返回会话，其它会话和专属终端继续运行。", "\nReturns to session; other sessions and dedicated terminals keep running.")
                : (!managedWorkspace()&&isLast ? com.termux.terminal.LumaLang.tr("\n这是最后一个窗口，关闭后将一并退出 Luma Root。", "\nThis is the last window; closing it also exits Luma Root.") : ""));
        LumaUi.Sheet sheet = new LumaUi.Sheet(this,
            com.termux.terminal.LumaLang.isZh() ? ("关闭 " + closing.mSessionName + "？") : ("Close " + closing.mSessionName + "?"), desc);
        TextView cancelBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("保留窗口", "Keep window"), v -> sheet.dismiss());
        TextView closeBtn = LumaUi.dangerButton(this, managedWorkspace()&&lastOrdinary
            ? com.termux.terminal.LumaLang.tr("关闭并返回", "Close & return")
            : (!managedWorkspace()&&isLast ? com.termux.terminal.LumaLang.tr("关闭并退出", "Close & exit") : com.termux.terminal.LumaLang.tr("确认关闭", "Close")), v -> {
            sheet.dismiss();
            terminalClosing=managedWorkspace()&&lastOrdinary;
            service.closeSession(closing,()->{
                TerminalView view = terminalViews.remove(closing);
                if (view != null) {
                    view.stopTextSelectionMode();
                    view.setTerminalCursorBlinkerState(false, true);
                    terminalArea.removeView(view);
                }
                if (service.session == null || lastOrdinary) {
                    service.unregisterListener(MainActivity.this);
                    if(!managedWorkspace())stopService(new Intent(this, ShellService.class));
                    if(managedWorkspace())leaveTerminal();else finish();
                } else {
                    selectWindow(service.session);
                }
            });
        });
        sheet.body.addView(LumaUi.buttonRow(this, cancelBtn, closeBtn), LumaUi.vSpace(this, 8));
        activeSheet = sheet;
        sheet.show();
    }

    private void scheduleStatusUpdate() {
        if (!resumed || statusPending || title == null) return;
        statusPending = true;
        title.postOnAnimation(() -> { statusPending = false; if (resumed) updateTitle(); });
    }

    private void updateTitle() {
        if (title == null) return;
        TerminalSession current = displayedSession();
        if (current == null) {
            title.setText("");
        } else {
            title.setText(formatWindowTitle(current));
        }
        if (titleDot != null) { titleDot.setActivityActive(resumed); titleDot.showSession(current); }
        for (java.util.Map.Entry<TerminalSession,StatusDot> entry : windowDots.entrySet()) {
            entry.getValue().setActivityActive(resumed);
            entry.getValue().showSession(entry.getKey());
        }
        int winCount = service == null ? 1 : (managedWorkspace()?service.getOrdinarySessions():service.getSessions()).size();
        if (windowsIcon != null) windowsIcon.setCount(winCount);
        if (windowsButton != null) {
            windowsButton.setContentDescription(com.termux.terminal.LumaLang.isZh()
                ? ("多窗口，共 " + winCount + " 个窗口，点击切换，长按新建")
                : ("Windows (" + winCount + "), tap to switch, long-press for new"));
        }
    }

    private void updateTerminalBackdrop() {
        int color = displayedSession() == null
            ? Color.TRANSPARENT
            : TerminalBackdrop.color(displayedSession().getEmulator(), terminal.getTopRow());
        if (color != terminalBackdropColor) {
            terminalBackdropColor = color;
            terminalArea.setBackgroundColor(color);
        }
        terminal.setBackgroundColor(color);
    }

    private void scheduleBackdropUpdate() {
        if (backdropPending || !resumed) return;
        backdropPending = true;
        terminalArea.postOnAnimation(() -> {
            backdropPending = false;
            if (resumed) updateTerminalBackdrop();
        });
    }

    private void applyAppearance() {
        if(managedWorkspace()){applyFontSize();return;}
        int opacity = prefs.getInt("opacity", 60), blur = prefs.getInt("blur", 24);
        boolean hasCustomWallpaper = new File(getFilesDir(), "wallpaper").isFile();
        tint.setBackgroundColor(hasCustomWallpaper ? Color.argb(opacity * 255 / 100, 15, 20, 32) : Color.TRANSPARENT);
        wallpaper.setRenderEffect((!hasCustomWallpaper || blur == 0) ? null : RenderEffect.createBlurEffect(dp(blur), dp(blur), Shader.TileMode.CLAMP));
        applyFontSize();
    }

    private void applyFontSize() {
        int pxSize = TerminalFontSize.pixels(fontSize, getResources().getDisplayMetrics().density);
        if (terminal != null) terminal.setTextSize(pxSize);
        for (TerminalView v : terminalViews.values()) {
            v.setTextSize(pxSize);
        }
    }

    private void loadWallpaper() { if(!managedWorkspace())queueWallpaper(null); }

    private void queueWallpaper(Uri source) {
        if(managedWorkspace())return;
        final int generation;
        synchronized (wallpaperLock) { generation = ++wallpaperGeneration; }
        final int width = getResources().getDisplayMetrics().widthPixels;
        final int height = getResources().getDisplayMetrics().heightPixels;
        wallpaperWorker.execute(() -> {
            File temporary = null;
            Bitmap decoded = null;
            try {
                File custom = new File(getFilesDir(), "wallpaper");
                File inputFile = custom;
                if (source != null) {
                    temporary = File.createTempFile("wallpaper-", ".tmp", getFilesDir());
                    try (InputStream in = getContentResolver().openInputStream(source);
                         OutputStream out = new FileOutputStream(temporary)) {
                        if (in == null) throw new IOException(com.termux.terminal.LumaLang.tr("无法读取图片", "Unable to read image"));
                        byte[] bytes = new byte[65536]; long total = 0; int count;
                        while ((count = in.read(bytes)) != -1) {
                            if (Thread.currentThread().isInterrupted() || generation != wallpaperGeneration) return;
                            total += count;
                            if (total > WallpaperImages.MAX_SOURCE_BYTES) throw new IOException(com.termux.terminal.LumaLang.tr("图片文件过大", "Image file is too large"));
                            out.write(bytes, 0, count);
                        }
                    }
                    inputFile = temporary;
                }
                if (inputFile.isFile()) decoded = WallpaperImages.decode(inputFile, width, height);
                synchronized (wallpaperLock) {
                    if (generation != wallpaperGeneration || Thread.currentThread().isInterrupted()) {
                        if (decoded != null) decoded.recycle();
                        return;
                    }
                    if (temporary != null && !temporary.renameTo(custom)) throw new IOException(com.termux.terminal.LumaLang.tr("无法保存图片", "Failed to save image"));
                }
                final Bitmap result = decoded;
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != wallpaperGeneration) { if (result != null) result.recycle(); return; }
                    wallpaper.setImageBitmap(result);
                    applyAppearance();
                });
            } catch (Exception | OutOfMemoryError e) {
                if (decoded != null) decoded.recycle();
                if (generation == wallpaperGeneration) runOnUiThread(() -> {
                    if (!isDestroyed()) Toast.makeText(this, com.termux.terminal.LumaLang.tr("无法加载图片，已保留原背景", "Failed to load image; previous background kept"), Toast.LENGTH_SHORT).show();
                });
            } finally { if (temporary != null) temporary.delete(); }
        });
    }

    private void resetWallpaper() {
        if(managedWorkspace())return;
        synchronized (wallpaperLock) {
            wallpaperGeneration++;
            File custom = new File(getFilesDir(), "wallpaper");
            if (custom.exists() && !custom.delete()) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("无法删除背景图片", "Failed to delete background image"), Toast.LENGTH_SHORT).show(); return;
            }
        }
        loadWallpaper();
    }

    private void showKeyboard() {
        terminal.requestFocus();
        terminal.post(() -> getSystemService(InputMethodManager.class).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT));
    }

    private void toggleKeyboard() {
        if (terminal.getRootWindowInsets() != null && terminal.getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        } else {
            showKeyboard();
        }
    }

    private void settings() {
        if(managedWorkspace())return;
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, "Luma", null);
        sheet.body.addView(LumaUi.menuRow(this, com.termux.terminal.LumaLang.tr("外观设置", "Appearance"), null,
            Math.round(fontSize) + com.termux.terminal.LumaLang.tr("号", " pt"), false, v -> { sheet.dismiss(); appearance(); }));
        activeSheet = sheet;
        sheet.show();
    }

    private void openWallpaperPicker() {
        if(managedWorkspace())return;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 20);
        } catch (ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                fallback.setType("image/*");
                startActivityForResult(fallback, 20);
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("未找到可用的相册或文件选择器", "No gallery or file picker found"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void appearance() {
        if(managedWorkspace())return;
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("外观设置", "Appearance"), null);

        View opacitySlider = LumaUi.sliderCard(this, com.termux.terminal.LumaLang.tr("背景深浅", "Background tint"), "%", 25, 85, prefs.getInt("opacity", 60), v -> {
            prefs.edit().putInt("opacity", v).apply();
            applyAppearance();
        });
        sheet.body.addView(opacitySlider);

        View blurSlider = LumaUi.sliderCard(this, com.termux.terminal.LumaLang.tr("背景模糊", "Background blur"), " dp", 0, 60, prefs.getInt("blur", 24), v -> {
            prefs.edit().putInt("blur", v).apply();
            applyAppearance();
        });
        sheet.body.addView(blurSlider, LumaUi.vSpace(this, 8));

        File customWallpaper = new File(getFilesDir(), "wallpaper");
        TextView changeWp = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("更换壁纸", "Change wallpaper"), v -> {
            sheet.dismiss();
            openWallpaperPicker();
        });
        sheet.body.addView(changeWp, LumaUi.vSpace(this, 10));
        if (customWallpaper.isFile()) {
            TextView resetWp = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("恢复默认纯黑背景", "Restore pure black background"), v -> {
                resetWallpaper();
                sheet.dismiss();
            });
            sheet.body.addView(resetWp, LumaUi.vSpace(this, 10));
        }

        TextView done = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("完成", "Done"), v -> sheet.dismiss());
        sheet.body.addView(done, LumaUi.vSpace(this, 12));

        activeSheet = sheet;
        sheet.show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if(linkOpener!=null&&linkOpener.onActivityResult(request,result,data))return;
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null && data.getData() != null) {
            queueWallpaper(data.getData());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        syncForegroundTerminal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if(managedWorkspace())openManagedTerminalIfReady();
        syncForegroundTerminal();
        updateTitle();
        if (terminal != null) {
            terminal.updateAccessibilityState();
            terminal.setTerminalCursorBlinkerState(true, true);
            terminal.invalidate();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (titleDot != null) titleDot.setActivityActive(false);
        for (StatusDot dot : windowDots.values()) dot.setActivityActive(false);
        if (extraKeys != null) extraKeys.cancelPendingPresses();
        if (terminal != null) terminal.setTerminalCursorBlinkerState(false, true);
        super.onPause();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        syncForegroundTerminal();
        super.onStop();
    }

    @Override public void onBackPressed(){handleTerminalBack();}
    private void handleTerminalBack(){
        if(linkOpener!=null&&linkOpener.dismiss())return;
        if(activeSheet!=null&&activeSheet.dialog.isShowing()){dismissActiveSheet();return;}
        if(terminal!=null&&terminal.getRootWindowInsets()!=null&&terminal.getRootWindowInsets().isVisible(WindowInsets.Type.ime())){
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());return;
        }
        if(managedWorkspace())leaveTerminal();else moveTaskToBack(true);
    }
    private void leaveTerminal(){
        terminalClosing = true;
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        ForegroundTerminalTracker.clear(this, foregroundToken);
        if(isSessionTerminal()&&service!=null){
            service.closeDedicatedWindow(managedTerminalRequest.windowId);
        }
        if(isTaskRoot()){
            startActivity(new Intent().setClassName(getPackageName(),"io.github.modao7067.luma.gui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);
        }else{
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        ForegroundTerminalTracker.clear(this, foregroundToken);
        if(backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        pendingPaste.cancel();
        dismissActiveSheet();
        synchronized (wallpaperLock) { wallpaperGeneration++; }
        wallpaperWorker.shutdownNow();
        if (service != null) {
            service.unregisterListener(MainActivity.this);
        }
        for (TerminalView view : terminalViews.values()) {
            view.setTerminalCursorBlinkerState(false, true);
        }
        if (bound) unbindService(connection);
        if(linkOpener!=null)linkOpener.close();
        super.onDestroy();
    }

    private final class ViewClient extends LogCallbacks implements TerminalViewClient {
        private final TerminalView view;
        ViewClient(TerminalView view) {
            this.view = view;
        }
        public float onScale(float factor) {
            float density = getResources().getDisplayMetrics().density;
            int previousPixels = TerminalFontSize.pixels(fontSize, density);
            fontSize = TerminalFontSize.scale(fontSize, factor, density);
            if (TerminalFontSize.pixels(fontSize, density) != previousPixels) {
                prefs.edit().putFloat("font", fontSize).apply();
                applyFontSize();
            }
            return 1f;
        }
        public void onSingleTapUp(MotionEvent e) {
            if (displayedSession() == null || displayedSession().getEmulator() == null || !displayedSession().getEmulator().isMouseTrackingActive()) {
                showKeyboard();
            }
        }
        public boolean onLinkTap(String target){
            if(linkOpener==null)linkOpener=new TerminalLinkOpener(MainActivity.this,
                    (path,pid,destination,limit,highlight,columns)->TerminalLinkReader.copy(MainActivity.this,path,pid,destination,limit,highlight,columns));
            return linkOpener.open(target,view);
        }
        public boolean shouldBackButtonBeMappedToEscape() { return false; }
        public boolean shouldEnforceCharBasedInput() { return false; }
        public int getInputMode() { return 0; }
        public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        public boolean isTerminalViewSelected() { return view == terminal; }
        public void copyModeChanged(boolean b) {}
        public boolean onKeyDown(int code, KeyEvent e, TerminalSession s) { return false; }
        public boolean onKeyUp(int code, KeyEvent e) { return false; }
        public boolean onLongPress(MotionEvent e) { return false; }
        public boolean readControlKey() { return extraKeys != null && Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.CTRL, true)); }
        public boolean readAltKey() { return extraKeys != null && Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.ALT, true)); }
        public boolean readShiftKey() { return extraKeys != null && Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.SHIFT, true)); }
        public boolean readFnKey() { return extraKeys != null && Boolean.TRUE.equals(extraKeys.readSpecialButton(SpecialButton.FN, true)); }
        public boolean onCodePoint(int code, boolean ctrl, TerminalSession s) { return false; }
        public boolean onPasteFromClipboard(CharSequence text) {
            pendingPaste.begin(TerminalPaste.request(MainActivity.this, view.mTermSession, text,
                MainActivity.this::displayedSession));
            return true;
        }
        public void onEmulatorSet() {
            if (view == terminal) updateTerminalBackdrop();
            view.setTerminalCursorBlinkerRate(600);
            view.setTerminalCursorBlinkerState(view == terminal && resumed, true);
        }
    }
}
