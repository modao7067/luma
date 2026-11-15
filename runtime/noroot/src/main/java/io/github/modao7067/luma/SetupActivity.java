package io.github.modao7067.luma;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;

public final class SetupActivity extends Activity {
    private ShellService service;
    private boolean bound, leaving, permissionFlow;
    private TextView status;
    private TextView start;
    private ProgressBar progress;
    private ImageView wallpaper;
    private LumaUi.Sheet activeSheet;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ShellService.LocalBinder) binder).get();
            poll.run();
        }
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };
    private final Runnable poll = new Runnable() {
        public void run() {
            if (isDestroyed() || leaving) return;
            if (service != null) {
                if (service.preparationError != null) {
                    status.setText(com.termux.terminal.LumaLang.tr("环境准备未完成：", "Environment setup incomplete: ") + service.preparationError);
                    status.setTextColor(LumaUi.DANGER);
                    progress.setVisibility(View.GONE);
                    start.setText(com.termux.terminal.LumaLang.tr("重新尝试", "Retry"));
                    start.setEnabled(true);
                    start.setAlpha(1f);
                    return;
                }
                if (service.environmentReady) {
                    permissions();
                    return;
                }
                status.setText(service.preparation);
                status.setTextColor(LumaUi.ACCENT);
            }
            handler.postDelayed(this, 350);
        }
    };

    private int dp(float n) {
        return LumaUi.dp(this, n);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        permissionFlow = state != null && state.getBoolean("permissionFlow");

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setRenderEffect(RenderEffect.createBlurEffect(dp(26), dp(26), Shader.TileMode.CLAMP));
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        View tint = new View(this);
        tint.setBackgroundColor(Color.TRANSPARENT);
        root.addView(tint, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(22), dp(32), dp(22), dp(32));
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left + dp(22), bars.top + dp(24), bars.right + dp(22), bars.bottom + dp(24));
            return insets;
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        card.setBackground(LumaUi.roundedRect(LumaUi.BG_SURFACE, dp(28), Math.max(1, dp(0.8f)), LumaUi.STROKE_SUBTLE));
        card.setElevation(dp(12));

        LinearLayout topBadgeRow = new LinearLayout(this);
        topBadgeRow.setOrientation(LinearLayout.HORIZONTAL);
        topBadgeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView versionBadge = LumaUi.badge(this, "ARM64", LumaUi.ACCENT, LumaUi.ACCENT_SURFACE);
        topBadgeRow.addView(versionBadge, new LinearLayout.LayoutParams(-2, -2));
        card.addView(topBadgeRow, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Luma");
        title.setTextSize(34f);
        title.setTextColor(LumaUi.TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(10), 0, 0);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText(com.termux.terminal.LumaLang.tr(
            "独立无 Root Debian 13 终端环境\n首次启动将离线解压基础系统与运行组件，卸载应用会清理私有环境数据。",
            "Standalone rootless Debian 13 terminal environment\nFirst launch unpacks the base system and runtime offline; uninstalling clears private environment data."
        ));
        intro.setTextColor(LumaUi.TEXT_SECONDARY);
        intro.setTextSize(14.5f);
        intro.setLineSpacing(dp(3), 1f);
        intro.setPadding(0, dp(8), 0, dp(18));
        card.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusBox.setBackground(LumaUi.roundedRect(LumaUi.BG_CARD, dp(16), 1, 0x12ffffff));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LumaUi.ACCENT));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progLp.rightMargin = dp(12);
        statusBox.addView(progress, progLp);

        status = new TextView(this);
        status.setText(com.termux.terminal.LumaLang.tr("就绪，点击下方按钮开始初始化", "Ready — tap below to initialize"));
        status.setTextColor(LumaUi.TEXT_SECONDARY);
        status.setTextSize(13.5f);
        statusBox.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(statusBox, new LinearLayout.LayoutParams(-1, -2));

        start = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("准备环境并开始", "Set up & start"), v -> begin());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, -2);
        startLp.topMargin = dp(20);
        card.addView(start, startLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        container.addView(card, cardLp);
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        root.post(() -> {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        });
        loadWallpaper();

        if (LinuxEnvironment.ready(this)) {
            permissions();
            return;
        }
        if (state != null && state.getBoolean("started")) begin();
    }

    private void loadWallpaper() {
        File custom = new File(getFilesDir(), "wallpaper");
        if (!custom.isFile()) {
            wallpaper.setImageDrawable(null);
            return;
        }
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                bitmap = WallpaperImages.decode(custom, metrics.widthPixels, metrics.heightPixels);
            } catch (IOException | OutOfMemoryError failure) {
                android.util.Log.w("SetupWallpaper", "Optional wallpaper unavailable", failure);
            }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (!isDestroyed()) wallpaper.setImageBitmap(result);
            });
        }, "SetupWallpaper").start();
    }

    private void begin() {
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
            stopService(new Intent(this, ShellService.class));
        }
        start.setEnabled(false);
        start.setAlpha(0.55f);
        start.setText(com.termux.terminal.LumaLang.tr("正在解压环境…", "Extracting environment…"));
        progress.setVisibility(View.VISIBLE);
        status.setText(com.termux.terminal.LumaLang.tr("准备 Debian 环境…", "Preparing Debian environment…"));
        status.setTextColor(LumaUi.ACCENT);
        notificationPermission();
        Intent intent = new Intent(this, ShellService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
    }

    private void notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(0).getBoolean("notificationAsked", false)) {
            getPreferences(0).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void dismissActiveSheet() {
        if (activeSheet != null) {
            activeSheet.dismiss();
            activeSheet = null;
        }
    }

    private void permissions() {
        if (leaving || permissionFlow) return;
        if (getSharedPreferences("setup", 0).getBoolean("storageExplained", false)) {
            openTerminal();
            return;
        }
        permissionFlow = true;
        dismissActiveSheet();
        LumaUi.Sheet sheet = new LumaUi.Sheet(this,
            com.termux.terminal.LumaLang.tr("访问手机文件", "Access device files"),
            com.termux.terminal.LumaLang.tr("开启后可在终端内通过 /sdcard 直接读写手机共享存储；暂不开启也可正常使用私有 /workspace。", "Enable to read and write shared storage directly via /sdcard in the terminal; you can still use the private /workspace without it."));
        sheet.setDismissible(false);
        TextView skipBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("暂不开启", "Not now"), v -> {
            sheet.dismiss();
            finishPermission();
        });
        TextView grantBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("前往授权", "Grant access"), v -> {
            sheet.dismiss();
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())), 30);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 30);
                } catch (ActivityNotFoundException ignored) {
                    finishPermission();
                }
            }
        });
        sheet.body.addView(LumaUi.buttonRow(this, skipBtn, grantBtn), LumaUi.vSpace(this, 6));
        activeSheet = sheet;
        sheet.show();
    }

    private void finishPermission() {
        getSharedPreferences("setup", 0).edit().putBoolean("storageExplained", true).apply();
        permissionFlow = false;
        openTerminal();
    }

    private void openTerminal() {
        if (leaving) return;
        leaving = true;
        if (!getSharedPreferences("setup", 0).getBoolean("batteryExplained", false)
            && !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            dismissActiveSheet();
            LumaUi.Sheet sheet = new LumaUi.Sheet(this,
                com.termux.terminal.LumaLang.tr("允许后台持续运行", "Allow background execution"),
                com.termux.terminal.LumaLang.tr("将 Luma 加入电池优化白名单，可减少切到后台或锁屏时终端会话与本地服务被系统冻结中断。", "Exempting Luma from battery optimization reduces terminal and local service suspension while in background or locked."));
            sheet.setDismissible(false);
            TextView skipBtn = LumaUi.secondaryButton(this, com.termux.terminal.LumaLang.tr("暂不设置", "Not now"), v -> {
                sheet.dismiss();
                batteryExplained();
                leaving = false;
                openTerminal();
            });
            TextView allowBtn = LumaUi.primaryButton(this, com.termux.terminal.LumaLang.tr("前往设置", "Open settings"), v -> {
                sheet.dismiss();
                batteryExplained();
                try {
                    startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())), 31);
                } catch (ActivityNotFoundException e) {
                    leaving = false;
                    openTerminal();
                }
            });
            sheet.body.addView(LumaUi.buttonRow(this, skipBtn, allowBtn), LumaUi.vSpace(this, 6));
            activeSheet = sheet;
            sheet.show();
            return;
        }
        notificationPermission();
        Intent terminal=new Intent(this,MainActivity.class);
        String ticket=getIntent().getStringExtra(ManagedTerminalRequest.EXTRA_TICKET);
        if(ticket!=null)terminal.putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket);
        startActivity(terminal);
        finish();
    }

    private void batteryExplained() {
        getSharedPreferences("setup", 0).edit().putBoolean("batteryExplained", true).apply();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 30) finishPermission();
        if (request == 31) {
            leaving = false;
            openTerminal();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("started", bound);
        state.putBoolean("permissionFlow", false);
    }

    @Override
    protected void onDestroy() {
        dismissActiveSheet();
        handler.removeCallbacksAndMessages(null);
        if (bound) unbindService(connection);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
