package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

@Composable
internal fun Modifier.codeBlockScrollBoundary(
    vertical: ScrollState,
    horizontal: ScrollState,
): Modifier = nestedScroll(remember(vertical, horizontal) {
    CodeBlockScrollBoundary(
        canScrollVertically = { vertical.canScrollForward || vertical.canScrollBackward },
        canScrollHorizontally = { horizontal.canScrollForward || horizontal.canScrollBackward },
        canScrollVerticallyInDirection = { deltaY ->
            if (deltaY < 0f) vertical.canScrollForward else if (deltaY > 0f) vertical.canScrollBackward else false
        },
        canScrollHorizontallyInDirection = { deltaX ->
            if (deltaX < 0f) horizontal.canScrollForward else if (deltaX > 0f) horizontal.canScrollBackward else false
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
