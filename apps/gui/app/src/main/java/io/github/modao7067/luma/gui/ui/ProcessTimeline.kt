package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.net.Uri
import java.util.IdentityHashMap
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.MediaResource
import io.github.modao7067.luma.gui.session.SubAgentState

internal data class ConversationRow(val id: String, val entries: List<ConversationItem>, val agents: List<SubAgentState> = emptyList()) {
    val process: Boolean get() = id.startsWith("process:") || entries.firstOrNull()?.role == "tool" || agents.isNotEmpty()
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

internal fun sessionConversationRows(
    messages: List<ConversationItem>,
    agents: List<SubAgentState>,
    loading: Boolean,
    activeTurnId: String?,
): Pair<List<ConversationRow>, String?> {
    val base = conversationRows(messages, agents)
    val tail = base.lastOrNull()
    val allRows = if (!loading && activeTurnId != null && tail != null && !tail.process &&
        tail.entries.firstOrNull()?.let { it.role == "user" && it.delivery.isEmpty() } == true
    ) {
        base + ConversationRow("process:turn-open:${tail.id}", emptyList())
    } else {
        base
    }
    val openProcessRowId = if (!loading && activeTurnId != null) {
        allRows.lastOrNull()?.takeIf { it.process }?.id
    } else {
        null
    }
    return allRows to openProcessRowId
}

internal fun processGroupSlotKeys(rows: List<ConversationRow>): Map<String, String> {
    if (rows.isEmpty()) return emptyMap()
    val slots = LinkedHashMap<String, String>(rows.size)
    var userOrdinal = 0
    var groupInTurn = 0
    rows.forEach { row ->
        if (row.process) {
            slots[row.id] = "u$userOrdinal:g$groupInTurn"
            groupInTurn++
        } else {
            val first = row.entries.firstOrNull()
            if (first?.role == "user" && first.delivery != "rejected") {
                userOrdinal++
                groupInTurn = 0
            }
        }
    }
    return slots
}

private const val NATIVE_PROCESS_GAP_CAP_MS = 30L * 60_000L

internal fun nativeProcessGroupDurationMs(
    rows: List<ConversationRow>,
    rowIndex: Int,
    turnOpen: Boolean = false,
): Long? {
    val row = rows.getOrNull(rowIndex) ?: return null
    if (!row.process) return null
    val entries = processGroupEntries(row)
    if (entries.isEmpty() || processGroupHasActive(entries, turnOpen = turnOpen)) return null
    var minEntryTs = 0L
    var maxEntryEndTs = 0L
    var anyCompletedTs = false
    entries.forEach { entry ->
        if (entry.timestampMs > 0L && (minEntryTs == 0L || entry.timestampMs < minEntryTs)) {
            minEntryTs = entry.timestampMs
        }
        if (entry.completedAtMs > 0L) {
            anyCompletedTs = true
            if (entry.completedAtMs > maxEntryEndTs) maxEntryEndTs = entry.completedAtMs
        }
        if (entry.timestampMs > maxEntryEndTs) {
            maxEntryEndTs = entry.timestampMs
        }
    }
    val prevItem = rows.getOrNull(rowIndex - 1)?.takeIf { !it.process }?.entries?.firstOrNull()
    val userStartTs = prevItem?.takeIf {
        it.role == "user" && it.timestampMs > 0L &&
            (minEntryTs == 0L || (it.timestampMs <= minEntryTs && minEntryTs - it.timestampMs <= NATIVE_PROCESS_GAP_CAP_MS))
    }?.timestampMs ?: 0L
    val startMs = when {
        userStartTs > 0L -> userStartTs
        minEntryTs > 0L -> minEntryTs
        else -> return null
    }
    var endMs = maxEntryEndTs
    val nextItem = rows.getOrNull(rowIndex + 1)?.takeIf { !it.process }?.entries?.firstOrNull()
    val assistantNextTs = nextItem?.takeIf {
        it.role == "assistant" && it.timestampMs > 0L
    }?.timestampMs ?: 0L
    val baselineEnd = maxOf(startMs, endMs)
    val hasNextAssistantTs = assistantNextTs >= baselineEnd && assistantNextTs - baselineEnd <= NATIVE_PROCESS_GAP_CAP_MS
    if (hasNextAssistantTs) {
        endMs = assistantNextTs
    }
    val hasEndEvidence = anyCompletedTs || hasNextAssistantTs || (maxEntryEndTs > startMs)
    if (!hasEndEvidence || endMs < startMs) return null
    return (endMs - startMs).coerceAtLeast(1L)
}

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

internal class ProcessGroupExpansionState(
    initialGroupExpanded: Boolean = false,
    initialOpenItem: String? = null,
) {
    var groupExpanded by mutableStateOf(initialGroupExpanded)
    var expandedItemIds by mutableStateOf(
        if (initialOpenItem != null) setOf(initialOpenItem) else emptySet(),
    )
    var pinnedItemIds by mutableStateOf(emptySet<String>())
    var selectedBatchItemIds by mutableStateOf(emptyMap<String, String>())

    fun toggleGroup() {
        val next = !groupExpanded
        groupExpanded = next
        if (!next) {
            pinnedItemIds = emptySet()
        }
    }

    fun isItemExpanded(id: String): Boolean = id in expandedItemIds

    fun isItemPinned(id: String): Boolean = id in pinnedItemIds

    fun isAnyExpanded(ids: Collection<String>): Boolean = ids.any { it in expandedItemIds }

    fun isAnyPinned(ids: Collection<String>): Boolean = ids.any { it in pinnedItemIds }

    fun toggleItem(id: String, pinWhenOpen: Boolean = !groupExpanded) {
        if (id in expandedItemIds) {
            expandedItemIds = expandedItemIds - id
            pinnedItemIds = pinnedItemIds - id
        } else {
            expandedItemIds = expandedItemIds + id
            if (pinWhenOpen) {
                pinnedItemIds = pinnedItemIds + id
            }
        }
    }

    fun toggleItems(
        ids: Collection<String>,
        anchorId: String = ids.first(),
        pinWhenOpen: Boolean = !groupExpanded,
    ) {
        if (ids.any { it in expandedItemIds }) {
            val idSet = ids.toSet()
            expandedItemIds = expandedItemIds - idSet
            pinnedItemIds = pinnedItemIds - idSet
        } else {
            expandedItemIds = expandedItemIds + anchorId
            if (pinWhenOpen) {
                pinnedItemIds = pinnedItemIds + anchorId
            }
        }
    }

    fun selectedBatchItem(ids: List<String>, initiallyOpenItem: String? = null): String {
        val first = ids.first()
        val existingKey = ids.firstOrNull { it in selectedBatchItemIds }
        val saved = existingKey?.let { selectedBatchItemIds[it] }
        if (saved != null && saved in ids) return saved
        val expandedMatch = ids.firstOrNull { it in expandedItemIds }
        if (expandedMatch != null) return expandedMatch
        val initialMatch = initiallyOpenItem?.takeIf { it in ids }
        return initialMatch ?: first
    }

    fun selectBatchItem(batchIds: List<String>, selectedId: String) {
        val anchor = batchIds.firstOrNull { it in selectedBatchItemIds } ?: batchIds.firstOrNull() ?: return
        if (selectedBatchItemIds[anchor] != selectedId) {
            selectedBatchItemIds = selectedBatchItemIds + (anchor to selectedId)
        }
    }

    fun remapEntryId(oldId: String, newId: String) {
        if (oldId == newId) return
        if (oldId in expandedItemIds) {
            expandedItemIds = (expandedItemIds - oldId) + newId
        }
        if (oldId in pinnedItemIds) {
            pinnedItemIds = (pinnedItemIds - oldId) + newId
        }
        if (selectedBatchItemIds.containsKey(oldId) || selectedBatchItemIds.values.any { it == oldId }) {
            selectedBatchItemIds = buildMap(selectedBatchItemIds.size) {
                selectedBatchItemIds.forEach { (k, v) ->
                    put(if (k == oldId) newId else k, if (v == oldId) newId else v)
                }
            }
        }
    }
}

private val nextStandaloneDurationId = AtomicLong(0L)
private val StaticOneState: State<Float> = object : State<Float> { override val value: Float = 1f }
private val StaticZeroState: State<Float> = object : State<Float> { override val value: Float = 0f }

private data class LineDividerHeightKey(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontFamilyHash: Int,
    val density: Float,
    val fontScale: Float,
)

private val lineDividerHeightCache = HashMap<LineDividerHeightKey, Dp>(8)

@Composable
internal fun rememberLineDividerHeightDp(style: TextStyle): Dp {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val key = LineDividerHeightKey(
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        fontFamilyHash = style.fontFamily?.hashCode() ?: 0,
        density = density.density,
        fontScale = density.fontScale,
    )
    return remember(key, fontFamilyResolver, layoutDirection) {
        synchronized(lineDividerHeightCache) { lineDividerHeightCache[key] } ?: run {
            val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 2)
            val oneLinePx = measurer.measure("M", style = style, softWrap = false, maxLines = 1).size.height
            val twoLinePx = measurer.measure("M\nM", style = style, softWrap = false).size.height
            val lineStepPx = (twoLinePx - oneLinePx).coerceAtLeast(1)
            val slotPx = (2 * lineStepPx - oneLinePx).coerceAtLeast(with(density) { 1.dp.roundToPx() })
            val dp = with(density) { slotPx.toDp() }
            synchronized(lineDividerHeightCache) {
                if (lineDividerHeightCache.size >= 16) lineDividerHeightCache.clear()
                lineDividerHeightCache[key] = dp
            }
            dp
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
    durationKey: String? = null,
    durationMsOverride: Long? = null,
    fallbackDurationMs: Long? = null,
    turnOpen: Boolean = false,
    expansionState: ProcessGroupExpansionState? = null,
) {
    val expansion = expansionState ?: remember(initiallyExpanded, initiallyOpenItem, previewDetailClosing) {
        ProcessGroupExpansionState(
            initialGroupExpanded = initiallyExpanded,
            initialOpenItem = initiallyOpenItem.takeUnless { previewDetailClosing },
        )
    }
    val expanded = expansion.groupExpanded
    val entryState = rememberSaveableStateHolder()
    val langCode = LumaI18n.langCode()
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { processRowPalette(colors) }
    val inspection = LocalInspectionMode.current
    val localDurationId = remember { nextStandaloneDurationId.incrementAndGet() }
    val effectiveDurationKey = durationKey ?: "standalone:$localDurationId"
    val hasActiveEntries = processGroupHasActive(entries, turnOpen = turnOpen)
    val liveStoreDurationMs = if (inspection) null else ProcessDurationStore.observe(effectiveDurationKey, hasActiveEntries)
    val recordedDurationMs = durationMsOverride ?: liveStoreDurationMs ?: fallbackDurationMs
    val summaryEmphasis = if (hasActiveEntries) palette.activeGroupTitle else palette.emphasis
    val summary = remember(entries, cli, langCode, recordedDurationMs, turnOpen, summaryEmphasis) {
        processSummaryText(processGroupSummary(entries, cli, recordedDurationMs, turnOpen), summaryEmphasis)
    }
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
    val entryRenderKeys = remember(entries) { uniqueProcessRenderKeys(entries.map { it.id }) }
    val entryRenderKeyByRef = remember(entries, entryRenderKeys) {
        IdentityHashMap<ConversationItem, String>(entries.size).apply {
            entries.forEachIndexed { idx, item -> put(item, entryRenderKeys[idx]) }
        }
    }
    fun renderKeyOf(entry: ConversationItem): String = entryRenderKeyByRef[entry] ?: entry.id
    val settledEntryIds = remember { HashSet<String>().apply { addAll(entryRenderKeys) } }
    val prevEntryIds = remember { mutableListOf<String>().apply { addAll(entryRenderKeys) } }
    if (prevEntryIds.isNotEmpty()) {
        val currentIds = entryRenderKeys.toSet()
        val prevIdSet = prevEntryIds.toSet()
        entries.forEachIndexed { idx, entry ->
            val oldId = prevEntryIds.getOrNull(idx)
            val currentKey = entryRenderKeys[idx]
            if (oldId != null && oldId != currentKey && oldId !in currentIds && currentKey !in prevIdSet) {
                settledEntryIds.add(currentKey)
                expansion.remapEntryId(oldId, entry.id)
            }
        }
    }
    SideEffect {
        settledEntryIds.addAll(entryRenderKeys)
        prevEntryIds.clear()
        prevEntryIds.addAll(entryRenderKeys)
    }
    val arrivalStates = entries.indices.associate { idx ->
        val renderKey = entryRenderKeys[idx]
        key(renderKey) {
            val initiallySettled = remember(renderKey) { renderKey in settledEntryIds }
            if (initiallySettled) {
                renderKey to StaticOneState
            } else {
                var introduced by rememberSaveable(renderKey) { mutableStateOf(false) }
                val arrivalState = animateFloatAsState(
                    targetValue = if (introduced) 1f else 0f,
                    animationSpec = revealSpring(),
                    label = "process-arrival-reveal",
                )
                LaunchedEffect(Unit) { introduced = true }
                renderKey to arrivalState
            }
        }
    }
    val retentionStates = entries.indices.associate { idx ->
        val entry = entries[idx]
        val renderKey = entryRenderKeys[idx]
        key(renderKey) {
            val targetRetained = processEntryRetained(entry) || expansion.isItemPinned(entry.id)
            var everDynamic by remember(renderKey) { mutableStateOf(targetRetained) }
            if (targetRetained) everDynamic = true
            if (!everDynamic) {
                renderKey to StaticZeroState
            } else {
                val retainedState = animateFloatAsState(
                    targetValue = if (targetRetained) 1f else 0f,
                    animationSpec = revealSpring(),
                    label = "process-completion-reveal",
                )
                renderKey to retainedState
            }
        }
    }
    val hasPinnedEntries = entries.any { expansion.isItemPinned(it.id) }
    var childrenEverVisible by remember {
        mutableStateOf(
            expanded || (previewGroupFraction ?: 0f) > 0f || hasPinnedEntries || entries.any { processEntryRetained(it) },
        )
    }
    if (expanded || hasPinnedEntries) childrenEverVisible = true
    if (!childrenEverVisible) {
        val childrenVisible by remember(expanded, previewGroupFraction, entries, expansion, entryRenderKeyByRef) {
            derivedStateOf {
                val gf = groupFractionProvider()
                spatialContentVisible(expanded, gf) || entries.any {
                    processEntryRetained(it) || expansion.isItemPinned(it.id) || revealFraction(retentionStates[renderKeyOf(it)]?.value ?: 0f) > 0f
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
                    .fillMaxWidth()
                    .clip(LumaCardShape(10.dp))
                    .clickable {
                        onManualLayoutToggle?.invoke()
                        expansion.toggleGroup()
                    }
                    .semantics { stateDescription = (if (hasActiveEntries) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("已展开执行过程", "Expanded steps") else tr("已折叠执行过程", "Collapsed steps") }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProcessGroupIndicator(active = hasActiveEntries, palette = palette)
                Text(
                    summary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .processRowShimmer(
                            active = hasActiveEntries,
                            shimmerPeak = palette.shimmerPeak,
                            durationMillis = PROCESS_GROUP_TITLE_SHIMMER_DURATION_MS,
                            minBandWidth = 40.dp,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
                    color = palette.text,
                )
            }
            if (childrenEverVisible) {
                val allSlices = remember(entries, cli, initiallyOpenItem) {
                    groupProcessRunSlices(entries, cli, initiallyOpenItem)
                }
                val latestRetentionStates by rememberUpdatedState(retentionStates)
                val latestArrivalStates by rememberUpdatedState(arrivalStates)
                val sliceSignatures by remember(allSlices, previewGroupFraction, expansion, entryRenderKeyByRef) {
                    derivedStateOf {
                        allSlices.map { slice ->
                            val sliceEntries = slice.entriesList()
                            val retained = sliceEntries.any { processEntryRetained(it) || expansion.isItemPinned(it.id) }
                            val retiring = !retained && sliceEntries.any { revealFraction(latestRetentionStates[renderKeyOf(it)]?.value ?: 0f) > 0f }
                            val arriving = sliceEntries.all { revealFraction(latestArrivalStates[renderKeyOf(it)]?.value ?: 1f) < 1f }
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
                        .fillMaxWidth()
                        .padding(start = 10.dp),
                ) {
                    dynamicRuns.forEachIndexed { runIndex, (runRetained, runSlices) ->
                        val runEntries = remember(runSlices) { runSlices.flatMap { it.entriesList() } }
                        val firstId = renderKeyOf(runEntries.first())
                        val isFirstRun = runIndex == 0
                        key(firstId) {
                            val runFractionProvider = remember(firstId, runEntries, runRetained, previewGroupFraction, entryRenderKeyByRef) {
                                {
                                    val gf = groupFractionProvider()
                                    val maxRetention = runEntries.maxOfOrNull { revealFraction(latestRetentionStates[renderKeyOf(it)]?.value ?: 0f) } ?: 0f
                                    val rf = if (runRetained) 1f else maxOf(gf, maxRetention)
                                    val af = runEntries.maxOfOrNull { revealFraction(latestArrivalStates[renderKeyOf(it)]?.value ?: 1f) } ?: 1f
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
                                                val batchId = "read-batch:${renderKeyOf(slice.entries.first())}"
                                                val batchEntryIds = slice.entries.map { it.id }
                                                val batchExpanded = expansion.isAnyExpanded(batchEntryIds)
                                                val batchSelectedId = expansion.selectedBatchItem(batchEntryIds, initiallyOpenItem)
                                                key(batchId) {
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        entryState.SaveableStateProvider(batchId) {
                                                            ProcessReadBatchEntry(
                                                                entries = slice.entries,
                                                                cli = cli,
                                                                expanded = batchExpanded,
                                                                selectedId = batchSelectedId,
                                                                onToggle = { expansion.toggleItems(batchEntryIds, anchorId = batchSelectedId) },
                                                                onSelect = { expansion.selectBatchItem(batchEntryIds, it) },
                                                                initiallyOpenItem = initiallyOpenItem,
                                                                previewDetailFraction = previewDetailFraction,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            is ProcessRunSlice.EditGroup -> {
                                                val editGroupId = "edit-group:${renderKeyOf(slice.entries.first())}"
                                                key(editGroupId) {
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        ProcessEditCardGroup(
                                                            entries = slice.entries,
                                                            cli = cli,
                                                            entryState = entryState,
                                                            initiallyOpenItem = initiallyOpenItem,
                                                            previewDetailClosing = previewDetailClosing,
                                                            previewDetailFraction = previewDetailFraction,
                                                            expansion = expansion,
                                                        )
                                                    }
                                                }
                                            }
                                            is ProcessRunSlice.Single -> {
                                                val entry = slice.entry
                                                val entryKey = renderKeyOf(entry)
                                                key(entryKey) {
                                                    val entryRetention = retentionStates[entryKey]
                                                    val isRetiring by remember(entryKey, runRetained, entryRetention) {
                                                        derivedStateOf {
                                                            !runRetained && revealFraction(entryRetention?.value ?: 0f) > 0f
                                                        }
                                                    }
                                                    Column(Modifier.padding(bottom = 2.dp)) {
                                                        entryState.SaveableStateProvider(entryKey) {
                                                            ProcessEntry(
                                                                entry,
                                                                cli = cli,
                                                                retiring = isRetiring,
                                                                expanded = expansion.isItemExpanded(entry.id),
                                                                onToggle = { expansion.toggleItem(entry.id) },
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
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    previewDetailFraction: Float? = null,
) {
    if (item.kind == "subAgent") {
        SubAgentEntry(item, initiallyExpanded = expanded, previewDetailFraction = previewDetailFraction, expandedOverride = expanded, onToggle = onToggle)
        return
    }
    val reasoning = item.kind == "reasoning"
    val running = item.outcome == "running"
    val failed = item.outcome == "failed"
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { processRowPalette(colors) }
    val labelColor = palette.emphasis
    val contextColor = palette.text
    val sepColor = palette.separator
    val label = processEntryLabel(item, cli)
    val symbol = when {
        reasoning -> MenuSymbol.LIGHTBULB
        item.kind == "compaction" -> MenuSymbol.COMPRESS
        else -> when (processCategory(item, cli)) {
            ProcessCategory.Terminal -> MenuSymbol.TERMINAL
            ProcessCategory.Edit -> MenuSymbol.EDIT_DOCUMENT
            ProcessCategory.Read -> MenuSymbol.FILE
            ProcessCategory.Search -> MenuSymbol.SEARCH
            ProcessCategory.WebSearch, ProcessCategory.Browse -> MenuSymbol.BROWSER
            ProcessCategory.Image -> MenuSymbol.IMAGE
            ProcessCategory.Tool -> MenuSymbol.TOOLBOX
        }
    }
    val cleanTitle = remember(
        item.text,
        if (reasoning) item.detail else "",
        item.input,
        item.kind,
        item.toolName,
        item.toolKind,
        item.outcome,
        item.commandActions,
        cli,
    ) {
        processEntryContext(item, cli)
    }
    val canExpand = !(reasoning && item.detail.isBlank())
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .let { mod ->
                    if (canExpand) {
                        mod
                            .clickable {
                                onManualLayoutToggle?.invoke()
                                onToggle()
                            }
                            .semantics { stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起$label", "Collapse $label") else tr("展开$label", "Expand $label") }
                    } else if (running) {
                        mod.semantics { stateDescription = tr("进行中", "Running") }
                    } else {
                        mod
                    }
                }
                .processRowShimmer(active = running, shimmerPeak = palette.shimmerPeak)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides labelColor) {
                MenuIcon(symbol, size = 16.dp)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = labelColor)) { append(label) }
                    if (cleanTitle.isNotBlank()) {
                        withStyle(SpanStyle(color = sepColor)) { append(" · ") }
                        withStyle(SpanStyle(color = contextColor)) { append(cleanTitle) }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                        Modifier.padding(start = 30.dp, bottom = 6.dp),
                        fontFamily = if (reasoning) null else terminalFont(),
                        color = contextColor,
                    )
                }
            } else null,
        ) {
            val readPreview = if (!reasoning && processCategory(item, cli) == ProcessCategory.Read && !failed && hasProcessReadPreview(item)) {
                remember(item, cli) {
                    parseProcessReadPreview(item, cli)
                }
            } else null
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                if (reasoning) {
                    if (item.detail.isNotBlank()) {
                        val reasoningMarkdown = rememberMarkdownPresentation(
                            identity = "reasoning:${item.id}",
                            source = item.detail,
                            complete = !running,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            NativeMarkdown(item.detail, presentation = reasoningMarkdown)
                        }
                    }
                } else if (readPreview != null && (readPreview.fileName.isNotBlank() || item.detail.isNotBlank())) {
                    ProcessSingleReadPreviewCard(item, readPreview)
                } else {
                    ProcessMergedToolCard(
                        item = item,
                        cli = cli,
                        displayDetail = displayDetail,
                        running = running,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessMergedToolCard(
    item: ConversationItem,
    cli: String,
    displayDetail: String,
    running: Boolean,
) {
    val inputPresentation = remember(item.input, item.kind, item.toolName, item.toolKind, item.commandActions, cli) {
        processInputPresentation(item, cli)
    }
    val inputCode = inputPresentation.code
    val hasInput = inputCode.isNotBlank()
    val hasOutput = displayDetail.isNotBlank()
    val showOutputSlot = hasOutput || (hasInput && running) || (!hasInput && messageMedia(item).isEmpty())
    val showDivider = hasInput && showOutputSlot
    if (!hasInput && !showOutputSlot) return

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
    val headerCaption = if (inputPresentation.caption == inputPresentation.language && inputPresentation.language.isNotBlank()) {
        inputPresentation.caption.lowercase()
    } else {
        inputPresentation.caption
    }
    val vScroll = rememberScrollState()
    val scrollBoundary = remember(vScroll) {
        CodeBlockScrollBoundary(
            canScrollVertically = { vScroll.canScrollForward || vScroll.canScrollBackward },
            canScrollHorizontally = { false },
            canScrollVerticallyInDirection = { deltaY ->
                if (deltaY < 0f) vScroll.canScrollForward else if (deltaY > 0f) vScroll.canScrollBackward else false
            },
            canScrollHorizontallyInDirection = { false },
        )
    }
    val termFont = terminalFont()
    val codeStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = termFont,
        fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
        letterSpacing = 0.sp,
        lineHeight = 19.sp,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val singleLineHeightDp = rememberLineDividerHeightDp(codeStyle)
    val dividerColor = colors.outlineVariant.copy(alpha = 0.45f)
    val wrapIndicatorColor = colors.onSurfaceVariant.copy(alpha = if (dark) 0.46f else 0.40f)
    val density = LocalDensity.current
    val fallbackViewportPx = with(density) { 260.dp.toPx() }
    val padVPx = with(density) { 8.dp.toPx() }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var outputOffsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = LumaCardShape(12.dp),
        color = blockColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (hasInput) {
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
                        headerCaption,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurfaceVariant,
                        fontFamily = termFont,
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(LumaCardShape(8.dp))
                            .clickable {
                                runCatching {
                                    clipboard.setText(AnnotatedString(safeUtf16Prefix(inputCode, 262_144)))
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                    .clipToBounds()
                    .nestedScroll(scrollBoundary)
                    .verticalScroll(vScroll)
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (hasInput) {
                        LumaSelectionContainer {
                            ViewportWrappedCode(
                                code = inputCode,
                                visibleEnd = inputCode.length,
                                language = inputPresentation.language,
                                colors = colors,
                                style = codeStyle,
                                streamingHint = running && !hasOutput,
                                viewportTopPx = { vScroll.value.toFloat() - padVPx },
                                viewportHeightPx = { if (viewportHeightPx > 1f) viewportHeightPx else fallbackViewportPx },
                                showSoftWrapIndicators = true,
                                wrapIndicatorColor = wrapIndicatorColor,
                                contentPaddingStart = SOFT_WRAP_GUTTER_START,
                                contentPaddingEnd = SOFT_WRAP_GUTTER_END,
                            )
                        }
                    }
                    if (showDivider) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = SOFT_WRAP_GUTTER_START, end = SOFT_WRAP_GUTTER_END)
                                .height(singleLineHeightDp)
                                .drawBehind {
                                    val y = size.height / 2f
                                    drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                },
                        )
                    }
                    if (showOutputSlot) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords -> outputOffsetY = coords.positionInParent().y },
                        ) {
                            if (hasOutput) {
                                LumaSelectionContainer {
                                    ViewportWrappedCode(
                                        code = displayDetail,
                                        visibleEnd = displayDetail.length,
                                        language = processOutputLanguage(item),
                                        colors = colors,
                                        style = codeStyle,
                                        streamingHint = running,
                                        viewportTopPx = { vScroll.value.toFloat() - padVPx - outputOffsetY },
                                        viewportHeightPx = { if (viewportHeightPx > 1f) viewportHeightPx else fallbackViewportPx },
                                        showSoftWrapIndicators = true,
                                        wrapIndicatorColor = wrapIndicatorColor,
                                        contentPaddingStart = SOFT_WRAP_GUTTER_START,
                                        contentPaddingEnd = SOFT_WRAP_GUTTER_END,
                                    )
                                }
                            } else {
                                Box(Modifier.padding(start = SOFT_WRAP_GUTTER_START, end = SOFT_WRAP_GUTTER_END)) {
                                    Text(
                                        if (running) tr("等待工具输出…", "Waiting for tool output…") else tr("工具未返回文本输出。", "Tool returned no text output."),
                                        fontFamily = termFont,
                                        style = codeStyle,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
    expanded: Boolean,
    selectedId: String,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    initiallyOpenItem: String?,
    previewDetailFraction: Float?,
) {
    val running = entries.any { it.outcome == "running" }
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { processRowPalette(colors) }
    val labelColor = palette.emphasis
    val contextColor = palette.text
    val sepColor = palette.separator
    val langCode = LumaI18n.langCode()
    val batchToolLabel = remember(entries, cli, langCode) {
        val labels = entries.map { processEntryLabel(it, cli) }.distinct()
        if (labels.size == 1) labels.single() else labels.joinToString(tr("、", ", "))
    }
    val fileNamesSummary = remember(entries, cli) {
        formatProcessReadBatchSummary(entries, cli)
    }
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    onManualLayoutToggle?.invoke()
                    onToggle()
                }
                .semantics {
                    stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起批量读取", "Collapse batch read") else tr("展开批量读取", "Expand batch read")
                }
                .processRowShimmer(active = running, shimmerPeak = palette.shimmerPeak)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides labelColor) {
                MenuIcon(MenuSymbol.FOLDER, size = 16.dp)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = labelColor)) {
                        append(batchToolLabel)
                    }
                    if (fileNamesSummary.isNotBlank()) {
                        withStyle(SpanStyle(color = sepColor)) { append(" · ") }
                        withStyle(SpanStyle(color = contextColor)) {
                            append(fileNamesSummary)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TopReveal(
            expanded = expanded,
            previewFraction = previewDetailFraction.takeIf { entries.any { e -> e.id == initiallyOpenItem } },
        ) {
            val tabHeaders = remember(entries, cli) { entries.map { parseProcessReadTabHeader(it, cli) } }
            val previewCache = remember(entries, cli) { HashMap<String, ProcessReadPreview>() }
            val activeIndex = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            val activeEntry = entries[activeIndex]
            val activePreview = remember(activeEntry, cli) {
                synchronized(previewCache) {
                    previewCache.getOrPut(activeEntry.id) { parseProcessReadPreview(activeEntry, cli) }
                }
            }
            val dark = codePaletteDark(colors)
            LaunchedEffect(expanded, entries, activeEntry.id, cli, dark) {
                if (!expanded) return@LaunchedEffect
                withContext(Dispatchers.Default) {
                    val order = buildList(entries.size) {
                        if (activeIndex + 1 < entries.size) add(entries[activeIndex + 1])
                        if (activeIndex - 1 >= 0) add(entries[activeIndex - 1])
                        for (i in entries.indices) {
                            if (i != activeIndex && i != activeIndex + 1 && i != activeIndex - 1) add(entries[i])
                        }
                    }
                    for (entry in order) {
                        coroutineContext.ensureActive()
                        val preview = synchronized(previewCache) { previewCache[entry.id] }
                            ?: parseProcessReadPreview(entry, cli).also { parsed ->
                                synchronized(previewCache) { previewCache[entry.id] = parsed }
                            }
                        if (preview.cleanText.isNotBlank() && preview.language !in setOf("md", "markdown")) {
                            val cappedText = if (preview.lines.size > MAX_PROCESS_DISPLAY_ROWS) {
                                preview.lines.subList(0, MAX_PROCESS_DISPLAY_ROWS).joinToString("\n") { it.text }
                            } else {
                                preview.cleanText
                            }
                            codeTokenIndex(cappedText, preview.language) { coroutineContext.ensureActive() }
                        }
                    }
                }
            }
            val headerBg = contentBlockColors(colors).header
            val bodyBg = contentBlockColors(colors).surface
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
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
                            val tabShape = LumaCardShape(8.dp)
                            entries.forEachIndexed { idx, entry ->
                                val h = tabHeaders[idx]
                                val selected = entry.id == activeEntry.id
                                Surface(
                                    shape = tabShape,
                                    color = if (selected) contentBlockColors(colors).selected else contentBlockColors(colors).tab,
                                    modifier = Modifier
                                        .clip(tabShape)
                                        .selectable(selected = selected, role = Role.Tab) {
                                            if (selectedId != entry.id) {
                                                onManualLayoutToggle?.invoke()
                                                onSelect(entry.id)
                                            }
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(
                                            h.fileName,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            ),
                                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                            fontFamily = terminalFont(),
                                            maxLines = 1,
                                        )
                                        if (h.rangeLabel.isNotBlank()) {
                                            Text(
                                                h.rangeLabel,
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
                        key(activeEntry.id) {
                            val (resolvedActivePreview, resolvingActive) = rememberResolvedReadPreview(activePreview)
                            ProcessReadFileBody(activeEntry, resolvedActivePreview, resolvingActive)
                        }
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
    val density = LocalDensity.current
    val fallbackViewportPx = with(density) { 236.dp.toPx() }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 236.dp)
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
            .clipToBounds()
            .codeBlockScrollBoundary(vScroll, hScroll)
            .verticalScroll(vScroll),
    ) {
        Column(Modifier.fillMaxWidth()) {
            ViewportNoWrapCodeBlock(
                code = displayCleanText,
                language = preview.language,
                colors = colors,
                style = codeStyle,
                streamingHint = false,
                hScroll = hScroll,
                viewportTopPx = { vScroll.value.toFloat() },
                viewportHeightPx = { if (viewportHeightPx > 1f) viewportHeightPx else fallbackViewportPx },
                gutterWidth = if (preview.sourceLinesKnown) readGutterWidth else 0.dp,
                gutterBg = gutterBg,
                gutterDividerColor = borderColor,
                gutterTextStyle = gutterTextStyle,
                gutterPadEnd = 6.dp,
                lineNumberAt = if (preview.sourceLinesKnown) {
                    { idx -> displayLines.getOrNull(idx)?.lineNumber?.toString() ?: "" }
                } else {
                    null
                },
                contentPaddingHorizontal = 12.dp,
                contentPaddingVertical = 8.dp,
            )
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
        val titleMaxW = (maxW - countReserved).coerceAtLeast(0)
        val titlePlaceable = measurables.first { it.layoutId == "title" }.measure(loose.copy(maxWidth = titleMaxW))
        val usedAfterCount = titlePlaceable.width + countReserved
        val remForDir = maxW - usedAfterCount - gapPx
        val dirPlaceable = if (remForDir >= minDirPx) {
            measurables.firstOrNull { it.layoutId == "dir" }?.measure(loose.copy(maxWidth = remForDir))
        } else null
        val height = maxOf(titlePlaceable.height, countPlaceable?.height ?: 0, dirPlaceable?.height ?: 0)
        layout(maxW, constraints.constrainHeight(height)) {
            titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
            var cursorX = titlePlaceable.width
            if (countPlaceable != null) {
                cursorX += gapPx
                countPlaceable.placeRelative(cursorX, (height - countPlaceable.height) / 2)
                cursorX += countPlaceable.width
            }
            if (dirPlaceable != null) {
                cursorX += gapPx
                dirPlaceable.placeRelative(cursorX, (height - dirPlaceable.height) / 2)
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
    expansion: ProcessGroupExpansionState? = null,
) {
    val activeExpansion = expansion ?: remember(initiallyOpenItem, previewDetailClosing) {
        ProcessGroupExpansionState(
            initialOpenItem = initiallyOpenItem.takeUnless { previewDetailClosing },
        )
    }
    val langCode = LumaI18n.langCode()
    val mergedEdits = remember(entries, cli, langCode) { mergeConsecutiveProcessEdits(entries, cli) }
    val mergedRenderKeys = remember(mergedEdits) { uniqueProcessRenderKeys(mergedEdits.map { it.id }, prefix = "edit") }
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
        val renderKey = mergedRenderKeys[index]
        key(renderKey) {
            val mergedEntryIds = merged.entries.map { it.id }
            val isInitiallyOpen = initiallyOpenItem != null && merged.entries.any { it.id == initiallyOpenItem }
            val expanded = activeExpansion.isAnyExpanded(mergedEntryIds)
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
                activeExpansion.toggleItems(mergedEntryIds)
            }
        }
    }

    val rowVerticalPad = if (mergedEdits.size > 1) 6.dp else 5.dp
    val palette = remember(colors) { processRowPalette(colors) }
    Column(Modifier.fillMaxWidth()) {
        mergedEdits.forEachIndexed { index, merged ->
            val renderKey = mergedRenderKeys[index]
            key(renderKey) {
                entryState.SaveableStateProvider(renderKey) {
                    val running = merged.outcome == "running"
                    val labelColor = palette.emphasis
                    val contextColor = palette.text
                    val sepColor = palette.separator
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
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { toggleHandlers[index]() }
                                .semantics {
                                    stateDescription = (if (running) tr("进行中 · ", "Running · ") else "") + if (expanded) tr("收起${merged.toolLabel}", "Collapse ${merged.toolLabel}") else tr("展开${merged.toolLabel}", "Expand ${merged.toolLabel}")
                                }
                                .processRowShimmer(active = running, shimmerPeak = palette.shimmerPeak)
                                .padding(horizontal = 6.dp, vertical = rowVerticalPad),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompositionLocalProvider(LocalContentColor provides labelColor) {
                                MenuIcon(MenuSymbol.EDIT_DOCUMENT, size = 16.dp)
                            }
                            EditHeaderTitleLayout(
                                modifier = Modifier.weight(1f),
                                title = {
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = labelColor)) {
                                                append(merged.toolLabel)
                                            }
                                            if (merged.fileName.isNotBlank() && merged.fileName != merged.toolLabel) {
                                                withStyle(SpanStyle(color = sepColor)) { append(" · ") }
                                                withStyle(SpanStyle(color = contextColor)) {
                                                    append(merged.fileName)
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
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
                            if (merged.addedCount > 0 || merged.removedCount > 0) {
                                Row(
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

internal fun uniqueProcessRenderKeys(ids: List<String>, prefix: String = "entry"): List<String> {
    val used = HashSet<String>(ids.size * 2)
    return ids.mapIndexed { index, raw ->
        val base = raw.takeIf { it.isNotBlank() } ?: "$prefix#$index"
        var candidate = base
        if (!used.add(candidate)) {
            candidate = "$base#$index"
            var suffix = 1
            while (!used.add(candidate)) {
                candidate = "$base#$index:$suffix"
                suffix++
            }
        }
        candidate
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

    val density = LocalDensity.current
    val fallbackViewportPx = with(density) { 260.dp.toPx() }
    val globalWidestLineWidthPx = rememberDiffWidestLineWidthPx(displayHunks, codeStyle)
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
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
                var rowOffsetInColumn by remember(hunkIdx) { mutableFloatStateOf(0f) }
                ViewportDiffHunkView(
                    diffLines = hunk.diffLines,
                    language = language,
                    colors = colors,
                    codeStyle = codeStyle,
                    gutterNumStyle = gutterNumStyle,
                    gutterWidthDp = gutterWidthDp,
                    gutterDefaultBg = gutterDefaultBg,
                    borderColor = borderColor,
                    addGutterBg = addGutterBg,
                    addRowBg = addRowBg,
                    addBarColor = addBarColor,
                    delGutterBg = delGutterBg,
                    delRowBg = delRowBg,
                    delBarColor = delBarColor,
                    vPad = vPad,
                    hScroll = hScroll,
                    minCodeContentWidthPx = globalWidestLineWidthPx,
                    viewportTopPx = { vScroll.value.toFloat() - rowOffsetInColumn },
                    viewportHeightPx = { if (viewportHeightPx > 1f) viewportHeightPx else fallbackViewportPx },
                    gutterMeasurer = textMeasurer,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        rowOffsetInColumn = coords.positionInParent().y
                    },
                )
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
