package io.github.modao7067.luma.gui.ui

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
    Surface(onClick = onClick, shape = LumaCardShape(20.dp), color = colors.surfaceContainerLow,
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
    Surface(onClick = onToggle, enabled = enabled, shape = LumaCardShape(20.dp), color = colors.surfaceContainerLow,
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
            if (action != null) FilledTonalButton(onClick = onAction, enabled = enabled) { Text(action) }
        }
    }
}

private data class EnvironmentRead(val state: EnvironmentSettingsState? = null, val error: String? = null)
private fun readEnvironmentSettings(access: EnvironmentSettingsAccess): EnvironmentRead = try { EnvironmentRead(access.read()) }
    catch (_: Exception) { EnvironmentRead(error = "无法读取环境状态") }

internal fun shizukuStatusDetail(state: EnvironmentSettingsState): String = when {
    state.shizukuAuthorized -> when (state.shizukuUid) {
        0 -> "已连接 · Root (UID 0)"
        2000 -> "已连接 · ADB Shell (UID 2000)"
        else -> if (state.shizukuUid > 0) "已连接 · UID ${state.shizukuUid}" else "已连接 · ADB Shell"
    }
    state.shizukuRunning -> "未授权 · 点击授权"
    state.shizukuInstalled -> "未运行 · 点击打开 Shizuku"
    else -> "未安装 · 点击查看"
}

@Composable
internal fun ColumnScope.EnvironmentSettingsContent(suppliedAccess: EnvironmentSettingsAccess? = null) {
    val context = LocalContext.current
    val access = suppliedAccess ?: remember(context) { AndroidEnvironmentSettingsAccess(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var read by remember(access) { mutableStateOf(readEnvironmentSettings(access)) }
    var openError by remember { mutableStateOf<String?>(null) }
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
        catch (_: Exception) { openError = "无法打开系统设置" }
    }
    val unavailable = if (read.error == null) "读取中…" else "读取失败"
    val rootDetail = when {
        rootSwitch.running -> rootSwitch.progress ?: "正在切换…"
        state == null -> unavailable
        rootEnabled && rootActive == false -> "没检测到 Root，激活后就能继续用"
        rootEnabled -> "运行中"
        else -> "用 Root 运行内置 Debian，不用再开后台保活和 Shizuku"
    }
    SettingsActionRow(
        title = "Root 模式",
        symbol = MenuSymbol.SHIELD,
        detail = rootDetail,
        action = if (state != null && !rootEnabled) "切换" else null,
        enabled = !rootSwitch.running,
    ) { confirmRootSwitch = true }
    if (confirmRootSwitch) {
        AlertDialog(
            onDismissRequest = { confirmRootSwitch = false },
            title = { Text("切换到 Root 模式？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("切换后无法撤销。想换回普通模式只能清除 Luma 的数据，工作区文件会一起删掉。")
                    Text("之后每次使用都需要 Root，临时 Root 重启后要重新激活。")
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRootSwitch = false; openError = null; access.startRootSwitch() }) { Text("切换") }
            },
            dismissButton = { TextButton(onClick = { confirmRootSwitch = false }) { Text("取消") } },
        )
    }
    SettingsNavigationRow("手机文件访问", MenuSymbol.FOLDER,
        state?.let { (if (it.storageGranted) "已授权" else "未授权") + " · /sdcard" } ?: unavailable) { open(EnvironmentSetting.STORAGE) }
    SettingsNavigationRow("通知", MenuSymbol.NOTIFICATIONS,
        state?.let { if (it.notificationsEnabled) "已开启" else "已关闭" } ?: unavailable) { open(EnvironmentSetting.NOTIFICATIONS) }
    if (!rootEnabled) {
        SettingsNavigationRow("Shizuku 提权", MenuSymbol.TERMINAL,
            state?.let(::shizukuStatusDetail) ?: unavailable) { open(EnvironmentSetting.SHIZUKU) }
        SettingsNavigationRow("忽略电池优化", MenuSymbol.BATTERY,
            state?.let { if (it.batteryIgnored) "已允许" else "未允许" } ?: unavailable) { open(EnvironmentSetting.BATTERY) }
        SettingsNavigationRow("无障碍保活", MenuSymbol.ACCESSIBILITY,
            state?.let { if (it.accessibilityConnected) "已连接" else if (it.accessibilityEnabled) "已开启，待系统连接" else "未开启" } ?: unavailable) { open(EnvironmentSetting.ACCESSIBILITY) }
        SettingsToggleRow(
            title = "后台唤醒锁",
            symbol = MenuSymbol.LOCK,
            detail = state?.let { if (it.wakeLockEnabled) "已开启" else "已关闭" } ?: unavailable,
            checked = state?.wakeLockEnabled == true,
            enabled = state != null && !rootSwitch.running,
        ) {
            val current = state ?: return@SettingsToggleRow
            try {
                access.setWakeLock(!current.wakeLockEnabled)
                read = readEnvironmentSettings(access)
                openError = null
            } catch (_: Exception) {
                openError = "无法切换后台唤醒锁"
            }
        }
    }
    (openError ?: rootSwitch.error?.let { "切换没成功：$it" } ?: read.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Text("环境信息", Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = LumaCardShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (rootEnabled) "Debian 13 · ARM64 · Chroot" else "Debian 13 · ARM64 · PRoot", style = MaterialTheme.typography.titleMedium)
            Text("工作区  /workspace\n手机存储  /sdcard", style = MaterialTheme.typography.bodyMedium)
            Text("局域网 IPv4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state?.lanAddresses?.joinToString("\n")?.ifEmpty { "未连接局域网" } ?: unavailable, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
