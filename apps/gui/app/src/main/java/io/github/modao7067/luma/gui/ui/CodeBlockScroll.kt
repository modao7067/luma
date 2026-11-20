package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

internal val SOFT_WRAP_GUTTER_START = 14.dp
internal val SOFT_WRAP_GUTTER_END = 12.dp

internal fun isSoftWrappedLineStart(text: CharSequence, lineIndex: Int, lineStartOffset: Int): Boolean =
    lineIndex >= 1 && lineStartOffset > 0 && lineStartOffset <= text.length && text[lineStartOffset - 1] != '\n'

internal fun isSoftWrappedVisualLine(layout: TextLayoutResult, lineIndex: Int): Boolean {
    if (lineIndex !in 1 until layout.lineCount) return false
    return isSoftWrappedLineStart(layout.layoutInput.text, lineIndex, layout.getLineStart(lineIndex))
}

internal fun softWrappedVisualLines(
    layout: TextLayoutResult,
    visibleTopPx: Float = 0f,
    visibleBottomPx: Float = layout.size.height.toFloat(),
): IntArray {
    if (layout.lineCount <= 1 || visibleBottomPx <= visibleTopPx) return IntArray(0)
    val first = layout.getLineForVerticalPosition(visibleTopPx.coerceAtLeast(0f)).coerceAtLeast(1)
    val last = layout.getLineForVerticalPosition(visibleBottomPx.coerceAtLeast(0f)).coerceAtMost(layout.lineCount - 1)
    if (first > last) return IntArray(0)
    val matches = ArrayList<Int>()
    for (line in first..last) {
        if (isSoftWrappedVisualLine(layout, line)) matches += line
    }
    return matches.toIntArray()
}

internal fun DrawScope.drawSoftWrapIndicators(
    layout: TextLayoutResult?,
    color: Color,
    reusablePath: Path,
    visibleTopPx: Float = 0f,
    visibleBottomPx: Float = size.height,
    topPaddingPx: Float = 0f,
) {
    if (layout == null || layout.lineCount <= 1 || visibleBottomPx <= visibleTopPx) return
    val first = layout.getLineForVerticalPosition((visibleTopPx - topPaddingPx).coerceAtLeast(0f)).coerceAtLeast(1)
    val last = layout.getLineForVerticalPosition((visibleBottomPx - topPaddingPx).coerceAtLeast(0f)).coerceAtMost(layout.lineCount - 1)
    if (first > last) return
    val leftX = 3.8.dp.toPx()
    val rightX = 9.2.dp.toPx()
    val cornerRadius = 1.6.dp.toPx()
    val stemTopOffset = 2.6.dp.toPx()
    val tipOffset = 1.1.dp.toPx()
    val wingX = 1.85.dp.toPx()
    val wingY = 1.65.dp.toPx()
    val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    reusablePath.reset()
    var hasSegments = false
    for (line in first..last) {
        if (!isSoftWrappedVisualLine(layout, line)) continue
        hasSegments = true
        val cy = topPaddingPx + (layout.getLineTop(line) + layout.getLineBottom(line)) * 0.5f
        val stemTopY = cy - stemTopOffset
        val tipY = cy + tipOffset
        reusablePath.moveTo(leftX, stemTopY)
        reusablePath.lineTo(leftX, tipY - cornerRadius)
        reusablePath.quadraticTo(leftX, tipY, leftX + cornerRadius, tipY)
        reusablePath.lineTo(rightX, tipY)
        reusablePath.moveTo(rightX - wingX, tipY - wingY)
        reusablePath.lineTo(rightX, tipY)
        reusablePath.lineTo(rightX - wingX, tipY + wingY)
    }
    if (hasSegments) {
        drawPath(reusablePath, color = color, style = stroke)
    }
}

@Composable
internal fun Modifier.codeBlockScrollBoundary(
    vertical: ScrollState,
    horizontal: ScrollState? = null,
): Modifier = nestedScroll(remember(vertical, horizontal) {
    CodeBlockScrollBoundary(
        canScrollVertically = { vertical.canScrollForward || vertical.canScrollBackward },
        canScrollHorizontally = {
            horizontal != null && horizontal.maxValue < Int.MAX_VALUE && (horizontal.canScrollForward || horizontal.canScrollBackward)
        },
        canScrollVerticallyInDirection = { deltaY ->
            if (deltaY < 0f) vertical.canScrollForward else if (deltaY > 0f) vertical.canScrollBackward else false
        },
        canScrollHorizontallyInDirection = { deltaX ->
            if (horizontal == null || horizontal.maxValue == Int.MAX_VALUE) {
                false
            } else if (deltaX < 0f) {
                horizontal.canScrollForward
            } else if (deltaX > 0f) {
                horizontal.canScrollBackward
            } else {
                false
            }
        },
    )
})

internal class CodeBlockScrollBoundary(
    private val canScrollVertically: () -> Boolean,
    private val canScrollHorizontally: () -> Boolean,
    private val canScrollVerticallyInDirection: (Float) -> Boolean = { canScrollVertically() },
    private val canScrollHorizontallyInDirection: (Float) -> Boolean = { canScrollHorizontally() },
) : NestedScrollConnection {
    private var chainedVertically = false
    private var chainedHorizontally = false

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = when (source) {
        NestedScrollSource.UserInput -> {
            if (available.y != 0f) {
                chainedVertically = true
            } else if (consumed.y != 0f) {
                chainedVertically = false
            }
            if (available.x != 0f) {
                chainedHorizontally = true
            } else if (consumed.x != 0f) {
                chainedHorizontally = false
            }
            Offset.Zero
        }
        NestedScrollSource.SideEffect -> {
            Offset(
                x = if (canScrollHorizontally() && !chainedHorizontally) available.x else 0f,
                y = if (canScrollVertically() && !chainedVertically) available.y else 0f,
            )
        }
        else -> Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val passVerticalToParent = chainedVertically ||
            (consumed.y == 0f && available.y != 0f && !canScrollVerticallyInDirection(available.y))
        val passHorizontalToParent = chainedHorizontally ||
            (consumed.x == 0f && available.x != 0f && !canScrollHorizontallyInDirection(available.x))
        chainedVertically = false
        chainedHorizontally = false
        return Velocity(
            x = if (canScrollHorizontally() && !passHorizontalToParent) available.x else 0f,
            y = if (canScrollVertically() && !passVerticalToParent) available.y else 0f,
        )
    }
}
