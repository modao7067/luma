package io.github.modao7067.luma.gui.ui

import com.termux.view.CursorTrailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CursorTrailStateTest {
    private val cellW = 20f
    private val cellH = 40f

    private fun push(
        state: CursorTrailState,
        col: Int,
        row: Int,
        nowNanos: Long,
        visible: Boolean = true,
        sourceTopRow: Int = 0,
        totalRows: Int = 30,
        altScreen: Boolean = false,
        rows: Int = 30,
        columns: Int = 80,
        sessionToken: Int = 1,
        bar: Boolean = false,
    ): Boolean {
        val left = col * cellW
        val right = if (bar) left + cellW * 0.25f else left + cellW
        val top = 10f + row * cellH
        val bottom = top + cellH
        return state.onCursorFrame(
            visible,
            left,
            top,
            right,
            bottom,
            0xffabefdc.toInt(),
            sourceTopRow,
            totalRows,
            altScreen,
            rows,
            columns,
            cellW,
            cellH,
            sessionToken,
            nowNanos,
        )
    }

    @Test
    fun horizontalJumpFliesFromOldPositionWithoutTeleportingToTarget() {
        for (frameNanos in listOf(8_333_333L, 16_666_667L)) {
            val state = CursorTrailState()
            var now = 1_000_000_000L
            assertFalse(push(state, 2, 5, now))
            assertFalse(state.isAnimating())

            now += frameNanos
            assertTrue(push(state, 22, 5, now))
            assertTrue(state.isAnimating())
            assertTrue(state.hasDrawableTrail())
            assertEquals(4, state.hullCount)
            assertTrue("Leading edge should launch from old line instead of teleporting to target", state.boundsRight < 22 * cellW)
            assertTrue("Trailing edge should lag behind leading edge", state.boundsRight - state.boundsLeft > cellW)

            var steps = 0
            while (state.isAnimating() && steps < 180) {
                now += frameNanos
                state.step(now)
                steps++
            }
            assertFalse("Expected trail to converge within 180 frames", state.isAnimating())
            assertFalse(state.hasDrawableTrail())
            assertTrue("Expected crisp multi-frame flight, got $steps", steps in 10..60)
        }
    }

    @Test
    fun diagonalLineJumpFliesAcrossLinesWithClockwiseQuadEdges() {
        val state = CursorTrailState()
        val t0 = 1_000_000_000L
        push(state, 20, 4, t0)
        assertTrue(push(state, 4, 5, t0 + 8_333_333L))
        assertTrue(state.step(t0 + 25_000_000L))

        val n = state.hullCount
        assertEquals(4, n)
        assertTrue("Quad should still be flying down from line 4 to line 5", state.boundsTop < 10f + 5 * cellH)

        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += state.getHullX(i)
            cy += state.getHullY(i)
        }
        cx /= n
        cy /= n

        val edges = FloatArray(16)
        state.fillEdgeUniforms(edges)
        for (i in 0 until n) {
            val ax = edges[i * 4]
            val ay = edges[i * 4 + 1]
            val bx = edges[i * 4 + 2]
            val by = edges[i * 4 + 3]
            val ex = bx - ax
            val ey = by - ay
            assertTrue(hypot(ex.toDouble(), ey.toDouble()) > 0.01)
            val cross = ex * (cy - ay) - ey * (cx - ax)
            assertTrue("Expected CW screen-space edge winding (cross > 0), got $cross", cross > 0f)
        }
        assertTrue(state.axisSpan > cellW)
    }

    @Test
    fun bottomRowOutputScrollFliesDownFromPreviousLine() {
        val state = CursorTrailState()
        var now = 1_500_000_000L
        push(state, 24, 29, now, totalRows = 40)

        now += 8_333_333L
        assertTrue(push(state, 8, 29, now, totalRows = 42))
        assertTrue(state.isAnimating())
        val expectedBottomLineTop = 10f + 29 * cellH
        assertTrue(
            "After scrolling up 2 lines, flying cursor must originate above bottom row: ${state.boundsTop} < $expectedBottomLineTop",
            state.boundsTop < expectedBottomLineTop - cellH,
        )

        var steps = 0
        while (state.isAnimating() && steps < 120) {
            now += 8_333_333L
            state.step(now)
            steps++
        }
        assertFalse(state.isAnimating())

        now += 8_333_333L
        assertTrue(push(state, 8, 29, now, totalRows = 43))
        assertTrue(state.isAnimating())
        assertTrue(
            "Bare Enter at bottom row must fly down from row 28 to row 29",
            state.boundsTop < expectedBottomLineTop,
        )
    }

    @Test
    fun briefTuiCursorHidePreservesAnchorWithinGraceWindow() {
        val state = CursorTrailState()
        var now = 2_000_000_000L
        push(state, 2, 2, now)
        now += 10_000_000L
        push(state, 2, 2, now, visible = false)
        assertFalse(state.hasDrawableTrail())

        now += 40_000_000L
        assertTrue(push(state, 20, 8, now, visible = true))
        assertTrue(state.isAnimating())
        assertTrue(state.hasDrawableTrail())

        now += 300_000_000L
        push(state, 20, 8, now, visible = false)
        now += CursorTrailState.HIDE_GRACE_NANOS + 50_000_000L
        assertFalse(push(state, 3, 1, now, visible = true))
        assertFalse(state.isAnimating())
    }

    @Test
    fun scrollResizeAltScreenAndSessionSwitchSnapWithoutTrail() {
        val state = CursorTrailState()
        var now = 3_000_000_000L
        push(state, 4, 4, now)

        now += 8_333_333L
        assertFalse(push(state, 18, 4, now, sourceTopRow = -2))
        assertFalse(state.isAnimating())

        now += 8_333_333L
        assertFalse(push(state, 4, 4, now, sourceTopRow = 0))

        now += 8_333_333L
        assertFalse(push(state, 20, 12, now, altScreen = true))
        assertFalse(state.isAnimating())

        now += 8_333_333L
        assertFalse(push(state, 2, 2, now, altScreen = true, rows = 24))
        assertFalse(state.isAnimating())

        now += 8_333_333L
        assertFalse(push(state, 30, 10, now, altScreen = true, rows = 24, sessionToken = 2))
        assertFalse(state.isAnimating())
    }

    @Test
    fun cursorShapeToggleOnSameCellSnapsCleanly() {
        val state = CursorTrailState()
        val t0 = 4_000_000_000L
        push(state, 10, 5, t0, bar = false)
        assertFalse(push(state, 10, 5, t0 + 8_333_333L, bar = true))
        assertFalse(state.isAnimating())
    }

    @Test
    fun rapidRetargetingContinuesWithoutSnappingBack() {
        val state = CursorTrailState()
        var now = 5_000_000_000L
        push(state, 0, 0, now)
        now += 8_333_333L
        assertTrue(push(state, 10, 0, now))
        now += 16_666_667L
        state.step(now)
        val midLeft = state.boundsLeft
        assertTrue(midLeft > 0f)

        now += 8_333_333L
        assertTrue(push(state, 20, 4, now))
        assertTrue(state.isAnimating())
        assertTrue(state.boundsLeft >= midLeft - 1f)
    }

    @Test
    fun briefHideDuringActiveAnimationDoesNotKillTrail() {
        val state = CursorTrailState()
        var now = 6_000_000_000L
        push(state, 2, 3, now)
        now += 8_333_333L
        assertTrue(push(state, 20, 3, now))
        assertTrue(state.isAnimating())

        now += 16_666_667L
        assertTrue(push(state, 20, 3, now, visible = false))
        assertTrue(state.isAnimating())
        assertTrue(state.hasDrawableTrail())

        now += 25_000_000L
        assertTrue(state.step(now))
        assertTrue(state.hasDrawableTrail())

        now += CursorTrailState.HIDE_GRACE_NANOS + 10_000_000L
        assertFalse(state.step(now))
        assertFalse(state.isAnimating())
    }

    @Test
    fun singleCellTypingAnimatesSmoothlyAndSettlesBriskly() {
        val state = CursorTrailState()
        var now = 7_000_000_000L
        push(state, 5, 4, now)

        now += 8_333_333L
        assertTrue(push(state, 6, 4, now))
        assertTrue(state.isAnimating())
        assertTrue(state.hasDrawableTrail())
        assertTrue("Single-cell typing should glide smoothly from col 5", state.boundsLeft < 6 * cellW)

        var steps = 0
        while (state.isAnimating() && steps < 40) {
            now += 8_333_333L
            state.step(now)
            steps++
        }
        assertFalse(state.isAnimating())
        assertTrue("Expected brisk 1-cell glide (<= 20 frames @ 120Hz), got $steps", steps in 4..20)
    }

    @Test
    fun rapidTypingAndRapidBackspaceStretchVisibleTailWhileKeepingHeadResponsive() {
        val state = CursorTrailState()
        var now = 7_500_000_000L
        push(state, 5, 4, now)

        var maxTypingSpanCells = 0f
        for (col in 6..15) {
            push(state, col, 4, now)
            for (f in 0 until 6) {
                now += 8_333_333L
                state.step(now)
                val spanCells = (state.boundsRight - state.boundsLeft) / cellW
                if (spanCells > maxTypingSpanCells) maxTypingSpanCells = spanCells
            }
        }
        assertTrue(
            "Rapid typing should stretch a visible multi-cell tail, got $maxTypingSpanCells cells",
            maxTypingSpanCells > 2.4f,
        )

        var maxBackspaceSpanCells = 0f
        for (col in 14 downTo 4) {
            push(state, col, 4, now)
            for (f in 0 until 5) {
                now += 8_333_333L
                state.step(now)
                val spanCells = (state.boundsRight - state.boundsLeft) / cellW
                if (spanCells > maxBackspaceSpanCells) maxBackspaceSpanCells = spanCells
            }
        }
        assertTrue(
            "Rapid backspace should stretch a visible multi-cell tail, got $maxBackspaceSpanCells cells",
            maxBackspaceSpanCells > 2.8f,
        )

        var settleSteps = 0
        while (state.isAnimating() && settleSteps < 60) {
            now += 8_333_333L
            state.step(now)
            settleSteps++
        }
        assertFalse("Tail should snap shut promptly after releasing backspace", state.isAnimating())
        assertTrue("Expected clean elastic snap-shut within 40 frames @ 120Hz, got $settleSteps", settleSteps in 10..40)
    }

    @Test
    fun invalidCommandColZeroBurstFliesDirectlyFromCommandLineToPrompt() {
        val state = CursorTrailState()
        var now = 8_000_000_000L
        push(state, 34, 10, now)

        now += 43_000_000L
        assertTrue(push(state, 0, 11, now))
        assertTrue(state.isHoldingColZero())
        assertTrue(state.hasDrawableTrail())
        assertTrue("Hold should keep cursor parked at old command line", state.boundsLeft >= 33 * cellW)

        now += 39_000_000L
        assertTrue(push(state, 0, 12, now))
        assertTrue(state.isHoldingColZero())

        now += 22_000_000L
        assertTrue(push(state, 0, 13, now))
        assertTrue(state.isHoldingColZero())

        now += 20_000_000L
        assertTrue(push(state, 27, 13, now))
        assertFalse(state.isHoldingColZero())
        assertTrue(state.isAnimating())
        assertTrue(
            "Launch must start from old command row 10, not row 13",
            state.boundsTop < 10f + 11 * cellH,
        )
        assertTrue(
            "Launch must start from old command col 34, not col 0",
            state.boundsRight > 28 * cellW,
        )
    }

    @Test
    fun slowCommandAtColZeroReleasesHoldAfterTimeout() {
        val state = CursorTrailState()
        var now = 9_000_000_000L
        push(state, 30, 10, now)

        now += 20_000_000L
        assertTrue(push(state, 0, 11, now))
        assertTrue(state.isHoldingColZero())

        now += CursorTrailState.COL_ZERO_HOLD_NANOS + 5_000_000L
        assertTrue(state.step(now))
        assertFalse(state.isHoldingColZero())
        assertTrue(state.isAnimating())
        assertTrue("After hold timeout, cursor should fly toward col 0", state.boundsLeft < 30 * cellW)
    }
}
