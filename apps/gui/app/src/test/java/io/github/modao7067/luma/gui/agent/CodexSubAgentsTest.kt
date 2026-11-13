package io.github.modao7067.luma.gui.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CodexSubAgentsTest {
    private fun call(id: String, target: String, status: String = "running", tool: String = "spawnAgent") = obj(
        "type" to "collabAgentToolCall", "id" to id, "tool" to tool, "senderThreadId" to "parent",
        "receiverThreadIds" to JSONArray(listOf(target)), "agentsStates" to obj(target to obj("status" to status)),
        "prompt" to "Native task $target", "status" to "completed")
    @Test fun nativeConcurrentAgentsAreIndependentOfSuccessfulLaunchingTool() {
        val a = CodexSubAgents { "parent" }
        val one = call("launch-a", "child-a"); val two = call("launch-b", "child-b")
        a.item(one, "turn"); a.item(two, "turn")
        assertEquals(listOf("child-a", "child-b"), a.values().map { it.id })
        assertTrue(a.values().all { it.status == "running" && it.turnId == "turn" })
        assertEquals("succeeded", CodexProjection.item(one).outcome)
        assertEquals("分派子代理", CodexProjection.item(one).text)
        assertEquals("Native task child-a", CodexProjection.item(one).input)
        a.item(call("wait", "child-a", "completed", "wait"), "later-turn")
        assertEquals(listOf("succeeded", "running"), a.values().map { it.status })
        assertEquals("launch-a", a.values().first().itemId)
        assertEquals("turn", a.values().first().turnId)
    }
    @Test fun completionErrorCancellationAndNotFoundRetainNativeMeaning() {
        for ((native, normalized) in listOf("pendingInit" to "pending", "completed" to "succeeded", "errored" to "failed", "interrupted" to "cancelled", "shutdown" to "closed", "notFound" to "unknown")) {
            val a = CodexSubAgents { "parent" }; a.item(call("i", "child", native), "t")
            assertEquals(normalized, a.values().single().status)
            assertEquals(native, a.values().single().nativeStatus)
        }
    }
    @Test fun metadataIsParentScopedAndLateNamesDoNotReplaceTimelineAnchor() {
        val a = CodexSubAgents { "parent" }
        a.metadata(obj("id" to "unrelated", "agentNickname" to "Other"))
        a.metadata(obj("id" to "child", "agentNickname" to "Ada", "agentRole" to "explorer", "source" to obj("subAgent" to obj("thread_spawn" to obj("parent_thread_id" to "parent")))))
        a.item(call("launch", "child"), "turn")
        a.item(call("another", "other").put("senderThreadId", "unrelated"), "other-turn")
        assertEquals(1, a.values().size)
        assertEquals("Ada", a.values().single().name)
        assertEquals("explorer", a.values().single().role)
        assertEquals("launch", a.values().single().itemId)
    }
    @Test fun childTurnSettlementIsNativeAndUnrelatedSessionsCannotLeak() {
        val a = CodexSubAgents { "parent" }; val b = CodexSubAgents { "other-parent" }
        a.item(call("i", "child"), "turn")
        val event = obj("method" to "turn/completed", "params" to obj("threadId" to "child", "turn" to obj("status" to "failed", "error" to obj("message" to "native error"))))
        a.event(event); b.event(event)
        assertEquals("failed", a.values().single().status); assertEquals("native error", a.values().single().detail)
        assertTrue(b.values().isEmpty())
        a.event(obj("method" to "thread/status/changed", "params" to obj("threadId" to "child", "status" to obj("type" to "idle"))))
        assertEquals("failed", a.values().single().status)
        a.event(obj("method" to "turn/started", "params" to obj("threadId" to "child")))
        assertEquals("running", a.values().single().status)
    }
    @Test fun replayUsesSameNativeIdsWithoutDuplicatingAgentsOrSharingSessionState() {
        val history = listOf(call("a", "one"), call("b", "two"), call("wait", "one", "completed", "wait"))
        val a = CodexSubAgents { "parent" }; val restored = CodexSubAgents { "parent" }
        history.forEach { a.item(it, "turn") }; history.forEach { restored.item(it, "turn") }
        assertEquals(a.values(), restored.values())
        history.forEach { restored.item(it, "turn") }
        assertEquals(2, restored.values().size)
    }
    @Test fun subAgentActivityAndMetadataRejectForeignThreadsAndTrackVerification() {
        val a = CodexSubAgents { "parent" }
        a.item(obj("type" to "subAgentActivity", "id" to "act-self", "agentThreadId" to "parent", "kind" to "started"), "turn")
        a.item(obj("type" to "subAgentActivity", "id" to "act-blank", "agentThreadId" to "   ", "kind" to "started"), "turn")
        a.item(obj("type" to "subAgentActivity", "id" to "act-foreign-sender", "agentThreadId" to "foreign-1", "senderThreadId" to "other-parent", "kind" to "started"), "turn")
        assertTrue(a.values().isEmpty())
        assertFalse(a.acceptsRequestsFrom("foreign-1"))

        a.item(obj("type" to "subAgentActivity", "id" to "act-unverified", "agentThreadId" to "maybe-child", "agentPath" to "worker", "kind" to "started"), "turn")
        assertTrue(a.contains("maybe-child"))
        assertFalse(a.isVerified("maybe-child"))
        assertTrue(a.acceptsRequestsFrom("maybe-child"))

        assertFalse(a.metadata(obj("id" to "maybe-child", "parentThreadId" to "other-parent")))
        assertFalse(a.contains("maybe-child"))
        assertFalse(a.acceptsRequestsFrom("maybe-child"))
        a.item(obj("type" to "subAgentActivity", "id" to "act-again", "agentThreadId" to "maybe-child", "kind" to "started"), "turn")
        assertFalse(a.contains("maybe-child"))

        a.item(call("spawn-ok", "verified-child"), "turn")
        assertTrue(a.isVerified("verified-child"))
        assertTrue(a.acceptsRequestsFrom("verified-child"))
        assertTrue(a.metadata(obj("id" to "verified-child", "parentThreadId" to "parent", "agentNickname" to "Helper")))
        assertEquals("Helper", a.values().single().name)

        assertFalse(a.metadata(obj("id" to "verified-child")))
        assertTrue(a.values().isEmpty())
        assertFalse(a.acceptsRequestsFrom("verified-child"))
    }
}
