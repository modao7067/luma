package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class MarkdownPresentation(val document: MarkdownDocument, val visibleUnits: Int)

private const val REVEAL_IDLE_RESET_NANOS = 100_000_000L

@Composable
internal fun rememberMarkdownPresentation(
    identity: Any,
    source: String,
    complete: Boolean = true,
    animate: Boolean = false,
    animateArrival: Boolean = false,
): MarkdownPresentation {
    val inspection = LocalInspectionMode.current
    if (inspection) {
        return MarkdownPresentation(remember(source) { MarkdownDocumentParser().parse(source, complete) }, Int.MAX_VALUE)
    }
    val session = remember(identity) { markdownParseCoordinator.session(identity, source) }
    DisposableEffect(session) { onDispose { session.close() } }
    val snapshot by session.snapshot.collectAsState()
    val failure by session.failure.collectAsState()
    val target = remember(source, complete) { MarkdownParseTarget(source, complete) }
    val submittedRef = remember(session) { arrayOfNulls<MarkdownParseTarget>(1) }
    var retryTarget by remember(session) { mutableStateOf<MarkdownParseTarget?>(null) }
    SideEffect {
        if (submittedRef[0] !== target) {
            submittedRef[0] = target
            retryTarget = if (markdownParseCoordinator.offer(session, target)) null else target
        }
    }
    if (retryTarget != null) {
        LaunchedEffect(session, retryTarget) {
            val pending = retryTarget ?: return@LaunchedEffect
            session.submit(pending)
            if (retryTarget === pending) retryTarget = null
        }
    }
    val document = snapshot?.takeIf { failure == null && source.startsWith(it.source) }
        ?: remember(source) {
            val text = MarkdownAst(MarkdownKind.TEXT, literal = source, inlineUnits = source.length, revealUnits = source.length)
            val paragraph = MarkdownAst(MarkdownKind.PARAGRAPH, children = listOf(text), inlineUnits = source.length, revealUnits = source.length, nodeCount = 2)
            MarkdownDocument(source, listOf(MarkdownDocumentBlock(0, paragraph)), complete = false)
        }
    val targetUnits = document.revealUnits
    val initiallyComplete = remember(identity) { complete && !animateArrival }
    var cursor by remember(identity) { mutableIntStateOf(if (animate && animateArrival && source.length <= 240) 0 else targetUnits) }
    val shouldAnimate = animate && !initiallyComplete
    val revealTarget = remember(session) { mutableIntStateOf(targetUnits) }
    SideEffect {
        if (revealTarget.intValue != targetUnits) {
            revealTarget.intValue = targetUnits
        }
    }
    if (shouldAnimate) {
        LaunchedEffect(session) {
            var lastFrameNanos = 0L
            var carry = 0f
            snapshotFlow { revealTarget.intValue }.collect { goal ->
                if (cursor > goal) {
                    cursor = goal
                    carry = 0f
                }
                if (cursor >= goal) {
                    carry = 0f
                    return@collect
                }
                while (cursor < revealTarget.intValue) {
                    val dtSeconds = withFrameNanos { frameNanos ->
                        val rawDt = if (lastFrameNanos == 0L) Long.MAX_VALUE else frameNanos - lastFrameNanos
                        val dt = if (rawDt > REVEAL_IDLE_RESET_NANOS || rawDt <= 0L) {
                            12_000_000L
                        } else {
                            rawDt.coerceIn(1_000_000L, 50_000_000L)
                        }
                        lastFrameNanos = frameNanos
                        dt / 1_000_000_000f
                    }
                    val currentGoal = revealTarget.intValue
                    if (cursor > currentGoal) {
                        cursor = currentGoal
                        carry = 0f
                        break
                    }
                    val remaining = currentGoal - cursor
                    if (remaining <= 0) break
                    carry += (45f + remaining / 0.28f) * dtSeconds
                    val step = carry.toInt()
                    if (step > 0) {
                        carry -= step
                        cursor = (cursor + step).coerceAtMost(currentGoal)
                    }
                }
                carry = 0f
            }
        }
    }
    return MarkdownPresentation(document, if (!shouldAnimate) Int.MAX_VALUE else cursor.coerceAtMost(targetUnits))
}
