package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.pow

private class TestFrameClock : MonotonicFrameClock {
    private var nanos = 0L
    private val waiters = mutableListOf<(Long) -> Unit>()
    var frameCount = 0
        private set

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        suspendCancellableCoroutine { cont: CancellableContinuation<R> ->
            val callback: (Long) -> Unit = { frameTime ->
                try {
                    cont.resume(onFrame(frameTime))
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            waiters += callback
            cont.invokeOnCancellation { waiters.remove(callback) }
        }

    suspend fun step(millis: Long = 16L) {
        nanos += millis * 1_000_000L
        frameCount += 1
        val current = waiters.toList()
        waiters.clear()
        current.forEach { it(nanos) }
        yield()
    }
}

class PrismMotionAlignmentTest {
    @Test
    fun continuousSpringAdvancesAcrossPerMoveRetargets() = runBlocking {
        val clock = TestFrameClock()
        val scope = this
        val job = launch(clock) {
            val spring = ContinuousSpring(this, 0f, rest = 0.05f)
            spring.springTo(20f, PrismSprings.track())
            clock.step(16L)
            spring.springTo(40f, PrismSprings.track())
            spring.springTo(60f, PrismSprings.track())
            spring.springTo(80f, PrismSprings.track())
            clock.step(16L)
            val afterFirstMoveFrame = spring.value
            assertTrue("expected ContinuousSpring to advance past 0 after retargets but was $afterFirstMoveFrame", afterFirstMoveFrame > 5f)

            spring.springTo(90f, PrismSprings.track())
            spring.springTo(100f, PrismSprings.track())
            spring.springTo(110f, PrismSprings.track())
            clock.step(16L)
            val afterSecondMoveFrame = spring.value
            assertTrue(
                "expected ContinuousSpring to keep advancing without stalling ($afterFirstMoveFrame -> $afterSecondMoveFrame)",
                afterSecondMoveFrame > afterFirstMoveFrame + 5f,
            )

            repeat(30) { clock.step(16L) }
            assertEquals(110f, spring.value, 0.1f)
            assertEquals(0f, spring.velocity, 0.1f)
        }
        job.join()
        assertTrue(scope === this)
    }

    @Test
    fun continuousSpringScalesVelocityTimeByMotionDurationScale() {
        val spec = PrismSprings.press()
        val reference = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 100f, 0f)
        for (scaleFactor in listOf(0.5f, 2.0f)) {
            val clock = TestFrameClock()
            val scale = object : MotionDurationScale {
                override val scaleFactor = scaleFactor
            }
            runBlocking(clock + scale) {
                val springJob = launch {
                    val spring = ContinuousSpring(this, 0f, rest = 0.05f)
                    spring.springTo(100f, spec)
                    clock.step(16L)
                    clock.step(64L)
                    val expectedPlayNanos = (64_000_000L / scaleFactor).toLong()
                    assertEquals(reference.getValueFromNanos(expectedPlayNanos), spring.value, 0.01f)
                    assertEquals(reference.getVelocityVectorFromNanos(expectedPlayNanos).value, spring.velocity, 0.01f)
                    spring.springTo(0f, spec)
                    assertEquals(reference.getVelocityVectorFromNanos(expectedPlayNanos).value, spring.velocity, 0.01f)
                    repeat(200) { clock.step(16L) }
                    assertEquals(0f, spring.value, 0.1f)
                }
                springJob.join()
            }
        }
    }

    @Test
    fun continuousSpringSnapsUnderZeroDurationScale() {
        val clock = TestFrameClock()
        val zeroScale = object : MotionDurationScale {
            override val scaleFactor = 0f
        }
        runBlocking(clock + zeroScale) {
            val spring = ContinuousSpring(this, 0f, rest = 0.05f)
            spring.springTo(64f, PrismSprings.track())
            assertEquals(64f, spring.value, 0f)
            assertEquals(0f, clock.frameCount.toFloat(), 0f)
        }
    }

    @Test
    fun pagePushStartsFastWithoutOvershootAndRespectsZeroScale() {
        val clock = TestFrameClock()
        runBlocking(clock) {
            val offset = Animatable(0f)
            val samples = mutableListOf<Float>()
            val runner = launch { offset.animatePagePush(1f) }
            yield()
            clock.step(16L)
            repeat(6) {
                clock.step(10L)
                samples += offset.value
            }
            val at50ms = samples[4]
            assertTrue("expected page push > 0.40 after 50ms but was $at50ms", at50ms > 0.40f)
            repeat(40) {
                if (runner.isActive) {
                    clock.step(16L)
                    samples += offset.value
                }
            }
            runner.join()
            assertTrue("expected no overshoot on page push but max was ${samples.maxOrNull()}", samples.all { it <= 1.0001f })
            assertEquals(1f, offset.value, 0.001f)
        }

        val zeroClock = object : MonotonicFrameClock {
            var frames = 0
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(++frames * 16_000_000L)
        }
        val zeroScale = object : MotionDurationScale {
            override val scaleFactor = 0f
        }
        runBlocking(zeroClock + zeroScale) {
            val offset = Animatable(0f)
            offset.animatePagePush(1f)
            assertEquals(1f, offset.value, 0f)
        }
    }

    @Test
    fun upstreamSpringsMatchBounceAndStiffnessContracts() {
        val unfold = TargetBasedAnimation(PrismSprings.unfold(), Float.VectorConverter, 0f, 1f)
        val unfoldSamples = (0L..600L step 10L).map { unfold.getValueFromNanos(it * 1_000_000L) }
        val unfoldPeak = unfoldSamples.maxOrNull() ?: 0f
        assertTrue("expected unfold() to softly overshoot between 1.003 and 1.015 but was $unfoldPeak", unfoldPeak in 1.003f..1.015f)
        assertEquals(1f, unfoldSamples.last(), 0.02f)

        val fold = TargetBasedAnimation(PrismSprings.fold(), Float.VectorConverter, 1f, 0f)
        val foldSamples = (0L..600L step 10L).map { fold.getValueFromNanos(it * 1_000_000L) }
        assertTrue("expected fold() to have zero overshoot below 0", (foldSamples.minOrNull() ?: 0f) >= -0.0001f)
        assertEquals(0f, foldSamples.last(), 0.01f)

        val sheet = PrismSprings.sheet()
        assertEquals(1000f / 3f, sheet.stiffness, 0.001f)
        assertEquals(1f, sheet.dampingRatio, 0.0001f)

        val alert = PrismSprings.alert()
        assertEquals((2f * PI.toFloat() / 0.275f).pow(2), alert.stiffness, 0.01f)
        assertEquals(1f, alert.dampingRatio, 0.0001f)
    }

    @Test
    fun materialStandardCollapseSpringDeceleratesSmoothlyIntoRest() {
        val spec = MaterialStandardMotion.defaultSpatialSpec<Float>()
        val anim = TargetBasedAnimation(spec, Float.VectorConverter, 1f, 0f)
        val steps = (0L..300L step 16L).map { anim.getValueFromNanos(it * 1_000_000L) }
        val drops = steps.zipWithNext { a, b -> a - b }
        val peakDrop = drops.maxOrNull() ?: 0f
        val peakIndex = drops.indexOf(peakDrop)
        assertTrue("Expected collapse spring to peak early instead of slamming at end (peakIdx=$peakIndex)", peakIndex in 1..4)
        val finalActiveDrop = drops.drop(peakIndex + 3).lastOrNull { it > 0.001f } ?: 0f
        assertTrue("Expected collapse spring to decelerate smoothly into rest ($finalActiveDrop vs peak $peakDrop)", finalActiveDrop < peakDrop * 0.25f)
        assertEquals(0f, steps.last(), 0.01f)
    }
}
