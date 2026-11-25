package io.github.modao7067.luma;

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
import io.github.modao7067.luma.keys.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class MainActivity extends Activity {
    private TerminalView terminal;
    private CursorTrailView cursorTrail;
    private TerminalLinkOpener linkOpener;
    private final LinkedHashMap<TerminalSession, TerminalView> terminalViews = new LinkedHashMap<>();
    private TerminalHeader terminalHeader;
    private android.window.OnBackInvokedCallback backCallback;
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
        Thread t = new Thread(r,"LumaWallpaper"); t.setDaemon(true); return t;
    });
    private final Object wallpaperLock = new Object();
    private volatile int wallpaperGeneration;
    private VirtualKeysView extraKeys;
    private SharedPreferences prefs;
    private float fontSize;
    private boolean storageWasGranted;
    private boolean backdropPending;
    private LumaUi.Sheet activeSheet;
    private final TerminalPaste.Pending pendingPaste = new TerminalPaste.Pending();
    private float terminalFrameRate = 60f;
    private ManagedTerminalRequest managedTerminalRequest;
    private boolean legacyLaunchApplied;
    private final String foregroundToken = java.util.UUID.randomUUID().toString();
    private boolean activityStarted;
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundHeartbeat = new Runnable() {
        public void run() {
            if (activityStarted && isSessionTerminal() && !isFinishing()) {
                ForegroundTerminalTracker.record(MainActivity.this, foregroundToken, managedTerminalRequest);
                foregroundHandler.postDelayed(this, 60_000L);
            }
        }
    };
    private void syncForegroundTerminal() {
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        if (activityStarted && isSessionTerminal() && !isFinishing()) {
            ForegroundTerminalTracker.record(this, foregroundToken, managedTerminalRequest);
            foregroundHandler.postDelayed(foregroundHeartbeat, 60_000L);
        } else {
            ForegroundTerminalTracker.clear(this, foregroundToken);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((ShellService.LocalBinder) b).get();
            if (service.isClosing()) {
                Toast.makeText(MainActivity.this,com.termux.terminal.LumaLang.tr("正在关闭，请稍后重新打开","Closing; please reopen shortly"),Toast.LENGTH_SHORT).show();
                finish(); return;
            }
            service.registerListener(MainActivity.this, new ShellService.Listener() {
                public void onEnvironmentReady(){openTerminalIfReady();}
                public void onTerminalError(String message){
                    if (service != null && !service.environmentReady && title != null) title.setText(com.termux.terminal.LumaLang.tr("环境准备失败", "Environment setup failed"));
                    Toast.makeText(MainActivity.this,message,Toast.LENGTH_LONG).show();
                }
                public void onScreenChanged(TerminalSession session) {
                    TerminalView view = terminalViews.get(session);
                    if (view != null) view.onScreenUpdated();
                    if (session == displayedSession() && titleDot != null) titleDot.onOutput(session);
                    StatusDot windowDot = windowDots.get(session);
                    if (windowDot != null) windowDot.onOutput(session);
                    if (session == displayedSession()) scheduleBackdropUpdate();
                }
                public void onColorsChanged(TerminalSession session) {
                    TerminalView view = terminalViews.get(session);
                    if (view != null) view.invalidate();
                    if (session == displayedSession()) scheduleBackdropUpdate();
                }
                public void onCursorChanged() {
                    if (terminal != null) terminal.invalidate();
                    if (cursorTrail != null && cursorTrail.isTrailEnabled()) cursorTrail.invalidate();
                }
                public void onSessionStateChanged(TerminalSession session) {
                    scheduleStatusUpdate();
                }
            });
            if (service.environmentReady) openTerminalIfReady();
            else if (service.preparationError != null) {
                title.setText(com.termux.terminal.LumaLang.tr("环境准备失败", "Environment setup failed"));
                Toast.makeText(MainActivity.this, com.termux.terminal.LumaLang.tr("环境准备失败：", "Environment setup failed: ") + service.preparationError, Toast.LENGTH_LONG).show();
            } else title.setText(com.termux.terminal.LumaLang.tr("准备环境…", "Preparing environment…"));
        }
        public void onServiceDisconnected(ComponentName n) {
            service = null;
        }
    };

    private boolean isSessionTerminal(){
        return managedTerminalRequest!=null&&managedTerminalRequest.isSessionTerminal();
    }
    private void takeManagedTerminalRequest(Intent intent){
        try {ManagedTerminalRequest request=ManagedTerminalRequest.take(this,intent,"noroot");if(request!=null)managedTerminalRequest=request;}
        catch(java.io.IOException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();finish();}
    }
    private void openTerminalIfReady(){
        if(service==null||!service.environmentReady||isFinishing())return;
        TerminalSession displayed=displayedSession();
        if(displayed!=null&&service.allowsSelection(managedTerminalRequest,displayed)&&terminal.getVisibility()==View.VISIBLE)return;
        try {
            boolean legacy=managedTerminalRequest!=null&&"legacy".equals(managedTerminalRequest.terminalMode);
            TerminalSession opened=managedTerminalRequest==null||(legacy&&legacyLaunchApplied)
                ?service.openOrdinarySession(null):service.openManagedSession(managedTerminalRequest);
            selectWindow(opened);
            if(opened!=null)legacyLaunchApplied=true;
            syncForegroundTerminal();
        }catch(IllegalStateException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();finish();}
    }
    @Override protected void onSaveInstanceState(Bundle state){
        if(managedTerminalRequest!=null)state.putString("lumaManagedTerminal",managedTerminalRequest.save());
        state.putBoolean("lumaLegacyLaunchApplied",legacyLaunchApplied);
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        dismissActiveSheet();
        if(linkOpener!=null)linkOpener.dismiss();
        pendingPaste.cancel();
        managedTerminalRequest=null;
        legacyLaunchApplied=false;
        takeManagedTerminalRequest(intent);
        if(terminalHeader!=null)terminalHeader.setSessionLocked(isSessionTerminal());
        if(terminal!=null)terminal.setVisibility(View.GONE);
        syncForegroundTerminal();
        openTerminalIfReady();
    }

    private int dp(float n) {
        return LumaUi.dp(this, n);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!LinuxEnvironment.ready(this)) {
            Intent setup=new Intent(this,SetupActivity.class);
            String ticket=getIntent().getStringExtra(ManagedTerminalRequest.EXTRA_TICKET);
            if(ticket!=null)setup.putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket);
            startActivity(setup);
            finish();
            return;
        }
        if(state!=null)try{
            managedTerminalRequest=ManagedTerminalRequest.restore(state.getString("lumaManagedTerminal"),"noroot");
            legacyLaunchApplied=state.getBoolean("lumaLegacyLaunchApplied",false);
        }catch(java.io.IOException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();finish();return;}
        takeManagedTerminalRequest(getIntent());
        if(isFinishing())return;
        storageWasGranted = LinuxEnvironment.storageGranted();
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
            attributes.preferredRefreshRate = terminalFrameRate;
            getWindow().setAttributes(attributes);
        }
        prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        fontSize = TerminalFontSize.clamp(prefs.getFloat("font", TerminalFontSize.DEFAULT),
                getResources().getDisplayMetrics().density);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);

        tint = new View(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        titleDot = new StatusDot(this);
        terminalHeader = new TerminalHeader(this,titleDot);
        TerminalHeader header = terminalHeader;
        title = header.title;
        header.back.setOnClickListener(v -> leaveTerminal());
        title.setOnClickListener(v -> showWindows());
        title.setOnLongClickListener(v -> {
            if (!isSessionTerminal() && displayedSession() != null) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                renameWindow(displayedSession());
            }
            return true;
        });
        header.keyboard.setOnClickListener(v -> toggleKeyboard());
        windowsIcon = header.windowsIcon;
        windowsButton = header.windows;
        windowsButton.setOnClickListener(v -> showWindows());
        windowsButton.setOnLongClickListener(v -> {
            if (!isSessionTerminal() && service != null && service.environmentReady) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                service.createSession();
                selectWindow(service.session);
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("已新建 ", "Created ") + service.session.mSessionName, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        header.setSessionLocked(isSessionTerminal());

        content.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        View line = new View(this);
        line.setBackgroundColor(0x18ffffff);
        content.addView(line, new LinearLayout.LayoutParams(-1, 1));

        terminal = createTerminalView();
        terminalArea = new FrameLayout(this);
        terminalArea.setPadding(0, 0, 0, 0);
        terminalArea.addView(terminal, new FrameLayout.LayoutParams(-1, -1));
        cursorTrail = new CursorTrailView(this);
        cursorTrail.setTargetFrameRate(terminalFrameRate);
        cursorTrail.setTrailEnabled(TerminalCursorTrailPrefs.isEnabled(this));
        cursorTrail.setActive(false);
        terminalArea.addView(cursorTrail, new FrameLayout.LayoutParams(-1, -1));
        terminal.setCursorTrailView(cursorTrail);
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
        if(Build.VERSION.SDK_INT>=33) {
            backCallback=this::handleTerminalBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
        applyAppearance();
        root.post(() -> {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        Intent intent = new Intent(this, ShellService.class);
        startForegroundService(intent);
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
        view.setForceBlockCursor(true);
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
        if (service == null || !service.allowsSelection(managedTerminalRequest,session)) return;
        if(terminal!=null&&terminal.getCurrentSession()==session){
            service.selectSession(session);terminal.setVisibility(View.VISIBLE);terminal.setCursorTrailView(cursorTrail);updateTitle();return;
        }
        for(java.util.Iterator<java.util.Map.Entry<TerminalSession,TerminalView>> entries=terminalViews.entrySet().iterator();entries.hasNext();){
            java.util.Map.Entry<TerminalSession,TerminalView> entry=entries.next();
            if(service.getSessions().contains(entry.getKey()))continue;
            entry.getValue().setCursorTrailView(null);
            entry.getValue().setTerminalCursorBlinkerState(false,true);
            terminalArea.removeView(entry.getValue());entries.remove();
        }
        pendingPaste.selectionChanged(session);
        if (extraKeys != null) extraKeys.cancelPendingPresses();
        if (terminal != null) {
            terminal.stopTextSelectionMode();
            terminal.setCursorTrailView(null);
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
                if (cursorTrail != null) cursorTrail.bringToFront();
            }
            terminalViews.put(session, selected);
        }
        terminal = selected;
        terminal.updateAccessibilityState();
        terminal.setVisibility(View.VISIBLE);
        terminal.setTextSize(TerminalFontSize.pixels(fontSize, getResources().getDisplayMetrics().density));
        terminal.attachSession(session);
        terminal.setCursorTrailView(cursorTrail);
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
        if (isSessionTerminal() || service == null || !service.environmentReady) return;
        dismissActiveSheet();
        ArrayList<TerminalSession> windows = new ArrayList<>(service.getOrdinarySessions());
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("终端窗口", "Terminal windows"), null);

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
                        oldView.setCursorTrailView(null);
                        oldView.setTerminalCursorBlinkerState(false, true);
                        terminalArea.removeView(oldView);
                    }
                    TerminalSession replaced = service.reconnectSession(window);
                    selectWindow(replaced);
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
            service.createSession();
            selectWindow(service.session);
        });
        sheet.body.addView(newWinBtn, LumaUi.vSpace(this, 14));

        activeSheet = sheet;
        sheet.dialog.setOnDismissListener(dialog -> { if (activeSheet == sheet) windowDots.clear(); });
        sheet.show();
    }

    private void renameWindow(TerminalSession target) {
        if (isSessionTerminal() || service == null || !service.allowsSelection(null,target)) return;
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("重命名窗口", "Rename window"),
            com.termux.terminal.LumaLang.isZh() ? ("为「" + target.mSessionName + "」设置便于识别的窗口名称") : ("Set a display name for \"" + target.mSessionName + "\""));
        EditText input = LumaUi.inputField(this, target.mSessionName, com.termux.terminal.LumaLang.tr("输入窗口名称", "Enter window name"));
        sheet.body.addView(input, LumaUi.vSpace(this, 2));

        TextView cancelBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("取消", "Cancel"), v -> sheet.dismiss());
        TextView saveBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("保存", "Save"), v -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                target.mSessionName = name;
                updateTitle();
            }
            sheet.dismiss();
        });
        sheet.body.addView(LumaUi.buttonRow(this, cancelBtn, saveBtn), LumaUi.vSpace(this, 14));
        activeSheet = sheet;
        sheet.show();
    }

    private void confirmCloseWindow(TerminalSession closing) {
        if (isSessionTerminal() || service == null || !service.allowsSelection(null,closing)) return;
        dismissActiveSheet();
        boolean isLast = service.getOrdinarySessions().size() == 1;
        boolean guiEntry = managedTerminalRequest != null;
        String desc = (com.termux.terminal.LumaLang.isZh()
            ? ("关闭后，「" + closing.mSessionName + "」内的前台进程与终端会话将立即结束。")
            : ("Closing \"" + closing.mSessionName + "\" immediately terminates its foreground processes and terminal session."))
            + (isLast ? (guiEntry
                ? com.termux.terminal.LumaLang.tr("\n这是最后一个普通窗口，关闭后返回会话。", "\nThis is the last regular window; closing it returns to the session.")
                : com.termux.terminal.LumaLang.tr("\n这是最后一个普通窗口，关闭后退出终端。", "\nThis is the last regular window; closing it exits the terminal.")) : "");
        LumaUi.Sheet sheet = new LumaUi.Sheet(this,
            com.termux.terminal.LumaLang.isZh() ? ("关闭 " + closing.mSessionName + "？") : ("Close " + closing.mSessionName + "?"), desc);
        TextView cancelBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("保留窗口", "Keep window"), v -> sheet.dismiss());
        TextView closeBtn = LumaUi.dangerButton(this, isLast
            ? (guiEntry ? com.termux.terminal.LumaLang.tr("关闭并返回", "Close & return") : com.termux.terminal.LumaLang.tr("关闭并退出", "Close & exit"))
            : com.termux.terminal.LumaLang.tr("确认关闭", "Close"), v -> {
            sheet.dismiss();
            TerminalView view = terminalViews.remove(closing);
            if (view != null) {
                view.stopTextSelectionMode();
                view.setCursorTrailView(null);
                view.setTerminalCursorBlinkerState(false, true);
                terminalArea.removeView(view);
            }
            service.closeSession(closing);
            if (service.session == null) {
                if(service.stopAfterLastWindow(managedTerminalRequest)) {
                    service.unregisterListener(MainActivity.this);
                    stopService(new Intent(this, ShellService.class));
                }
                leaveTerminal();
            } else {
                selectWindow(service.session);
            }
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
        int winCount = service == null ? 0 : service.getOrdinarySessions().size();
        if (windowsIcon != null) windowsIcon.setCount(winCount);
        if (windowsButton != null) {
            windowsButton.setContentDescription(com.termux.terminal.LumaLang.isZh()
                ? ("多窗口，共 " + winCount + " 个窗口，点击切换，长按新建")
                : ("Windows (" + winCount + "), tap to switch, long-press for new"));
        }
    }

    private void updateTerminalBackdrop() {
        int color = Color.BLACK;
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
        wallpaper.setVisibility(View.GONE);
        tint.setVisibility(View.GONE);
        applyFontSize();
    }

    private void applyFontSize() {
        int pxSize = TerminalFontSize.pixels(fontSize, getResources().getDisplayMetrics().density);
        if (terminal != null) terminal.setTextSize(pxSize);
        for (TerminalView v : terminalViews.values()) {
            v.setTextSize(pxSize);
        }
    }

    private void loadWallpaper() { queueWallpaper(null); }

    private void queueWallpaper(Uri source) {
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

    private int runningServiceCount() {
        try {
            if (service == null || service.tasks() == null) return 0;
            JSONArray arr = service.tasks().listTasks().optJSONArray("services");
            if (arr == null) return 0;
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                String phase = arr.getJSONObject(i).optString("phase", "exited");
                if (!"exited".equals(phase)) count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private void settings() {
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, "Luma", null);

        View rowAppearance = LumaUi.menuRow(
            this,
            com.termux.terminal.LumaLang.tr("外观设置", "Appearance"),
            null,
            Math.round(fontSize) + com.termux.terminal.LumaLang.tr("号", " pt"),
            false,
            v -> {
                sheet.dismiss();
                appearance();
            }
        );
        sheet.body.addView(rowAppearance);

        int svcRunning = runningServiceCount();
        View rowServices = LumaUi.menuRow(
            this,
            com.termux.terminal.LumaLang.tr("后台服务", "Background services"),
            null,
            svcRunning > 0 ? (svcRunning + com.termux.terminal.LumaLang.tr(" 运行中", " running")) : com.termux.terminal.LumaLang.tr("空闲", "Idle"),
            svcRunning > 0,
            v -> {
                sheet.dismiss();
                servicesSheet();
            }
        );
        sheet.body.addView(rowServices, LumaUi.vSpace(this, 8));

        boolean storageOk = LinuxEnvironment.storageGranted();
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        View rowEnv = LumaUi.menuRow(
            this,
            com.termux.terminal.LumaLang.tr("环境信息", "Environment info"),
            null,
            (storageOk && batteryOk) ? com.termux.terminal.LumaLang.tr("状态完好", "Ready") : com.termux.terminal.LumaLang.tr("查看授权", "Check permissions"),
            storageOk && batteryOk,
            v -> {
                sheet.dismiss();
                environmentInfo();
            }
        );
        sheet.body.addView(rowEnv, LumaUi.vSpace(this, 8));

        activeSheet = sheet;
        sheet.show();
    }

    private void openWallpaperPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 20);
        } catch (ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_PICK);
                fallback.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                startActivityForResult(fallback, 20);
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("未找到可用的相册或文件选择器", "No gallery or file picker found"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void appearance() {
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

        boolean trailOn = TerminalCursorTrailPrefs.isEnabled(this);
        View trailRow = LumaUi.menuRow(
            this,
            com.termux.terminal.LumaLang.tr("光标拖尾", "Cursor trail"),
            null,
            trailOn ? com.termux.terminal.LumaLang.tr("已开启", "On") : com.termux.terminal.LumaLang.tr("已关闭", "Off"),
            trailOn,
            v -> {
                boolean next = !TerminalCursorTrailPrefs.isEnabled(this);
                TerminalCursorTrailPrefs.setEnabled(this, next);
                if (cursorTrail != null) cursorTrail.setTrailEnabled(next);
                if (terminal != null) terminal.syncCursorTrail();
                sheet.dismiss();
                appearance();
            }
        );
        sheet.body.addView(trailRow, LumaUi.vSpace(this, 8));

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

    private void servicesSheet() {
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this,
            com.termux.terminal.LumaLang.tr("后台托管服务", "Managed background services"),
            com.termux.terminal.LumaLang.tr("由 TaskRegistry 常驻托管的本地网关与 Web 服务", "Local gateways and web services managed persistently by TaskRegistry"));

        JSONArray services = null;
        try {
            if (service != null && service.tasks() != null) {
                services = service.tasks().listTasks().optJSONArray("services");
            }
        } catch (Exception ignored) {}

        if (services == null || services.length() == 0) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));
            TextView emptyTitle = new TextView(this);
            emptyTitle.setText(com.termux.terminal.LumaLang.tr("当前没有托管服务", "No managed services"));
            emptyTitle.setTextColor(LumaUi.TEXT_PRIMARY);
            emptyTitle.setTextSize(15f);
            emptyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            empty.addView(emptyTitle);

            TextView emptySub = new TextView(this);
            emptySub.setText(com.termux.terminal.LumaLang.tr("可在终端通过 lumactl start --port 0 -- <命令> 启动常驻 Web 界面或本地 API 网关。", "Start persistent web UIs or local API gateways in the terminal via lumactl start --port 0 -- <command>."));
            emptySub.setTextColor(LumaUi.TEXT_SECONDARY);
            emptySub.setTextSize(12.5f);
            emptySub.setPadding(0, dp(4), 0, 0);
            empty.addView(emptySub);
            sheet.body.addView(empty);
        } else {
            for (int i = 0; i < services.length(); i++) {
                JSONObject svc = services.optJSONObject(i);
                if (svc == null) continue;
                String id = svc.optString("id", "");
                String phase = svc.optString("phase", "exited");
                String ready = svc.optString("ready", "unknown");
                String baseUrl = svc.optString("baseUrl", "");
                int pid = svc.optInt("pid", -1);
                boolean active = !"exited".equals(phase);

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(16), dp(14), dp(16), dp(14));
                card.setBackground(LumaUi.roundedRect(
                    active ? LumaUi.BG_CARD_ACTIVE : LumaUi.BG_CARD,
                    dp(16),
                    1,
                    active ? LumaUi.STROKE_ACCENT : 0x12ffffff
                ));

                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView idView = new TextView(this);
                idView.setText(id);
                idView.setTextColor(LumaUi.TEXT_PRIMARY);
                idView.setTextSize(15.5f);
                idView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                topRow.addView(idView, new LinearLayout.LayoutParams(0, -2, 1f));

                String stateLabel = active
                    ? ("yes".equals(ready) ? com.termux.terminal.LumaLang.tr("运行中 · 已就绪", "Running · Ready") : com.termux.terminal.LumaLang.tr("启动中", "Starting"))
                    : com.termux.terminal.LumaLang.tr("已退出", "Exited");
                TextView badge = LumaUi.badge(
                    this,
                    stateLabel,
                    active ? LumaUi.ACCENT : LumaUi.TEXT_SECONDARY,
                    active ? LumaUi.ACCENT_SURFACE : 0x16ffffff
                );
                topRow.addView(badge);
                card.addView(topRow);

                TextView detail = new TextView(this);
                String meta = (pid > 0 ? ("PID " + pid) : com.termux.terminal.LumaLang.tr("进程已结束", "Process ended")) + (baseUrl.isEmpty() ? "" : ("  ·  " + baseUrl));
                detail.setText(meta);
                detail.setTextColor(LumaUi.TEXT_SECONDARY);
                detail.setTextSize(12.5f);
                detail.setPadding(0, dp(4), 0, dp(10));
                card.addView(detail);

                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.HORIZONTAL);

                if (TaskRegistry.isSafeHttpUrl(baseUrl) && active) {
                    TextView openBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("打开网页", "Open URL"), true, v -> {
                        if (!TaskRegistry.isSafeHttpUrl(baseUrl)) return;
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl)));
                        } catch (Exception ignored) {}
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = dp(8);
                    actions.addView(openBtn, lp);

                    TextView copyBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("复制地址", "Copy URL"), false, v -> {
                        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("URL", baseUrl));
                        Toast.makeText(this, com.termux.terminal.LumaLang.tr("已复制 ", "Copied ") + baseUrl, Toast.LENGTH_SHORT).show();
                    });
                    LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
                    lp2.rightMargin = dp(8);
                    actions.addView(copyBtn, lp2);
                }

                TextView logBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("日志", "Logs"), false, v -> {
                    sheet.dismiss();
                    serviceLogSheet(id);
                });
                LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-2, -2);
                logLp.rightMargin = dp(8);
                actions.addView(logBtn, logLp);

                if (active) {
                    TextView stopBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("停止", "Stop"), false, v -> {
                        new Thread(() -> {
                            try {
                                if (service != null && service.tasks() != null) {
                                    service.tasks().stopTask(id, -1L, false);
                                }
                            } catch (Exception ignored) {}
                            runOnUiThread(() -> {
                                if (!isDestroyed()) servicesSheet();
                            });
                        }).start();
                    });
                    stopBtn.setTextColor(LumaUi.DANGER);
                    actions.addView(stopBtn);
                }

                card.addView(actions);
                sheet.body.addView(card, LumaUi.vSpace(this, i == 0 ? 0 : 8));
            }
        }

        TextView closeBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("关闭", "Close"), v -> sheet.dismiss());
        sheet.body.addView(closeBtn, LumaUi.vSpace(this, 14));
        activeSheet = sheet;
        sheet.show();
    }

    private void serviceLogSheet(String id) {
        dismissActiveSheet();
        String logContent = (service != null && service.tasks() != null)
            ? service.tasks().readTailLog(id, 4096)
            : "";
        if (logContent.trim().isEmpty()) logContent = com.termux.terminal.LumaLang.tr("（暂无日志输出）", "(No log output yet)");
        LumaUi.Sheet sheet = new LumaUi.Sheet(this,
            com.termux.terminal.LumaLang.tr("服务日志 · ", "Service logs · ") + id,
            com.termux.terminal.LumaLang.tr("显示最近 4 KB 输出", "Showing latest 4 KB of output"));

        TextView logView = new TextView(this);
        logView.setText(logContent);
        logView.setTextColor(LumaUi.TEXT_PRIMARY);
        logView.setTextSize(11.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackground(LumaUi.roundedRect(0x16000000, dp(14), 1, 0x16ffffff));
        logView.setTextIsSelectable(true);
        sheet.body.addView(logView);

        TextView backBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("返回服务列表", "Back to services"), v -> {
            sheet.dismiss();
            servicesSheet();
        });
        sheet.body.addView(backBtn, LumaUi.vSpace(this, 12));
        activeSheet = sheet;
        sheet.show();
    }

    private void environmentInfo() {
        dismissActiveSheet();
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { version = ""; }
        LumaUi.Sheet sheet = new LumaUi.Sheet(this, com.termux.terminal.LumaLang.tr("环境信息", "Environment info"), "Luma " + version + " · Debian 13 (trixie) ARM64");

        LinearLayout coreCard = new LinearLayout(this);
        coreCard.setOrientation(LinearLayout.VERTICAL);
        coreCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        coreCard.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));

        TextView coreTitle = new TextView(this);
        coreTitle.setText(com.termux.terminal.LumaLang.tr("容器与目录映射", "Container & path mounts"));
        coreTitle.setTextColor(LumaUi.TEXT_PRIMARY);
        coreTitle.setTextSize(15f);
        coreTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        coreCard.addView(coreTitle);

        java.util.List<String> lans = LinuxEnvironment.lanIpv4Addresses(this);
        String lanText = lans.isEmpty() ? com.termux.terminal.LumaLang.tr("未连接局域网", "Not connected to LAN") : String.join(", ", lans);
        TextView coreDetail = new TextView(this);
        coreDetail.setText(com.termux.terminal.LumaLang.tr(
            "工作区目录：/workspace\n共享存储映射：/sdcard\n本机局域网 IPv4：",
            "Workspace: /workspace\nShared storage: /sdcard\nLAN IPv4: "
        ) + lanText);
        coreDetail.setTextColor(LumaUi.TEXT_SECONDARY);
        coreDetail.setTextSize(13f);
        coreDetail.setLineSpacing(dp(3), 1f);
        coreDetail.setPadding(0, dp(6), 0, 0);
        coreCard.addView(coreDetail);
        sheet.body.addView(coreCard);

        boolean storageGranted = LinuxEnvironment.storageGranted();
        LinearLayout storageCard = new LinearLayout(this);
        storageCard.setOrientation(LinearLayout.VERTICAL);
        storageCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        storageCard.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));

        LinearLayout stHeader = new LinearLayout(this);
        stHeader.setOrientation(LinearLayout.HORIZONTAL);
        stHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView stTitle = new TextView(this);
        stTitle.setText(com.termux.terminal.LumaLang.tr("手机文件访问", "Device file access"));
        stTitle.setTextColor(LumaUi.TEXT_PRIMARY);
        stTitle.setTextSize(15f);
        stTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stHeader.addView(stTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        stHeader.addView(LumaUi.badge(
            this,
            storageGranted ? com.termux.terminal.LumaLang.tr("已授权", "Granted") : com.termux.terminal.LumaLang.tr("未授权", "Not granted"),
            storageGranted ? LumaUi.ACCENT : LumaUi.WARN,
            storageGranted ? LumaUi.ACCENT_SURFACE : LumaUi.WARN_SURFACE
        ));
        storageCard.addView(stHeader);

        TextView stDesc = new TextView(this);
        stDesc.setText(com.termux.terminal.LumaLang.tr(
            "允许终端通过 /sdcard 读写手机共享存储。修改系统授权后，新建窗口即可生效。",
            "Allows reading and writing shared storage via /sdcard in the terminal. Open a new window after changing permission."
        ));
        stDesc.setTextColor(LumaUi.TEXT_SECONDARY);
        stDesc.setTextSize(12.5f);
        stDesc.setPadding(0, dp(5), 0, dp(10));
        storageCard.addView(stDesc);

        TextView stBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("所有文件访问设置", "All files access"), !storageGranted, v -> {
            sheet.dismiss();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("请在系统设置中管理所有文件访问权限", "Manage All files access in Android Settings"), Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout stBtnRow = new LinearLayout(this);
        stBtnRow.addView(stBtn);
        storageCard.addView(stBtnRow);
        sheet.body.addView(storageCard, LumaUi.vSpace(this, 8));

        PowerManager power = getSystemService(PowerManager.class);
        boolean batteryIgnored = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        boolean notifEnabled = getSystemService(NotificationManager.class).areNotificationsEnabled();

        LinearLayout bgCard = new LinearLayout(this);
        bgCard.setOrientation(LinearLayout.VERTICAL);
        bgCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        bgCard.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));

        LinearLayout bgHeader = new LinearLayout(this);
        bgHeader.setOrientation(LinearLayout.HORIZONTAL);
        bgHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView bgTitle = new TextView(this);
        bgTitle.setText(com.termux.terminal.LumaLang.tr("后台运行与通知授权", "Background execution & notifications"));
        bgTitle.setTextColor(LumaUi.TEXT_PRIMARY);
        bgTitle.setTextSize(15f);
        bgTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bgHeader.addView(bgTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        bgHeader.addView(LumaUi.badge(
            this,
            (batteryIgnored && notifEnabled) ? com.termux.terminal.LumaLang.tr("已授权", "Granted") : com.termux.terminal.LumaLang.tr("需关注", "Attention"),
            (batteryIgnored && notifEnabled) ? LumaUi.ACCENT : LumaUi.WARN,
            (batteryIgnored && notifEnabled) ? LumaUi.ACCENT_SURFACE : LumaUi.WARN_SURFACE
        ));
        bgCard.addView(bgHeader);

        TextView bgDesc = new TextView(this);
        bgDesc.setText(
            com.termux.terminal.LumaLang.tr("电池优化白名单：", "Battery exemption: ") + (batteryIgnored ? com.termux.terminal.LumaLang.tr("已允许忽略电池优化", "Exempted") : com.termux.terminal.LumaLang.tr("尚未忽略电池优化", "Not exempted")) + "\n" +
            com.termux.terminal.LumaLang.tr("前台常驻通知：", "Foreground notification: ") + (notifEnabled ? com.termux.terminal.LumaLang.tr("已开启", "Enabled") : com.termux.terminal.LumaLang.tr("已关闭（建议开启以防系统回收）", "Disabled (enable to reduce background kills)"))
        );
        bgDesc.setTextColor(LumaUi.TEXT_SECONDARY);
        bgDesc.setTextSize(12.5f);
        bgDesc.setLineSpacing(dp(3), 1f);
        bgDesc.setPadding(0, dp(5), 0, dp(10));
        bgCard.addView(bgDesc);

        LinearLayout bgActions = new LinearLayout(this);
        bgActions.setOrientation(LinearLayout.HORIZONTAL);

        TextView batBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("电池优化设置", "Battery settings"), !batteryIgnored, v -> {
            sheet.dismiss();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("请在应用信息中设置省电策略为无限制", "Set battery usage to Unrestricted in App Info"), Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams batLp = new LinearLayout.LayoutParams(-2, -2);
        batLp.rightMargin = dp(8);
        bgActions.addView(batBtn, batLp);

        TextView notifBtn = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("通知设置", "Notifications"), !notifEnabled, v -> {
            sheet.dismiss();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("请在应用信息中开启通知", "Enable notifications in App Info"), Toast.LENGTH_LONG).show();
            }
        });
        bgActions.addView(notifBtn);
        bgCard.addView(bgActions);
        sheet.body.addView(bgCard, LumaUi.vSpace(this, 8));

        boolean accessibilityEnabled = BackgroundAccessibilityService.isEnabled(this);
        boolean accessibilityConnected = BackgroundAccessibilityService.isConnected();
        LinearLayout accessibilityCard = new LinearLayout(this);
        accessibilityCard.setOrientation(LinearLayout.VERTICAL);
        accessibilityCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        accessibilityCard.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));
        LinearLayout accessibilityHeader = new LinearLayout(this);
        accessibilityHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView accessibilityTitle = new TextView(this);
        accessibilityTitle.setText(com.termux.terminal.LumaLang.tr("无障碍保活", "Accessibility keep-alive"));
        accessibilityTitle.setTextColor(LumaUi.TEXT_PRIMARY);
        accessibilityTitle.setTextSize(15f);
        accessibilityTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        accessibilityHeader.addView(accessibilityTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        accessibilityHeader.addView(LumaUi.badge(this,
            accessibilityConnected ? com.termux.terminal.LumaLang.tr("已授权", "Active") : accessibilityEnabled ? com.termux.terminal.LumaLang.tr("待连接", "Pending") : com.termux.terminal.LumaLang.tr("未授权", "Inactive"),
            accessibilityConnected ? LumaUi.ACCENT : LumaUi.WARN,
            accessibilityConnected ? LumaUi.ACCENT_SURFACE : LumaUi.WARN_SURFACE));
        accessibilityCard.addView(accessibilityHeader);
        TextView accessibilityDesc = new TextView(this);
        accessibilityDesc.setText((accessibilityConnected ? com.termux.terminal.LumaLang.tr("服务已连接。", "Service connected. ") : accessibilityEnabled ? com.termux.terminal.LumaLang.tr("已开启，等待系统连接。", "Enabled; waiting for system connection. ") : com.termux.terminal.LumaLang.tr("在系统无障碍设置中开启“Luma 后台保活”。", "Enable \"Luma Background Keep-Alive\" in Android Accessibility Settings. "))
            + com.termux.terminal.LumaLang.tr("帮助后台终端与 Web 服务保持可响应，效果取决于系统。仅接收 Luma 自身窗口事件，不读取窗口内容或操作屏幕。", "Helps background terminals and web services stay responsive (system-dependent). Receives only Luma's own window events; never reads screen content or performs actions."));
        accessibilityDesc.setTextColor(LumaUi.TEXT_SECONDARY);
        accessibilityDesc.setTextSize(12.5f);
        accessibilityDesc.setLineSpacing(dp(3), 1f);
        accessibilityDesc.setPadding(0, dp(5), 0, dp(10));
        accessibilityCard.addView(accessibilityDesc);
        TextView accessibilityButton = LumaUi.chipButton(this, com.termux.terminal.LumaLang.tr("无障碍保活设置", "Accessibility settings"), !accessibilityConnected, v -> {
            sheet.dismiss();
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (ActivityNotFoundException e) {
                Toast.makeText(this, com.termux.terminal.LumaLang.tr("请在系统无障碍设置中开启 Luma 后台保活", "Enable Luma Background Keep-Alive in Android Accessibility Settings"), Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout accessibilityActions = new LinearLayout(this);
        accessibilityActions.addView(accessibilityButton);
        accessibilityCard.addView(accessibilityActions);
        sheet.body.addView(accessibilityCard, LumaUi.vSpace(this, 8));

        TextView closeBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("知道了", "Got it"), v -> sheet.dismiss());
        sheet.body.addView(closeBtn, LumaUi.vSpace(this, 14));

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
        openTerminalIfReady();
        syncForegroundTerminal();
        updateTitle();
        if (cursorTrail != null) {
            cursorTrail.setTrailEnabled(TerminalCursorTrailPrefs.isEnabled(this));
            cursorTrail.setActive(true);
        }
        if (terminal != null) {
            terminal.updateAccessibilityState();
            terminal.setTerminalCursorBlinkerState(true, true);
            terminal.invalidate();
            terminal.syncCursorTrail();
        }
        boolean granted = LinuxEnvironment.storageGranted();
        if (granted != storageWasGranted) {
            storageWasGranted = granted;
            Toast.makeText(this, com.termux.terminal.LumaLang.tr("文件授权已更改，请新建窗口以更新 /sdcard 映射", "File access permission changed; open a new window to update /sdcard mount"), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (cursorTrail != null) cursorTrail.setActive(false);
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

    private void leaveTerminal() {
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        ForegroundTerminalTracker.clear(this, foregroundToken);
        if(isSessionTerminal()&&service!=null) {
            service.closeDedicatedWindow(managedTerminalRequest.windowId);
        }
        if(managedTerminalRequest!=null && isTaskRoot()) {
            startActivity(new Intent().setClassName(getPackageName(),"io.github.modao7067.luma.gui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
            return;
        }
        finish();
    }

    private void handleTerminalBack() {
        if(linkOpener!=null&&linkOpener.dismiss())return;
        if(activeSheet!=null&&activeSheet.dialog.isShowing()){dismissActiveSheet();return;}
        if (terminal != null && terminal.getRootWindowInsets() != null && terminal.getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        } else if(managedTerminalRequest!=null) {
            leaveTerminal();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override public void onBackPressed() { handleTerminalBack(); }

    @Override
    protected void onDestroy() {
        foregroundHandler.removeCallbacks(foregroundHeartbeat);
        ForegroundTerminalTracker.clear(this, foregroundToken);
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        pendingPaste.cancel();
        dismissActiveSheet();
        synchronized (wallpaperLock) { wallpaperGeneration++; }
        wallpaperWorker.shutdownNow();
        if (service != null) {
            service.unregisterListener(MainActivity.this);
        }
        if (cursorTrail != null) cursorTrail.setActive(false);
        for (TerminalView view : terminalViews.values()) {
            view.setCursorTrailView(null);
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
