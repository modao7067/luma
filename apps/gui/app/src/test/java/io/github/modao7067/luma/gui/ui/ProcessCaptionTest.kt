package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessCaptionTest {
    private fun reasoning(outcome: String) = ConversationItem(outcome, "tool", kind = "reasoning", outcome = outcome)
    private val tool = ConversationItem("tool", "tool", kind = "commandExecution", outcome = "succeeded")

    @Test fun completedCountKeepsTheToolCount() {
        assertEquals("1 次 shell · 1 次 思考", processGroupCaption(listOf(tool, reasoning("succeeded"))))
    }
    @Test fun runningReasoningDoesNotClaimCompletion() {
        assertEquals("1 次 shell · 1 次 思考", processGroupCaption(listOf(tool, reasoning("running"))))
    }
    @Test fun mixedOutcomesCountOnlySucceededReasoning() {
        val entries = listOf("succeeded", "running", "failed", "cancelled", "").map(::reasoning)
        assertEquals("5 次 思考", processGroupCaption(entries))
    }
    @Test fun failedCancelledAndUnknownStatesDoNotClaimCompletion() {
        assertEquals("3 次 思考", processGroupCaption(listOf("failed", "cancelled", "").map(::reasoning)))
    }
    @Test fun aSingleReasoningEntryUsesTheRequestedLabels() {
        assertEquals("思考中", reasoningCaption("running"))
        listOf("succeeded", "failed", "cancelled", "").forEach { assertEquals("思考", reasoningCaption(it)) }
    }
    @Test fun captionsDoNotRewriteTheUnderlyingContent() {
        val item = reasoning("succeeded").copy(text = "CLI 原始文字", detail = "用户保留的内容")
        processGroupCaption(listOf(item))
        assertEquals("CLI 原始文字", item.text)
        assertEquals("用户保留的内容", item.detail)
    }
}
