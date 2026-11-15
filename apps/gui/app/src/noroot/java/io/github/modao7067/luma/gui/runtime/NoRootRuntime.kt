package io.github.modao7067.luma.gui.runtime

import android.content.Context
import io.github.modao7067.luma.GuiRuntimeBridge

internal object NoRootRuntime {
    fun environmentSettings(app: Context): EnvironmentSettingsState {
        val rootEnabled = DebianRootMode.isEnabled(app)
        val shizukuInstalled = !rootEnabled && io.github.modao7067.luma.ShizukuBridge.isInstalled(app)
        val shizukuRunning = shizukuInstalled && io.github.modao7067.luma.ShizukuBridge.isRunning(app)
        val shizukuAuthorized = shizukuRunning && io.github.modao7067.luma.ShizukuBridge.isAuthorized(app)
        val shizukuUid = if (shizukuAuthorized) io.github.modao7067.luma.ShizukuBridge.serverUid(app) else -1
        return EnvironmentSettingsState(
            android.os.Environment.isExternalStorageManager(),
            requireNotNull(app.getSystemService(android.os.PowerManager::class.java)).isIgnoringBatteryOptimizations(app.packageName),
            requireNotNull(app.getSystemService(android.app.NotificationManager::class.java)).areNotificationsEnabled(),
            io.github.modao7067.luma.BackgroundAccessibilityService.isEnabled(app),
            io.github.modao7067.luma.BackgroundAccessibilityService.isConnected(),
            GuiRuntimeBridge.lanIpv4Addresses(app),
            GuiRuntimeBridge.isWakeLockEnabled(app),
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning,
            shizukuAuthorized = shizukuAuthorized,
            shizukuUid = shizukuUid,
            rootModeEnabled = rootEnabled,
            suBinaryVisible = DebianRootMode.isSuBinaryVisible(),
        )
    }
    fun openShizuku(app: Context) = io.github.modao7067.luma.ShizukuBridge.openOrRequestAuthorization(app)
    fun setWakeLockEnabled(app: Context, enabled: Boolean) = GuiRuntimeBridge.setWakeLockEnabled(app, enabled)
    fun updateKeepAliveStatus(statusText: String) = GuiRuntimeBridge.updateSessionStatus(statusText)
    fun stopKeepAliveService(app: Context) = GuiRuntimeBridge.stopBackgroundService(app)
    fun syncGuestDns(app: Context) = GuiRuntimeBridge.syncDns(app)
    fun stageForRoot(app: Context) = GuiRuntimeBridge.stageForRoot(app)
    fun prepareForRootChroot(app: Context, onProgress: (String) -> Unit) =
        GuiRuntimeBridge.prepareEnvironmentHeadless(app) { msg -> onProgress(msg) }
    fun observeEnvironmentSettings(app: Context, onChanged: () -> Unit): AutoCloseable {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var closed = false
        val listener = Runnable { handler.post { if (!closed) onChanged() } }
        val prefs = app.applicationContext.getSharedPreferences("background", Context.MODE_PRIVATE)
        val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "wakeLockEnabled") listener.run()
        }
        val rootCloseable = DebianRootMode.observe { listener.run() }
        io.github.modao7067.luma.BackgroundAccessibilityService.addConnectionListener(listener)
        io.github.modao7067.luma.ShizukuBridge.addListener(listener)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        return AutoCloseable {
            closed = true
            rootCloseable.close()
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            io.github.modao7067.luma.ShizukuBridge.removeListener(listener)
            io.github.modao7067.luma.BackgroundAccessibilityService.removeConnectionListener(listener)
        }
    }
    fun isReady(app: Context) = GuiRuntimeBridge.isReady(app)
    fun connect(app: Context, token: String) = GuiRuntimeBridge.connect(app, token)
    fun stopCodexIfIdle(app: Context) = GuiRuntimeBridge.stopCodexIfIdle(app)
    fun connectOpenCode(app: Context, password: String, launcher: String) = GuiRuntimeBridge.connectOpenCode(app, password, launcher)
    fun stopOpenCodeIfIdle(app: Context) = GuiRuntimeBridge.stopOpenCodeIfIdle(app)
    fun connectNativeCli(app: Context, launcher: String, token: String, directory: String, task: String) = GuiRuntimeBridge.connectNativeCli(app, launcher, token, directory, task)
    fun stopNativeCli(app: Context, task: String) = GuiRuntimeBridge.stopNativeCli(app, task)
    fun copyFile(app: Context, path: String, destination: java.io.File, limit: Long) = GuiRuntimeBridge.copyFile(app, path, destination, limit)
    fun execute(app: Context, command: List<String>) = GuiRuntimeBridge.execute(app, command)
}
