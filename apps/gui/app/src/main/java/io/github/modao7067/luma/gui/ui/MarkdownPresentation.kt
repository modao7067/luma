package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.*

internal data class MarkdownPresentation(val document: MarkdownDocument, val visibleUnits: Int)

@Composable
internal fun rememberMarkdownPresentation(
    identity: Any,
    source: String,
    complete: Boolean = true,
    animate: Boolean = false,
    animateArrival: Boolean = false,
): MarkdownPresentation {
    if (LocalInspectionMode.current) {
        return MarkdownPresentation(remember(source) { MarkdownDocumentParser().parse(source, complete) }, Int.MAX_VALUE)
    }
    val session = remember(identity) { markdownParseCoordinator.session(identity, source) }
    DisposableEffect(session) { onDispose { session.close() } }
    val snapshot by session.snapshot.collectAsState()
    val failure by session.failure.collectAsState()
    val target = remember(source, complete) { MarkdownParseTarget(source, complete) }
    LaunchedEffect(session, target) { session.submit(target) }
    val document = snapshot?.takeIf { failure == null && source.startsWith(it.source) }
        ?: remember(source) {
            val text = MarkdownAst(MarkdownKind.TEXT, literal = source, inlineUnits = source.length, revealUnits = source.length)
            val paragraph = MarkdownAst(MarkdownKind.PARAGRAPH, children = listOf(text), inlineUnits = source.length, revealUnits = source.length, nodeCount = 2)
            MarkdownDocument(source, listOf(MarkdownDocumentBlock(0, paragraph)), complete = false)
        }
    val initiallyComplete = remember(identity) { complete && !animateArrival }
    var cursor by remember(identity) { mutableIntStateOf(if (animate && animateArrival && source.length <= 240) 0 else document.revealUnits) }
    val inspection = LocalInspectionMode.current
    val shouldAnimate = animate && !initiallyComplete && !inspection
    LaunchedEffect(session, document, shouldAnimate) {
        cursor = cursor.coerceAtMost(document.revealUnits)
        if (!shouldAnimate) { cursor = document.revealUnits; return@LaunchedEffect }
        var lastFrameNanos = 0L
        var carry = 0f
        while (cursor < document.revealUnits) {
            val dtSeconds = withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) 16_000_000L else (frameNanos - lastFrameNanos).coerceIn(1_000_000L, 50_000_000L)
                lastFrameNanos = frameNanos
                dt / 1_000_000_000f
            }
            val remaining = document.revealUnits - cursor
            carry += (45f + remaining / 0.28f) * dtSeconds
            val step = carry.toInt()
            if (step > 0) {
                carry -= step
                cursor = (cursor + step).coerceAtMost(document.revealUnits)
            }
        }
    }
    return MarkdownPresentation(document, if (!shouldAnimate) Int.MAX_VALUE else cursor.coerceAtMost(document.revealUnits))
}
