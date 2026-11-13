package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UserMessageBubbleGeometryTest {
    @get:Rule val rule = createComposeRule()
    private var item by mutableStateOf(ConversationItem("message", "user", "早"))
    private val recovered = mutableListOf<String>()
    private fun fixture() {
        val actions = object : CockpitActions by previewActions {
            override fun recoverSubmission(id: String) { recovered += id }
        }
        rule.setContent { MaterialTheme { ConversationTypography {
            Box(Modifier.width(320.dp)) { UserMessageBubble(item, actions) }
        } } }
    }
    private fun bounds(tag: String) = rule.onNodeWithTag("$tag:message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun assertNoDeliverySpace() {
        val bubble = bounds("user-bubble")
        val message = bounds("user-message")
        assertEquals("confirmed message ends at its bubble", bubble.bottom, message.bottom, 0.5f)
        rule.onNodeWithTag("user-delivery:message", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun singleCharacterSingleLineAndMultilineHaveSymmetricPaddingAndNoStatusSlot() {
        fixture()
        for (text in listOf("早", "嘿，早安", "第一行\n第二行\n第三行", "长消息在窄屏下自然换行，气泡高度只由正文与相同的上下边距决定。")) {
            rule.runOnIdle { item = item.copy(text = text) }
            val bubble = bounds("user-bubble")
            val body = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertEquals(11f * rule.density.density, body.top - bubble.top, 0.5f)
            assertEquals("no empty Row's 8dp spacing below text", body.top - bubble.top, bubble.bottom - body.bottom, 0.5f)
            assertNoDeliverySpace()
            println("PASS text=$text bubble=$bubble body=$body")
        }
    }

    @Test fun everyDeliveryStateStaysBelowAnUnchangedBubbleAndCompletionRemovesTheWholeRow() {
        fixture()
        val settled = bounds("user-bubble")
        for (delivery in listOf("pending", "unknown", "rejected", "")) {
            rule.runOnIdle { item = item.copy(delivery = delivery) }
            rule.waitForIdle()
            val bubble = bounds("user-bubble")
            assertEquals("delivery cannot resize the colored surface", settled, bubble)
            if (delivery.isEmpty()) {
                assertNoDeliverySpace()
                rule.onNodeWithContentDescription("编辑未发送消息").assertDoesNotExist()
            } else {
                val status = bounds("user-delivery")
                assertTrue("status belongs below bubble: $bubble / $status", status.top >= bubble.bottom + 3f * rule.density.density)
                rule.onNodeWithText(if (delivery == "rejected") "未发送" else "发送中").assertIsDisplayed()
                if (delivery == "rejected") rule.onNodeWithContentDescription("编辑未发送消息").performClick()
            }
        }
        assertEquals(listOf("message"), recovered)
    }

    @Test fun attachmentsKeepOnlyTheirOwnContentSpacingIncludingImageOnlyMessages() {
        val validBitmap = mediaFixtureBitmap()
        item = item.copy(text = "图片说明", images = listOf("file:///valid-test-image.png"))
        rule.setContent { MaterialTheme { ConversationTypography {
            CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(validBitmap)) {
                Box(Modifier.width(320.dp)) { UserMessageBubble(item, previewActions) }
            }
        } } }
        rule.waitForIdle()
        val textBubble = bounds("user-bubble")
        val imageCard = rule.onNodeWithTag("media-card:file:///valid-test-image.png").fetchSemanticsNode().boundsInRoot
        assertEquals(8f * rule.density.density, textBubble.top - imageCard.bottom, 1f)
        assertNoDeliverySpace()

        rule.runOnIdle { item = item.copy(text = "") }
        rule.waitForIdle()
        rule.onNodeWithTag("user-bubble:message").assertDoesNotExist()
        rule.onNodeWithTag("media-card:file:///valid-test-image.png").assertIsDisplayed()
    }

    @Test fun failedAndOpaqueImagesCollapseWithoutLeavingPreviewPlaceholderOrSpacer() {
        item = item.copy(
            text = "图片说明",
            images = listOf("file:///missing-test-image.png", "codex-file:file-opaque"),
        )
        fixture()
        rule.waitForIdle()
        rule.onNodeWithTag("media-card:file:///missing-test-image.png").assertDoesNotExist()
        rule.onNodeWithTag("media-card:codex-file:file-opaque").assertDoesNotExist()
        rule.onNodeWithText("无法预览").assertDoesNotExist()
        val bubble = bounds("user-bubble")
        val message = bounds("user-message")
        assertEquals("collapsed failed media must not leave 8dp spacer above text bubble", bubble.top, message.top, 0.5f)
        assertNoDeliverySpace()

        rule.runOnIdle { item = item.copy(text = "") }
        rule.waitForIdle()
        rule.onNodeWithTag("user-bubble:message").assertDoesNotExist()
        assertEquals(0f, bounds("user-message").height, 0.5f)
    }
}
