package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.modao7067.luma.gui.runtime.*

@Composable
internal fun SettingsNavigationRow(title: String, symbol: MenuSymbol, detail: String = "", onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Surface(onClick = { haptic.tick(); onClick() }, shape = LumaCardShape(20.dp), color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(38.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) { MenuIcon(symbol, size = 19.dp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            MenuIcon(MenuSymbol.DOWN, size = 20.dp, rotationDegrees = -90f)
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, symbol: MenuSymbol, detail: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Surface(onClick = { haptic.tick(); onToggle() }, enabled = enabled, shape = LumaCardShape(20.dp), color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(38.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) { MenuIcon(symbol, size = 19.dp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsActionRow(title: String, symbol: MenuSymbol, detail: String, action: String?, enabled: Boolean, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Surface(shape = LumaCardShape(20.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(38.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) { MenuIcon(symbol, size = 19.dp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (action != null) FilledTonalButton(onClick = { haptic.tick(); onAction() }, enabled = enabled) { Text(action) }
        }
    }
}

private data class EnvironmentRead(val state: EnvironmentSettingsState? = null, val readFailed: Boolean = false)
private fun readEnvironmentSettings(access: EnvironmentSettingsAccess): EnvironmentRead = try { EnvironmentRead(access.read()) }
    catch (_: Exception) { EnvironmentRead(readFailed = true) }

private enum class EnvironmentOpenError { SETTINGS, WAKE_LOCK }

internal fun shizukuStatusDetail(state: EnvironmentSettingsState): String = when {
    state.shizukuAuthorized -> when (state.shizukuUid) {
        0 -> tr("已连接 · Root (UID 0)", "Connected · Root (UID 0)")
        2000 -> tr("已连接 · ADB Shell (UID 2000)", "Connected · ADB Shell (UID 2000)")
        else -> if (state.shizukuUid > 0) tr("已连接 · UID ${state.shizukuUid}", "Connected · UID ${state.shizukuUid}") else tr("已连接 · ADB Shell", "Connected · ADB Shell")
    }
    state.shizukuRunning -> tr("未授权 · 点击授权", "Unauthorized · Tap to grant")
    state.shizukuInstalled -> tr("未运行 · 点击打开 Shizuku", "Not running · Tap to open Shizuku")
    else -> tr("未安装 · 点击查看", "Not installed · Tap to view")
}

@Composable
internal fun ColumnScope.EnvironmentSettingsContent(suppliedAccess: EnvironmentSettingsAccess? = null) {
    val context = LocalContext.current
    val access = suppliedAccess ?: remember(context) { AndroidEnvironmentSettingsAccess(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var read by remember(access) { mutableStateOf(readEnvironmentSettings(access)) }
    var openError by remember { mutableStateOf<EnvironmentOpenError?>(null) }
    val rootSwitch by access.rootSwitch.collectAsState()
    var confirmRootSwitch by remember { mutableStateOf(false) }
    var wasSwitching by remember { mutableStateOf(rootSwitch.running) }
    var rootActive by remember { mutableStateOf<Boolean?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle, access) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                read = readEnvironmentSettings(access)
                resumeTick++
            }
        }
        lifecycle.addObserver(observer)
        val connection = access.observe {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) read = readEnvironmentSettings(access)
        }
        onDispose { connection.close(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(rootSwitch.running) {
        if (wasSwitching && !rootSwitch.running) read = readEnvironmentSettings(access)
        wasSwitching = rootSwitch.running
    }
    val state = read.state
    val rootEnabled = state?.rootModeEnabled == true
    LaunchedEffect(rootEnabled, resumeTick, access) {
        if (!rootEnabled) {
            rootActive = null
        } else {
            rootActive = runCatching { access.checkRootActive() }.getOrDefault(false)
        }
    }
    fun open(setting: EnvironmentSetting) {
        try { access.open(setting); openError = null }
        catch (_: Exception) { openError = EnvironmentOpenError.SETTINGS }
    }
    val unavailable = if (!read.readFailed) tr("读取中…", "Loading…") else tr("读取失败", "Failed to load")
    val rootDetail = when {
        rootSwitch.running -> rootSwitch.progress ?: tr("正在切换…", "Switching…")
        state == null -> unavailable
        rootEnabled && rootActive == false -> tr("没检测到 Root，激活后就能继续用", "Root not detected; activate it to continue")
        rootEnabled -> tr("运行中", "Running")
        else -> tr("用 Root 运行内置 Debian，不用再开后台保活和 Shizuku", "Run built-in Debian via Root without foreground keep-alive or Shizuku")
    }
    SettingsActionRow(
        title = tr("Root 模式", "Root mode"),
        symbol = MenuSymbol.SHIELD,
        detail = rootDetail,
        action = if (state != null && !rootEnabled) tr("切换", "Switch") else null,
        enabled = !rootSwitch.running,
    ) { confirmRootSwitch = true }
    if (confirmRootSwitch) {
        AlertDialog(
            onDismissRequest = { confirmRootSwitch = false },
            title = { Text(tr("切换到 Root 模式？", "Switch to Root mode?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("切换后无法撤销。想换回普通模式只能清除 Luma 的数据，工作区文件会一起删掉。", "This cannot be undone. Reverting to standard mode requires clearing Luma app data, which also deletes workspace files."))
                    Text(tr("之后每次使用都需要 Root，临时 Root 重启后要重新激活。", "Root access will be required every time; temporary Root must be re-activated after reboot."))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRootSwitch = false; openError = null; access.startRootSwitch() }) { Text(tr("切换", "Switch")) }
            },
            dismissButton = { TextButton(onClick = { confirmRootSwitch = false }) { Text(tr("取消", "Cancel")) } },
        )
    }
    SettingsNavigationRow(tr("手机文件访问", "Device storage access"), MenuSymbol.FOLDER,
        state?.let { (if (it.storageGranted) tr("已授权", "Granted") else tr("未授权", "Not granted")) + " · /sdcard" } ?: unavailable) { open(EnvironmentSetting.STORAGE) }
    SettingsNavigationRow(tr("通知", "Notifications"), MenuSymbol.NOTIFICATIONS,
        state?.let { if (it.notificationsEnabled) tr("已开启", "On") else tr("已关闭", "Off") } ?: unavailable) { open(EnvironmentSetting.NOTIFICATIONS) }
    if (!rootEnabled) {
        SettingsNavigationRow(tr("Shizuku 提权", "Shizuku privilege"), MenuSymbol.TERMINAL,
            state?.let(::shizukuStatusDetail) ?: unavailable) { open(EnvironmentSetting.SHIZUKU) }
        SettingsNavigationRow(tr("忽略电池优化", "Ignore battery optimizations"), MenuSymbol.BATTERY,
            state?.let { if (it.batteryIgnored) tr("已允许", "Allowed") else tr("未允许", "Restricted") } ?: unavailable) { open(EnvironmentSetting.BATTERY) }
        SettingsNavigationRow(tr("无障碍保活", "Accessibility keep-alive"), MenuSymbol.ACCESSIBILITY,
            state?.let { if (it.accessibilityConnected) tr("已连接", "Connected") else if (it.accessibilityEnabled) tr("已开启，待系统连接", "Enabled, awaiting system bind") else tr("未开启", "Off") } ?: unavailable) { open(EnvironmentSetting.ACCESSIBILITY) }
        SettingsToggleRow(
            title = tr("后台唤醒锁", "Background wake lock"),
            symbol = MenuSymbol.LOCK,
            detail = state?.let { if (it.wakeLockEnabled) tr("已开启", "On") else tr("已关闭", "Off") } ?: unavailable,
            checked = state?.wakeLockEnabled == true,
            enabled = state != null && !rootSwitch.running,
        ) {
            val current = state ?: return@SettingsToggleRow
            try {
                access.setWakeLock(!current.wakeLockEnabled)
                read = readEnvironmentSettings(access)
                openError = null
            } catch (_: Exception) {
                openError = EnvironmentOpenError.WAKE_LOCK
            }
        }
    }
    val openErrorMessage = when (openError) {
        EnvironmentOpenError.SETTINGS -> tr("无法打开系统设置", "Unable to open system settings")
        EnvironmentOpenError.WAKE_LOCK -> tr("无法切换后台唤醒锁", "Unable to toggle background wake lock")
        null -> null
    }
    val readErrorMessage = if (read.readFailed) tr("无法读取环境状态", "Unable to read environment status") else null
    (openErrorMessage ?: rootSwitch.error?.let { tr("切换没成功：$it", "Switch failed: $it") } ?: readErrorMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Text(tr("环境信息", "Environment info"), Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = LumaCardShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (rootEnabled) "Debian 13 · ARM64 · Chroot" else "Debian 13 · ARM64 · PRoot", style = MaterialTheme.typography.titleMedium)
            Text(tr("工作区  /workspace\n手机存储  /sdcard", "Workspace  /workspace\nStorage  /sdcard"), style = MaterialTheme.typography.bodyMedium)
            Text(tr("局域网 IPv4", "LAN IPv4"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state?.lanAddresses?.joinToString("\n")?.ifEmpty { tr("未连接局域网", "Not connected to LAN") } ?: unavailable, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
