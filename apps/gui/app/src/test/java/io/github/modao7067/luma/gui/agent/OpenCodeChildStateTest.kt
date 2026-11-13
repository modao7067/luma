package io.github.modao7067.luma.gui.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OpenCodeChildStateTest {
    private fun failedCall() = obj("id" to "message", "type" to "assistant", "content" to JSONArray().put(obj(
        "id" to "call", "type" to "tool", "name" to "subagent", "time" to obj("created" to 100L, "ran" to 110L),
        "state" to obj("status" to "error", "input" to obj("prompt" to "Child task"),
            "metadata" to obj("sessionID" to "child", "status" to "running"),
            "error" to obj("message" to "Native foreground subagent failed")))))
    @Test fun failedForegroundCallWithoutChildOutcomeDoesNotClaimRunning() {
        val projected = OpenCodeProjection.subAgents(listOf(failedCall())).single()
        assertEquals("unknown", projected.status)
        assertEquals("child", projected.id)
        assertEquals("message:tool:call", projected.itemId)
    }
    private fun child(outcome: String, idleAt: Long) = obj("id" to "child", "parentID" to "parent", "outcome" to outcome, "time" to obj("idle" to idleAt))
    @Test fun actualNativeForegroundFailureUsesTheChildsOwnOutcome() {
        val fixture = org.json.JSONObject(javaClass.getResourceAsStream("/native/opencode-child-failure.json")!!.bufferedReader().use { it.readText() })
        val messages = fixture.getJSONArray("messages").objects()
        val child = fixture.getJSONObject("child")
        val id = child.getString("id")
        assertEquals("unknown", OpenCodeProjection.subAgents(messages).single().status)
        val projected = OpenCodeProjection.subAgents(messages, mapOf(id to child), emptySet(), fixture.getString("parentSessionId")).single()
        assertEquals("failed", projected.status); assertEquals("failed", projected.nativeStatus)
        assertTrue(projected.detail.contains("LUMA_INTENTIONAL_CHILD_PROVIDER_ERROR"))
    }
    @Test fun aResumedChildCannotInheritItsPreviousTerminalOutcome() {
        val message = failedCall()
        message.getJSONArray("content").getJSONObject(0).getJSONObject("time").put("ran", 300L)
        val snapshot = mapOf("child" to child("succeeded", 200L))
        assertEquals("unknown", OpenCodeProjection.subAgents(listOf(message), snapshot, emptySet(), "parent").single().status)
        assertEquals("running", OpenCodeProjection.subAgents(listOf(message), snapshot, setOf("child"), "parent").single().status)
        val cancelled = OpenCodeProjection.subAgents(listOf(message), mapOf("child" to child("interrupted", 400L)), emptySet(), "parent").single()
        assertEquals("cancelled", cancelled.status); assertEquals("message:tool:call", cancelled.itemId)
    }
    @Test fun cachedSnapshotsCannotOverwriteANewerNativeCompletion() {
        val message = failedCall(); val part = message.getJSONArray("content").getJSONObject(0)
        part.getJSONObject("state").put("status", "completed").getJSONObject("metadata").put("status", "completed")
        part.getJSONObject("time").put("completed", 400L)
        assertEquals("succeeded", OpenCodeProjection.subAgents(listOf(message), mapOf("child" to child("failed", 200L)), emptySet(), "parent").single().status)
    }
    @Test fun snapshotsFromAnotherParentCannotChangeTheAgentState() {
        val wrong = child("succeeded", 400L).put("parentID", "another-parent")
        assertEquals("unknown", OpenCodeProjection.subAgents(listOf(failedCall()), mapOf("child" to wrong), setOf("child"), "parent").single().status)
    }
    @Test fun nativeChildLifecycleOnlyInvalidatesItsOwningSessionsKnownChildren() {
        fun event(type: String, id: String, parentId: String? = null) = obj("type" to type, "data" to obj("sessionID" to id).apply { if (parentId != null) put("parentID", parentId) })
        for (type in listOf("session.execution.started", "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted", "session.idle", "session.renamed", "session.deleted")) {
            assertTrue(OpenCodeProjection.childLifecycleEvent(event(type, "child"), setOf("child")))
            assertFalse(OpenCodeProjection.childLifecycleEvent(event(type, "foreign-child"), setOf("child")))
        }
        assertTrue(OpenCodeProjection.childLifecycleEvent(event("session.created", "new-child", "parent"), emptySet(), "parent"))
        assertTrue(OpenCodeProjection.childLifecycleEvent(event("session.forked", "fork-child", "parent"), emptySet(), "parent"))
        assertFalse(OpenCodeProjection.childLifecycleEvent(event("session.created", "other-child", "other-parent"), emptySet(), "parent"))
        assertFalse(OpenCodeProjection.childLifecycleEvent(event("session.text.delta", "child"), setOf("child")))
    }
    @Test fun childSnapshotsWithMatchingParentIdAreDiscoveredEvenWhenToolMetadataIsEmpty() {
        val parentMsg = obj("id" to "msg", "type" to "assistant", "content" to JSONArray().put(obj(
            "id" to "call", "type" to "tool", "name" to "subagent",
            "state" to obj("status" to "running", "input" to obj("prompt" to "Audit GUI"), "metadata" to obj()))))
        val childSnap = obj("id" to "ses_child_1", "parentID" to "ses_parent", "title" to "Audit Compose UI", "agent" to "general", "time" to obj("busy" to 150L))
        val foreignSnap = obj("id" to "ses_foreign", "parentID" to "ses_other", "title" to "Foreign", "agent" to "explore")
        val projected = OpenCodeProjection.subAgents(listOf(parentMsg), mapOf("ses_child_1" to childSnap, "ses_foreign" to foreignSnap), setOf("ses_child_1"), "ses_parent")
        assertEquals(1, projected.size)
        val agent = projected.single()
        assertEquals("ses_child_1", agent.id)
        assertEquals("Audit Compose UI", agent.name)
        assertEquals("general", agent.role)
        assertEquals("running", agent.status)
    }
}
