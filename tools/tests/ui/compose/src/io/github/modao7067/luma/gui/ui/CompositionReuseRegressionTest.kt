package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompositionReuseRegressionTest {
    @get:Rule val rule = createComposeRule()
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }
    @Test fun rootReuseNeverCollidesWithANestedReusableGroupIndex() {
        lateinit var parent: CompositionContext
        rule.setContent { parent = rememberCompositionContext() }
        val failures = mutableListOf<Pair<Int, String>>()
        rule.runOnIdle {
            for (offset in 80..105) {
                val composition = ReusableComposition(NoOpApplier(), parent)
                val content: @Composable () -> Unit = {
                    repeat(offset) { i -> key(i) {} }
                    key(offset) { ReusableContent(offset) {} }
                }
                try {
                    composition.setContent(content)
                    composition.setContentWithReuse(content)
                } catch (failure: IllegalArgumentException) {
                    failures += offset to failure.message.orEmpty()
                    println("REPRO offset=$offset: ${failure.stackTraceToString()}")
                } finally { composition.dispose() }
            }
        }
        assertTrue("Public reusable composition hit root-index collision: $failures", failures.isEmpty())
    }
}
