package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentDetailView
import io.github.modao7067.luma.gui.session.SubAgentState
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.LocalOverScrollState
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.overScrollVertical

internal fun subAgentStatus(status: String) = when (status) {
    "pending" -> tr("等待中", "Pending")
    "running" -> tr("进行中", "Running")
    "succeeded" -> tr("已完成", "Done")
    "failed" -> tr("失败", "Failed")
    "cancelled" -> tr("已取消", "Cancelled")
    "closed" -> tr("已关闭", "Closed")
    else -> tr("状态待确认", "Pending")
}

internal fun subAgentTitle(agent: SubAgentState): String = agent.name.ifBlank {
    if (agent.id.length > 20) tr("子代理 · ${agent.id.takeLast(8)}", "Sub-agent · ${agent.id.takeLast(8)}") else agent.id
}

internal fun subAgentProcessItem(agent: SubAgentState): ConversationItem {
    val startMs = agent.messages.firstOrNull { it.timestampMs > 0L }?.timestampMs ?: 0L
    val endMs = if (subAgentIsHistorical(agent.status)) {
        agent.messages.maxOfOrNull { maxOf(it.completedAtMs, it.timestampMs) } ?: 0L
    } else {
        0L
    }
    return ConversationItem(
        "subagent:${agent.id}", "tool",
        text = subAgentTitle(agent),
        detail = agent.detail, input = agent.task, kind = "subAgent", outcome = agent.status,
        status = subAgentStatus(agent.status), toolName = agent.role, toolKind = agent.id, turnId = agent.turnId,
        timestampMs = startMs, completedAtMs = endMs,
    )
}

@Composable
internal fun SubAgentGroup(agents: List<SubAgentState>, initiallyExpanded: Boolean = false,
                          initiallyOpenItem: String? = null, animateInitialArrival: Boolean = false, previewGroupFraction: Float? = null) {
    ProcessGroup(agents.map(::subAgentProcessItem), initiallyExpanded = initiallyExpanded,
        initiallyOpenItem = initiallyOpenItem?.let { "subagent:$it" },
        animateInitialArrival = animateInitialArrival, previewGroupFraction = previewGroupFraction)
}

@Composable
internal fun SubAgentEntry(
    item: ConversationItem,
    initiallyExpanded: Boolean,
    previewDetailFraction: Float?,
    expandedOverride: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    var localExpanded by rememberSaveable(item.id) { mutableStateOf(initiallyExpanded) }
    val expanded = expandedOverride ?: localExpanded
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { processRowPalette(colors) }
    val running = item.outcome == "running" || item.outcome == "pending"
    val labelColor = palette.emphasis
    val contextColor = palette.text
    val sepColor = palette.separator
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .blockHorizontalPageSwipe(allowVerticalScroll = true)
                .clickable {
                    haptic.tick()
                    onManualLayoutToggle?.invoke()
                    if (onToggle != null) onToggle() else localExpanded = !localExpanded
                }
                .semantics {
                    stateDescription = "${item.status} · ${if (expanded) tr("收起子代理详情", "Collapse sub-agent details") else tr("展开子代理详情", "Expand sub-agent details")}"
                }
                .processRowShimmer(active = running, shimmerPeak = palette.shimmerPeak)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides labelColor) {
                MenuIcon(MenuSymbol.MODEL, size = 16.dp)
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.toolName.isNotBlank()) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = sepColor,
                        maxLines = 1,
                    )
                    Text(
                        item.toolName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TopReveal(expanded, previewFraction = previewDetailFraction) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                SubAgentDetails(item.input, item.detail, item.outcome, item.toolKind)
            }
        }
    }
}

@Composable
internal fun SubAgentDetails(task: String, detail: String, status: String, id: String) {
    val colors = MaterialTheme.colorScheme
    val dark = codePaletteDark(colors)
    val blockColors = contentBlockColors(colors)
    val copiedTint = if (dark) Color(0xFF34D399) else Color(0xFF10B981)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }
    val hasTask = task.isNotBlank()
    val hasDetail = detail.isNotBlank()
    val dividerHeightDp = rememberLineDividerHeightDp(MaterialTheme.typography.bodyLarge)
    val dividerColor = colors.outlineVariant.copy(alpha = 0.45f)
    Surface(
        shape = LumaCardShape(12.dp),
        color = blockColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (hasTask) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(blockColors.header)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Task",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurfaceVariant,
                        fontFamily = terminalFont(),
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(LumaCardShape(8.dp))
                            .clickable {
                                runCatching {
                                    clipboard.setText(AnnotatedString(task.substring(0, minOf(task.length, 262_144))))
                                }.onSuccess {
                                    copied = true
                                }
                            }
                            .semantics { contentDescription = tr("复制", "Copy") },
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                            ComposerGlyph(if (copied) "check" else "copy")
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (hasTask) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        NativeMarkdown(task)
                    }
                }
                if (hasTask && hasDetail) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(dividerHeightDp)
                            .drawBehind {
                                val y = size.height / 2f
                                drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            },
                    )
                }
                if (hasDetail) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        NativeMarkdown(detail)
                    }
                } else if (!hasTask) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            subAgentStatus(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubAgentDetailHeader(
    detail: SubAgentDetailView,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusLabel = subAgentStatus(detail.agent.status)
    DetailPageHeader(
        title = subAgentTitle(detail.agent),
        subtitle = modelEffortLabel(detail.modelLabel, detail.effort),
        backLabel = tr("返回父会话", "Back to parent session"),
        onBack = onBack,
    ) {
        LiquidGlass(
            Modifier
                .size(FloatingChromeSize)
                .semantics {
                    contentDescription = statusLabel
                    stateDescription = statusLabel
                },
            cornerRadius = FloatingChromeRadius,
        ) {
            val dotColor = when (detail.agent.status) {
                "failed" -> colors.error
                "running", "pending" -> colors.primary
                "succeeded" -> colors.tertiary
                else -> colors.outline
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .glyphPulse(detail.agent.status)
                        .size(8.dp)
                        .background(dotColor, CircleShape),
                )
            }
        }
    }
}

@Composable
internal fun SubAgentDetailScreen(
    detail: SubAgentDetailView,
    cli: String,
    actions: CockpitActions,
    onBack: () -> Unit,
) = ConversationTypography {
    val identity = remember(detail.parentSessionId, detail.agent.id) {
        subAgentIdentityKey(detail.parentSessionId, detail.agent.id)
    }
    val subAgentTurnActive = !detail.loading && (detail.agent.status == "running" || detail.agent.status == "pending")
    val rows = remember(detail.messages, subAgentTurnActive, detail.agent.detail) {
        val summaryText = detail.agent.detail.trim()
        val hasTrailingSummary = summaryText.isNotBlank() && detail.messages.lastOrNull()?.text?.trim() != summaryText
        val base = conversationRows(detail.messages)
        val tail = base.lastOrNull()
        if (subAgentTurnActive && !hasTrailingSummary &&
            (tail == null || (!tail.process && tail.entries.firstOrNull()?.role == "user"))
        ) {
            base + ConversationRow("process:turn-open:${tail?.id ?: "root"}", emptyList())
        } else {
            base
        }
    }
    val openProcessRowId = if (subAgentTurnActive) rows.lastOrNull()?.takeIf { it.process }?.id else null
    val nativeProcessDurations = remember(rows, openProcessRowId) {
        buildMap<String, Long> {
            rows.forEachIndexed { idx, r ->
                if (r.process) {
                    nativeProcessGroupDurationMs(rows, idx, turnOpen = r.id == openProcessRowId)?.let { put(r.id, it) }
                }
            }
        }
    }
    val anchoring = rememberConversationRowAnchoring(identity, rows, detail.loading, heightKeyPrefix = "$identity:", openProcessRowId = openProcessRowId)
    val list = anchoring.list
    val tailFollow = anchoring.tailFollow
    val arrivingRowIds = anchoring.arrivingRowIds
    val rowHeights = anchoring.rowHeights
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val task = detail.agent.task.trim()
    val showTask = task.isNotBlank() && detail.messages.firstOrNull()?.let { it.role == "user" && it.text.trim() == task } != true
    val summary = detail.agent.detail.trim()
    val showSummary = summary.isNotBlank() && detail.messages.lastOrNull()?.text?.trim() != summary
    val parentMediaContext = LocalMediaContext.current
    CompositionLocalProvider(LocalMediaContext provides MediaContext(detail.parentSessionId, detail.cwd.ifBlank { parentMediaContext.cwd.orEmpty() }),
        LocalOverScrollState provides tailFollow.overscrollState) {
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        LiquidScaffold(
            floatingBottomClearance = floatingConversationClearance(false),
            wrapTop = false,
            wrapBottom = false,
            topBar = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubAgentDetailHeader(detail, onBack)
                    if (detail.loading) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .height(2.dp)
                                .clip(LumaCardShape(2.dp)),
                        )
                    }
                }
            },
            bottomBar = {},
            overlay = { padding ->
                ConversationScrollOverlay(
                    visible = !tailFollow.atTail,
                    padding = padding,
                    onJump = { scope.launch { tailFollow.jumpToTail(list) } },
                )
            },
        ) { padding ->
            LazyColumn(
                state = list,
                overscrollEffect = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("subagent-detail-list")
                    .nestedScroll(tailFollow.nestedScrollConnection)
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.Top,
            ) {
                if (showTask) {
                    item(key = "subagent-task:$identity") {
                        SubAgentTaskBlock(identity, task, actions::previewFile)
                    }
                }
                itemsIndexed(rows, key = { _, it -> anchoring.key(it.id) }) { index, row ->
                    val rowKey = anchoring.key(row.id)
                    val topSpacing = if (index > 0 || showTask) 16.dp else 0.dp
                    val animateRowArrival = remember(rowKey) { rowKey in arrivingRowIds }
                    SideEffect { anchoring.markRowComposed(rowKey) }
                    if (row.process) {
                        ConversationProcessRowItem(
                            identity = identity,
                            rowKey = rowKey,
                            animateArrival = animateRowArrival,
                            topSpacing = topSpacing,
                            entries = row.entries,
                            cli = cli,
                            fallbackDurationMs = nativeProcessDurations[row.id],
                            turnOpen = row.id == openProcessRowId,
                            expansionState = anchoring.processExpansionState(rowKey),
                        )
                    } else {
                        val entry = row.entries.first()
                        val animateMessagePop = animateRowArrival && entry.role == "user"
                        val markdown = if (entry.role == "assistant") rememberMarkdownPresentation(
                            identity = "$identity:$rowKey", source = entry.text,
                            complete = detail.agent.status != "running" || detail.messages.lastOrNull { it.role == "assistant" }?.id != entry.id,
                            animate = true, animateArrival = false,
                        ) else null
                        val displayedEntry = entry.copy(delivery = "")
                        SmoothTailGrowth(
                            rowId = "$identity:$rowKey",
                            contentKey = (((markdown?.visibleUnits ?: displayedEntry.text.length) xor System.identityHashCode(markdown?.document)) * 31 + displayedEntry.images.size) * 31 + displayedEntry.status.hashCode(),
                            knownHeights = rowHeights,
                            animateArrival = animateRowArrival,
                            animatePop = animateMessagePop,
                            topSpacing = topSpacing,
                        ) {
                            ConversationMessage(displayedEntry, actions, cli, markdown)
                        }
                    }
                }
                if (showSummary) {
                    item(key = "subagent-summary:$identity") {
                        val topSpacing = if (showTask || rows.isNotEmpty()) 16.dp else 0.dp
                        val failed = detail.agent.status == "failed"
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = topSpacing),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                if (failed) tr("错误", "Error") else tr("结果", "Result"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (failed) colors.error else colors.onSurfaceVariant,
                            )
                            NativeMarkdown(summary, actions::previewFile)
                        }
                    }
                }
            }
        }
    }
}

}

@Composable
internal fun SubAgentTaskBlock(
    identity: String,
    task: String,
    onFileLink: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val lines = remember(task) { task.lines() }
    val collapsible = task.length > 220 || lines.size > 5
    var expanded by rememberSaveable(identity, "task-expanded") { mutableStateOf(false) }
    val excerpt = remember(task, collapsible) {
        if (!collapsible) task else lines.take(4).joinToString("\n").take(200).trimEnd() + "…"
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                tr("任务", "Task"),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurfaceVariant,
            )
            if (collapsible) {
                val haptic = LocalHapticFeedback.current
                TextButton(
                    onClick = {
                        haptic.tick()
                        expanded = !expanded
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp).blockHorizontalPageSwipe(allowVerticalScroll = true),
                ) {
                    Text(
                        if (expanded) tr("收起任务", "Collapse task") else tr("展开任务", "Expand task"),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (collapsible) {
            TopReveal(
                expanded = expanded,
                collapsedContent = { NativeMarkdown(excerpt, onFileLink) },
            ) {
                NativeMarkdown(task, onFileLink)
            }
        } else {
            NativeMarkdown(task, onFileLink)
        }
    }
}

internal fun subAgentIsHistorical(status: String) = status in setOf("succeeded", "failed", "cancelled", "closed")
internal fun orderedCurrentAgents(agents: List<SubAgentState>) = agents.filterNot { subAgentIsHistorical(it.status) }.sortedBy { if (it.status == "running") 0 else 1 }

@Composable
internal fun SubAgentPopupItems(
    agents: List<SubAgentState>, selectedAgentId: String?, historyExpanded: Boolean,
    onToggleHistory: () -> Unit, enabled: Boolean, onSelect: (SubAgentState) -> Unit,
) {
    val menuActive = LocalMenuActive.current
    val current = orderedCurrentAgents(agents)
    val history = agents.filter { subAgentIsHistorical(it.status) }
    @Composable fun AgentRow(agent: SubAgentState) {
        val subtitle = agent.task.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { agent.role }
        ActionMenuItem(label = subAgentTitle(agent), icon = MenuSymbol.MODEL,
            description = subtitle, selected = selectedAgentId == agent.id,
            warning = agent.status == "failed", iconContainer = true, enabled = enabled,
            statusAtEdge = true, statusContent = { SubAgentMenuStatus(agent.status) },
            onClick = { onSelect(agent) })
    }
    current.forEach { agent -> key(agent.id) { AgentRow(agent) } }
    if (history.isNotEmpty()) {
        ActionMenuItem(label = tr("历史", "History"), icon = MenuSymbol.FOLDER, badge = history.size.toString(),
            trailing = if (historyExpanded) MenuSymbol.UP else MenuSymbol.DOWN, enabled = enabled, onClick = onToggleHistory)
        AnimatedVisibility(historyExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            CompositionLocalProvider(LocalMenuActive provides (menuActive && enabled && historyExpanded)) {
                MenuChoiceList(history, key = { it.id }) { AgentRow(it) }
            }
        }
    }
}

@Composable
internal fun SubAgentMenuStatus(status: String) {
    val statusInkInset = when (status) {
        "running" -> 4.6f
        "succeeded" -> 5f + 14f * 3f / 24f
        "failed" -> 5f + 14f / 24f
        "cancelled", "closed" -> 9f
        else -> 0f
    }.dp
    val colors = MaterialTheme.colorScheme
    val tint = when (status) {
        "running" -> colors.primary
        "failed" -> colors.error
        else -> colors.onSurfaceVariant
    }.copy(alpha = .85f)
    Box {
        CompositionLocalProvider(LocalContentColor provides tint) {
            if (status == "running" || subAgentIsHistorical(status)) {
                ProcessStatus(status, Modifier.offset(x = statusInkInset))
            } else {
                Text(subAgentStatus(status), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}
