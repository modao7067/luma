package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentState
import org.junit.Assert.*
import org.junit.Test

class SubAgentTimelineTest {
    @Test fun namelessAgentsDoNotShareTheirRoleAsTheirOnlyIdentity() {
        val first = SubAgentState("019a4100-7b31-75dd-bb19-26344622aa53", role = "worker")
        val second = first.copy(id = "019a4100-7b31-75dd-bb19-26344622bb64")
        assertNotEquals(subAgentTitle(first), subAgentTitle(second))
        assertEquals("子代理 · 4622aa53", subAgentTitle(first))
        assertEquals("LumaChild", subAgentTitle(first.copy(id = "LumaChild")))
    }

    @Test fun nativeIdentityStaysWithFirstToolAcrossWaitAndAnswer() {
        val messages = listOf(ConversationItem("spawn", "tool", turnId = "t1"),
            ConversationItem("answer", "assistant", turnId = "t1"), ConversationItem("wait", "tool", turnId = "t1"))
        val agents = listOf(SubAgentState("one", itemId = "spawn", turnId = "t1"), SubAgentState("two", itemId = "spawn", turnId = "t1"))
        val rows = conversationRows(messages, agents)
        assertEquals(listOf("one", "two"), rows.first().agents.map { it.id })
        assertEquals("message:answer", rows[1].id)
        assertTrue(rows.last().agents.isEmpty())
        assertEquals(rows.map { it.id }, conversationRows(messages, agents.map { it.copy(status = "succeeded") }).map { it.id })
    }
    @Test fun differentTurnsAndMissingParentNeverMergeIntoWrongWork() {
        val messages = listOf(ConversationItem("a", "tool", turnId = "t1"), ConversationItem("b", "tool", turnId = "t2"))
        val rows = conversationRows(messages, listOf(SubAgentState("one", turnId = "t2"), SubAgentState("two", turnId = "t3")))
        assertEquals(3, rows.size)
        assertTrue(rows[0].agents.isEmpty())
        assertEquals("one", rows[1].agents.single().id)
        assertEquals("agents:t3", rows[2].id)
        assertTrue(rows[2].entries.isEmpty())
    }
    @Test fun completionCountsNeverComeFromParentToolOutcome() {
        val rows = conversationRows(listOf(ConversationItem("spawn", "tool", outcome = "succeeded")),
            listOf(SubAgentState("child", itemId = "spawn", status = "running")))
        assertEquals("running", rows.single().agents.single().status)
        assertEquals("等待中", subAgentStatus("pending"))
        assertEquals("已关闭", subAgentStatus("closed"))
        assertEquals("状态待确认", subAgentStatus("anything"))
        assertFalse(processEntryRetained(ConversationItem("closed", "tool", kind = "subAgent", outcome = "closed")))
        assertTrue(processEntryRetained(ConversationItem("legacy-closed", "tool", outcome = "closed")))
        assertTrue(processEntryRetained(ConversationItem("unknown", "tool", outcome = "unknown")))
    }
}
