package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockScrollTest {
    @Test fun `partial fling frame and remaining velocity stay inside overflowing axes`() = runBlocking {
        val boundary = CodeBlockScrollBoundary({ true }, { true })
        assertEquals(Offset(-11f, 23f), boundary.onPostScroll(Offset(-4f, 7f), Offset(-11f, 23f), NestedScrollSource.SideEffect))
        assertEquals(Velocity(510f, -900f), boundary.onPostFling(Velocity(30f, -40f), Velocity(510f, -900f)))
    }

    @Test fun `direct drag remains available to conversation at code boundaries`() {
        val boundary = CodeBlockScrollBoundary({ true }, { true })
        assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(30f, -60f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, boundary.onPreScroll(Offset(30f, -60f), NestedScrollSource.SideEffect))
    }

    @Test fun `short code passes vertical inertia even when a line overflows horizontally`() = runBlocking {
        val boundary = CodeBlockScrollBoundary({ false }, { true })
        assertEquals(Offset(15f, 0f), boundary.onPostScroll(Offset.Zero, Offset(15f, -20f), NestedScrollSource.SideEffect))
        assertEquals(Velocity(500f, 0f), boundary.onPostFling(Velocity.Zero, Velocity(500f, -700f)))
    }

    @Test fun `narrow long code contains only vertical inertia`() = runBlocking {
        val boundary = CodeBlockScrollBoundary({ true }, { false })
        assertEquals(Offset(0f, -20f), boundary.onPostScroll(Offset.Zero, Offset(15f, -20f), NestedScrollSource.SideEffect))
        assertEquals(Velocity(0f, -700f), boundary.onPostFling(Velocity.Zero, Velocity(500f, -700f)))
    }

    @Test fun `short code is not a scroll dead zone`() = runBlocking {
        val boundary = CodeBlockScrollBoundary({ false }, { false })
        assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(0f, -20f), NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, boundary.onPostFling(Velocity.Zero, Velocity(0f, -700f)))
    }

    @Test fun `range changes are read without recreating boundary`() = runBlocking {
        var hasVerticalOverflow = true
        val boundary = CodeBlockScrollBoundary({ hasVerticalOverflow }, { false })
        assertEquals(Offset(0f, 20f), boundary.onPostScroll(Offset.Zero, Offset(0f, 20f), NestedScrollSource.SideEffect))
        hasVerticalOverflow = false
        assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(0f, 20f), NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, boundary.onPostFling(Velocity.Zero, Velocity(0f, 700f)))
    }

    @Test fun `dragging past boundary chains normal fling inertia to outer conversation`() = runBlocking {
        val boundary = CodeBlockScrollBoundary(
            canScrollVertically = { true },
            canScrollHorizontally = { false },
            canScrollVerticallyInDirection = { deltaY -> deltaY > 0f },
        )
        assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(0f, -48f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(0f, -12f), NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, boundary.onPostFling(Velocity.Zero, Velocity(0f, -1400f)))
    }

    @Test fun `isSoftWrappedLineStart distinguishes soft wraps from real newlines`() {
        assertFalse(isSoftWrappedLineStart("alpha beta", 0, 0))

        val newlineText = "alpha\nbeta"
        assertFalse(isSoftWrappedLineStart(newlineText, 1, "alpha\n".length))

        val crlfText = "alpha\r\nbeta"
        assertFalse(isSoftWrappedLineStart(crlfText, 1, "alpha\r\n".length))

        val blankLineText = "alpha\n\nbeta"
        assertFalse(isSoftWrappedLineStart(blankLineText, 1, "alpha\n".length))
        assertFalse(isSoftWrappedLineStart(blankLineText, 2, "alpha\n\n".length))

        val trailingNewlineText = "alpha\n"
        assertFalse(isSoftWrappedLineStart(trailingNewlineText, 1, trailingNewlineText.length))

        val spaceWrappedText = "alpha beta"
        assertTrue(isSoftWrappedLineStart(spaceWrappedText, 1, "alpha ".length))

        val tokenWrappedText = "/workspace/app/module/file.kt"
        assertTrue(isSoftWrappedLineStart(tokenWrappedText, 1, "/workspace/app/".length))
    }
}
