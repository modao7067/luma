package io.github.modao7067.luma.gui.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class EnvironmentSettingsState(
    val storageGranted: Boolean,
    val batteryIgnored: Boolean,
    val notificationsEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val lanAddresses: List<String> = emptyList(),
    val wakeLockEnabled: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuAuthorized: Boolean = false,
    val shizukuUid: Int = -1,
    val rootModeEnabled: Boolean = false,
    val suBinaryVisible: Boolean = false,
)

internal data class RootSwitchState(val running: Boolean = false, val progress: String? = null, val error: String? = null)

private val idleRootSwitch: StateFlow<RootSwitchState> = MutableStateFlow(RootSwitchState()).asStateFlow()

internal enum class EnvironmentSetting { STORAGE, BATTERY, NOTIFICATIONS, ACCESSIBILITY, SHIZUKU }

internal interface EnvironmentSettingsAccess {
    fun read(): EnvironmentSettingsState
    fun observe(onChanged: () -> Unit): AutoCloseable
    fun open(setting: EnvironmentSetting)
    fun setWakeLock(enabled: Boolean) {}
    val rootSwitch: StateFlow<RootSwitchState> get() = idleRootSwitch
    fun startRootSwitch() {}
    suspend fun checkRootActive(): Boolean = false
}

internal class AndroidEnvironmentSettingsAccess(private val context: Context) : EnvironmentSettingsAccess {
    override fun read() = NoRootRuntime.environmentSettings(context)
    override fun observe(onChanged: () -> Unit) = NoRootRuntime.observeEnvironmentSettings(context, onChanged)
    override fun setWakeLock(enabled: Boolean) = NoRootRuntime.setWakeLockEnabled(context, enabled)
    override val rootSwitch: StateFlow<RootSwitchState> get() = DebianRootMode.switchState
    override fun startRootSwitch() { DebianRootMode.startSwitch(context) }
    override suspend fun checkRootActive(): Boolean = DebianRootMode.probeRootActive()
    override fun open(setting: EnvironmentSetting) {
        if (setting == EnvironmentSetting.SHIZUKU) {
            NoRootRuntime.openShizuku(context)
            return
        }
        val intent = environmentSettingsIntent(setting, context.packageName)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

internal fun environmentSettingsIntent(setting: EnvironmentSetting, packageName: String): Intent = when (setting) {
    EnvironmentSetting.STORAGE -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
    EnvironmentSetting.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    EnvironmentSetting.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    EnvironmentSetting.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    EnvironmentSetting.SHIZUKU -> Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
}
