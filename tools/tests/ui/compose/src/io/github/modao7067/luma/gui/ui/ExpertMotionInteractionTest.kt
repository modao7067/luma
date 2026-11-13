package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.*
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
class ExpertMotionInteractionTest {
    @get:Rule val rule = createComposeRule()
    @Test fun collapsedComposerButtonsStayVerticallyCenteredAcrossDevicesAndSettleInOneStep() {
        var density by mutableStateOf(androidx.compose.ui.unit.Density(2.5f, 1.0f))
        var fraction by mutableFloatStateOf(1f)
        val view = sampleView().copy(draft = "", activeTurnId = null, queue = emptyList())
        rule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
                MaterialTheme {
                    ConversationTypography {
                        Box(Modifier.width(360.dp).testTag("composer-shell")) {
                            CompactComposer(
                                state = CockpitState(selected = view),
                                view = view,
                                actions = previewActions,
                                onPickImages = {},
                                onModels = {},
                                previewExpanded = fraction > 0f,
                                previewMotionFraction = fraction,
                            )
                        }
                    }
                }
            }
        }
        fun shellBounds() = rule.onNodeWithTag("composer-shell").fetchSemanticsNode().boundsInRoot
        fun plusBounds() = rule.onNodeWithContentDescription("添加附件").fetchSemanticsNode().boundsInRoot
        fun sendBounds() = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot

        listOf(
            androidx.compose.ui.unit.Density(2.25f, 1.0f),
            androidx.compose.ui.unit.Density(3.125f, 1.0f),
            androidx.compose.ui.unit.Density(2.75f, 1.35f),
        ).forEach { targetDensity ->
            rule.runOnIdle {
                density = targetDensity
                fraction = 1f
            }
            val scale = targetDensity.density
            val bottomGaps = mutableListOf<Float>()
            for (step in 10 downTo 0) {
                val f = step / 10f
                rule.runOnIdle { fraction = f }
                val shell = shellBounds()
                val plus = plusBounds()
                val send = sendBounds()
                assertEquals("plus and send share exact Y at f=$f ($targetDensity)", plus.center.y, send.center.y, 0.5f)
                assertEquals("plus center X must be concentric with 24dp left corner arc at f=$f ($targetDensity)", 24f * scale, plus.center.x - shell.left, 1.5f)
                assertEquals("send center X must be concentric with 24dp right corner arc at f=$f ($targetDensity)", 24f * scale, shell.right - send.center.x, 1.5f)
                assertEquals("plus center Y must be concentric with 24dp bottom corner arc at f=$f ($targetDensity)", 24f * scale, shell.bottom - plus.center.y, 1.5f)
                assertEquals("send center Y must be concentric with 24dp bottom corner arc at f=$f ($targetDensity)", 24f * scale, shell.bottom - send.center.y, 1.5f)
                bottomGaps += (shell.bottom - plus.center.y)
                if (step == 0) {
                    assertEquals("collapsed + button must be vertically centered on $targetDensity", shell.center.y, plus.center.y, 1.5f)
                    assertEquals("collapsed send button must be vertically centered on $targetDensity", shell.center.y, send.center.y, 1.5f)
                }
            }
            val spread = (bottomGaps.maxOrNull() ?: 0f) - (bottomGaps.minOrNull() ?: 0f)
            assertTrue("buttons must not shift vertically relative to pill bottom during collapse ($targetDensity): gaps=$bottomGaps", spread <= 0.5f)
        }
    }
    @Test fun firstClickExpandAnimatesSmoothlyFromZeroWithoutSnapping() {
        var expanded by mutableStateOf(false)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(320.dp).testTag("reveal-shell")) {
                TopReveal(expanded = expanded) {
                    Text("代码块或工具详情", Modifier.height(160.dp))
                }
            }
        } }
        fun height() = rule.onNodeWithTag("reveal-shell").fetchSemanticsNode().boundsInRoot.height
        assertEquals("collapsed item starts at 0 height", 0f, height(), 0.5f)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { expanded = true }
        val heights = mutableListOf<Float>()
        repeat(35) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); heights += height() }
        val fullHeight = heights.last()
        assertTrue("expanded item reaches full height (>100px): $fullHeight", fullHeight > 100f)
        assertTrue("first expand must animate through intermediate heights instead of snapping: $heights", heights.any { it > 1f && it < fullHeight - 1f })
        rule.mainClock.autoAdvance = true
        println("PASS first expand heights=$heights")
    }
    @Test fun completedExcerptRetractsAndRetiresWithoutAStaleCapturedContentLambda() {
        var live by mutableStateOf(true)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(320.dp).testTag("excerpt-shell")) {
                TopReveal(expanded = false, collapsedContent = if (live) ({ Text("运行中的摘要", Modifier.height(72.dp)) }) else null) {
                    Text("完整详情", Modifier.height(160.dp))
                }
            }
        } }
        fun height() = rule.onNodeWithTag("excerpt-shell").fetchSemanticsNode().boundsInRoot.height
        val before = height()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { live = false }
        val heights = mutableListOf(before)
        repeat(35) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); heights += height() }
        assertTrue("excerpt must retract through intermediate geometry: $heights", heights.any { it > 0 && it < before })
        assertEquals("settled success must release its excerpt", 0f, heights.last(), 1f)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("运行中的摘要").assertDoesNotExist()
        println("PASS retired excerpt heights=$heights")
    }
    @Test fun pendingModelOptionsStayReadOnlyUntilDeliveryIsConfirmed() {
        var view by mutableStateOf(sampleView().copy(activeTurnId = null, queue = emptyList(), sending = true))
        val calls = mutableListOf<String>()
        val models = listOf(ModelOption("other", "其它模型"))
        val actions = object : CockpitActions by previewActions {
            override fun configure(model: String, effort: String) { calls += model }
        }
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 700.dp)) {
            ModelMenu(true, {}, CockpitState(models = models), view, actions)
        } } }
        rule.onNodeWithText("模型").performClick()
        rule.onNodeWithText("等待送达确认后可修改").assertExists()
        rule.onNodeWithText("其它模型").assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { assertTrue(calls.isEmpty()); view = view.copy(sending = false, uncertainSubmission = true) }
        rule.onNodeWithText("其它模型").assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(uncertainSubmission = false) }
        rule.onNodeWithText("其它模型").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("other"), calls) }
        println("PASS pending/uncertain model mutations disabled; receipt unlocks exact native selection")
    }
    @Test fun equalLookingCacheMarkersCannotKeepAnOldMarkdownBody() {
        var markdown by mutableStateOf("A`B`")
        rule.setContent { MaterialTheme { NativeMarkdown(markdown) } }
        rule.onNodeWithText("A B ").assertExists()
        rule.runOnIdle { markdown = "ACB" }
        rule.onNodeWithText("ACB").assertExists()
        rule.onNodeWithText("A B ").assertDoesNotExist()
        println("PASS structural cache retains optimization without confusing code markup with literal text")
    }
}
