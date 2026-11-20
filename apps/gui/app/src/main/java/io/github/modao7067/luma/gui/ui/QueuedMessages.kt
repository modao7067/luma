package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.*

@Composable
internal fun QueuedMessages(
    view: SessionView,
    actions: CockpitActions,
    modifier: Modifier = Modifier,
    onCollapsingChanged: ((Boolean) -> Unit)? = null,
) {
    var editing by remember(view.summary.id) { mutableStateOf<QueueEntry?>(null) }
    var discard by remember(view.summary.id) { mutableStateOf(false) }
    val hasItems = view.queue.isNotEmpty() || view.queueRecovery != null
    val visibilityState = remember(view.summary.id) { MutableTransitionState(hasItems) }
    visibilityState.targetState = hasItems
    val isCollapsing = remember(visibilityState) {
        derivedStateOf { !visibilityState.targetState && !visibilityState.isIdle }
    }.value
    SideEffect {
        onCollapsingChanged?.invoke(isCollapsing)
    }
    var retained by remember(view.summary.id) { mutableStateOf(view) }
    val seenIds = remember(view.summary.id) { HashSet<String>().apply { addAll(view.queue.map { it.id }) } }
    val arrivingIds = view.queue.map { it.id }.filterNot { it in seenIds }.toSet()
    SideEffect {
        if (hasItems) retained = view
        seenIds.addAll(view.queue.map { it.id })
    }
    val displayed = if (hasItems) view else retained
    AnimatedVisibility(
        visibleState = visibilityState,
        enter = expandVertically(spatialSizeSpring(), expandFrom = Alignment.Bottom) + fadeIn(spatialSpring()),
        exit = shrinkVertically(spatialSizeSpring(), shrinkTowards = Alignment.Bottom) + fadeOut(spatialSpring()),
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (displayed.queue.isNotEmpty()) Text(tr("待发消息 · ${displayed.queue.size}", "Queued · ${displayed.queue.size}"),
                Modifier.padding(start = 14.dp, top = 2.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            displayed.queue.forEach { entry ->
                key(entry.id) {
                    var expanded by remember { mutableStateOf(false) }
                    val isArriving = remember(entry.id) { entry.id in arrivingIds }
                    var docked by remember(entry.id) { mutableStateOf(!isArriving) }
                    LaunchedEffect(entry.id) { docked = true }
                    val dockScale by animateFloatAsState(
                        targetValue = if (docked) 1f else 0.96f,
                        animationSpec = spatialSpring(),
                        label = "queue-dock-scale",
                    )
                    val dockAlpha by animateFloatAsState(
                        targetValue = if (docked) 1f else 0f,
                        animationSpec = spatialSpring(),
                        label = "queue-dock-alpha",
                    )
                    val actionable = entry.canEdit || entry.canSteer || entry.canRemove || entry.canDismiss
                    val current = view.queue.any { it.id == entry.id }
                    val active = hasItems && current
                    val itemAnchor = rememberMorphAnchor()
                    val itemElastic = rememberElasticPress()
                    val dockStyle = PrismGlassStyle.dock(MaterialTheme.colorScheme)
                    Box(
                        Modifier.graphicsLayer {
                            scaleX = dockScale
                            scaleY = dockScale
                            alpha = dockAlpha
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .morphAnchor(
                                    anchor = itemAnchor,
                                    enabled = actionable && active,
                                    elastic = itemElastic,
                                    onOpen = { expanded = !expanded },
                                ),
                        ) {
                            LiquidGlass(
                                Modifier.fillMaxWidth(),
                                cornerRadius = 20.dp,
                                style = dockStyle,
                                shadow = GlassShadow.Dock,
                                press = itemElastic,
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            entry.text,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        )
                                        Text(
                                            entry.status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        MorphMenu(
                            expanded = expanded && active,
                            onDismiss = { expanded = false },
                            anchor = itemAnchor,
                            width = 228.dp,
                            placement = MorphPlacement.Start,
                            preferAbove = true,
                        ) {
                            val enabled = active && (entry.localSubmission || entry.localRecovery || (entry.hostOwned && !view.loading && !view.queueBusy) || (view.connected && !view.queueBusy && view.queueRecovery == null))
                            if (entry.canSteer) {
                                ActionMenuItem(
                                    tr("插话", "Steer"),
                                    MenuSymbol.SEND,
                                    iconContainer = true,
                                    enabled = enabled && view.activeTurnId != null,
                                    onClick = { expanded = false; actions.steerQueued(entry.id) },
                                )
                            }
                            if (entry.canEdit) {
                                ActionMenuItem(
                                    tr("编辑", "Edit"),
                                    MenuSymbol.EDIT,
                                    iconContainer = true,
                                    enabled = enabled,
                                    onClick = {
                                        expanded = false
                                        if (entry.localSubmission || entry.hostOwned || entry.localRecovery) actions.restoreQueuedToComposer(entry.id) else editing = entry
                                    },
                                )
                            }
                            if (entry.canRemove || entry.canDismiss) {
                                ActionMenuItem(
                                    tr("删除", "Remove"),
                                    MenuSymbol.DELETE,
                                    iconContainer = true,
                                    destructive = true,
                                    enabled = active && (entry.canDismiss || enabled),
                                    onClick = { expanded = false; actions.removeQueued(entry.id) },
                                )
                            }
                        }
                    }
                }
            }
            displayed.queueRecovery?.let { recovery ->
                val recoveryHaptic = LocalHapticFeedback.current
                LiquidGlass(
                    Modifier.fillMaxWidth().blockHorizontalPageSwipe(),
                    cornerRadius = 20.dp,
                    style = PrismGlassStyle.dock(MaterialTheme.colorScheme),
                    shadow = GlassShadow.Dock,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (recovery.phase == "removed") tr("未能追加，原消息已保留", "Could not append; original message kept") else tr("转发结果待确认，请核对会话和队列", "Forwarding unconfirmed; verify session and queue"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(recovery.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (recovery.phase == "removed") {
                                FilledTonalButton(
                                    enabled = hasItems && !view.queueBusy,
                                    onClick = {
                                        recoveryHaptic.tick()
                                        actions.resolveQueueRecovery(true)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) { Text(tr("恢复到草稿", "Restore to draft"), style = MaterialTheme.typography.labelMedium) }
                            } else {
                                FilledTonalButton(
                                    enabled = hasItems && !view.queueBusy,
                                    onClick = {
                                        recoveryHaptic.tick()
                                        actions.reconnect()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) { Text(tr("重新同步", "Resync"), style = MaterialTheme.typography.labelMedium) }
                            }
                            TextButton(
                                enabled = hasItems && !view.queueBusy,
                                onClick = {
                                    recoveryHaptic.tick()
                                    discard = true
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) { Text(tr("已核对", "Dismiss"), style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry ->
        val current = view.queue.find { it.id == entry.id }
        val editable = current?.canEdit == true && (current.localSubmission || (view.connected && !view.queueBusy && view.queueRecovery == null))
        LaunchedEffect(current?.id) { if (current == null) editing = null }
        var text by rememberSaveable(entry.id) { mutableStateOf(entry.editableText) }
        val editHaptic = LocalHapticFeedback.current
        LiquidModalDialog(
            title = tr("编辑待发消息", "Edit queued message"),
            onDismiss = { editing = null },
            confirmButton = {
                Button(onClick = { editHaptic.tick(); actions.editQueued(entry.id, text); editing = null }, enabled = editable) { Text(tr("保存", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = { editHaptic.tick(); editing = null }) { Text(tr("取消", "Cancel")) }
            },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = LumaCardShape(16.dp),
                minLines = 3,
                maxLines = 7,
            )
        }
    }
    if (discard) {
        val discardHaptic = LocalHapticFeedback.current
        LiquidModalDialog(
            title = tr("清除转发记录？", "Clear forwarding record?"),
            onDismiss = { discard = false },
            confirmButton = {
                Button(onClick = { discardHaptic.tick(); discard = false; actions.resolveQueueRecovery(false) }) { Text(tr("确认清除", "Clear")) }
            },
            dismissButton = {
                TextButton(onClick = { discardHaptic.tick(); discard = false }) { Text(tr("保留", "Keep")) }
            },
        ) {
            Text(
                tr("请先确认消息是否已执行。清除不会撤销后端操作，也不会重发原消息。", "Check whether the message already ran first. Clearing will neither undo backend execution nor resend it."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
