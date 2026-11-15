package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.system.Os
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.modao7067.luma.gui.GuiProduct
import io.github.modao7067.luma.gui.session.CliCatalog
import io.github.modao7067.luma.gui.session.SessionSummary
import io.github.modao7067.luma.gui.session.formatSessionActivityTime
import io.github.modao7067.luma.gui.session.sortSessionsByActivity
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionSidebar(
    sessions: List<SessionSummary>,
    selected: String?,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
    enabledClis: Set<String> = CliCatalog.visibleIds.toSet(),
    onRename: (String, String) -> Unit = { _, _ -> },
    onArchive: (String) -> Unit = {},
    initiallyExpandedClis: Set<String> = emptySet(),
    onRenameRequested: ((SessionSummary) -> Unit)? = null,
    freezeOrder: Boolean = false,
    environment: String = GuiProduct.environment,
    onSwitchEnvironment: (() -> Unit)? = null,
    nowMs: Long? = null,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val liveNowMs by produceState(initialValue = nowMs ?: System.currentTimeMillis(), nowMs, inspection) {
        if (nowMs != null || inspection) {
            value = nowMs ?: System.currentTimeMillis()
        } else {
            while (true) {
                val cur = System.currentTimeMillis()
                value = cur
                val delayMs = (60_000L - (cur % 60_000L)).coerceIn(1_000L, 60_000L)
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }
    val preferences = remember(context, inspection) {
        if (inspection) null else context.getSharedPreferences("cockpit-navigation", Context.MODE_PRIVATE)
    }
    val expansion = remember { mutableStateMapOf<String, Boolean>().apply {
        CliCatalog.sortedIds.forEach { cli -> put(cli, preferences?.getBoolean("sidebar-expanded:$cli", false) ?: (cli in initiallyExpandedClis)) }
    } }
    val frozenIdsByCli = remember { HashMap<String, List<String>>() }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    val listState = rememberLazyListState()
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val bottomNavPadding = systemInsets.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 14.dp),
    ) {
        SidebarEnvironmentHeader(
            environment = environment,
            onClick = onSwitchEnvironment,
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomNavPadding + 16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("session-sidebar-list")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val maxFadePx = 12.dp.toPx()
                    val topFadePx = when {
                        !listState.canScrollBackward -> 0f
                        listState.firstVisibleItemIndex > 0 -> maxFadePx
                        else -> listState.firstVisibleItemScrollOffset.toFloat().coerceIn(0f, maxFadePx)
                    }
                    if (topFadePx > 0.5f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black,
                                startY = 0f,
                                endY = topFadePx,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(size.width, topFadePx),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (listState.canScrollForward) {
                        val bottomStart = (size.height - maxFadePx).coerceAtLeast(0f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startY = bottomStart,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, bottomStart),
                            size = Size(size.width, size.height - bottomStart),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) {
            CliCatalog.sortedIds.filter { it in enabledClis }.forEach { cli ->
                val rawChildren = sessions.filter { it.cli == cli }
                val children = if (!freezeOrder) {
                    sortSessionsByActivity(rawChildren).also { sorted -> frozenIdsByCli[cli] = sorted.map { it.id } }
                } else {
                    val existingOrder = frozenIdsByCli[cli]
                    if (existingOrder == null) {
                        sortSessionsByActivity(rawChildren).also { sorted -> frozenIdsByCli[cli] = sorted.map { it.id } }
                    } else {
                        val byId = rawChildren.associateBy { it.id }
                        val known = existingOrder.toHashSet()
                        val added = sortSessionsByActivity(rawChildren.filter { it.id !in known })
                        (added + existingOrder.mapNotNull { byId[it] }).also { merged -> frozenIdsByCli[cli] = merged.map { it.id } }
                    }
                }
                val hasSelected = children.any { it.id == selected }
                val expanded = expansion[cli] == true
                val expansionKey = "sidebar-expanded:$cli"
                item(key = "cli:$cli") {
                    val chevronRotationState = animateFloatAsState(if (expanded) 180f else 0f, spatialSpring(), label = "sidebar-cli-chevron")
                    Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(LumaCardShape(16.dp))
                                    .background(
                                        when {
                                            hasSelected && !expanded -> colors.secondaryContainer
                                            hasSelected -> colors.secondaryContainer.copy(alpha = 0.5f)
                                            else -> androidx.compose.ui.graphics.Color.Transparent
                                        },
                                    )
                                    .clickable {
                                        expansion[cli] = !expanded
                                        preferences?.edit()?.putBoolean(expansionKey, !expanded)?.apply()
                                    }
                                    .semantics { stateDescription = if (expanded) tr("已展开", "Expanded") else tr("已折叠", "Collapsed") }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProviderIcon(cli, selected = hasSelected)
                                Column(
                                    Modifier
                                        .padding(start = 10.dp, end = 6.dp)
                                        .weight(1f),
                                ) {
                                    Text(
                                        cliDisplayLabel(cli),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (hasSelected) colors.onSecondaryContainer else colors.onSurface,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                }
                                Surface(
                                    color = if (hasSelected) colors.secondaryContainer else colors.surfaceContainerHigh,
                                    shape = LumaCardShape(10.dp),
                                    modifier = Modifier.padding(end = 6.dp),
                                ) {
                                    Text(
                                        children.size.toString(),
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (hasSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                    )
                                }
                                CompositionLocalProvider(
                                    LocalContentColor provides if (hasSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                ) {
                                    MenuIcon(MenuSymbol.DOWN, size = 18.dp, rotationProvider = { chevronRotationState.value })
                                }
                            }
                            LiquidSurface(
                                Modifier
                                    .size(38.dp)
                                    .focusProperties { canFocus = false }
                                    .liquidClickable(cornerRadius = 19.dp) {
                                        onNew(cli)
                                    }
                                    .semantics { contentDescription = tr("新建 $cli 会话", "New $cli session") },
                                cornerRadius = 19.dp,
                                tonalTint = colors.surfaceContainerHigh,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    ComposerGlyph("plus")
                                }
                            }
                        }
                    }
                }
                if (children.isEmpty()) item(key = "empty:$cli") {
                    SidebarChildReveal(expanded) {
                        Text(tr("暂无会话", "No sessions"), Modifier.padding(start = 48.dp, top = 12.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                children.forEach { session ->
                    item(key = "session:${session.id}") {
                        SidebarChildReveal(expanded) {
                            Column(Modifier.padding(start = 28.dp, top = 2.dp, bottom = 2.dp)) {
                                val active = selected == session.id
                                val rowRadius = 16.dp
                                var menuOpen by remember { mutableStateOf(false) }
                                Box {
                                    Surface(
                                        shape = LumaCardShape(rowRadius),
                                        color = if (active) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusProperties { canFocus = false }
                                            .clip(LumaCardShape(rowRadius))
                                            .combinedClickable(
                                                onClick = { onOpen(session.id) },
                                                onLongClick = { menuOpen = true },
                                            )
                                            .semantics {
                                                this.selected = active
                                                if (session.hasUnreadDot) stateDescription = tr("未读", "Unread")
                                            },
                                    ) {
                                        SidebarSessionRowContent(session, active = active, nowMs = liveNowMs)
                                    }
                                    OriginMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                                        ActionMenuItem(
                                            label = tr("重命名", "Rename"),
                                            icon = MenuSymbol.EDIT,
                                            iconContainer = true,
                                            onClick = {
                                                menuOpen = false
                                                if (onRenameRequested != null) onRenameRequested(session) else renaming = session
                                            },
                                        )
                                        ActionMenuItem(
                                            label = tr("归档", "Archive"),
                                            icon = MenuSymbol.DELETE,
                                            destructive = true,
                                            iconContainer = true,
                                            onClick = {
                                                menuOpen = false
                                                onArchive(session.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { target -> SessionRenameDialog(target, onRename, onDismiss = { renaming = null }) }
}

internal data class SidebarEnvInfo(
    val osName: String,
    val modeBadge: String,
    val versionArch: String,
)

internal fun defaultSidebarEnvInfo(environment: String): SidebarEnvInfo {
    val arch = runCatching { Os.uname().machine }.getOrNull()?.takeIf { it.isNotBlank() } ?: "aarch64"
    return if (environment == "root") {
        SidebarEnvInfo("Ubuntu", "ROOT", "26.04.01-LTS · $arch")
    } else {
        SidebarEnvInfo("Debian", "PROOT", "13-Trixie · $arch")
    }
}

internal fun parseOsReleaseContent(raw: String, environment: String): SidebarEnvInfo? {
    val arch = runCatching { Os.uname().machine }.getOrNull()?.takeIf { it.isNotBlank() } ?: "aarch64"
    val map = HashMap<String, String>()
    raw.lineSequence().forEach { line ->
        val idx = line.indexOf('=')
        if (idx > 0) {
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim().removeSurrounding("\"")
            map[k] = v
        }
    }
    val fullName = map["NAME"]?.takeIf { it.isNotBlank() } ?: return null
    val name = fullName.substringBefore(' ').takeIf { it.isNotBlank() } ?: return null
    val verRaw = map["VERSION"]
        ?: map["PRETTY_NAME"]?.removePrefix(fullName)?.removePrefix(name)?.trim()
        ?: map["VERSION_ID"].orEmpty()
    val baseVer = verRaw
        .substringBefore('(')
        .trim()
        .replace(Regex("""^(\d+\.\d+)\.(\d)\b"""), "$1.0$2")
        .replace(" LTS", "-LTS")
        .ifBlank { map["VERSION_ID"].orEmpty() }
    val codename = (map["VERSION_CODENAME"]
        ?: verRaw.substringAfter('(', "").substringBefore(')').trim())
        .takeIf { it.isNotBlank() }
    val codenameLabel = codename?.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString()
    }
    val isDebian = map["ID"].equals("debian", ignoreCase = true) || name.equals("Debian", ignoreCase = true)
    val verClean = if (isDebian && codenameLabel != null && baseVer.isNotBlank() && !baseVer.contains(codename, ignoreCase = true)) {
        "$baseVer-$codenameLabel"
    } else {
        baseVer.ifBlank { codenameLabel.orEmpty() }
    }
    val badge = if (environment == "root") "ROOT" else "PROOT"
    val sub = if (verClean.isNotBlank()) "$verClean · $arch" else arch
    return SidebarEnvInfo(name, badge, sub)
}

@Composable
private fun SidebarEnvironmentHeader(
    environment: String,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    var rootChrootEnabled by remember(environment, context) {
        mutableStateOf(!inspection && environment == "noroot" && io.github.modao7067.luma.gui.runtime.DebianRootMode.isEnabled(context))
    }
    if (!inspection && environment == "noroot") {
        DisposableEffect(context) {
            val sub = io.github.modao7067.luma.gui.runtime.DebianRootMode.observe {
                rootChrootEnabled = io.github.modao7067.luma.gui.runtime.DebianRootMode.isEnabled(context)
            }
            onDispose { sub.close() }
        }
    }
    val effectiveBadge = when {
        environment == "root" -> "ROOT"
        rootChrootEnabled -> "CHROOT"
        else -> "PROOT"
    }
    var envInfo by remember(environment) { mutableStateOf(defaultSidebarEnvInfo(environment)) }
    if (!inspection) {
        LaunchedEffect(environment, rootChrootEnabled) {
            withContext(Dispatchers.IO) {
                val raw = runCatching {
                    if (environment == "root") {
                        val direct = File("/data/local/linux/ubuntu/etc/os-release")
                        if (direct.canRead()) {
                            direct.readText()
                        } else {
                            val tmp = File(context.cacheDir, "sidebar-os-release.tmp")
                            io.github.modao7067.luma.root.GuiRuntimeBridge.copyFile(context, "/etc/os-release", tmp, 4096)
                            tmp.readText().also { tmp.delete() }
                        }
                    } else {
                        sequenceOf(
                            "linux/debian/etc/os-release",
                            "linux/debian/usr/lib/os-release",
                            "usr/var/lib/proot-distro/installed-rootfs/debian/etc/os-release",
                        ).map { File(context.filesDir, it) }
                            .firstOrNull { it.canRead() }
                            ?.readText()
                    }
                }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    parseOsReleaseContent(raw, environment)?.let { envInfo = it }
                }
            }
        }
    }
    val displayedInfo = envInfo.copy(modeBadge = effectiveBadge)
    val badgeBg = if (dark) Color(0xFFEEEcf8) else Color(0xFF464652)
    val badgeFg = if (dark) Color(0xFF282832) else Color(0xFFFAFAFC)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumaCardShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        envInfo.osName,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = badgeBg,
                    ) {
                        Text(
                            displayedInfo.modeBadge,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            ),
                            color = badgeFg,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    envInfo.versionArch,
                    Modifier.padding(top = 3.dp, start = 1.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp),
            thickness = 1.dp,
            color = colors.outlineVariant.copy(alpha = 0.42f),
        )
    }
}

internal fun subAgentIdentityKey(parentSessionId: String, agentId: String) = "agent:${parentSessionId.length}:$parentSessionId:$agentId"
internal fun sidebarAgentKey(sessionId: String, agentId: String) = subAgentIdentityKey(sessionId, agentId)
internal fun parseSubAgentIdentityKey(key: String, parentSessionId: String): String? {
    val prefix = "agent:${parentSessionId.length}:$parentSessionId:"
    return if (key.startsWith(prefix)) key.removePrefix(prefix).takeIf { it.isNotEmpty() } else null
}

@Composable
private fun SidebarChildReveal(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(expanded, enter = expandVertically(spatialSizeSpring()) + fadeIn(),
        exit = shrinkVertically(spatialSizeSpring()) + fadeOut()) { content() }
}

@Composable
private fun SidebarSessionRowContent(session: SessionSummary, active: Boolean, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (active) colors.onSecondaryContainer else colors.onSurface,
            )
            val detail = formatSessionActivityTime(session.lastActiveAt, nowMs)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.onSecondaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant,
                )
            }
        }
        if (session.hasUnreadDot) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(colors.primary, CircleShape)
                    .testTag("session-unread-dot:${session.id}"),
            )
        }
    }
}
