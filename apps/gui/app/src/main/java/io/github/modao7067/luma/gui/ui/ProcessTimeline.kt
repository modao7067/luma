package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.net.Uri
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.MediaResource
import io.github.modao7067.luma.gui.session.SubAgentState

internal data class ConversationRow(val id: String, val entries: List<ConversationItem>, val agents: List<SubAgentState> = emptyList()) {
    val process: Boolean get() = entries.firstOrNull()?.role == "tool" || agents.isNotEmpty()
}

internal fun conversationRows(messages: List<ConversationItem>, agents: List<SubAgentState> = emptyList()): List<ConversationRow> {
    val rows = mutableListOf<ConversationRow>()
    var process = mutableListOf<ConversationItem>()
    fun flush() {
        if (process.isNotEmpty()) {
            rows += ConversationRow("process:${process.first().id}", process.toList())
            process = mutableListOf()
        }
    }
    messages.forEach { item ->
        if (item.role == "tool") {
            if (item.turnId.isNotBlank() && process.lastOrNull()?.turnId?.let { it.isNotBlank() && it != item.turnId } == true) flush()
            process += item
        }
        else {
            flush()
            rows += ConversationRow("message:${item.id}", listOf(item))
        }
    }
    flush()
    agents.associateBy { it.id }.values.forEach { agent ->
        val itemMatches = rows.withIndex().filter { (_, row) -> agent.itemId.isNotBlank() && row.entries.any { it.id == agent.itemId } }
        val exact = itemMatches.firstOrNull { (_, row) -> row.entries.any {
            it.id == agent.itemId && (agent.turnId.isBlank() || it.turnId.isBlank() || agent.turnId == it.turnId)
        } }?.index ?: -1
        val turns = rows.withIndex().filter { (_, row) -> row.process && agent.turnId.isNotBlank() && row.entries.any { it.turnId == agent.turnId } }
        val index = when {
            exact >= 0 && rows[exact].process -> exact
            itemMatches.isNotEmpty() -> -1
            turns.size == 1 -> turns.single().index
            else -> -1
        }
        if (index >= 0) rows[index] = rows[index].copy(agents = rows[index].agents + agent)
        else {
            val key = "agents:${agent.itemId.ifBlank { agent.turnId.ifBlank { agent.id } }}"
            val existing = rows.indexOfFirst { it.id == key }
            if (existing >= 0) rows[existing] = rows[existing].copy(agents = rows[existing].agents + agent)
            else {
                val parent = if (exact >= 0) exact else rows.indexOfLast { row -> agent.turnId.isNotBlank() && row.entries.any { it.turnId == agent.turnId } }
                rows.add(if (parent >= 0) parent + 1 else rows.size, ConversationRow(key, emptyList(), listOf(agent)))
            }
        }
    }
    return rows
}

internal fun processGroupEntries(row: ConversationRow): List<ConversationItem> =
    if (row.agents.isEmpty()) row.entries else row.entries + row.agents.associateBy { it.id }.values.map(::subAgentProcessItem)

internal data class ProcessPresentationRun(
    val entries: List<ConversationItem>,
    val retained: Boolean,
    val fraction: Float,
)

internal fun processPresentationRuns(
    entries: List<ConversationItem>,
    retentionFractions: Map<String, Float>,
    groupFraction: Float,
    arrivalFractions: Map<String, Float> = emptyMap(),
): List<ProcessPresentationRun> = buildList {
    processRevealRuns(entries).forEach { run ->
        run.entries.forEach { entry ->
            val retained = if (run.retained) 1f else maxOf(groupFraction, retentionFractions[entry.id] ?: 0f)
            val fraction = retained * (arrivalFractions[entry.id] ?: 1f)
            val previous = lastOrNull()
            if (previous?.retained == run.retained && previous.fraction == fraction) {
                this[lastIndex] = previous.copy(entries = previous.entries + entry)
            } else {
                add(ProcessPresentationRun(listOf(entry), run.retained, fraction))
            }
        }
    }
}

@Composable
internal fun ProcessGroup(
    entries: List<ConversationItem>,
    initiallyExpanded: Boolean = false,
    initiallyOpenItem: String? = null,
    previewDetailFraction: Float? = null,
    previewDetailClosing: Boolean = false,
    previewGroupFraction: Float? = null,
    cli: String = "Codex",
    animateInitialArrival: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val entryState = rememberSaveableStateHolder()
    val langCode = LumaI18n.langCode()
    val summary = remember(entries, cli, langCode) { processSummaryText(processGroupSummary(entries, cli)) }
    val hasRunningEntries = entries.any { it.outcome == "running" }
    val colors = MaterialTheme.colorScheme
    val border = colors.outlineVariant.copy(alpha = 0.55f)
    var groupIntroduced by rememberSaveable { mutableStateOf(!animateInitialArrival) }
    LaunchedEffect(Unit) { groupIntroduced = true }
    val animatedFractionState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = revealSpring(),
        label = "process-group-reveal",
    )
    val groupFractionProvider: () -> Float = {
        (previewGroupFraction ?: revealFraction(animatedFractionState.value)).coerceIn(0f, 1f)
    }
    val settledEntryIds = remember { HashSet<String>().apply { addAll(entries.map { it.id }) } }
    val prevEntryIds = remember { mutableListOf<String>().apply { addAll(entries.map { it.id }) } }
    if (prevEntryIds.isNotEmpty()) {
        val currentIds = entries.map { it.id }.toSet()
        val prevIdSet = prevEntryIds.toSet()
        entries.forEachIndexed { idx, entry ->
            val oldId = prevEntryIds.getOrNull(idx)
            if (oldId != null && oldId != entry.id && oldId !in currentIds && entry.id !in prevIdSet) {
                settledEntryIds.add(entry.id)
            }
        }
    }
    SideEffect {
        settledEntryIds.addAll(entries.map { it.id })
        prevEntryIds.clear()
        prevEntryIds.addAll(entries.map { it.id })
    }
    val arrivalStates = entries.associate { entry ->
        key(entry.id) {
            var introduced by rememberSaveable(entry.id) { mutableStateOf(entry.id in settledEntryIds) }
            val arrivalState = animateFloatAsState(
                targetValue = if (introduced) 1f else 0f,
                animationSpec = revealSpring(),
                label = "process-arrival-reveal",
            )
            LaunchedEffect(Unit) { introduced = true }
            entry.id to arrivalState
        }
    }
    val retentionStates = entries.associate { entry ->
        key(entry.id) {
            val retainedState = animateFloatAsState(
                targetValue = if (processEntryRetained(entry)) 1f else 0f,
                animationSpec = revealSpring(),
                label = "process-completion-reveal",
            )
            entry.id to retainedState
        }
    }
    var childrenEverVisible by remember {
        mutableStateOf(
            expanded || (previewGroupFraction ?: 0f) > 0f || entries.any { processEntryRetained(it) },
        )
    }
    if (expanded) childrenEverVisible = true
    if (!childrenEverVisible) {
        val childrenVisible by remember(expanded, previewGroupFraction, entries) {
            derivedStateOf {
                val gf = groupFractionProvider()
                spatialContentVisible(expanded, gf) || entries.any {
                    processEntryRetained(it) || revealFraction(retentionStates[it.id]?.value ?: 0f) > 0f
                }
            }
        }
        if (childrenVisible) childrenEverVisible = true
    }
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    val groupBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .clip(LumaCardShape(14.dp))
                    .clickable {
                        onManualLayoutToggle?.invoke()
                        expanded = !expanded
                    }
                    .semantics { stateDescription = (if (hasRunningEntries) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("已展开执行过程", "Expanded steps") else tr("已折叠执行过程", "Collapsed steps") }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MenuIcon(
                    MenuSymbol.DOWN,
                    size = 18.dp,
                    rotationProvider = { groupFractionProvider() * 180f },
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                    color = colors.onSurfaceVariant,
                )
            }
            if (childrenEverVisible) {
                val allSlices = remember(entries, cli, initiallyOpenItem) {
                    groupProcessRunSlices(entries, cli, initiallyOpenItem)
                }
                val latestRetentionStates by rememberUpdatedState(retentionStates)
                val latestArrivalStates by rememberUpdatedState(arrivalStates)
                val sliceSignatures by remember(allSlices, previewGroupFraction) {
                    derivedStateOf {
                        allSlices.map { slice ->
                            val sliceEntries = slice.entriesList()
                            val retained = sliceEntries.any { processEntryRetained(it) }
                            val retiring = !retained && sliceEntries.any { revealFraction(latestRetentionStates[it.id]?.value ?: 0f) > 0f }
                            val arriving = sliceEntries.all { revealFraction(latestArrivalStates[it.id]?.value ?: 1f) < 1f }
                            Triple(retained, retiring, arriving)
                        }
                    }
                }
                val dynamicRuns = remember(allSlices, sliceSignatures, previewGroupFraction) {
                    buildList<Pair<Boolean, List<ProcessRunSlice>>> {
                        allSlices.forEachIndexed { index, slice ->
                            val (retained, retiring, arriving) = sliceSignatures[index]
                            val previous = lastOrNull()
                            if (!retiring && !arriving && previous != null && previous.first == retained &&
                                !sliceSignatures[index - 1].second && !sliceSignatures[index - 1].third
                            ) {
                                this[lastIndex] = previous.copy(second = previous.second + slice)
                            } else {
                                add(retained to listOf(slice))
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .padding(start = 14.dp)
                        .drawBehind {
                            val inset = 4.dp.toPx()
                            val endY = size.height - inset
                            if (endY > inset) {
                                drawLine(border, Offset(0f, inset), Offset(0f, endY), 1.5.dp.toPx())
                            }
                        }
                        .padding(start = 12.dp),
                ) {
                    dynamicRuns.forEachIndexed { runIndex, (runRetained, runSlices) ->
                        val runEntries = remember(runSlices) { runSlices.flatMap { it.entriesList() } }
                        val firstId = runEntries.first().id
                        val isFirstRun = runIndex == 0
                        key(firstId) {
                            val runFractionProvider = remember(firstId, runEntries, runRetained, previewGroupFraction) {
                                {
                                    val gf = groupFractionProvider()
                                    val maxRetention = runEntries.maxOfOrNull { revealFraction(latestRetentionStates[it.id]?.value ?: 0f) } ?: 0f
                                    val rf = if (runRetained) 1f else maxOf(gf, maxRetention)
                                    val af = runEntries.maxOfOrNull { revealFraction(latestArrivalStates[it.id]?.value ?: 1f) } ?: 1f
                                    (rf * af).coerceIn(0f, 1f)
                                }
                            }
                            TopReveal(
                                expanded = expanded || runRetained,
                                previewFraction = previewGroupFraction?.let { runFractionProvider() },
                                fractionProvider = if (previewGroupFraction == null) runFractionProvider else null,
                                keepDetailComposed = true,
                            ) {
                                Column(Modifier.padding(top = if (isFirstRun) 2.dp else 0.dp)) {
                                    runSlices.forEach { slice ->
                                        when (slice) {
                                            is ProcessRunSlice.ReadBatch -> {
                                                val batchId = "read-batch:${slice.entries.first().id}"
                                                key(batchId) {
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        entryState.SaveableStateProvider(batchId) {
                                                            ProcessReadBatchEntry(
                                                                entries = slice.entries,
                                                                cli = cli,
                                                                initiallyOpenItem = initiallyOpenItem,
                                                                previewDetailClosing = previewDetailClosing,
                                                                previewDetailFraction = previewDetailFraction,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            is ProcessRunSlice.EditGroup -> {
                                                val editGroupId = "edit-group:${slice.entries.first().id}"
                                                key(editGroupId) {
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        ProcessEditCardGroup(
                                                            entries = slice.entries,
                                                            cli = cli,
                                                            entryState = entryState,
                                                            initiallyOpenItem = initiallyOpenItem,
                                                            previewDetailClosing = previewDetailClosing,
                                                            previewDetailFraction = previewDetailFraction,
                                                        )
                                                    }
                                                }
                                            }
                                            is ProcessRunSlice.Single -> {
                                                val entry = slice.entry
                                                key(entry.id) {
                                                    val entryRetention = retentionStates[entry.id]
                                                    val isRetiring by remember(entry.id, runRetained, entryRetention) {
                                                        derivedStateOf {
                                                            !runRetained && revealFraction(entryRetention?.value ?: 0f) > 0f
                                                        }
                                                    }
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        entryState.SaveableStateProvider(entry.id) {
                                                            ProcessEntry(
                                                                entry,
                                                                cli = cli,
                                                                retiring = isRetiring,
                                                                initiallyExpanded = initiallyOpenItem == entry.id && !previewDetailClosing,
                                                                previewDetailFraction = previewDetailFraction.takeIf { initiallyOpenItem == entry.id },
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
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (animateInitialArrival && previewGroupFraction == null) {
            TopReveal(expanded = groupIntroduced) { groupBody() }
        } else {
            groupBody()
        }
        MessageMedia(entries.flatMap(::messageMedia).distinctBy { it.source })
    }
}

@Composable
private fun ProcessEntry(
    item: ConversationItem,
    cli: String,
    retiring: Boolean = false,
    initiallyExpanded: Boolean = false,
    previewDetailFraction: Float? = null,
) {
    if (item.kind == "subAgent") {
        SubAgentEntry(item, initiallyExpanded, previewDetailFraction)
        return
    }
    var expanded by rememberSaveable(item.id) { mutableStateOf(initiallyExpanded) }
    val reasoning = item.kind == "reasoning"
    val running = item.outcome == "running"
    val failed = item.outcome == "failed"
    val colors = MaterialTheme.colorScheme
    val color = when {
        failed -> colors.error
        running -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val label = processEntryLabel(item, cli)
    val symbol = if (reasoning) MenuSymbol.THINKING else when (processCategory(item, cli)) {
        ProcessCategory.Terminal -> MenuSymbol.TERMINAL
        ProcessCategory.Edit -> MenuSymbol.DIFF
        ProcessCategory.Read -> MenuSymbol.FOLDER
        ProcessCategory.Search -> MenuSymbol.SEARCH
        ProcessCategory.WebSearch, ProcessCategory.Browse -> MenuSymbol.BROWSER
        ProcessCategory.Image -> MenuSymbol.IMAGE
        ProcessCategory.Tool -> MenuSymbol.EXTENSION
    }
    val cleanTitle = processEntryContext(item, cli)
    val canExpand = !(reasoning && item.detail.isBlank())
    val chevronFractionState = animateFloatAsState(
        targetValue = if (canExpand && expanded) 1f else 0f,
        animationSpec = revealSpring(),
        label = "process-entry-chevron",
    )
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumaCardShape(12.dp))
                .let { mod ->
                    if (canExpand) {
                        mod
                            .clickable {
                                onManualLayoutToggle?.invoke()
                                expanded = !expanded
                            }
                            .semantics { stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起$label", "Collapse $label") else tr("展开$label", "Expand $label") }
                    } else if (running) {
                        mod.semantics { stateDescription = tr("进行中", "Running") }
                    } else {
                        mod
                    }
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    MenuIcon(symbol, size = 14.dp)
                }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) { append(label) }
                    if (cleanTitle.isNotBlank()) {
                        append(" · ")
                        withStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.82f))) { append(cleanTitle) }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides color.copy(alpha = 0.85f)) {
                    ProcessStatus(item.outcome)
                }
                if (canExpand) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant.copy(alpha = 0.7f)) {
                        MenuIcon(MenuSymbol.DOWN, size = 16.dp, rotationProvider = { revealFraction(chevronFractionState.value) * 180f })
                    }
                }
            }
        }
        val displayDetail = remember(item.detail, reasoning) {
            if (reasoning) item.detail else sanitizeProcessToolDetail(item.detail)
        }
        val liveExcerptText = remember(displayDetail, running, retiring) {
            if ((running || retiring) && displayDetail.isNotBlank()) processLiveExcerpt(displayDetail, maxChars = 2400) else ""
        }
        TopReveal(
            expanded = canExpand && expanded,
            previewFraction = previewDetailFraction,
            collapsedContent = if ((running || retiring) && liveExcerptText.isNotBlank()) {
                {
                    LiveExcerpt(
                        liveExcerptText,
                        Modifier.padding(start = 32.dp, bottom = 6.dp),
                        fontFamily = if (reasoning) null else terminalFont(),
                        color = if (failed) color else colors.onSurfaceVariant,
                    )
                }
            } else null,
        ) {
            val readPreview = if (!reasoning && processCategory(item, cli) == ProcessCategory.Read && !failed && hasProcessReadPreview(item)) {
                remember(item, cli) {
                    parseProcessReadPreview(item, cli)
                }
            } else null
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reasoning) {
                    if (item.detail.isNotBlank()) {
                        val reasoningMarkdown = rememberMarkdownPresentation(
                            identity = "reasoning:${item.id}",
                            source = item.detail,
                            complete = !running,
                        )
                        NativeMarkdown(item.detail, presentation = reasoningMarkdown)
                    }
                } else if (readPreview != null && (readPreview.fileName.isNotBlank() || item.detail.isNotBlank())) {
                    ProcessSingleReadPreviewCard(item, readPreview)
                } else {
                    val inputPresentation = remember(item.input, item.kind, item.toolName, item.toolKind, item.commandActions, cli) {
                        processInputPresentation(item, cli)
                    }
                    if (inputPresentation.code.isNotBlank()) {
                        CodeBlock(inputPresentation.code, inputPresentation.language, caption = inputPresentation.caption)
                    }
                    if (displayDetail.isNotBlank()) {
                        CodeBlock(
                            displayDetail,
                            processOutputLanguage(item),
                            caption = processOutputCaption(item),
                        )
                    } else if (messageMedia(item).isEmpty()) {
                        Text(
                            if (running) tr("等待工具输出…", "Waiting for tool output…") else tr("工具未返回文本输出。", "Tool returned no text output."),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal sealed interface ProcessRunSlice {
    fun entriesList(): List<ConversationItem>
    data class ReadBatch(val entries: List<ConversationItem>) : ProcessRunSlice {
        override fun entriesList(): List<ConversationItem> = entries
    }
    data class EditGroup(val entries: List<ConversationItem>) : ProcessRunSlice {
        override fun entriesList(): List<ConversationItem> = entries
    }
    data class Single(val entry: ConversationItem) : ProcessRunSlice {
        override fun entriesList(): List<ConversationItem> = listOf(entry)
    }
}

internal fun groupProcessRunSlices(
    entries: List<ConversationItem>,
    cli: String,
    initiallyOpenItem: String?,
): List<ProcessRunSlice> {
    if (entries.isEmpty()) return emptyList()
    fun isReasoning(item: ConversationItem) = item.kind == "reasoning"
    fun isMergeableRead(item: ConversationItem) =
        !isReasoning(item) && processCategory(item, cli) == ProcessCategory.Read && item.outcome != "failed" && hasSingleFileReadPreview(item)
    fun isMergeableEdit(item: ConversationItem) =
        !isReasoning(item) && processCategory(item, cli) == ProcessCategory.Edit &&
            item.outcome != "failed" && item.id != initiallyOpenItem && hasProcessEditPreview(item)
    val result = mutableListOf<ProcessRunSlice>()
    var i = 0
    while (i < entries.size) {
        val current = entries[i]
        if (isMergeableRead(current)) {
            var lastReadIdx = i
            var scan = i + 1
            while (scan < entries.size) {
                val candidate = entries[scan]
                if (isMergeableRead(candidate)) {
                    lastReadIdx = scan
                    scan++
                } else if (isReasoning(candidate)) {
                    scan++
                } else {
                    break
                }
            }
            val span = entries.subList(i, lastReadIdx + 1)
            val reads = span.filter(::isMergeableRead)
            if (reads.size >= 2) {
                span.filter(::isReasoning).forEach { result += ProcessRunSlice.Single(it) }
                result += ProcessRunSlice.ReadBatch(reads)
                i = lastReadIdx + 1
            } else {
                result += ProcessRunSlice.Single(current)
                i++
            }
            continue
        }
        if (isMergeableEdit(current)) {
            var lastEditIdx = i
            var scan = i + 1
            while (scan < entries.size) {
                val candidate = entries[scan]
                if (isMergeableEdit(candidate)) {
                    lastEditIdx = scan
                    scan++
                } else if (isReasoning(candidate)) {
                    scan++
                } else {
                    break
                }
            }
            val span = entries.subList(i, lastEditIdx + 1)
            val edits = span.filter(::isMergeableEdit)
            if (edits.size >= 2) {
                span.filter(::isReasoning).forEach { result += ProcessRunSlice.Single(it) }
                result += ProcessRunSlice.EditGroup(edits)
                i = lastEditIdx + 1
            } else {
                result += ProcessRunSlice.EditGroup(listOf(current))
                i++
            }
            continue
        }
        result += ProcessRunSlice.Single(current)
        i++
    }
    return result
}

@Composable
private fun ProcessReadBatchEntry(
    entries: List<ConversationItem>,
    cli: String,
    initiallyOpenItem: String?,
    previewDetailClosing: Boolean,
    previewDetailFraction: Float?,
) {
    val initialOpen = initiallyOpenItem != null && entries.any { it.id == initiallyOpenItem } && !previewDetailClosing
    var expanded by rememberSaveable(entries.first().id) { mutableStateOf(initialOpen) }
    var selectedId by rememberSaveable(entries.first().id) {
        mutableStateOf(entries.find { it.id == initiallyOpenItem }?.id ?: entries.first().id)
    }
    val running = entries.any { it.outcome == "running" }
    val colors = MaterialTheme.colorScheme
    val color = if (running) colors.primary else colors.onSurfaceVariant
    val langCode = LumaI18n.langCode()
    val batchToolLabel = remember(entries, cli, langCode) {
        val labels = entries.map { processEntryLabel(it, cli) }.distinct()
        if (labels.size == 1) labels.single() else labels.joinToString(tr("、", ", "))
    }
    val fileNamesSummary = remember(entries, cli) {
        formatProcessReadBatchSummary(entries, cli)
    }
    val chevronFractionState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = revealSpring(),
        label = "process-read-batch-chevron",
    )
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumaCardShape(12.dp))
                .clickable {
                    onManualLayoutToggle?.invoke()
                    expanded = !expanded
                }
                .semantics {
                    stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起批量读取", "Collapse batch read") else tr("展开批量读取", "Expand batch read")
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    MenuIcon(MenuSymbol.FOLDER, size = 14.dp)
                }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) {
                        append(batchToolLabel)
                    }
                    if (fileNamesSummary.isNotBlank()) {
                        append(" · ")
                        withStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.82f))) {
                            append(fileNamesSummary)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides color.copy(alpha = 0.85f)) {
                    ProcessStatus(if (running) "running" else "succeeded")
                }
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant.copy(alpha = 0.7f)) {
                    MenuIcon(MenuSymbol.DOWN, size = 16.dp, rotationProvider = { revealFraction(chevronFractionState.value) * 180f })
                }
            }
        }
        TopReveal(
            expanded = expanded,
            previewFraction = previewDetailFraction.takeIf { entries.any { e -> e.id == initiallyOpenItem } },
        ) {
            val previews = remember(entries, cli) { entries.map { parseProcessReadPreview(it, cli) } }
            val activeIndex = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            val activeEntry = entries[activeIndex]
            val activePreview = previews[activeIndex]
            val headerBg = contentBlockColors(colors).header
            val bodyBg = contentBlockColors(colors).surface
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 10.dp),
            ) {
                Surface(
                    shape = LumaCardShape(12.dp),
                    color = bodyBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(headerBg)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            entries.forEachIndexed { idx, entry ->
                                val p = previews[idx]
                                val selected = entry.id == activeEntry.id
                                Surface(
                                    shape = LumaCardShape(8.dp),
                                    color = if (selected) contentBlockColors(colors).selected else contentBlockColors(colors).tab,
                                    modifier = Modifier.selectable(selected = selected, role = Role.Tab) {
                                        if (selectedId != entry.id) {
                                            onManualLayoutToggle?.invoke()
                                            selectedId = entry.id
                                        }
                                    },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(
                                            p.fileName,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            ),
                                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                            fontFamily = terminalFont(),
                                            maxLines = 1,
                                        )
                                        if (p.rangeLabel.isNotBlank()) {
                                            Text(
                                                p.rangeLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurfaceVariant.copy(alpha = 0.68f),
                                                fontFamily = terminalFont(),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val (resolvedActivePreview, resolvingActive) = rememberResolvedReadPreview(activePreview)
                        ProcessReadFileBody(activeEntry, resolvedActivePreview, resolvingActive)
                    }
                }
            }
        }
    }
}

private val rejectedReadPreviewSchemes = setOf("content:", "android.resource:", "http:", "https:", "data:")

internal fun isAllowedReadPreviewSource(rawPath: String): Boolean {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty() || '\u0000' in trimmed) return false
    val lower = trimmed.lowercase(Locale.ROOT)
    if (rejectedReadPreviewSchemes.any { lower.startsWith(it) }) return false
    val schemeMatch = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(lower)
    if (schemeMatch != null && schemeMatch.value !in setOf("file:", "sandbox:")) return false
    return true
}

internal fun readBoundedUtf8Text(file: File, maxBytes: Int = MAX_PROCESS_PREVIEW_CHARS): String? {
    if (!file.isFile || !file.canRead()) return null
    val bytes = file.inputStream().use { input ->
        val out = ByteArrayOutputStream(minOf(file.length().coerceAtLeast(0L), maxBytes.toLong()).toInt().coerceAtLeast(256))
        val buffer = ByteArray(8192)
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n <= 0) break
            for (idx in 0 until n) {
                if (buffer[idx] == 0.toByte()) return null
            }
            out.write(buffer, 0, n)
            remaining -= n
        }
        out.toByteArray()
    }
    if (bytes.isEmpty()) return ""
    val truncated = bytes.size > maxBytes
    var validLen = if (truncated) maxBytes else bytes.size
    if (truncated && validLen > 0) {
        var cont = 0
        while (cont < 3 && validLen - 1 - cont >= 0 && (bytes[validLen - 1 - cont].toInt() and 0xC0) == 0x80) {
            cont++
        }
        val leadIdx = validLen - 1 - cont
        if (leadIdx >= 0) {
            val lead = bytes[leadIdx].toInt() and 0xFF
            val expectedLen = when {
                (lead and 0x80) == 0 -> 1
                (lead and 0xE0) == 0xC0 -> 2
                (lead and 0xF0) == 0xE0 -> 3
                (lead and 0xF8) == 0xF0 -> 4
                else -> 0
            }
            if (expectedLen == 0 || cont + 1 < expectedLen) {
                validLen = leadIdx
            }
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, validLen)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

@Composable
private fun rememberResolvedReadPreview(
    basePreview: ProcessReadPreview,
): Pair<ProcessReadPreview, Boolean> {
    if (!basePreview.resolveFromFile || basePreview.cleanText.isNotBlank() ||
        basePreview.filePath.isBlank() ||
        !isAllowedReadPreviewSource(basePreview.filePath) ||
        LocalInspectionMode.current
    ) {
        return basePreview to false
    }
    val actions = LocalMediaActions.current ?: return basePreview to false
    val mediaContext = LocalMediaContext.current
    return produceState(initialValue = basePreview to true, basePreview, actions, mediaContext) {
        value = basePreview to true
        val loaded = withContext(Dispatchers.IO) {
            try {
                val resource = MediaResource(basePreview.filePath, basePreview.fileName, "text/plain").inContext(mediaContext)
                val resolved = runCatching { actions.resolveMedia(resource) }.getOrNull()
                val localPath = resolved?.uri?.let { Uri.parse(it).path }
                val rawText = if (!localPath.isNullOrBlank()) {
                    readBoundedUtf8Text(File(localPath), MAX_PROCESS_PREVIEW_CHARS)
                } else {
                    null
                }
                if (!rawText.isNullOrEmpty()) {
                    populateReadPreviewFromText(basePreview, rawText)
                } else {
                    basePreview
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                basePreview
            }
        }
        value = loaded to false
    }.value
}

@Composable
private fun ProcessSingleReadPreviewCard(
    item: ConversationItem,
    preview: ProcessReadPreview,
) {
    val (resolvedPreview, resolvingFile) = rememberResolvedReadPreview(preview)
    val colors = MaterialTheme.colorScheme
    val headerBg = contentBlockColors(colors).header
    val bodyBg = contentBlockColors(colors).surface
    Surface(
        shape = LumaCardShape(12.dp),
        color = bodyBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp)
                    .background(headerBg)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        resolvedPreview.fileName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        fontFamily = terminalFont(),
                        maxLines = 1,
                    )
                    if (resolvedPreview.parentDir.isNotBlank()) {
                        Text(
                            resolvedPreview.parentDir,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                            fontFamily = terminalFont(),
                            maxLines = 1,
                        )
                    }
                }
                if (resolvedPreview.rangeLabel.isNotBlank()) {
                    Text(
                        resolvedPreview.rangeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                        fontFamily = terminalFont(),
                    )
                }
            }
            ProcessReadFileBody(item, resolvedPreview, resolvingFile)
        }
    }
}

@Composable
private fun ProcessReadFileBody(
    item: ConversationItem,
    preview: ProcessReadPreview,
    resolving: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (preview.cleanText.isBlank()) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                when {
                    item.outcome == "running" || resolving -> tr("正在读取文件…", "Reading file…")
                    !preview.resolveFromFile -> tr("未返回文件内容。", "No file content returned.")
                    else -> tr("文件为空。", "File is empty.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }
    if (preview.language in setOf("md", "markdown") && preview.lines.size <= 160) {
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clipToBounds()
                .codeBlockScrollBoundary(vScroll, hScroll)
                .verticalScroll(vScroll)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            NativeMarkdown(preview.cleanText)
        }
        return
    }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val gutterBg = contentBlockColors(colors).header
    val borderColor = colors.outlineVariant.copy(alpha = 0.38f)
    val displayLines = remember(preview.lines) {
        if (preview.lines.size > MAX_PROCESS_DISPLAY_ROWS) preview.lines.subList(0, MAX_PROCESS_DISPLAY_ROWS) else preview.lines
    }
    val omittedLineCount = (preview.lines.size - displayLines.size).coerceAtLeast(0)
    val displayCleanText = remember(preview.cleanText, preview.lines, displayLines) {
        if (omittedLineCount == 0) preview.cleanText else displayLines.joinToString("\n") { it.text }
    }
    val maxReadDigits = remember(displayLines) {
        displayLines.maxOfOrNull { it.lineNumber.toString().length }?.coerceAtLeast(2) ?: 2
    }
    val readGutterWidth = when {
        maxReadDigits <= 3 -> 42.dp
        maxReadDigits == 4 -> 50.dp
        else -> 58.dp
    }
    val termFont = terminalFont()
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = termFont,
        fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
        letterSpacing = 0.sp,
        lineHeight = 19.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val gutterTextStyle = codeStyle.copy(color = colors.onSurfaceVariant.copy(alpha = 0.55f))
    val readTextMeasurer = rememberTextMeasurer(cacheSize = 512)
    var readCodeLayout by remember(displayLines) { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 236.dp)
            .clipToBounds()
            .codeBlockScrollBoundary(vScroll, hScroll)
            .verticalScroll(vScroll),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .drawBehind {
                        if (!preview.sourceLinesKnown) return@drawBehind
                        val gutterW = readGutterWidth.toPx()
                        val padV = 8.dp.toPx()
                        val padH = 6.dp.toPx()
                        val fallbackH = 19.sp.toPx()
                        val viewportMargin = 260.dp.toPx() + padV * 2f
                        val scrollY = vScroll.value.toFloat()
                        val visibleTop = scrollY - viewportMargin
                        val visibleBottom = scrollY + viewportMargin * 2f
                        val layout = readCodeLayout
                        drawRect(gutterBg, Offset.Zero, Size(gutterW, size.height))
                        displayLines.forEachIndexed { idx, rLine ->
                            val top: Float
                            val bottom: Float
                            if (layout != null && idx < layout.lineCount) {
                                top = padV + layout.getLineTop(idx)
                                bottom = padV + layout.getLineBottom(idx)
                            } else {
                                top = padV + idx * fallbackH
                                bottom = top + fallbackH
                            }
                            if (bottom < visibleTop || top > visibleBottom) return@forEachIndexed
                            val m = readTextMeasurer.measure(rLine.lineNumber.toString(), style = gutterTextStyle, softWrap = false, maxLines = 1)
                            val x = (gutterW - padH - m.size.width).coerceAtLeast(2f)
                            val y = if (layout != null && idx < layout.lineCount) {
                                padV + layout.getLineBaseline(idx) - m.firstBaseline
                            } else {
                                top + (fallbackH - m.size.height) / 2f
                            }
                            drawText(m, topLeft = Offset(x, y))
                        }
                    },
            ) {
                if (preview.sourceLinesKnown) {
                    Spacer(Modifier.width(readGutterWidth))
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(borderColor),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    LumaSelectionContainer {
                        Text(
                            rememberHighlightedCode(displayCleanText, preview.language, colors),
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(hScroll),
                            fontFamily = termFont,
                            style = codeStyle,
                            softWrap = false,
                            onTextLayout = { readCodeLayout = it },
                        )
                    }
                }
            }
            if (omittedLineCount > 0) {
                HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.28f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(gutterBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        tr("… 已省略剩余 $omittedLineCount 行", "… $omittedLineCount more lines omitted"),
                        fontFamily = termFont,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

private fun editSmoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
private fun EditHeaderTitleLayout(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    countBadge: (@Composable () -> Unit)? = null,
    parentDir: (@Composable () -> Unit)? = null,
) {
    Layout(
        content = {
            Box(Modifier.layoutId("title")) { title() }
            if (countBadge != null) Box(Modifier.layoutId("count")) { countBadge() }
            if (parentDir != null) Box(Modifier.layoutId("dir")) { parentDir() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val gapPx = 5.dp.roundToPx()
        val minDirPx = 40.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val countPlaceable = measurables.firstOrNull { it.layoutId == "count" }?.measure(loose)
        val countReserved = if (countPlaceable != null) countPlaceable.width + gapPx else 0
        val stackMetadata = countPlaceable != null && maxW - countReserved < 96.dp.roundToPx()
        val titleMaxW = if (stackMetadata) maxW else (maxW - countReserved).coerceAtLeast(0)
        val titlePlaceable = measurables.first { it.layoutId == "title" }.measure(loose.copy(maxWidth = titleMaxW))
        val usedAfterCount = titlePlaceable.width + countReserved
        val remForDir = if (stackMetadata) maxW - countReserved else maxW - usedAfterCount - gapPx
        val dirPlaceable = if (remForDir >= minDirPx) {
            measurables.firstOrNull { it.layoutId == "dir" }?.measure(loose.copy(maxWidth = remForDir))
        } else null
        val metadataHeight = maxOf(countPlaceable?.height ?: 0, dirPlaceable?.height ?: 0)
        val height = if (stackMetadata) titlePlaceable.height + gapPx + metadataHeight
            else maxOf(titlePlaceable.height, metadataHeight)
        layout(maxW, constraints.constrainHeight(height)) {
            titlePlaceable.placeRelative(0, if (stackMetadata) 0 else (height - titlePlaceable.height) / 2)
            var cursorX = if (stackMetadata) 0 else titlePlaceable.width
            val metadataTop = if (stackMetadata) titlePlaceable.height + gapPx else 0
            val metadataRowHeight = if (stackMetadata) metadataHeight else height
            if (countPlaceable != null) {
                if (!stackMetadata) cursorX += gapPx
                countPlaceable.placeRelative(cursorX, metadataTop + (metadataRowHeight - countPlaceable.height) / 2)
                cursorX += countPlaceable.width
            }
            if (dirPlaceable != null) {
                cursorX += gapPx
                dirPlaceable.placeRelative(cursorX, metadataTop + (metadataRowHeight - dirPlaceable.height) / 2)
            }
        }
    }
}

@Composable
internal fun ProcessEditCardGroup(
    entries: List<ConversationItem>,
    cli: String,
    entryState: SaveableStateHolder,
    initiallyOpenItem: String?,
    previewDetailClosing: Boolean,
    previewDetailFraction: Float?,
) {
    val langCode = LumaI18n.langCode()
    val mergedEdits = remember(entries, cli, langCode) { mergeConsecutiveProcessEdits(entries, cli) }
    val colors = MaterialTheme.colorScheme
    val dark = codePaletteDark(colors)
    val headerBg = contentBlockColors(colors).header
    val bodyBg = contentBlockColors(colors).surface
    val editPalette = codePalette(dark)
    val addTextColor = editPalette.added
    val delTextColor = editPalette.removed
    val onManualLayoutToggle = LocalManualLayoutToggle.current

    val expandedFlags = BooleanArray(mergedEdits.size)
    val matchingPreviews = arrayOfNulls<Float>(mergedEdits.size)
    val openFractions = ArrayList<() -> Float>(mergedEdits.size)
    val toggleHandlers = ArrayList<() -> Unit>(mergedEdits.size)

    mergedEdits.forEachIndexed { index, merged ->
        key(merged.id) {
            val isInitiallyOpen = initiallyOpenItem != null && merged.entries.any { it.id == initiallyOpenItem }
            var expanded by rememberSaveable(merged.id) {
                mutableStateOf(isInitiallyOpen && !previewDetailClosing)
            }
            val previewFrac = previewDetailFraction.takeIf { isInitiallyOpen }
            val animState = animateFloatAsState(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = revealSpring(),
                label = "process-edit-open",
            )
            expandedFlags[index] = expanded
            matchingPreviews[index] = previewFrac
            openFractions += { (previewFrac ?: revealFraction(animState.value)).coerceIn(0f, 1f) }
            toggleHandlers += {
                onManualLayoutToggle?.invoke()
                expanded = !expanded
            }
        }
    }

    val rowVerticalPad = if (mergedEdits.size > 1) 7.dp else 6.dp
    Column(Modifier.fillMaxWidth()) {
        mergedEdits.forEachIndexed { index, merged ->
            key(merged.id) {
                entryState.SaveableStateProvider(merged.id) {
                    val running = merged.outcome == "running"
                    val failed = merged.outcome == "failed"
                    val color = when {
                        failed -> colors.error
                        running -> colors.primary
                        else -> colors.onSurfaceVariant
                    }
                    var headerHeightPx by remember { mutableIntStateOf(0) }
                    val clipBuffer = remember { Path() }
                    val expanded = expandedFlags[index]
                    val previewFrac = matchingPreviews[index]
                    val fractionProvider = openFractions[index]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                val f = fractionProvider()
                                if (f <= 0.001f || size.width <= 1f || size.height <= 1f) {
                                    drawContent()
                                    return@drawWithContent
                                }
                                val fPrev = if (index > 0) openFractions[index - 1]() else 0f
                                val fNext = if (index < mergedEdits.lastIndex) openFractions[index + 1]() else 0f
                                val frameAlpha = editSmoothStep(0.04f, 0.48f, f)
                                val topJoin = editSmoothStep(0.04f, 0.72f, minOf(fPrev, f))
                                val bottomJoin = editSmoothStep(0.04f, 0.72f, minOf(f, fNext))
                                val maxR = 12.dp.toPx()
                                val topR = maxR * (1f - topJoin)
                                val bottomR = maxR * (1f - bottomJoin)

                                clipBuffer.reset()
                                clipBuffer.addRoundRect(
                                    RoundRect(
                                        left = 0f,
                                        top = 0f,
                                        right = size.width,
                                        bottom = size.height,
                                        topLeftCornerRadius = CornerRadius(topR),
                                        topRightCornerRadius = CornerRadius(topR),
                                        bottomLeftCornerRadius = CornerRadius(bottomR),
                                        bottomRightCornerRadius = CornerRadius(bottomR),
                                    ),
                                )
                                clipPath(clipBuffer) {
                                    val headerH = headerHeightPx.toFloat().coerceIn(0f, size.height)
                                    drawRect(bodyBg.copy(alpha = bodyBg.alpha * frameAlpha),
                                        topLeft = Offset(0f, headerH), size = Size(size.width, size.height - headerH))
                                    drawRect(headerBg.copy(alpha = headerBg.alpha * frameAlpha), size = Size(size.width, headerH))
                                    this@drawWithContent.drawContent()
                                }
                            },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { headerHeightPx = it.height }
                                .clip(LumaCardShape(12.dp))
                                .clickable { toggleHandlers[index]() }
                                .semantics {
                                    stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起${merged.toolLabel}", "Collapse ${merged.toolLabel}") else tr("展开${merged.toolLabel}", "Expand ${merged.toolLabel}")
                                }
                                .padding(horizontal = 6.dp, vertical = rowVerticalPad),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .background(color.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides color) {
                                    MenuIcon(MenuSymbol.DIFF, size = 14.dp)
                                }
                            }
                            EditHeaderTitleLayout(
                                modifier = Modifier.weight(1f),
                                title = {
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) {
                                                append(merged.toolLabel)
                                            }
                                            if (merged.fileName.isNotBlank() && merged.fileName != merged.toolLabel) {
                                                append(" · ")
                                                withStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.84f))) {
                                                    append(merged.fileName)
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                countBadge = if (merged.editCount > 1) {
                                    {
                                        Box(
                                            Modifier
                                                .background(
                                                    colors.onSurfaceVariant.copy(alpha = if (dark) 0.11f else 0.09f),
                                                    RoundedCornerShape(5.dp),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                tr("${merged.editCount} 处", "${merged.editCount} edits"),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = terminalFont(),
                                                color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                } else null,
                                parentDir = if (merged.parentDir.isNotBlank()) {
                                    {
                                        Text(
                                            merged.parentDir.trimEnd('/'),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = terminalFont(),
                                            color = colors.onSurfaceVariant.copy(alpha = 0.56f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else null,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (merged.addedCount > 0 || merged.removedCount > 0) {
                                    Row(
                                        Modifier.padding(end = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (merged.addedCount > 0) {
                                            Text(
                                                "+${merged.addedCount}",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = addTextColor,
                                                fontFamily = terminalFont(),
                                            )
                                        }
                                        if (merged.removedCount > 0) {
                                            Text(
                                                "-${merged.removedCount}",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = delTextColor,
                                                fontFamily = terminalFont(),
                                            )
                                        }
                                    }
                                }
                                CompositionLocalProvider(LocalContentColor provides color.copy(alpha = 0.85f)) {
                                    ProcessStatus(merged.outcome)
                                }
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant.copy(alpha = 0.7f)) {
                                    MenuIcon(MenuSymbol.DOWN, size = 16.dp, rotationProvider = { fractionProvider() * 180f })
                                }
                            }
                        }
                        TopReveal(
                            expanded = expanded,
                            previewFraction = previewFrac,
                            fractionProvider = if (previewFrac == null) fractionProvider else null,
                        ) {
                            StreamingDiffViewer(
                                hunks = merged.hunks,
                                language = merged.language,
                                running = running,
                                colors = colors,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingDiffViewer(
    hunks: List<ProcessEditHunk>,
    language: String,
    running: Boolean,
    colors: ColorScheme,
) {
    if (hunks.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (running) tr("正在写入变更…", "Writing changes…") else tr("未包含行级差异。", "No line-level diff included."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }
    val dark = codePaletteDark(colors)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val borderColor = colors.outlineVariant.copy(alpha = 0.38f)
    val hunkBandBg = contentBlockColors(colors).header
    val gutterDefaultBg = contentBlockColors(colors).header
    val diffPalette = codePalette(dark)
    val addBarColor = diffPalette.added
    val delBarColor = diffPalette.removed
    val addRowBg = addBarColor.copy(alpha = if (dark) 0.14f else 0.12f)
    val delRowBg = delBarColor.copy(alpha = if (dark) 0.14f else 0.12f)
    val addGutterBg = addBarColor.copy(alpha = if (dark) 0.22f else 0.18f)
    val delGutterBg = delBarColor.copy(alpha = if (dark) 0.22f else 0.18f)
    val termFont = terminalFont()
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = termFont,
        fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
        letterSpacing = 0.sp,
        lineHeight = 19.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val gutterNumStyle = codeStyle.copy(color = colors.onSurfaceVariant.copy(alpha = 0.58f))
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val (displayHunks, omittedDiffRows) = remember(hunks) {
        val totalRows = hunks.sumOf { it.diffLines.size }
        if (totalRows <= MAX_PROCESS_DISPLAY_ROWS) {
            hunks to 0
        } else {
            var remaining = MAX_PROCESS_DISPLAY_ROWS
            val capped = ArrayList<ProcessEditHunk>(hunks.size)
            for (hunk in hunks) {
                if (remaining <= 0) break
                if (hunk.diffLines.size <= remaining) {
                    capped += hunk
                    remaining -= hunk.diffLines.size
                } else {
                    capped += hunk.copy(diffLines = hunk.diffLines.subList(0, remaining))
                    remaining = 0
                }
            }
            capped to (totalRows - MAX_PROCESS_DISPLAY_ROWS).coerceAtLeast(0)
        }
    }
    val maxDigits = remember(displayHunks) {
        displayHunks.maxOfOrNull { h ->
            h.diffLines.maxOfOrNull { l ->
                maxOf(l.oldLine?.toString()?.length ?: 0, l.newLine?.toString()?.length ?: 0)
            } ?: 2
        }?.coerceAtLeast(2) ?: 2
    }
    val gutterWidthDp = when {
        maxDigits <= 2 -> 48.dp
        maxDigits == 3 -> 56.dp
        maxDigits == 4 -> 68.dp
        else -> 80.dp
    }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clipToBounds()
            .codeBlockScrollBoundary(vScroll, hScroll)
            .verticalScroll(vScroll),
    ) {
        Column(Modifier.fillMaxWidth()) {
            displayHunks.forEachIndexed { hunkIdx, hunk ->
                if (hunkIdx > 0) {
                    HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.28f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(hunkBandBg)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⋯  " + hunk.headerLabel,
                            fontFamily = termFont,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.66f),
                            maxLines = 1,
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.28f))
                }
                val vPad = if (hunkIdx == 0 && displayHunks.size == 1 && omittedDiffRows == 0) 6.dp else 5.dp
                val hunkCode = remember(hunk.diffLines) {
                    hunk.diffLines.joinToString("\n") { line ->
                        val prefix = when (line.kind) {
                            DiffLineKind.ADDITION -> "+ "
                            DiffLineKind.DELETION -> "- "
                            DiffLineKind.CONTEXT -> "  "
                        }
                        prefix + line.text
                    }
                }
                val highlighted = rememberHighlightedCode(hunkCode, language, colors)
                var codeLayout by remember(hunk.diffLines) { mutableStateOf<TextLayoutResult?>(null) }
                var rowOffsetInColumn by remember(hunkIdx) { mutableFloatStateOf(0f) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .onGloballyPositioned { coords ->
                            rowOffsetInColumn = coords.positionInParent().y
                        }
                        .drawBehind {
                            val fallbackLineH = 19.sp.toPx()
                            val padPx = vPad.toPx()
                            val barW = 2.dp.toPx()
                            val gutterW = gutterWidthDp.toPx()
                            val colPad = 4.dp.toPx()
                            val colGap = 4.dp.toPx()
                            val singleColW = ((gutterW - colPad * 2f - colGap) / 2f).coerceAtLeast(8f)
                            val viewportMargin = 260.dp.toPx() + padPx * 2f
                            val localScrollY = vScroll.value.toFloat() - rowOffsetInColumn
                            val visibleTop = localScrollY - viewportMargin
                            val visibleBottom = localScrollY + viewportMargin * 2f
                            val layout = codeLayout

                            drawRect(gutterDefaultBg, Offset.Zero, Size(gutterW, size.height))
                            hunk.diffLines.forEachIndexed { idx, dLine ->
                                val top: Float
                                val bottom: Float
                                val rowH: Float
                                val baselineY: Float?
                                if (layout != null && idx < layout.lineCount) {
                                    top = padPx + layout.getLineTop(idx)
                                    bottom = padPx + layout.getLineBottom(idx)
                                    rowH = (bottom - top).coerceAtLeast(1f)
                                    baselineY = padPx + layout.getLineBaseline(idx)
                                } else {
                                    top = padPx + idx * fallbackLineH
                                    bottom = top + fallbackLineH
                                    rowH = fallbackLineH
                                    baselineY = null
                                }
                                if (bottom < visibleTop || top > visibleBottom) return@forEachIndexed

                                when (dLine.kind) {
                                    DiffLineKind.ADDITION -> {
                                        drawRect(addGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                        drawRect(addRowBg, Offset(gutterW, top), Size((size.width - gutterW).coerceAtLeast(0f), rowH))
                                        drawRect(addBarColor, Offset(0f, top), Size(barW, rowH))
                                    }
                                    DiffLineKind.DELETION -> {
                                        drawRect(delGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                        drawRect(delRowBg, Offset(gutterW, top), Size((size.width - gutterW).coerceAtLeast(0f), rowH))
                                        drawRect(delBarColor, Offset(0f, top), Size(barW, rowH))
                                    }
                                    DiffLineKind.CONTEXT -> Unit
                                }

                                dLine.oldLine?.let { oldNum ->
                                    val m = textMeasurer.measure(oldNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                    val x = (colPad + singleColW - m.size.width).coerceAtLeast(1f)
                                    val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                    drawText(m, topLeft = Offset(x, y))
                                }
                                dLine.newLine?.let { newNum ->
                                    val m = textMeasurer.measure(newNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                    val x = (gutterW - colPad - m.size.width).coerceAtLeast(colPad + singleColW + colGap)
                                    val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                    drawText(m, topLeft = Offset(x, y))
                                }
                            }
                        },
                ) {
                    Spacer(Modifier.width(gutterWidthDp))
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(borderColor),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = vPad),
                    ) {
                        LumaSelectionContainer {
                            Text(
                                highlighted,
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(hScroll),
                                fontFamily = termFont,
                                style = codeStyle,
                                softWrap = false,
                                onTextLayout = { codeLayout = it },
                            )
                        }
                    }
                }
            }
            if (omittedDiffRows > 0) {
                HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.28f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(hunkBandBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        tr("… 已省略剩余 $omittedDiffRows 行", "… $omittedDiffRows more diff lines omitted"),
                        fontFamily = termFont,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

private class LiveExcerptScrollMemo {
    var lastText: String = ""
    var lastFullHidden: Float = 0f
    var lastAnchor: String = ""
    var lastAnchorTop: Float = 0f
}

@Composable
private fun LiveExcerpt(
    text: String,
    modifier: Modifier,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    color: androidx.compose.ui.graphics.Color,
    maxLines: Int = 3,
) {
    val inspection = LocalInspectionMode.current
    val result = remember { arrayOfNulls<TextLayoutResult>(1) }
    val memo = remember { LiveExcerptScrollMemo() }
    var initialized by remember { mutableStateOf(false) }
    var lagPx by remember { mutableFloatStateOf(0f) }
    var targetHeightPx by remember { mutableFloatStateOf(0f) }
    var currentHeightPx by remember { mutableFloatStateOf(0f) }
    var avgLineHeightPx by remember { mutableFloatStateOf(36f) }
    val turnReaders = LocalTurnLayoutReaders.current
    if (turnReaders != null) {
        DisposableEffect(turnReaders) {
            val reader: () -> Any? = { currentHeightPx + lagPx }
            turnReaders.add(reader)
            onDispose { turnReaders.remove(reader) }
        }
    }
    if (!inspection) {
        LaunchedEffect(Unit) {
            var prevNanos = 0L
            while (true) {
                if (lagPx <= 0.4f && abs(targetHeightPx - currentHeightPx) <= 0.4f) {
                    prevNanos = 0L
                    snapshotFlow { lagPx > 0.4f || abs(targetHeightPx - currentHeightPx) > 0.4f }
                        .first { it }
                }
                withFrameNanos { nowNanos ->
                    if (prevNanos == 0L) {
                        prevNanos = nowNanos
                        return@withFrameNanos
                    }
                    val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    prevNanos = nowNanos
                    val lineH = avgLineHeightPx.coerceAtLeast(16f)
                    val backlogLines = lagPx / lineH
                    val scrollRate = 18f + (backlogLines - 0.6f).coerceAtLeast(0f) * 24f
                    val scrollDecay = exp(-scrollRate * dt)
                    val nextLag = lagPx * scrollDecay
                    lagPx = if (nextLag <= 0.4f) 0f else nextLag

                    val hDiff = targetHeightPx - currentHeightPx
                    val heightDecay = exp(-20f * dt)
                    currentHeightPx = if (abs(hDiff) * heightDecay <= 0.4f) {
                        targetHeightPx
                    } else {
                        targetHeightPx - hDiff * heightDecay
                    }
                }
            }
        }
    }
    androidx.compose.ui.layout.Layout(
        content = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = fontFamily,
                color = color,
                onTextLayout = { result[0] = it },
            )
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val layout = result[0]
        val lineCount = layout?.lineCount ?: 0
        val fullHiddenFloat = if (layout != null && lineCount > maxLines) layout.getLineTop(lineCount - maxLines) else 0f
        val fullHidden = fullHiddenFloat.roundToInt()
        val fullHeight = (placeable.height - fullHidden).coerceAtLeast(0)
        if (inspection) {
            layout(placeable.width, fullHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                placeable.place(0, -fullHidden)
            }
        } else {
            if (lineCount > 0 && layout != null) {
                val fullBottomFloat = layout.getLineBottom(lineCount - 1)
                val fullHeightFloat = (fullBottomFloat - fullHiddenFloat).coerceAtLeast(0f)
                val lineH = (fullBottomFloat / lineCount.toFloat()).coerceAtLeast(16f)
                avgLineHeightPx = lineH
                targetHeightPx = fullHeightFloat
                if (!initialized) {
                    initialized = true
                    if (lineCount <= 4) {
                        currentHeightPx = layout.getLineBottom(0).coerceAtLeast(0f)
                        lagPx = fullHiddenFloat.coerceIn(0f, lineH * 3.0f)
                    } else {
                        currentHeightPx = fullHeightFloat
                        lagPx = 0f
                    }
                } else if (text != memo.lastText || abs(fullHiddenFloat - memo.lastFullHidden) > 0.5f) {
                    val addedHiddenPx = when {
                        !memo.lastText.startsWith("…") && !text.startsWith("…") ->
                            (fullHiddenFloat - memo.lastFullHidden).coerceAtLeast(0f)
                        memo.lastAnchor.isNotEmpty() -> {
                            val idx = text.lastIndexOf(memo.lastAnchor)
                            if (idx in 0..text.length) {
                                val newAnchorLine = layout.getLineForOffset(idx)
                                val newAnchorTop = layout.getLineTop(newAnchorLine)
                                val trimmedTopPx = memo.lastAnchorTop - newAnchorTop
                                (fullHiddenFloat - (memo.lastFullHidden - trimmedTopPx)).coerceAtLeast(0f)
                            } else {
                                lineH * 1.5f
                            }
                        }
                        else -> (fullHiddenFloat - memo.lastFullHidden).coerceAtLeast(0f)
                    }
                    val maxLag = minOf(fullHiddenFloat, lineH * 3.0f)
                    lagPx = (lagPx + addedHiddenPx).coerceIn(0f, maxLag)
                }
                memo.lastText = text
                memo.lastFullHidden = fullHiddenFloat
                val anchorEnd = text.length
                val anchorStart = (anchorEnd - 64).coerceAtLeast(if (text.startsWith("…")) 1 else 0)
                if (anchorEnd > anchorStart) {
                    memo.lastAnchor = text.substring(anchorStart, anchorEnd)
                    memo.lastAnchorTop = layout.getLineTop(layout.getLineForOffset(anchorStart.coerceIn(0, text.length)))
                } else {
                    memo.lastAnchor = ""
                    memo.lastAnchorTop = 0f
                }
            }
            val shownHidden = (fullHiddenFloat - lagPx).roundToInt().coerceIn(0, placeable.height)
            val shownHeight = currentHeightPx.roundToInt().coerceIn(0, (placeable.height - shownHidden).coerceAtLeast(0))
            val height = shownHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, height) { placeable.place(0, -shownHidden) }
        }
    }
}
