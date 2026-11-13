package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.OverScrollState
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ConversationRowAnchoring(
    val list: LazyListState,
    val tailFollow: ConversationTailFollow,
    val canonicalRowIds: Map<String, String>,
    val arrivingRowIds: Set<String>,
    val rowHeights: HashMap<String, Int>,
    val measuredRowHeights: HashMap<String, Int>,
) {
    fun key(id: String): String = canonicalRowIds[id] ?: id
}

internal fun resolveCanonicalRowKeys(
    rows: List<ConversationRow>,
    prevRows: List<ConversationRow>,
    canonicalRowIds: MutableMap<String, String>,
    settledRowIds: MutableSet<String>,
    rowHeights: MutableMap<String, Int>,
    measuredRowHeights: MutableMap<String, Int>,
    heightKeyPrefix: String = "",
): Map<String, String> {
    val currentRowIdSet = rows.mapTo(HashSet(rows.size * 2)) { it.id }
    val prevCanonical = HashMap(canonicalRowIds)
    val staleKeys = ArrayList<String>()
    for ((id, target) in canonicalRowIds) {
        if (id !in currentRowIdSet || (target != id && target in currentRowIdSet)) {
            staleKeys.add(id)
        }
    }
    staleKeys.forEach(canonicalRowIds::remove)
    val seenTargets = HashSet<String>(canonicalRowIds.size * 2)
    for (row in rows) {
        val target = canonicalRowIds[row.id] ?: continue
        if (!seenTargets.add(target)) {
            canonicalRowIds.remove(row.id)
        }
    }
    if (prevRows.isNotEmpty()) {
        val prevRowIdSet = prevRows.mapTo(HashSet(prevRows.size * 2)) { it.id }
        val claimedKeys = HashSet<String>(currentRowIdSet).apply { addAll(canonicalRowIds.values) }
        rows.forEachIndexed { idx, row ->
            val oldRow = prevRows.getOrNull(idx)
            if (oldRow != null && oldRow.id != row.id &&
                oldRow.id !in currentRowIdSet && row.id !in prevRowIdSet &&
                oldRow.process == row.process &&
                oldRow.entries.firstOrNull()?.role == row.entries.firstOrNull()?.role
            ) {
                val canonical = prevCanonical[oldRow.id] ?: oldRow.id
                if (canonical !in claimedKeys) {
                    canonicalRowIds[row.id] = canonical
                    claimedKeys.add(canonical)
                    settledRowIds.add(row.id)
                    settledRowIds.add(canonical)
                    rowHeights["$heightKeyPrefix${oldRow.id}"]?.let { h ->
                        rowHeights["$heightKeyPrefix${row.id}"] = h
                        rowHeights["$heightKeyPrefix$canonical"] = h
                    }
                    measuredRowHeights[oldRow.id]?.let { h ->
                        measuredRowHeights[row.id] = h
                        measuredRowHeights[canonical] = h
                    }
                }
            }
        }
    }
    val resolved = HashMap<String, String>(rows.size * 2)
    val emitted = HashSet<String>(rows.size * 2)
    rows.forEachIndexed { idx, row ->
        val candidate = canonicalRowIds[row.id] ?: row.id
        val unique = when {
            emitted.add(candidate) -> candidate
            emitted.add(row.id) -> {
                canonicalRowIds.remove(row.id)
                row.id
            }
            else -> "${row.id}#$idx".also { emitted.add(it) }
        }
        resolved[row.id] = unique
    }
    return resolved
}

@Composable
internal fun rememberConversationRowAnchoring(
    identity: String,
    rows: List<ConversationRow>,
    loading: Boolean,
    heightKeyPrefix: String = "",
): ConversationRowAnchoring {
    val canonicalRowIds = remember(identity) { HashMap<String, String>() }
    val prevRows = remember(identity) { mutableListOf<ConversationRow>() }
    val settledRowIds = remember(identity) { HashSet<String>().apply { addAll(rows.map { it.id }) } }
    val rowHeights = remember(identity) { HashMap<String, Int>() }
    val measuredRowHeights = remember(identity) { HashMap<String, Int>() }
    val resolvedKeys = resolveCanonicalRowKeys(
        rows = rows,
        prevRows = prevRows,
        canonicalRowIds = canonicalRowIds,
        settledRowIds = settledRowIds,
        rowHeights = rowHeights,
        measuredRowHeights = measuredRowHeights,
        heightKeyPrefix = heightKeyPrefix,
    )
    if (loading || (settledRowIds.isEmpty() && rows.size > 1)) {
        rows.forEach {
            settledRowIds.add(it.id)
            settledRowIds.add(resolvedKeys[it.id] ?: it.id)
        }
    }
    val arrivingRowIds = rows.map { resolvedKeys[it.id] ?: it.id }.filterNot { it in settledRowIds }.toSet()
    SideEffect {
        rows.forEach {
            settledRowIds.add(it.id)
            settledRowIds.add(resolvedKeys[it.id] ?: it.id)
        }
        prevRows.clear()
        prevRows.addAll(rows)
    }
    val list = rememberLazyListState()
    val tailFollow = rememberConversationTailFollow(
        list,
        identity = identity,
        ready = !loading,
        followTailGrowth = true,
    )
    return ConversationRowAnchoring(list, tailFollow, resolvedKeys, arrivingRowIds, rowHeights, measuredRowHeights)
}

@Composable
internal fun ConversationProcessRowItem(
    identity: String,
    rowKey: String,
    animateArrival: Boolean,
    topSpacing: Dp,
    entries: List<ConversationItem>,
    cli: String,
) {
    var rowIntroduced by rememberSaveable(identity, rowKey) { mutableStateOf(!animateArrival) }
    LaunchedEffect(Unit) { rowIntroduced = true }
    val processContent: @Composable () -> Unit = {
        Column(
            Modifier.padding(top = topSpacing),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isNotEmpty()) {
                ProcessGroup(entries, cli = cli, animateInitialArrival = false)
            }
        }
    }
    if (animateArrival) {
        TopReveal(expanded = rowIntroduced) { processContent() }
    } else {
        processContent()
    }
}

internal val LocalManualLayoutToggle = compositionLocalOf<(() -> Unit)?> { null }

internal fun conversationTailDistance(
    list: LazyListState,
    readingSpaceBottomPx: Int = 0,
): Int? {
    val info = list.layoutInfo
    if (info.totalItemsCount <= 0) return 0
    val last = info.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index != info.totalItemsCount - 1) return null
    val usableEnd = info.viewportEndOffset - info.afterContentPadding
    val contentHeight = (last.size - readingSpaceBottomPx.coerceAtLeast(0)).coerceAtLeast(0)
    return (last.offset + contentHeight - usableEnd).coerceAtLeast(0)
}

internal class ConversationTailFollow(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val nearBottomPx: Int,
) {
    val overscrollState = OverScrollState()
    internal var readingSpaceBottomPx = 0
    internal var readingSpaceActive = false
    internal var collapseHoldPx by mutableIntStateOf(0)
    internal var heldTotalPx = 0
    internal var anyRevealAnimating by mutableStateOf(false)
    internal var collapseSettling by mutableStateOf(false)
    internal val manualLayoutPulse = mutableIntStateOf(0)
    internal fun resetReadingSpace() {
        readingSpaceActive = false
    }
    val atTail by derivedStateOf {
        !list.canScrollForward || conversationTailDistance(list, readingSpaceBottomPx) == 0
    }
    internal var followingTail by mutableStateOf(true)
    internal var anchoringInProgress by mutableStateOf(false)
    internal var suppressFollowUntilNanos by mutableLongStateOf(0L)
    internal var gestureRevision by mutableIntStateOf(0)
        private set
    private var lastDragTowardTail = false
    var userNavigated = false
        private set

    fun notifyManualLayoutToggle() {
        followingTail = false
        suppressFollowUntilNanos = System.nanoTime() + 900_000_000L
        gestureRevision++
        manualLayoutPulse.intValue++
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                gestureRevision++
                userNavigated = true
                lastDragTowardTail = available.y < 0f
                if (available.y > 0f) {
                    followingTail = false
                } else {
                    if (collapseHoldPx > 0 && list.canScrollForward) {
                        val pullPx = (-available.y).roundToInt().coerceAtLeast(1)
                        val reduction = minOf(collapseHoldPx, pullPx)
                        collapseHoldPx -= reduction
                        heldTotalPx = (heldTotalPx - reduction).coerceAtLeast(0)
                    }
                    val tailDist = conversationTailDistance(list, readingSpaceBottomPx)
                    if (!list.canScrollForward || tailDist == 0) {
                        followingTail = true
                    }
                }
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val towardTail = lastDragTowardTail
            lastDragTowardTail = false
            val revision = gestureRevision
            if (towardTail) scope.launch {
                withFrameNanos { }
                snapshotFlow { !list.isScrollInProgress && !overscrollState.isOverScrollActive }.first { it }
                if (revision == gestureRevision) {
                    val distance = conversationTailDistance(list, readingSpaceBottomPx)
                    if (!readingSpaceActive && distance != null && distance in 1..nearBottomPx) {
                        list.animateScrollBy(distance.toFloat(), spatialSpring())
                    }
                    if (!list.canScrollForward || conversationTailDistance(list, readingSpaceBottomPx) == 0) {
                        followingTail = true
                    }
                }
            }
            return Velocity.Zero
        }
    }

    suspend fun jumpToTail(list: LazyListState = this.list) {
        gestureRevision++
        lastDragTowardTail = false
        collapseHoldPx = 0
        heldTotalPx = 0
        collapseSettling = false
        followingTail = true
        val total = list.layoutInfo.totalItemsCount
        if (total <= 0) return
        list.scrollToItem(total - 1)
        conversationTailDistance(list, readingSpaceBottomPx)?.takeIf { it > 0 }?.let { list.scrollBy(it.toFloat()) }
        followingTail = true
    }

    suspend fun anchorRowToTop(
        index: Int,
        anchorOffsetPx: Int = 0,
        list: LazyListState = this.list,
    ) {
        if (index < 0) return
        val startRev = ++gestureRevision
        lastDragTowardTail = false
        collapseHoldPx = 0
        heldTotalPx = 0
        collapseSettling = false
        followingTail = true
        anchoringInProgress = true
        try {
            withFrameNanos { }
            if (gestureRevision != startRev) return
            if (list.layoutInfo.totalItemsCount <= index) {
                snapshotFlow { list.layoutInfo.totalItemsCount > index }.first { it }
                withFrameNanos { }
                if (gestureRevision != startRev) return
            }
            val targetOffset = -anchorOffsetPx
            var visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (visible == null) {
                if (index > 0) {
                    val info = list.layoutInfo
                    val span = (info.viewportEndOffset - info.afterContentPadding - info.viewportStartOffset).coerceAtLeast(1)
                    list.scrollToItem(index - 1, 0)
                    val prev = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index - 1 }
                    if (prev != null && prev.size > span) {
                        list.scrollToItem(index - 1, (prev.size - span).coerceAtLeast(0))
                    }
                    visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                } else {
                    list.scrollToItem(0, anchorOffsetPx.coerceAtLeast(0))
                    visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                }
            }
            if (gestureRevision != startRev) return
            if (visible != null) {
                val delta = visible.offset - targetOffset
                if (abs(delta) > 1) {
                    list.animateScrollBy(delta.toFloat(), spatialSpring())
                }
            } else {
                list.scrollToItem(index, anchorOffsetPx.coerceAtLeast(0))
            }
            if (gestureRevision != startRev) return
            val settled = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (settled == null) {
                list.scrollToItem(index, anchorOffsetPx.coerceAtLeast(0))
            } else {
                val rem = settled.offset - targetOffset
                if (abs(rem) > 1) {
                    list.scrollBy(rem.toFloat())
                }
            }
        } finally {
            anchoringInProgress = false
            if (gestureRevision == startRev) {
                followingTail = true
            }
        }
    }
}

private class ImeOpenAlignment {
    var lastImePx = 0
    var peakImePx by mutableIntStateOf(0)
    var sessionToken by mutableIntStateOf(0)
    var armed by mutableStateOf(false)
    var armedGestureRevision = 0
    var minClearBottom = 0
    var pendingScrollPx = 0f
}

private data class TailFollowSnapshot(
    val totalItems: Int,
    val lastVisibleIndex: Int,
    val lastKey: Any?,
    val lastOffset: Int,
    val lastSize: Int,
    val afterContentPadding: Int,
    val tailDistance: Int?,
    val scrolling: Boolean,
    val atBottom: Boolean,
    val following: Boolean,
    val holdingCollapse: Boolean,
    val readingSpaceActive: Boolean,
)

@Composable
internal fun rememberConversationTailFollow(
    list: LazyListState,
    identity: String = "conversation",
    ready: Boolean = true,
    imeBottomPx: Int = WindowInsets.ime.getBottom(LocalDensity.current),
    followTailGrowth: Boolean = false,
): ConversationTailFollow {
    var initiallyPositioned by rememberSaveable(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nearBottomPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val navigation = remember(list, identity, scope, nearBottomPx) {
        ConversationTailFollow(list, scope, nearBottomPx)
    }
    val imeAlignment = remember(list, identity) { ImeOpenAlignment() }
    val dropTolerancePx = with(LocalDensity.current) { 24.dp.roundToPx() }
    if (imeBottomPx <= 0) {
        imeAlignment.lastImePx = 0
        imeAlignment.peakImePx = 0
        imeAlignment.pendingScrollPx = 0f
        if (imeAlignment.armed) imeAlignment.armed = false
    } else if (imeAlignment.lastImePx <= 0) {
        val info = list.layoutInfo
        imeAlignment.lastImePx = imeBottomPx
        imeAlignment.peakImePx = imeBottomPx
        val last = info.visibleItemsInfo.lastOrNull()
        val tailDist = conversationTailDistance(list, navigation.readingSpaceBottomPx)
        val nearWindowPx = maxOf(nearBottomPx * 2, info.afterContentPadding)
        val nearTail = !list.canScrollForward || (tailDist != null && tailDist in 0..nearWindowPx)
        if (ready && last != null && nearTail) {
            imeAlignment.armedGestureRevision = navigation.gestureRevision
            val contentHeight = (last.size - navigation.readingSpaceBottomPx.coerceAtLeast(0)).coerceAtLeast(0)
            val clearBottom = info.viewportEndOffset - info.afterContentPadding
            val contentBottom = last.offset + contentHeight
            imeAlignment.minClearBottom = minOf(clearBottom, contentBottom)
            imeAlignment.pendingScrollPx = (contentBottom - clearBottom).coerceAtLeast(0).toFloat()
            imeAlignment.sessionToken++
            imeAlignment.armed = true
        } else if (imeAlignment.armed) {
            imeAlignment.pendingScrollPx = 0f
            imeAlignment.armed = false
        }
    } else {
        imeAlignment.lastImePx = imeBottomPx
        if (imeBottomPx > imeAlignment.peakImePx) {
            imeAlignment.peakImePx = imeBottomPx
        } else if (imeAlignment.peakImePx - imeBottomPx > dropTolerancePx) {
            imeAlignment.pendingScrollPx = 0f
            if (imeAlignment.armed) imeAlignment.armed = false
        }
    }
    val activeImeToken = if (imeAlignment.armed) imeAlignment.sessionToken else 0
    if (activeImeToken > 0) {
        LaunchedEffect(list, navigation, activeImeToken) {
            snapshotFlow {
                val info = list.layoutInfo
                listOf(info.viewportEndOffset - info.afterContentPadding, imeAlignment.peakImePx, navigation.gestureRevision)
            }.collectLatest { state ->
                val clearBottom = state[0]
                val gestureRev = state[2]
                if (!imeAlignment.armed || gestureRev != imeAlignment.armedGestureRevision) {
                    imeAlignment.pendingScrollPx = 0f
                    imeAlignment.armed = false
                    return@collectLatest
                }
                if (clearBottom < imeAlignment.minClearBottom) {
                    imeAlignment.pendingScrollPx += (imeAlignment.minClearBottom - clearBottom).toFloat()
                    imeAlignment.minClearBottom = clearBottom
                }
                var retries = 0
                while (imeAlignment.pendingScrollPx > 0.5f && imeAlignment.armed && navigation.gestureRevision == imeAlignment.armedGestureRevision) {
                    val needed = imeAlignment.pendingScrollPx
                    val consumed = list.scrollBy(needed)
                    imeAlignment.pendingScrollPx = (imeAlignment.pendingScrollPx - consumed).coerceAtLeast(0f)
                    if (imeAlignment.pendingScrollPx <= 0.5f) {
                        imeAlignment.pendingScrollPx = 0f
                        break
                    }
                    if (consumed <= 0.5f) {
                        if (!list.canScrollForward || retries >= 3) break
                        retries++
                    } else {
                        retries = 0
                    }
                    withFrameNanos { }
                }
                delay(400)
                imeAlignment.pendingScrollPx = 0f
                imeAlignment.armed = false
            }
        }
    }
    LaunchedEffect(list, navigation, ready) {
        if (ready && !initiallyPositioned) {
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
            withFrameNanos { }
            if (!navigation.userNavigated && !navigation.anchoringInProgress) {
                list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
                conversationTailDistance(list, navigation.readingSpaceBottomPx)?.takeIf { it > 0 }?.let { list.scrollBy(it.toFloat()) }
                navigation.followingTail = true
            }
            initiallyPositioned = true
        }
    }
    LaunchedEffect(list, navigation) {
        snapshotFlow {
            Triple(
                navigation.anyRevealAnimating,
                navigation.collapseHoldPx > 0,
                navigation.gestureRevision to (list.isScrollInProgress || navigation.overscrollState.isOverScrollActive),
            )
        }.collectLatest { (animating, hasHold, gestureAndScrolling) ->
                if (animating || !hasHold) return@collectLatest
                if (gestureAndScrolling.second) return@collectLatest
                withFrameNanos { }
                if (navigation.anyRevealAnimating || navigation.collapseHoldPx <= 0) return@collectLatest
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                if (last == null || !list.canScrollBackward || !list.canScrollForward) {
                    navigation.collapseHoldPx = 0
                    navigation.heldTotalPx = 0
                    navigation.collapseSettling = false
                    return@collectLatest
                }
                val usableEnd = info.viewportEndOffset - info.afterContentPadding
                val naturalSize = (last.size - navigation.collapseHoldPx).coerceAtLeast(0)
                val naturalBottom = last.offset + naturalSize
                val deficitPx = (usableEnd - naturalBottom).coerceAtLeast(0)
                if (deficitPx <= 0) {
                    navigation.collapseHoldPx = 0
                    navigation.heldTotalPx = 0
                    navigation.collapseSettling = false
                    return@collectLatest
                }
                val startHold = minOf(navigation.collapseHoldPx, deficitPx)
                val startRev = navigation.gestureRevision
                navigation.collapseSettling = true
                navigation.collapseHoldPx = startHold
                try {
                    val anim = Animatable(startHold.toFloat(), Float.VectorConverter, 0.5f)
                    anim.animateTo(0f, slowSettleTween()) {
                        if (navigation.gestureRevision != startRev || navigation.anyRevealAnimating) {
                            return@animateTo
                        }
                        navigation.collapseHoldPx = value.roundToInt().coerceAtLeast(0)
                    }
                    if (!navigation.anyRevealAnimating && navigation.gestureRevision == startRev) {
                        navigation.collapseHoldPx = 0
                        navigation.heldTotalPx = 0
                    }
                } finally {
                    navigation.collapseSettling = false
                }
            }
    }
    if (followTailGrowth && ready) {
        LaunchedEffect(list, navigation, initiallyPositioned) {
            if (!initiallyPositioned) return@LaunchedEffect
            val initInfo = list.layoutInfo
            var prevTotalItems = initInfo.totalItemsCount
            val initLast = initInfo.visibleItemsInfo.lastOrNull()
            var prevLastKey: Any? = initLast?.key
            var prevLastOffset = initLast?.offset ?: 0
            var prevLastSize = initLast?.let { (it.size - navigation.readingSpaceBottomPx.coerceAtLeast(0)).coerceAtLeast(0) } ?: 0
            var prevAfterContentPadding = initInfo.afterContentPadding
            var prevReadingSpaceActive = navigation.readingSpaceActive
            snapshotFlow {
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val tailDist = conversationTailDistance(list, navigation.readingSpaceBottomPx)
                val lastContentSize = last?.let { (it.size - navigation.readingSpaceBottomPx.coerceAtLeast(0)).coerceAtLeast(0) } ?: 0
                TailFollowSnapshot(
                    totalItems = info.totalItemsCount,
                    lastVisibleIndex = last?.index ?: -1,
                    lastKey = last?.key,
                    lastOffset = last?.offset ?: 0,
                    lastSize = lastContentSize,
                    afterContentPadding = info.afterContentPadding,
                    tailDistance = tailDist,
                    scrolling = list.isScrollInProgress || navigation.overscrollState.isOverScrollActive,
                    atBottom = !list.canScrollForward && (tailDist == null || tailDist == 0),
                    following = navigation.followingTail,
                    holdingCollapse = navigation.anyRevealAnimating || navigation.collapseSettling || navigation.collapseHoldPx > 0,
                    readingSpaceActive = navigation.readingSpaceActive,
                )
            }.collect { snap ->
                val itemAdded = snap.totalItems > prevTotalItems && prevTotalItems > 0
                val tailGrew = snap.lastKey != prevLastKey || snap.lastSize > prevLastSize
                val paddingGrew = snap.afterContentPadding > prevAfterContentPadding && imeBottomPx <= 0
                val readingSpaceExhausted = prevReadingSpaceActive && !snap.readingSpaceActive
                prevTotalItems = snap.totalItems
                prevLastKey = snap.lastKey
                prevLastOffset = snap.lastOffset
                prevLastSize = snap.lastSize
                prevAfterContentPadding = snap.afterContentPadding
                prevReadingSpaceActive = snap.readingSpaceActive
                if (snap.holdingCollapse || navigation.anchoringInProgress || snap.readingSpaceActive || System.nanoTime() < navigation.suppressFollowUntilNanos) {
                    return@collect
                }
                if (snap.atBottom && !snap.scrolling) {
                    navigation.followingTail = true
                }
                if (!snap.following || snap.scrolling || snap.totalItems <= 0) return@collect
                if (itemAdded && snap.lastVisibleIndex in 0 until (snap.totalItems - 1)) {
                    list.scrollToItem(snap.totalItems - 1)
                    conversationTailDistance(list, navigation.readingSpaceBottomPx)?.takeIf { it > 0 }?.let { list.scrollBy(it.toFloat()) }
                } else if ((tailGrew || paddingGrew || readingSpaceExhausted) && snap.tailDistance != null && snap.tailDistance > 0) {
                    list.scrollBy(snap.tailDistance.toFloat())
                }
            }
        }
    }
    return navigation
}
