package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MenuMotionTest {
    @Test fun zeroDurationNativeClockSkipsBothAnimations() {
        var frames=0
        val clock=object:MonotonicFrameClock {override suspend fun <R> withFrameNanos(onFrame:(Long)->R):R = onFrame(++frames*16_000_000L)}
        val scale=object:MotionDurationScale {override val scaleFactor=0f}
        runBlocking(clock+scale) {
            val progress=Animatable(0f)
            progress.animateTo(1f,menuMotionSpec(true));assertEquals(1f,progress.value,0f)
            progress.animateTo(0f,menuMotionSpec(false));assertEquals(0f,progress.value,0f)
        }
        assertTrue(frames<=4)
    }
}
