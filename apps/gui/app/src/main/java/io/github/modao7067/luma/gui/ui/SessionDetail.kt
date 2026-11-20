package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.*
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.LocalOverScrollState
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.overScrollVertical

@Composable
internal fun SessionDetail(
    state: CockpitState,
    view: SessionView,
    actions: CockpitActions,
    onPickImages: () -> Unit,
    onDrawer: () -> Unit = actions::showSessions,
    onNew: () -> Unit = {},
    onSettings: () -> Unit = {},
    onUsage: () -> Unit = {},
    currentSessionId: (() -> String?)? = null,
    onTakePhoto: (() -> Unit)? = null, takePhotoEnabled: Boolean = true,
) {
    val selection = rememberUpdatedState(state.selected?.summary?.id)
    val selectionProvider = rememberUpdatedState(currentSessionId)
    val sessionId = view.summary.id
    fun ownsSession(): Boolean {
        val live = selectionProvider.value
        return (if (live != null) live() else selection.value) == sessionId
    }
    val interactive = ownsSession()
    val guardedActions = remember(sessionId, actions) {
        CurrentSessionActions(actions) { ownsSession() }
    }
    CompositionLocalProvider(LocalSessionInteractive provides interactive,
        LocalMediaContext provides MediaContext(view.summary.id, view.summary.cwd)) {
        Box(Modifier.fillMaxSize().then(if (interactive) Modifier else Modifier
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })) {
            SessionDetailContent(state, view, guardedActions,
                onPickImages = { if (ownsSession()) onPickImages() },
                onDrawer = { if (ownsSession()) onDrawer() },
                onNew = { if (ownsSession()) onNew() },
                onSettings = { if (ownsSession()) onSettings() },
                onUsage = { if (ownsSession()) onUsage() },
                onTakePhoto = onTakePhoto?.let { action -> { if (ownsSession()) action() } }, takePhotoEnabled = takePhotoEnabled)
        }
    }
}

@Composable
private fun SessionDetailContent(
    state: CockpitState, view: SessionView, actions: CockpitActions,
    onPickImages: () -> Unit, onDrawer: () -> Unit, onNew: () -> Unit,
    onSettings: () -> Unit = {},
    onUsage: () -> Unit = {},
    onTakePhoto: (() -> Unit)? = null, takePhotoEnabled: Boolean = true,
) = ConversationTypography {
    var requestId by remember(view.summary.id) { mutableStateOf<String?>(null) }
    var openedAgentKey by rememberSaveable(view.summary.id) { mutableStateOf<String?>(null) }
    var lastSyncedBackendKey by remember(view.summary.id) { mutableStateOf<String?>(null) }
    val externalDetail = state.subAgentDetail?.takeIf { it.parentSessionId == view.summary.id }
    val externalKey = externalDetail?.let { subAgentIdentityKey(view.summary.id, it.agent.id) }
    if (externalKey != lastSyncedBackendKey) {
        lastSyncedBackendKey = externalKey
        openedAgentKey = externalKey
    }
    val isCurrentSession = LocalSessionInteractive.current
    SideEffect {
        if (!isCurrentSession) {
            requestId = null
            openedAgentKey = null
        }
    }
    val activeAgentId = openedAgentKey?.let { parseSubAgentIdentityKey(it, view.summary.id) }
    val currentSubAgentDetail = remember(view.summary.id, activeAgentId, view.subAgents, externalDetail) {
        if (activeAgentId == null) {
            null
        } else {
            val live = view.subAgents.find { it.id == activeAgentId }
            val matchedExternal = externalDetail?.takeIf { it.agent.id == activeAgentId }
            when {
                matchedExternal != null -> matchedExternal.copy(agent = live ?: matchedExternal.agent)
                live != null -> SubAgentDetailView(parentSessionId = view.summary.id, agent = live)
                else -> null
            }
        }
    }
    if (openedAgentKey != null && currentSubAgentDetail == null) {
        SideEffect { openedAgentKey = null }
    }
    var retainedSubAgentDetail by remember(view.summary.id) { mutableStateOf(currentSubAgentDetail) }
    if (currentSubAgentDetail != null) retainedSubAgentDetail = currentSubAgentDetail
    var capsuleOrigin by remember(view.summary.id) { mutableStateOf(TransformOrigin(0.10f, 0.88f)) }
    var detailBackProgress by remember(view.summary.id) { mutableFloatStateOf(0f) }
    val detailOpen = currentSubAgentDetail != null
    val rawDetailFraction by animateFloatAsState(
        targetValue = if (detailOpen) 1f else 0f,
        animationSpec = spatialSpring(),
        label = "subagent-detail-motion",
    )
    val detailFraction = (rawDetailFraction * (1f - detailBackProgress * 0.35f)).coerceIn(0f, 1f)
    val detailVisible = spatialContentVisible(detailOpen, rawDetailFraction)
    fun openSubAgentDetail(agentId: String) {
        openedAgentKey = subAgentIdentityKey(view.summary.id, agentId)
        actions.openSubAgent(view.summary.id, agentId)
    }
    fun closeSubAgentDetail() {
        openedAgentKey = null
        actions.closeSubAgent()
    }
    PredictiveBackHandler(enabled = isCurrentSession && detailOpen) { progress ->
        try {
            progress.collect { event ->
                detailBackProgress = event.progress.coerceIn(0f, 1f)
            }
            detailBackProgress = 0f
            closeSubAgentDetail()
        } catch (cancelled: CancellationException) {
            detailBackProgress = 0f
            throw cancelled
        }
    }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val rawImeBottomPx = WindowInsets.ime.getBottom(density)
    val imeBottomPx = if (requestId != null) 0 else rawImeBottomPx
    val imeVisible = imeBottomPx > 0
    val handledRequestIds = remember(view.summary.id) { view.requests.mapTo(HashSet()) { it.id } }
    val liveLeadRequestId = view.requests.firstOrNull()?.id
    LaunchedEffect(isCurrentSession, detailOpen, state.browserOpen, liveLeadRequestId, requestId) {
        if (isCurrentSession && !detailOpen && !state.browserOpen && requestId == null && liveLeadRequestId != null) {
            if (handledRequestIds.add(liveLeadRequestId) && rawImeBottomPx <= 0 && view.draft.isEmpty()) {
                focusManager.clearFocus()
                requestId = liveLeadRequestId
            }
        }
    }
    val initialExpanded = imeVisible || view.draft.isNotEmpty() || view.attachments.isNotEmpty() || view.skills.isNotEmpty()
    var composerSettled by remember(view.summary.id) { mutableStateOf(!initialExpanded) }
    var queueCollapsing by remember(view.summary.id) { mutableStateOf(false) }
    var sendCollapseArmed by remember(view.summary.id) { mutableStateOf(false) }
    var lockedBottomPadPx by remember(view.summary.id) { mutableIntStateOf(0) }
    var lastLiveBottomPadPx by remember(view.summary.id) { mutableIntStateOf(0) }
    val bottomChromeSettled = imeBottomPx <= 0 && composerSettled
    val releasedQueueIds = remember(view.summary.id) { HashSet<String>().apply { addAll(view.queue.map { it.id }) } }
    val currentQueueIds = remember(view.queue) { view.queue.mapTo(HashSet()) { it.id } }
    releasedQueueIds.retainAll(currentQueueIds)
    if (view.loading || bottomChromeSettled) {
        releasedQueueIds.addAll(currentQueueIds)
    }
    val gatedQueueView = remember(view, bottomChromeSettled, currentQueueIds) {
        if (view.loading || bottomChromeSettled) {
            view
        } else {
            val visibleQueue = view.queue.filter { it.id in releasedQueueIds }
            if (visibleQueue.size == view.queue.size) view else view.copy(queue = visibleQueue)
        }
    }
    val hasVisibleQueueItems = gatedQueueView.queue.isNotEmpty() || gatedQueueView.queueRecovery != null
    var hadVisibleQueueItems by remember(view.summary.id) { mutableStateOf(hasVisibleQueueItems) }
    SideEffect {
        hadVisibleQueueItems = hasVisibleQueueItems
    }
    val queueCollapseStarting = hadVisibleQueueItems && !hasVisibleQueueItems
    val effectiveQueueCollapsing = queueCollapsing || queueCollapseStarting
    val activeWide = imeVisible || view.draft.isNotEmpty() || view.attachments.isNotEmpty() || view.skills.isNotEmpty() || gatedQueueView.queue.isNotEmpty()
    val composerWidthState = animateFloatAsState(if (activeWide) 1f else 0.88f, spatialSpring(), label = "composer-width")
    val (allRows, openProcessRowId) = remember(view.messages, view.subAgents, view.activeTurnId, view.loading) {
        sessionConversationRows(view.messages, view.subAgents, view.loading, view.activeTurnId)
    }
    val processSlotKeys = remember(allRows) { processGroupSlotKeys(allRows) }
    val nativeProcessDurations = remember(allRows, openProcessRowId) {
        buildMap<String, Long> {
            allRows.forEachIndexed { idx, r ->
                if (r.process) {
                    nativeProcessGroupDurationMs(allRows, idx, turnOpen = r.id == openProcessRowId)?.let { put(r.id, it) }
                }
            }
        }
    }
    val releasedUserRowIds = remember(view.summary.id) {
        HashSet<String>().apply {
            allRows.filter { !it.process && it.entries.firstOrNull()?.role == "user" }.forEach { add(it.id) }
        }
    }
    val allLastUserPendingDelivery = allRows.lastOrNull { !it.process && it.entries.firstOrNull()?.role == "user" }
        ?.entries?.firstOrNull()?.delivery?.isNotEmpty() == true
    val allHistoryLoading = view.loading && !allLastUserPendingDelivery
    val readyToRevealTurn = allHistoryLoading || (bottomChromeSettled && !effectiveQueueCollapsing)
    if (readyToRevealTurn) {
        allRows.forEach { row ->
            if (!row.process && row.entries.firstOrNull()?.role == "user") {
                releasedUserRowIds.add(row.id)
            }
        }
    }
    val gatedRowIndex = if (readyToRevealTurn) -1 else allRows.indexOfFirst { row ->
        !row.process && row.entries.firstOrNull()?.role == "user" && row.id !in releasedUserRowIds
    }
    val rows = if (gatedRowIndex >= 0) allRows.subList(0, gatedRowIndex) else allRows
    if (!allHistoryLoading && (gatedRowIndex >= 0 || gatedQueueView.queue.size < view.queue.size) && !sendCollapseArmed) {
        sendCollapseArmed = true
        lockedBottomPadPx = maxOf(lockedBottomPadPx, lastLiveBottomPadPx)
    }
    val lastUserRowIndex = remember(rows) {
        rows.indexOfLast { !it.process && it.entries.firstOrNull()?.role == "user" }
    }
    val lastUserRowPendingDelivery = lastUserRowIndex >= 0 &&
        rows[lastUserRowIndex].entries.firstOrNull()?.delivery?.isNotEmpty() == true
    val historyLoading = view.loading && !lastUserRowPendingDelivery
    val anchoring = rememberConversationRowAnchoring(view.summary.id, rows, historyLoading, openProcessRowId = openProcessRowId)
    val list = anchoring.list
    val tailFollow = anchoring.tailFollow
    val arrivingRowIds = anchoring.arrivingRowIds
    val rowHeights = anchoring.rowHeights
    val measuredRowHeights = anchoring.measuredRowHeights
    val scope = rememberCoroutineScope()
    val rowTopSpacingPx = with(density) { 20.dp.roundToPx() }
    val allUserRowIds = remember(allRows) {
        allRows.filter { !it.process && it.entries.firstOrNull()?.role == "user" }.mapTo(HashSet()) { it.id }
    }
    releasedUserRowIds.retainAll(allUserRowIds)
    val lastUserRowKey = if (lastUserRowIndex >= 0) anchoring.key(rows[lastUserRowIndex].id) else null
    var savedAnchoredUserRowKey by rememberSaveable(view.summary.id) {
        mutableStateOf(if (rows.size == 1 && isLivePendingUserRow(rows.first())) null else lastUserRowKey)
    }
    var readingAnchorRowKey by rememberSaveable(view.summary.id) { mutableStateOf<String?>(null) }
    val anchoredUserRowKeys = remember(view.summary.id) {
        HashSet<String>().apply {
            savedAnchoredUserRowKey?.let(::add)
            rows.forEach { r ->
                if (!r.process && r.entries.firstOrNull()?.role == "user" && !(rows.size == 1 && isLivePendingUserRow(r))) {
                    add(anchoring.key(r.id))
                }
            }
        }
    }
    val currentUserKeys = remember(allRows, anchoring) {
        allRows.filter { !it.process && it.entries.firstOrNull()?.role == "user" }.mapTo(HashSet()) { anchoring.key(it.id) }
    }
    anchoredUserRowKeys.retainAll(currentUserKeys)
    if (historyLoading && lastUserRowKey != null) {
        anchoredUserRowKeys.add(lastUserRowKey)
        savedAnchoredUserRowKey = lastUserRowKey
    }
    val willAnchorNewUserRow = !historyLoading && lastUserRowKey != null && lastUserRowKey !in anchoredUserRowKeys
    if (willAnchorNewUserRow) {
        tailFollow.anchoringInProgress = true
    }
    if (sendCollapseArmed && readyToRevealTurn) {
        val residualDrop = (lockedBottomPadPx - lastLiveBottomPadPx).coerceAtLeast(0)
        sendCollapseArmed = false
        lockedBottomPadPx = 0
        if (!willAnchorNewUserRow && residualDrop > 0 && rows.isNotEmpty()) {
            tailFollow.collapseHoldPx = maxOf(tailFollow.collapseHoldPx, residualDrop)
        }
    }
    LaunchedEffect(lastUserRowKey) {
        if (!historyLoading && lastUserRowKey != null && lastUserRowKey !in anchoredUserRowKeys) {
            anchoredUserRowKeys.add(lastUserRowKey)
            savedAnchoredUserRowKey = lastUserRowKey
            readingAnchorRowKey = lastUserRowKey
            tailFollow.resetReadingSpace()
            val anchorOffsetPx = if (lastUserRowIndex > 0) rowTopSpacingPx else 0
            try {
                tailFollow.anchorRowToTop(lastUserRowIndex, anchorOffsetPx, list)
            } finally {
                anchoring.markRowComposed(lastUserRowKey)
            }
        }
    }
    var retainedSheetRequest by remember(view.summary.id) { mutableStateOf<UserRequest?>(null) }
    if (!isCurrentSession || detailOpen) {
        if (retainedSheetRequest != null) {
            SideEffect { retainedSheetRequest = null }
        }
    }
    val liveSheetRequest = if (isCurrentSession && !detailOpen) {
        requestId?.let { id -> view.requests.find { it.id == id } }
    } else {
        null
    }
    if (liveSheetRequest != null) {
        retainedSheetRequest = liveSheetRequest
    }
    val displaySheetRequest = if (isCurrentSession && !detailOpen) {
        requestId?.let { id -> liveSheetRequest ?: retainedSheetRequest?.takeIf { it.id == id } }
    } else {
        null
    }
    if (isCurrentSession && !detailOpen && requestId != null && displaySheetRequest == null) {
        LaunchedEffect(requestId) { requestId = null }
    }
    val hasSubAgentCapsule = view.subAgents.isNotEmpty()
    val sheetBlocking = displaySheetRequest != null && liveSheetRequest != null
    val parentInteractive = isCurrentSession && !detailOpen && !state.browserOpen && !sheetBlocking
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSessionInteractive provides parentInteractive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (parentInteractive) Modifier
                        else Modifier
                            .clearAndSetSemantics {}
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            },
                    ),
            ) {
                LiquidScaffold(
                    floatingBottomClearance = floatingConversationClearance(hasSubAgentCapsule),
                    topBar = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConversationHeader(
                                view,
                                actions,
                                onDrawer,
                                onNew,
                                onTerminal = { actions.openShell(actions.environment, "/workspace") },
                                onSettings = onSettings,
                                onUsage = onUsage,
                                hasError = view.error != null || state.error != null,
                            )
                            val currentRequest = view.requests.firstOrNull()
                            var retainedRequest by remember(view.summary.id) { mutableStateOf(currentRequest) }
                            SideEffect { if (currentRequest != null) retainedRequest = currentRequest }
                            AnimatedVisibility(
                                visible = currentRequest != null,
                                enter = collapsibleEnter(),
                                exit = collapsibleExit(),
                            ) {
                                val request = currentRequest ?: retainedRequest
                                if (request != null) {
                                    val bannerPalette = rememberRequestSheetPalette()
                                    val langCode = LumaI18n.langCode()
                                    val bannerTitle = remember(request.title, request.body, request.questions, langCode) {
                                        requestBannerTitle(request)
                                    }
                                    Surface(
                                        shape = LumaCardShape(18.dp),
                                        color = bannerPalette.cardBg,
                                        contentColor = bannerPalette.textPrimary,
                                        border = BorderStroke(0.5.dp, bannerPalette.cardBorder),
                                        tonalElevation = 0.dp,
                                        shadowElevation = if (bannerPalette.dark) 6.dp else 3.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(start = 13.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(bannerPalette.primaryBg),
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    bannerTitle,
                                                    style = MaterialTheme.typography.titleSmall.copy(
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    ),
                                                    color = bannerPalette.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (view.requests.size > 1) {
                                                    Text(
                                                        tr("${view.requests.size} 项待处理", "${view.requests.size} pending"),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                        color = bannerPalette.textMuted,
                                                    )
                                                }
                                            }
                                            val requestHaptic = LocalHapticFeedback.current
                                            Surface(
                                                onClick = {
                                                    requestHaptic.tick()
                                                    focusManager.clearFocus()
                                                    requestId = request.id
                                                },
                                                enabled = currentRequest != null,
                                                shape = CircleShape,
                                                color = bannerPalette.primaryBg,
                                                contentColor = bannerPalette.primaryText,
                                                modifier = Modifier
                                                    .height(31.dp)
                                                    .blockHorizontalPageSwipe(enabled = currentRequest != null),
                                            ) {
                                                Box(
                                                    Modifier.padding(horizontal = 13.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        if (request.answering) tr("提交中", "Sending") else tr("处理", "Review"),
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontSize = 12.5.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                        ),
                                                        color = bannerPalette.primaryText,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    wrapTop = false,
                    wrapBottom = false,
                    overlay = { padding ->
                        ConversationScrollOverlay(
                            visible = !tailFollow.atTail,
                            padding = padding,
                            subAgents = view.subAgents,
                            selectedAgentId = currentSubAgentDetail?.agent?.id,
                            onSelectSubAgent = { agent -> openSubAgentDetail(agent.id) },
                            onOriginChanged = { capsuleOrigin = it },
                        ) {
                            scope.launch { tailFollow.jumpToTail(list) }
                        }
                        val tailAssistantSig = view.messages.lastOrNull()
                            ?.takeIf { it.role == "assistant" && it.text.isNotBlank() }
                            ?.let { "${it.id}:${it.text.length}" }
                        BrowserFloatingPreview(
                            sessionId = view.summary.id,
                            browserOpen = state.browserOpen,
                            padding = padding,
                            activeTurnId = view.activeTurnId,
                            tailAssistantSignature = tailAssistantSig,
                            onOpenBrowser = actions::openBrowser,
                        )
                    },
                    bottomBar = {
                        val dynamicWidthModifier = Modifier.layout { measurable, constraints ->
                            val fraction = composerWidthState.value.coerceIn(0.88f, 1f)
                            val w = (constraints.maxWidth * fraction).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
                            val placeable = measurable.measure(Constraints.fixedWidth(w).copy(minHeight = 0, maxHeight = constraints.maxHeight))
                            layout(w, placeable.height) { placeable.placeRelative(0, 0) }
                        }
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            QueuedMessages(
                                view = gatedQueueView,
                                actions = actions,
                                modifier = dynamicWidthModifier,
                                onCollapsingChanged = { collapsing -> queueCollapsing = collapsing },
                            )
                            ComposerGlass(
                                dynamicWidthModifier,
                            ) {
                                CompactComposer(
                                    state = state,
                                    view = view,
                                    actions = actions,
                                    onPickImages = onPickImages,
                                    onModels = {},
                                    onTakePhoto = onTakePhoto,
                                    takePhotoEnabled = takePhotoEnabled,
                                    suppressIme = requestId != null,
                                    onComposerSettledChanged = { settled -> composerSettled = settled },
                                    onCollapseRequested = {
                                        composerSettled = false
                                        sendCollapseArmed = true
                                        lockedBottomPadPx = maxOf(lockedBottomPadPx, lastLiveBottomPadPx)
                                    },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val isListScrolling = list.isScrollInProgress
                    var lazyViewportHeightPx by rememberSaveable(view.summary.id) { mutableIntStateOf(0) }
                    val turnLayoutReaders = remember(view.summary.id) { LinkedHashSet<() -> Any?>() }
                    val turnAnimatingReaders = remember(view.summary.id) { LinkedHashSet<() -> Boolean>() }
                    val topPadPx = with(density) { padding.calculateTopPadding().roundToPx() }
                    val baseBottomPadPx = with(density) { padding.calculateBottomPadding().roundToPx() }
                    val safeBottomDp = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
                    val steadyBottomPadPx = with(density) {
                        steadyConversationBottomPaddingDp(floatingConversationClearance(hasSubAgentCapsule), safeBottomDp).roundToPx()
                    }
                    SideEffect {
                        lastLiveBottomPadPx = baseBottomPadPx
                        if (sendCollapseArmed && baseBottomPadPx > lockedBottomPadPx) {
                            lockedBottomPadPx = baseBottomPadPx
                        }
                    }
                    val sendCollapsePadDropPx = if (sendCollapseArmed && !readyToRevealTurn) {
                        (maxOf(lockedBottomPadPx, baseBottomPadPx) - baseBottomPadPx).coerceAtLeast(0)
                    } else 0
                    val effectiveBottomPadPx = if (imeBottomPx <= 0 && !sendCollapseArmed && bottomChromeSettled) {
                        baseBottomPadPx
                    } else {
                        steadyBottomPadPx
                    }
                    val usableViewportPx = (lazyViewportHeightPx - topPadPx - effectiveBottomPadPx).coerceAtLeast(0)
                    val defaultUserHeightPx = with(density) { 64.dp.roundToPx() }
                    val currentRowKeys = remember(rows, anchoring) {
                        rows.mapTo(HashSet(rows.size * 2)) { anchoring.key(it.id) }
                    }
                    measuredRowHeights.keys.retainAll(currentRowKeys)
                    val activeTurnSplitIndex = if (((readingAnchorRowKey != null && readingAnchorRowKey == lastUserRowKey) || willAnchorNewUserRow) && lastUserRowIndex in 0 until rows.size) {
                        lastUserRowIndex
                    } else {
                        -1
                    }
                    val indexedRows = if (activeTurnSplitIndex >= 0) rows.subList(0, activeTurnSplitIndex) else rows
                    val activeTurnRows = if (activeTurnSplitIndex >= 0) rows.subList(activeTurnSplitIndex, rows.size) else emptyList()
                    if (rows.isEmpty()) {
                        tailFollow.readingSpaceBottomPx = 0
                        tailFollow.resetReadingSpace()
                    }
                    CompositionLocalProvider(
                        LocalConversationScrolling provides isListScrolling,
                        LocalOverScrollState provides tailFollow.overscrollState,
                        LocalManualLayoutToggle provides tailFollow::notifyManualLayoutToggle,
                        LocalTurnLayoutReaders provides turnLayoutReaders,
                        LocalTurnAnimatingReaders provides turnAnimatingReaders,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onSizeChanged { lazyViewportHeightPx = it.height },
                        ) {
                            LazyColumn(
                                state = list,
                                overscrollEffect = null,
                                modifier = Modifier.fillMaxSize().testTag("conversation-list").nestedScroll(tailFollow.nestedScrollConnection).overScrollVertical(),
                                contentPadding = PaddingValues(
                                    start = 18.dp,
                                    end = 18.dp,
                                    top = padding.calculateTopPadding(),
                                    bottom = padding.calculateBottomPadding(),
                                ),
                                verticalArrangement = Arrangement.Top,
                            ) {
                                itemsIndexed(
                                    indexedRows,
                                    key = { _, it -> anchoring.key(it.id) },
                                    contentType = { _, it -> if (it.process) "process" else it.entries.firstOrNull()?.role ?: "message" },
                                ) { index, row ->
                                    val rowKey = anchoring.key(row.id)
                                    val topSpacing = if (index > 0) 20.dp else 0.dp
                                    val animateRowArrival = remember(rowKey) { rowKey in arrivingRowIds }
                                    SideEffect { anchoring.markRowComposed(rowKey) }
                                    val isTrailingStaticRow = activeTurnSplitIndex < 0 && index == indexedRows.lastIndex
                                    Box(
                                        Modifier.layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints.copy(minHeight = 0))
                                            val prevMeasured = measuredRowHeights.put(rowKey, placeable.height)
                                            if (prevMeasured == null && (tailFollow.anyRevealAnimating || tailFollow.collapseSettling || tailFollow.collapseHoldPx > 0)) {
                                                tailFollow.heldTotalPx += placeable.height
                                            }
                                            val extraBottomPx = if (isTrailingStaticRow && lastUserRowIndex in 0 until rows.size && usableViewportPx > 0) {
                                                tailFollow.manualLayoutPulse.intValue
                                                turnLayoutReaders.forEach { it.invoke() }
                                                val currentlyAnimating = turnAnimatingReaders.any { it.invoke() }
                                                val wasAnimating = tailFollow.anyRevealAnimating
                                                tailFollow.readingSpaceActive = false
                                                val naturalTotalPx = measuredRowHeights.values.sum()
                                                val holdPx = when {
                                                    currentlyAnimating -> {
                                                        if (tailFollow.heldTotalPx < naturalTotalPx) {
                                                            tailFollow.heldTotalPx = naturalTotalPx
                                                        }
                                                        val computedHold = (tailFollow.heldTotalPx - naturalTotalPx).coerceAtLeast(0)
                                                        if (tailFollow.collapseHoldPx != computedHold) {
                                                            tailFollow.collapseHoldPx = computedHold
                                                        }
                                                        computedHold
                                                    }
                                                    wasAnimating && tailFollow.heldTotalPx > naturalTotalPx -> {
                                                        val computedHold = (tailFollow.heldTotalPx - naturalTotalPx).coerceAtLeast(0)
                                                        if (tailFollow.collapseHoldPx != computedHold) {
                                                            tailFollow.collapseHoldPx = computedHold
                                                        }
                                                        computedHold
                                                    }
                                                    tailFollow.collapseSettling || tailFollow.collapseHoldPx > 0 -> {
                                                        val prevNatural = (tailFollow.heldTotalPx - tailFollow.collapseHoldPx).coerceAtLeast(0)
                                                        val growth = (naturalTotalPx - prevNatural).coerceAtLeast(0)
                                                        val adjustedHold = (tailFollow.collapseHoldPx - growth).coerceAtLeast(0)
                                                        if (tailFollow.collapseHoldPx != adjustedHold) {
                                                            tailFollow.collapseHoldPx = adjustedHold
                                                        }
                                                        tailFollow.heldTotalPx = naturalTotalPx + adjustedHold
                                                        adjustedHold
                                                    }
                                                    else -> {
                                                        tailFollow.heldTotalPx = naturalTotalPx
                                                        0
                                                    }
                                                }
                                                if (tailFollow.anyRevealAnimating != currentlyAnimating) {
                                                    tailFollow.anyRevealAnimating = currentlyAnimating
                                                }
                                                maxOf(holdPx, sendCollapsePadDropPx)
                                            } else if (isTrailingStaticRow) {
                                                tailFollow.readingSpaceActive = false
                                                sendCollapsePadDropPx
                                            } else {
                                                0
                                            }
                                            if (isTrailingStaticRow) {
                                                tailFollow.readingSpaceBottomPx = extraBottomPx
                                            }
                                            val totalHeight = (placeable.height + extraBottomPx).coerceIn(constraints.minHeight, constraints.maxHeight)
                                            layout(placeable.width, totalHeight) {
                                                placeable.placeRelative(0, 0)
                                            }
                                        },
                                    ) {
                                        if (row.process) {
                                            val entries = remember(row.entries, row.agents) { processGroupEntries(row) }
                                            ConversationProcessRowItem(
                                                identity = view.summary.id,
                                                rowKey = rowKey,
                                                animateArrival = animateRowArrival,
                                                topSpacing = topSpacing,
                                                entries = entries,
                                                cli = view.summary.cli,
                                                durationMsOverride = processSlotKeys[row.id]?.let { view.processDurations[it] },
                                                fallbackDurationMs = nativeProcessDurations[row.id],
                                                turnOpen = row.id == openProcessRowId,
                                                expansionState = anchoring.processExpansionState(rowKey),
                                            )
                                        } else {
                                            val entry = row.entries.first()
                                            val animateMessagePop = animateRowArrival && entry.role == "user"
                                            val markdown = if (entry.role == "assistant") rememberMarkdownPresentation(
                                                identity = "${view.summary.id}:$rowKey", source = entry.text,
                                                complete = view.activeTurnId == null || view.messages.lastOrNull { it.role == "assistant" }?.id != entry.id,
                                                animate = true, animateArrival = false,
                                            ) else null
                                            SmoothTailGrowth(
                                                rowId = rowKey,
                                                contentKey = (((markdown?.visibleUnits ?: entry.text.length) xor System.identityHashCode(markdown?.document)) * 31 + entry.images.size) * 31 + entry.delivery.hashCode() * 7 + entry.status.hashCode(),
                                                knownHeights = rowHeights,
                                                animateArrival = animateRowArrival && entry.role != "user",
                                                animatePop = animateMessagePop,
                                                topSpacing = topSpacing,
                                            ) {
                                                ConversationMessage(entry, actions, view.summary.cli, markdown)
                                            }
                                        }
                                    }
                                }
                                if (activeTurnRows.isNotEmpty()) {
                                    item(
                                        key = lastUserRowKey!!,
                                        contentType = "active-turn",
                                    ) {
                                        val userKey = lastUserRowKey
                                        val userTopAnchorOffsetPx = if (activeTurnSplitIndex > 0) rowTopSpacingPx else 0
                                        Box(
                                            Modifier.layout { measurable, constraints ->
                                                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                                                val prevMeasured = measuredRowHeights.put(userKey, placeable.height)
                                                if (prevMeasured == null && (tailFollow.anyRevealAnimating || tailFollow.collapseSettling || tailFollow.collapseHoldPx > 0)) {
                                                    tailFollow.heldTotalPx += placeable.height
                                                }
                                                val extraBottomPx = if (usableViewportPx > 0) {
                                                    tailFollow.manualLayoutPulse.intValue
                                                    turnLayoutReaders.forEach { it.invoke() }
                                                    val currentlyAnimating = turnAnimatingReaders.any { it.invoke() }
                                                    val wasAnimating = tailFollow.anyRevealAnimating
                                                    val visibleTurnHeightPx = (placeable.height - userTopAnchorOffsetPx).coerceAtLeast(0)
                                                    val baseReadingSpacePx = (usableViewportPx - visibleTurnHeightPx).coerceAtLeast(0)
                                                    tailFollow.readingSpaceActive = baseReadingSpacePx > 0
                                                    val naturalTotalPx = measuredRowHeights.values.sum() + baseReadingSpacePx
                                                    val scrolledPastTurnAnchor = list.firstVisibleItemIndex == activeTurnSplitIndex &&
                                                        list.firstVisibleItemScrollOffset > userTopAnchorOffsetPx + 1
                                                    val holdPx = when {
                                                        baseReadingSpacePx > 0 && !scrolledPastTurnAnchor -> {
                                                            tailFollow.heldTotalPx = naturalTotalPx
                                                            if (tailFollow.collapseHoldPx != 0) {
                                                                tailFollow.collapseHoldPx = 0
                                                            }
                                                            0
                                                        }
                                                        currentlyAnimating -> {
                                                            if (tailFollow.heldTotalPx < naturalTotalPx) {
                                                                tailFollow.heldTotalPx = naturalTotalPx
                                                            }
                                                            val computedHold = (tailFollow.heldTotalPx - naturalTotalPx).coerceAtLeast(0)
                                                            if (tailFollow.collapseHoldPx != computedHold) {
                                                                tailFollow.collapseHoldPx = computedHold
                                                            }
                                                            computedHold
                                                        }
                                                        wasAnimating && tailFollow.heldTotalPx > naturalTotalPx -> {
                                                            val computedHold = (tailFollow.heldTotalPx - naturalTotalPx).coerceAtLeast(0)
                                                            if (tailFollow.collapseHoldPx != computedHold) {
                                                                tailFollow.collapseHoldPx = computedHold
                                                            }
                                                            computedHold
                                                        }
                                                        tailFollow.collapseSettling || tailFollow.collapseHoldPx > 0 -> {
                                                            val prevNatural = (tailFollow.heldTotalPx - tailFollow.collapseHoldPx).coerceAtLeast(0)
                                                            val growth = (naturalTotalPx - prevNatural).coerceAtLeast(0)
                                                            val adjustedHold = (tailFollow.collapseHoldPx - growth).coerceAtLeast(0)
                                                            if (tailFollow.collapseHoldPx != adjustedHold) {
                                                                tailFollow.collapseHoldPx = adjustedHold
                                                            }
                                                            tailFollow.heldTotalPx = naturalTotalPx + adjustedHold
                                                            adjustedHold
                                                        }
                                                        else -> {
                                                            tailFollow.heldTotalPx = naturalTotalPx
                                                            0
                                                        }
                                                    }
                                                    if (tailFollow.anyRevealAnimating != currentlyAnimating) {
                                                        tailFollow.anyRevealAnimating = currentlyAnimating
                                                    }
                                                    baseReadingSpacePx + maxOf(holdPx, sendCollapsePadDropPx)
                                                } else {
                                                    tailFollow.readingSpaceActive = false
                                                    sendCollapsePadDropPx
                                                }
                                                tailFollow.readingSpaceBottomPx = extraBottomPx
                                                val totalHeight = (placeable.height + extraBottomPx).coerceIn(constraints.minHeight, constraints.maxHeight)
                                                layout(placeable.width, totalHeight) {
                                                    placeable.placeRelative(0, 0)
                                                }
                                            },
                                        ) {
                                            Column(Modifier.fillMaxWidth()) {
                                                activeTurnRows.forEachIndexed { turnOffset, row ->
                                                    val rowKey = anchoring.key(row.id)
                                                    val topSpacing = if (activeTurnSplitIndex + turnOffset > 0) 20.dp else 0.dp
                                                    key(rowKey) {
                                                        val animateRowArrival = remember(rowKey) { rowKey in arrivingRowIds }
                                                        SideEffect { anchoring.markRowComposed(rowKey) }
                                                        if (row.process) {
                                                            val entries = remember(row.entries, row.agents) { processGroupEntries(row) }
                                                            ConversationProcessRowItem(
                                                                identity = view.summary.id,
                                                                rowKey = rowKey,
                                                                animateArrival = animateRowArrival,
                                                                topSpacing = topSpacing,
                                                                entries = entries,
                                                                cli = view.summary.cli,
                                                                durationMsOverride = processSlotKeys[row.id]?.let { view.processDurations[it] },
                                                                fallbackDurationMs = nativeProcessDurations[row.id],
                                                                turnOpen = row.id == openProcessRowId,
                                                                expansionState = anchoring.processExpansionState(rowKey),
                                                            )
                                                        } else {
                                                            val entry = row.entries.first()
                                                            val animateMessagePop = animateRowArrival && entry.role == "user"
                                                            val markdown = if (entry.role == "assistant") rememberMarkdownPresentation(
                                                                identity = "${view.summary.id}:$rowKey", source = entry.text,
                                                                complete = view.activeTurnId == null || view.messages.lastOrNull { it.role == "assistant" }?.id != entry.id,
                                                                animate = true, animateArrival = false,
                                                            ) else null
                                                            SmoothTailGrowth(
                                                                rowId = rowKey,
                                                                contentKey = (((markdown?.visibleUnits ?: entry.text.length) xor System.identityHashCode(markdown?.document)) * 31 + entry.images.size) * 31 + entry.delivery.hashCode() * 7 + entry.status.hashCode(),
                                                                knownHeights = rowHeights,
                                                                animateArrival = animateRowArrival && entry.role != "user",
                                                                animatePop = animateMessagePop,
                                                                topSpacing = topSpacing,
                                                            ) {
                                                                ConversationMessage(entry, actions, view.summary.cli, markdown)
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
        val displayedDetail = currentSubAgentDetail ?: retainedSubAgentDetail
        if (detailVisible && displayedDetail != null) {
            val detailInteractive = isCurrentSession && detailOpen && detailFraction >= 0.5f
            CompositionLocalProvider(LocalSessionInteractive provides detailInteractive) {
                val corner = ((1f - detailFraction.coerceIn(0f, 1f)) * 28f).dp
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val f = detailFraction.coerceIn(0f, 1f)
                            val scale = 0.86f + 0.14f * f
                            scaleX = scale
                            scaleY = scale
                            alpha = f
                            transformOrigin = capsuleOrigin
                        }
                        .clip(LumaCardShape(corner))
                        .then(
                            if (detailInteractive) Modifier
                            else Modifier
                                .clearAndSetSemantics {}
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                },
                        ),
                ) {
                    SubAgentDetailScreen(
                        detail = displayedDetail,
                        cli = view.summary.cli,
                        actions = actions,
                        onBack = ::closeSubAgentDetail,
                    )
                }
            }
        }
        if (displaySheetRequest != null) {
            val activeId = displaySheetRequest.id
            val sheetClosing = liveSheetRequest == null
            RequestDialog(
                request = displaySheetRequest,
                actions = actions,
                canRespond = !sheetClosing && requestConnectionReady(view, activeId),
                reconnecting = view.loading,
                closing = sheetClosing,
                onDismiss = {
                    if (requestId == activeId) requestId = null
                    if (retainedSheetRequest?.id == activeId) retainedSheetRequest = null
                },
            )
        }
    }
}

@Composable
internal fun ConversationMessage(item: ConversationItem, actions: CockpitActions, cli: String, markdown: MarkdownPresentation? = null) {
    val colors = MaterialTheme.colorScheme
    when (item.role) {
        "user" -> UserMessageBubble(item, actions)
        "tool" -> ProcessGroup(listOf(item), cli = cli)
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.role != "assistant" && item.status.isNotBlank()) {
                Text(
                    item.status,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary,
                    fontFamily = terminalFont(),
                )
            }
            MessageMedia(messageMedia(item))
            NativeMarkdown(item.text, actions::previewFile, markdown)
            if (item.detail.isNotBlank()) {
                val detailHaptic = LocalHapticFeedback.current
                val onManualLayoutToggle = LocalManualLayoutToggle.current
                var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        detailHaptic.tick()
                        onManualLayoutToggle?.invoke()
                        expanded = !expanded
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp).blockHorizontalPageSwipe(allowVerticalScroll = true),
                ) {
                    Text(if (expanded) tr("收起详情", "Less") else tr("展开详情", "Details"), style = MaterialTheme.typography.labelMedium)
                }
                TopReveal(expanded = expanded) {
                    Box(Modifier.padding(top = 4.dp)) {
                        NativeMarkdown(item.detail, actions::previewFile)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.ConversationScrollOverlay(
    visible: Boolean,
    padding: PaddingValues,
    subAgents: List<SubAgentState> = emptyList(),
    selectedAgentId: String? = null,
    previewSubAgentsMenu: Boolean = false,
    previewRunningFraction: Float? = null,
    onSelectSubAgent: (SubAgentState) -> Unit = {},
    onOriginChanged: (TransformOrigin) -> Unit = {},
    onJump: () -> Unit,
) {
    val sessionInteractive = LocalSessionInteractive.current
    val hasAgents = subAgents.isNotEmpty()
    var retainedAgents by remember { mutableStateOf(subAgents) }
    if (hasAgents) retainedAgents = subAgents
    val displayedAgents = if (hasAgents) subAgents else retainedAgents
    val runningCount = displayedAgents.count { it.status == "running" }
    var retainedCount by remember { mutableIntStateOf(runningCount.coerceAtLeast(1)) }
    if (runningCount > 0) retainedCount = runningCount
    val numberStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    val numberWidth = with(density) { rememberTextMeasurer().measure(retainedCount.toString(), numberStyle).size.width.toDp() }
    val animatedExtraWidth by androidx.compose.animation.core.animateDpAsState(
        if (runningCount > 0) numberWidth + 10.dp else 0.dp, spatialDpSpring(), label = "subagent-running-width")
    val extraWidth = previewRunningFraction?.let { (numberWidth + 10.dp) * it.coerceIn(0f, 1f) } ?: animatedExtraWidth
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(previewSubAgentsMenu) }
    val menuActive = menuOpen && hasAgents && sessionInteractive
    SideEffect {
        if ((!hasAgents || !sessionInteractive) && menuOpen) {
            menuOpen = false
        }
    }
    var overlayCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lastOrigin by remember { mutableStateOf<TransformOrigin?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                if (overlayCoords !== coordinates) overlayCoords = coordinates
            },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = FloatingChromeHorizontalPadding,
                    end = FloatingChromeHorizontalPadding,
                    bottom = padding.calculateBottomPadding() + FloatingOverlayBottomGap,
                ),
        ) {
            val subAgentsProgressState = animateFloatAsState(
                targetValue = if (hasAgents) 1f else 0f,
                animationSpec = spatialSpring(),
                label = "subagent-capsule-visibility",
            )
            val subAgentsProgress = subAgentsProgressState.value.coerceIn(0f, 1f)
            if (spatialContentVisible(hasAgents, subAgentsProgress)) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            val s = 0.82f + 0.18f * subAgentsProgress
                            scaleX = s
                            scaleY = s
                            clip = false
                        }
                        .onGloballyPositioned { coordinates ->
                            val root = overlayCoords
                            if (root != null && root.isAttached && coordinates.isAttached) {
                                val overlaySize = root.size
                                val sz = coordinates.size
                                if (overlaySize.width > 0 && overlaySize.height > 0) {
                                    val localPos = root.localPositionOf(coordinates, Offset.Zero)
                                    val localCenter = localPos + Offset(with(density) { FloatingChromeSize.toPx() } / 2f, sz.height / 2f)
                                    val nextOrigin = TransformOrigin(
                                        (localCenter.x / overlaySize.width.toFloat()).coerceIn(0f, 1f),
                                        (localCenter.y / overlaySize.height.toFloat()).coerceIn(0f, 1f),
                                    )
                                    if (lastOrigin != nextOrigin) {
                                        lastOrigin = nextOrigin
                                        onOriginChanged(nextOrigin)
                                    }
                                }
                            }
                        },
                ) {
                    val subAgentAnchor = rememberMorphAnchor()
                    val subAgentElastic = rememberElasticPress()
                    Box(
                        Modifier
                            .height(FloatingChromeSize)
                            .width(FloatingChromeSize + extraWidth)
                            .testTag("subagent-capsule")
                            .morphAnchor(
                                anchor = subAgentAnchor,
                                enabled = hasAgents && sessionInteractive,
                                elastic = subAgentElastic,
                                onOpen = { menuOpen = !menuOpen },
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = tr("子代理 ${displayedAgents.size} 个", "${displayedAgents.size} sub-agents")
                                stateDescription = if (menuActive) tr("已展开", "Expanded") else tr("已收起", "Collapsed")
                            },
                    ) {
                        LiquidGlass(
                            Modifier.fillMaxSize(),
                            cornerRadius = FloatingChromeRadius,
                            alphaProvider = { subAgentsProgressState.value.coerceIn(0f, 1f) },
                            press = subAgentElastic,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.size(FloatingChromeSize).align(Alignment.CenterStart), contentAlignment = Alignment.Center) {
                                    Box(Modifier.glyphPulse(runningCount)) {
                                        MenuIcon(MenuSymbol.MODEL, size = 20.dp)
                                    }
                                }
                                Text(retainedCount.toString(), style = numberStyle, maxLines = 1,
                                    modifier = Modifier.align(Alignment.CenterStart).offset(x = 40.dp)
                                        .graphicsLayer { alpha = (extraWidth.value / (numberWidth.value + 10f)).coerceIn(0f, 1f) }
                                        .then(if (runningCount > 0) Modifier else Modifier.clearAndSetSemantics {}))
                            }
                        }
                    }
                    MorphMenu(
                        expanded = menuActive,
                        onDismiss = { menuOpen = false },
                        anchor = subAgentAnchor,
                        width = 280.dp,
                        placement = MorphPlacement.Start,
                        preferAbove = true,
                    ) {
                        SubAgentPopupItems(
                            agents = displayedAgents,
                            selectedAgentId = selectedAgentId,
                            historyExpanded = historyExpanded,
                            onToggleHistory = { historyExpanded = !historyExpanded },
                            enabled = hasAgents && sessionInteractive,
                            onSelect = { agent -> menuOpen = false; onSelectSubAgent(agent) },
                        )
                    }
                }
            }
            val jumpProgressState = animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = spatialSpring(),
                label = "jump-to-tail-visibility",
            )
            val jumpProgress = jumpProgressState.value.coerceIn(0f, 1f)
            if (spatialContentVisible(visible, jumpProgress)) {
                val jumpElastic = rememberElasticPress()
                LiquidGlass(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .graphicsLayer {
                            val s = 0.82f + 0.18f * jumpProgress
                            scaleX = s
                            scaleY = s
                            clip = false
                        }
                        .size(FloatingChromeSize)
                        .liquidClickable(
                            enabled = visible && sessionInteractive,
                            press = jumpElastic,
                            onClick = onJump,
                        )
                        .semantics { contentDescription = tr("↓ 回到底部", "↓ Scroll to bottom") },
                    cornerRadius = FloatingChromeRadius,
                    alphaProvider = { jumpProgressState.value.coerceIn(0f, 1f) },
                    press = jumpElastic,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ComposerGlyph("down")
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberStreamedMarkdownText(id: String, targetText: String, animateArrival: Boolean): String {
    if (LocalInspectionMode.current) return targetText
    var cursor by remember(id) {
        mutableIntStateOf(if (animateArrival && targetText.length <= 240) 0 else targetText.length)
    }
    if (targetText.length < cursor) cursor = targetText.length
    LaunchedEffect(id, targetText) {
        var lastFrameNanos = 0L
        var carry = 0f
        while (cursor < targetText.length) {
            val dtSeconds = withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 16_000_000L else (frameNanos - lastFrameNanos).coerceIn(1_000_000L, 50_000_000L)
                lastFrameNanos = frameNanos
                dt / 1_000_000_000f
            }
            val remaining = targetText.length - cursor
            carry += (45f + remaining / 0.28f) * dtSeconds
            val step = carry.toInt()
            if (step > 0) {
                carry -= step
                var next = (cursor + step).coerceAtMost(targetText.length)
                if (next < targetText.length && Character.isHighSurrogate(targetText[next - 1])) {
                    next = (next + 1).coerceAtMost(targetText.length)
                }
                cursor = next
            }
        }
    }
    return if (cursor >= targetText.length) targetText else targetText.substring(0, cursor)
}
