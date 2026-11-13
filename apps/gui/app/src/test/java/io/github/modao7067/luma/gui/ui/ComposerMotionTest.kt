package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ComposerMotionTest {
    @Test fun expansionIsContinuousMonotoneAndHasNoOvershoot() {
        val frames = (0L..800L step 16L).map { spatialSample(it) }
        assertEquals(0f, frames.first(), .0001f)
        assertTrue(frames.last() > .999f)
        assertTrue(frames.all { it in 0f..1.0001f })
        assertTrue(frames.zipWithNext().all { (a,b) -> b >= a })
        assertTrue(spatialSample(120) in .1f.. .9f)
    }
    @Test fun collapseReturnsThroughIntermediatePositions() {
        assertEquals(1f, spatialSample(0, 1f, 0f), .0001f)
        assertTrue(spatialSample(120, 1f, 0f) in .1f.. .9f)
        assertTrue(spatialSample(800, 1f, 0f) < .001f)
    }
    @Test fun interruptedOpeningKeepsExitPixelsUntilTheyAreGone() {
        assertTrue(spatialContentVisible(true, 0f))
        assertTrue(spatialContentVisible(false, spatialSample(60)))
        assertTrue(spatialContentVisible(false, spatialSample(120)))
        assertFalse(spatialContentVisible(false, 0f))
    }
    @Test fun reversalStartsAtCurrentPositionAndVelocity() {
        val opening = TargetBasedAnimation(spatialSpring(), Float.VectorConverter, 0f, 1f)
        val value = opening.getValueFromNanos(120_000_000L)
        val velocity = opening.getVelocityVectorFromNanos(120_000_000L).value
        assertEquals(value, spatialSample(0, value, 0f, velocity), .0001f)
        assertTrue(kotlin.math.abs(spatialSample(1, value, 0f, velocity) - value) < .03f)
        assertTrue(spatialSample(800, value, 0f, velocity) < .001f)
    }
    @Test fun zeroDurationSamplingImmediatelyReachesCorrectEndState() {
        assertEquals(1f, spatialSample(0, durationScale = 0f), 0f)
        assertEquals(0f, spatialSample(0, 1f, 0f, durationScale = 0f), 0f)
    }
    @Test fun nativeComposeClockWithZeroScaleSnapsBothDirections() {
        var frames = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(++frames * 16_000_000L)
        }
        val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        runBlocking(clock + scale) {
            val value = Animatable(0f)
            value.animateTo(1f, spatialSpring())
            assertEquals(1f, value.value, 0f)
            value.animateTo(0f, spatialSpring())
            assertEquals(0f, value.value, 0f)
        }
        assertTrue("zero scale must not play the spring", frames <= 4)
    }
    @Test fun textOnlyWidensAfterButtonsClearItsRowInBothDirections() {
        (0..100).map { it / 100f }.forEach { fraction ->
            if (composerTextWidthFraction(fraction) > 0f) assertEquals(1f, composerControlsFraction(fraction), 0f)
        }
        assertEquals(0f, composerTextWidthFraction(0f), 0f)
        assertEquals(1f, composerTextWidthFraction(1f), .0001f)
    }
    @Test fun transparentControlsCannotActivateAndExitDisablesImmediately() {
        assertFalse(composerToolsInteractive(true, 0f))
        assertFalse(composerToolsInteractive(true, .6f))
        assertFalse(composerToolsInteractive(true, .7f))
        assertTrue(composerToolsInteractive(true, .9f))
        assertTrue(composerToolsInteractive(true, 1f))
        assertFalse(composerToolsInteractive(false, 1f))
        assertFalse(composerToolsInteractive(false, .9f))
    }
}
