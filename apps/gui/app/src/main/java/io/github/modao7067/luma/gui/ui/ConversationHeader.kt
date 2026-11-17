package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import io.github.modao7067.luma.gui.session.*

@Composable
internal fun ConversationHeader(
    view: SessionView?,
    actions: CockpitActions,
    onDrawer: () -> Unit,
    onNew: () -> Unit,
    onTerminal: () -> Unit = { actions.openShell(actions.environment, "/workspace") },
    onSettings: () -> Unit = {},
    onUsage: () -> Unit = {},
    previewDashboard: Boolean = false,
    previewMore: Boolean = false,
    blankCli: String = "Codex",
    hasError: Boolean = view?.error != null,
) {
    val chromeHeight = FloatingChromeSize
    val chromeRadius = FloatingChromeRadius
    var more by remember { mutableStateOf(previewMore) }
    var dashboard by remember { mutableStateOf(previewDashboard) }
    var renaming by remember(view?.summary?.id) { mutableStateOf(false) }
    var bridgeUpdateConfirm by remember(view?.summary?.id) { mutableStateOf(false) }
    val bridgeUpdateOffered = view?.let { it.cliServiceUpdateRequired || it.cliServiceUpdateAvailable } == true
    if (!bridgeUpdateOffered && bridgeUpdateConfirm) {
        SideEffect { bridgeUpdateConfirm = false }
    }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlass(
            Modifier
                .size(chromeHeight)
                .liquidClickable(cornerRadius = chromeRadius, onClick = onDrawer)
                .semantics { contentDescription = tr("打开会话侧边栏", "Open session sidebar") },
            cornerRadius = chromeRadius,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HeaderGlyph(false)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LiquidGlass(
                Modifier
                    .fillMaxWidth()
                    .height(chromeHeight)
                    .liquidClickable(cornerRadius = chromeRadius, pressScale = 0.98f) {
                        dashboard = true
                        if (view?.connected == true) actions.refreshDashboard()
                    }
                    .semantics { contentDescription = tr("会话 Dashboard", "Session dashboard") },
                cornerRadius = chromeRadius,
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(
                            view?.summary?.title?.let { if (it == "未命名会话" || it == "Untitled session") tr("未命名会话", "Untitled session") else it } ?: tr("新会话", "New session"),
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = modelEffortLabel(
                            view?.summary?.let { it.modelLabel.ifBlank { it.model } }.orEmpty(),
                            view?.effort.orEmpty(),
                        ).ifBlank { cliDisplayLabel(view?.summary?.cli ?: blankCli) }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (view != null || hasError) {
                        val dotColor = when {
                            hasError -> colors.error
                            view?.activeTurnId != null -> colors.primary
                            view?.connected == true -> colors.tertiary
                            else -> colors.outline
                        }
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size(7.dp)
                                .background(dotColor, CircleShape)
                                .semantics { stateDescription = if (hasError) tr("连接异常", "Connection error") else if (view?.connected == true) tr("已连接", "Connected") else tr("连接中", "Connecting") },
                        )
                    }
                }
            }
            OriginMenu(dashboard, { dashboard = false }, width = 300.dp, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Dashboard", style = MaterialTheme.typography.titleMedium)
                    if (view != null) {
                        Surface(
                            color = if (view.connected) colors.secondaryContainer.copy(alpha = 0.7f) else colors.surfaceContainerHighest,
                            shape = LumaCardShape(10.dp),
                        ) {
                            Text(
                                view.connection,
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (view.connected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (view != null) {
                    val visibleMetrics = dashboardMetrics(view.metrics)
                    if (visibleMetrics.isNotEmpty()) {
                        Surface(
                            color = colors.surfaceContainer.copy(alpha = 0.55f),
                            shape = LumaCardShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column(
                                Modifier.heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                visibleMetrics.forEach { metric ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(metric.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        Text(metric.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
                    ActionMenuItem(tr("刷新", "Reconnect"), MenuSymbol.REFRESH, iconContainer = true, onClick = actions::reconnect)
                }
                ActionMenuItem(tr("终端", "Terminal"), MenuSymbol.TERMINAL, iconContainer = true, enabled = view?.let { it.canOpenTui } == true, onClick = { dashboard = false; actions.openTui() })
            }
        }
        Box {
            LiquidGlass(
                Modifier
                    .size(chromeHeight)
                    .liquidClickable(cornerRadius = chromeRadius, onClick = { more = true })
                    .semantics { contentDescription = tr("更多", "More") },
                cornerRadius = chromeRadius,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HeaderGlyph(true)
                }
            }
            OriginMenu(more, { more = false }) {
                if (bridgeUpdateOffered) {
                    ActionMenuItem(tr("更新CLI桥服务", "Update CLI bridge"), MenuSymbol.REFRESH, warning = true, iconContainer = true,
                        description = if (view?.cliServiceUpdateRequired == true) tr("CLI 桥需要更新", "CLI bridge update required") else tr("可选 · 新版 CLI 桥可用", "Optional · Newer CLI bridge available"),
                        onClick = { more = false; bridgeUpdateConfirm = true })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                ActionMenuItem(tr("重命名", "Rename"), MenuSymbol.EDIT, iconContainer = true, enabled = view != null, onClick = { more = false; renaming = true })
                ActionMenuItem(tr("终端", "Terminal"), MenuSymbol.TERMINAL, iconContainer = true, enabled = true, onClick = { more = false; onTerminal() })
                ActionMenuItem(tr("浏览器", "Browser"), MenuSymbol.BROWSER, iconContainer = true, enabled = view != null, onClick = { more = false; actions.openBrowser() })
                ActionMenuItem(tr("文件", "Files"), MenuSymbol.FOLDER, iconContainer = true, enabled = view != null, onClick = { more = false; actions.browseFiles(view!!.summary.cwd) })
                ActionMenuItem(tr("用量", "Usage"), MenuSymbol.SPEED, iconContainer = true, enabled = true, onClick = { more = false; onUsage() })
                ActionMenuItem(tr("设置", "Settings"), MenuSymbol.SETTINGS, iconContainer = true, enabled = true, onClick = { more = false; onSettings() })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
                ActionMenuItem(tr("新建会话", "New session"), MenuSymbol.NEW_CHAT, iconContainer = true, enabled = true, onClick = { more = false; onNew() })
            }
        }
    }
    if (bridgeUpdateConfirm && bridgeUpdateOffered && view != null) {
        AlertDialog(
            onDismissRequest = { bridgeUpdateConfirm = false },
            title = { Text(tr("更新CLI桥服务", "Update CLI bridge service")) },
            text = {
                val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                ActionWindowPolicy(window, enabled = true, filterImeInsets = true)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(NativeCliRuntimePolicy.restartWarning)
                    if (view.cliServiceUpdateImpact.isNotBlank()) Text(view.cliServiceUpdateImpact)
                }
            },
            confirmButton = {
                TextButton(onClick = { bridgeUpdateConfirm = false; actions.restartCliBridge() }) {
                    Text(tr("停止并更新CLI桥", "Stop & update CLI bridge"))
                }
            },
            dismissButton = { TextButton(onClick = { bridgeUpdateConfirm = false }) { Text(tr("取消", "Cancel")) } },
        )
    }
    if (renaming && view != null) SessionRenameDialog(view.summary, actions::renameSession, onDismiss = { renaming = false })
}

@Composable
private fun HeaderGlyph(more: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.9.dp.toPx()
        for ((index, fraction) in listOf(0.24f, 0.5f, 0.76f).withIndex()) {
            if (more) {
                drawCircle(color, radius = 1.65.dp.toPx(), center = Offset(size.width / 2f, size.height * fraction))
            } else {
                val endPadding = if (index == 1) 4.dp.toPx() else 2.5.dp.toPx()
                drawLine(
                    color,
                    Offset(2.5.dp.toPx(), size.height * fraction),
                    Offset(size.width - endPadding, size.height * fraction),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}
