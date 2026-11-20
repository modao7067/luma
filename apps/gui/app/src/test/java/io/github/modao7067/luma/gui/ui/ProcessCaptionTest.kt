package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SessionRecord
import io.github.modao7067.luma.gui.session.decodeSessionIndex
import io.github.modao7067.luma.gui.session.encodeSessionIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCaptionTest {
    private fun reasoning(outcome: String) = ConversationItem(outcome, "tool", kind = "reasoning", outcome = outcome)
    private val tool = ConversationItem("tool", "tool", kind = "commandExecution", outcome = "succeeded")

    @Test fun completedSummaryCountsStepsAndDuration() {
        assertEquals("2 步", processGroupCaption(listOf(tool, reasoning("succeeded"))))
        assertEquals("2 步 · 2m3s", processGroupCaption(listOf(tool, reasoning("succeeded")), durationMs = 123_000L))
    }
    @Test fun thinkingOnlyGroupsFoldDurationIntoThought() {
        assertEquals("已思考", processGroupCaption(listOf(reasoning("succeeded"))))
        assertEquals("思考了 12s", processGroupCaption(listOf(reasoning("succeeded")), durationMs = 12_000L))
    }
    @Test fun failedCallsAreSummarizedLikeAnyOtherCall() {
        val failed = tool.copy(id = "probe", outcome = "failed")
        assertEquals("2 步", processGroupCaption(listOf(tool, failed)))
        assertTrue(processGroupSummary(listOf(tool, failed)).none { it.emphasized })
    }
    @Test fun fileEditsAreSurfacedSeparately() {
        val edit = ConversationItem("edit", "tool", kind = "fileChange", outcome = "succeeded")
        assertEquals("3 步 · 修改 2 处 · 9s", processGroupCaption(listOf(tool, edit, edit.copy(id = "edit-2")), durationMs = 9_000L))
        assertEquals(listOf(false, true, false), processGroupSummary(listOf(tool, edit, edit.copy(id = "edit-2")), durationMs = 9_000L).map { it.emphasized })
    }
    @Test fun runningReasoningDoesNotClaimCompletion() {
        assertEquals("正在思考…", processGroupCaption(listOf(tool, reasoning("running"))))
    }
    @Test fun runningReasoningWinsAndSettledReasoningIsThought() {
        val entries = listOf("succeeded", "running", "failed", "cancelled", "").map(::reasoning)
        assertEquals("正在思考…", processGroupCaption(entries))
        assertEquals("已思考", processGroupCaption(entries.filterNot { it.outcome == "running" }))
    }
    @Test fun failedCancelledAndUnknownReasoningCarryNoFailureMark() {
        assertEquals("已思考", processGroupCaption(listOf("failed", "cancelled", "").map(::reasoning)))
    }
    @Test fun compactDurationsFormatSecondsMinutesAndHoursAndTrackLiveTransitions() {
        assertEquals("1s", formatProcessDuration(120L))
        assertEquals("45s", formatProcessDuration(45_000L))
        assertEquals("2m", formatProcessDuration(120_000L))
        assertEquals("2m3s", formatProcessDuration(123_000L))
        assertEquals("1h2m3s", formatProcessDuration(3_723_000L))
        assertEquals("25h", formatProcessDuration(90_000_000L))
        ProcessDurationStore.clearForTest()
        assertNull(ProcessDurationStore.observe("cold-group", active = false, nowMs = 1_000L))
        assertNull(ProcessDurationStore.observe("hot-group", active = true, nowMs = 1_000L))
        assertEquals(65_000L, ProcessDurationStore.observe("hot-group", active = false, nowMs = 66_000L))
        assertEquals(65_000L, ProcessDurationStore.observe("hot-group", active = false, nowMs = 99_000L))
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
    @Test fun openTurnShowsWorkingBeforeFirstStepAndBetweenCompletedStepsUntilEof() {
        val prevLang = System.getProperty("luma.lang")
        try {
            System.setProperty("luma.lang", "zh")
            LumaI18n.resetForTest()
            assertTrue(processGroupHasActive(emptyList(), turnOpen = true))
            assertFalse(processGroupHasActive(emptyList(), turnOpen = false))
            assertEquals("工作中…", processGroupCaption(emptyList(), turnOpen = true))
            assertEquals("工作中…", processGroupCaption(listOf(tool), turnOpen = true))
            assertEquals("正在思考…", processGroupCaption(listOf(tool, reasoning("running")), turnOpen = true))

            System.setProperty("luma.lang", "en")
            LumaI18n.resetForTest()
            assertEquals("Working…", processGroupCaption(emptyList(), turnOpen = true))
            assertEquals("Working…", processGroupCaption(listOf(tool), turnOpen = true))
        } finally {
            if (prevLang == null) System.clearProperty("luma.lang") else System.setProperty("luma.lang", prevLang)
            LumaI18n.resetForTest()
        }

        ProcessDurationStore.clearForTest()
        assertNull(ProcessDurationStore.observe("turn-open-group", active = processGroupHasActive(emptyList(), turnOpen = true), nowMs = 1_000L))
        assertNull(ProcessDurationStore.observe("turn-open-group", active = processGroupHasActive(listOf(reasoning("running")), turnOpen = true), nowMs = 2_500L))
        assertNull(ProcessDurationStore.observe("turn-open-group", active = processGroupHasActive(listOf(tool), turnOpen = true), nowMs = 4_000L))
        assertEquals(6_000L, ProcessDurationStore.observe("turn-open-group", active = processGroupHasActive(listOf(tool), turnOpen = false), nowMs = 7_000L))
    }

    @Test fun persistentTrackerSurvivesIdRehydrationAndStoreRoundTrip() {
        var nowMs = 10_000L
        val tracker = SessionProcessDurationTracker(clockMs = { nowMs })
        val persisted = linkedMapOf<String, Long>()
        val u1 = ConversationItem("client-u1", "user", "检查日志")
        val t1Running = ConversationItem("stream-tool-1", "tool", kind = "commandExecution", outcome = "running")
        val t1Done = t1Running.copy(outcome = "succeeded")
        val a1 = ConversationItem("stream-a1", "assistant", "完成了")

        assertFalse(tracker.reconcile(persisted, connected = true, loading = false, hydrating = false, activeTurnId = "t1", messages = listOf(u1), subAgents = emptyList()))
        nowMs = 15_000L
        assertFalse(tracker.reconcile(persisted, connected = true, loading = false, hydrating = false, activeTurnId = "t1", messages = listOf(u1, t1Running), subAgents = emptyList()))
        nowMs = 42_000L
        assertTrue(tracker.reconcile(persisted, connected = true, loading = false, hydrating = false, activeTurnId = null, messages = listOf(u1, t1Done, a1), subAgents = emptyList()))
        assertEquals(mapOf("u1:g0" to 32_000L), persisted)

        val u2 = ConversationItem("client-u2", "user", "第二轮")
        val t2Running = ConversationItem("stream-tool-2", "tool", kind = "commandExecution", outcome = "running")
        val t2Done = t2Running.copy(outcome = "succeeded")
        val baseMsgs = listOf(u1, t1Done, a1, u2)
        nowMs = 50_000L
        assertFalse(tracker.reconcile(persisted, connected = true, loading = false, hydrating = false, activeTurnId = "t2", messages = baseMsgs + t2Running, subAgents = emptyList()))
        nowMs = 55_000L
        assertFalse(tracker.reconcile(persisted, connected = false, loading = false, hydrating = false, activeTurnId = "t2", messages = baseMsgs + t2Running, subAgents = emptyList()))
        nowMs = 9_000_000L
        assertFalse(tracker.reconcile(persisted, connected = true, loading = false, hydrating = false, activeTurnId = null, messages = baseMsgs + t2Done, subAgents = emptyList()))
        assertFalse(persisted.containsKey("u2:g0"))

        val record = SessionRecord("s1", "标题", "noroot", "/workspace", cli = "Antigravity", processDurations = LinkedHashMap(persisted))
        val encoded = encodeSessionIndex(listOf(record))
        val restored = decodeSessionIndex(encoded).single()
        assertEquals(mapOf("u1:g0" to 32_000L), restored.processDurations)

        val rehydratedRows = sessionConversationRows(
            listOf(
                ConversationItem("agy-hist:0:user", "user", "检查日志"),
                ConversationItem("agy-hist:1:think", "tool", kind = "reasoning", outcome = "succeeded"),
                ConversationItem("agy-hist:2:text", "assistant", "完成了"),
            ),
            emptyList(),
            loading = false,
            activeTurnId = null,
        ).first
        val slotKeys = processGroupSlotKeys(rehydratedRows)
        val processRow = rehydratedRows.first { it.process }
        assertEquals(32_000L, restored.processDurations[slotKeys[processRow.id]])
    }

    @Test fun nativeProcessGroupDurationUsesTurnBoundariesWithoutIncludingLongIdleGaps() {
        val base = 1_760_000_000_000L
        val messages = listOf(
            ConversationItem("u1", "user", "run", timestampMs = base),
            ConversationItem("t1", "tool", kind = "commandExecution", outcome = "succeeded", timestampMs = base + 2_000L, completedAtMs = base + 9_000L),
            ConversationItem("a1", "assistant", "first reply", timestampMs = base + 12_000L),
            ConversationItem("t2", "tool", kind = "reasoning", outcome = "succeeded", timestampMs = base + 7_200_000L, completedAtMs = base + 7_205_000L),
            ConversationItem("a2", "assistant", "wakeup reply", timestampMs = base + 7_208_000L),
            ConversationItem("u2", "user", "cold codex"),
            ConversationItem("t3", "tool", kind = "commandExecution", outcome = "succeeded"),
            ConversationItem("a3", "assistant", "cold reply"),
        )
        val rows = conversationRows(messages)
        val firstProcIdx = rows.indexOfFirst { it.id == "process:t1" }
        val secondProcIdx = rows.indexOfFirst { it.id == "process:t2" }
        val thirdProcIdx = rows.indexOfFirst { it.id == "process:t3" }
        assertEquals(12_000L, nativeProcessGroupDurationMs(rows, firstProcIdx))
        assertEquals(8_000L, nativeProcessGroupDurationMs(rows, secondProcIdx))
        assertNull(nativeProcessGroupDurationMs(rows, thirdProcIdx))
    }
}
