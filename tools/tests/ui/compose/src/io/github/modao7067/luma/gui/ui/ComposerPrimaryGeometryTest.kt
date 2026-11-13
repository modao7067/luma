package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
class ComposerPrimaryGeometryTest {
    @get:Rule val rule = createComposeRule()
    @Test fun primarySharesTheCapsuleEndCenterAndRetainsAnOuterTouchTarget() {
        var fraction by mutableStateOf(0f)
        var sends = 0
        val view = sampleView().copy(draft = "x", activeTurnId = null, queue = emptyList())
        val actions = object : CockpitActions by previewActions { override fun send() { sends++ } }
        rule.setContent { MaterialTheme { Box(Modifier.width(360.dp).testTag("capsule")) {
            CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewMotionFraction = fraction)
        } } }
        val capsule = rule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val plus = rule.onNodeWithContentDescription("添加附件").fetchSemanticsNode().boundsInRoot
        val primary = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        val scale = rule.density.density
        rule.onNodeWithTag("capsule").performTouchInput { click(Offset(primary.center.x - capsule.left + 21f * scale, primary.center.y - capsule.top)) }
        rule.runOnIdle { println("PRIMARY_BASELINE capsule=$capsule plus=$plus primary=$primary outer21dpClicks=$sends") }
        assertEquals("outer slot should remain actionable", 1, sends)
        assertEquals("plus center must align with capsule left arc", 24f * scale, plus.center.x - capsule.left, 0.5f)
        assertEquals("plus center must align vertically with capsule arc", 24f * scale, capsule.bottom - plus.center.y, 0.5f)
        assertEquals("center must align with capsule right arc", 24f * scale, capsule.right - primary.center.x, 0.5f)
        assertEquals("center must align vertically with capsule arc", 24f * scale, capsule.bottom - primary.center.y, 0.5f)
        val editorId = rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().id
        rule.runOnIdle { fraction = 1f }
        val expanded = rule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val expandedPlus = rule.onNodeWithContentDescription("添加附件").fetchSemanticsNode().boundsInRoot
        val expandedPrimary = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        assertEquals(24f * scale, expandedPlus.center.x - expanded.left, 0.5f)
        assertEquals(24f * scale, expanded.bottom - expandedPlus.center.y, 0.5f)
        assertEquals(24f * scale, expanded.right - expandedPrimary.center.x, 0.5f)
        assertEquals(24f * scale, expanded.bottom - expandedPrimary.center.y, 0.5f)
        assertEquals(editorId, rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().id)
        println("PASS compact/expanded primary center24dp, outer21dp click, same editor node")
    }

    @Test fun emptyDraftRendersNoCliPlaceholderAndKeepsCapsuleGeometry() {
        var draft by mutableStateOf("")
        rule.setContent { MaterialTheme { Box(Modifier.width(360.dp).testTag("capsule")) {
            val view = sampleView().copy(draft = draft, activeTurnId = null, queue = emptyList())
            CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewMotionFraction = 0f)
        } } }
        rule.onNodeWithText("向", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("发消息", substring = true, useUnmergedTree = true).assertDoesNotExist()
        val emptyCapsule = rule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val emptyEditor = rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { draft = "x" }
        val nonEmptyCapsule = rule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val nonEmptyEditor = rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().boundsInRoot
        assertEquals(nonEmptyCapsule, emptyCapsule)
        assertEquals(nonEmptyEditor, emptyEditor)
    }
}
