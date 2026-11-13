package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexSubAgentHistoryTest {
    private fun metadata(parent: String = "parent", legacy: Boolean = false) = obj("id" to "child", "parentThreadId" to parent,
        "historyMode" to if (legacy) "legacy" else "paginated", "model" to "native-child-model", "reasoningEffort" to "high")
    private fun message(text: String) = obj("type" to "agentMessage", "id" to "output", "text" to text)
    private fun event(method: String, id: String = "child", vararg values: Pair<String, Any?>) = obj("method" to method, "params" to obj("threadId" to id, *values))
    @Test fun fullPaginationAndHydrationBoundariesRetainLaterDeltasWithoutDuplicatingEarlierText() = runBlocking {
        val methods = mutableListOf<String>()
        lateinit var history: CodexSubAgentHistory
        history = CodexSubAgentHistory("parent", "child", { method, params ->
            methods += method
            when (method) {
                "thread/read" -> obj("thread" to metadata())
                "thread/turns/list" -> obj("data" to JSONArray().put(obj("id" to "turn", "status" to "inProgress")))
                "thread/items/list" -> if (params.stringOrNull("cursor") == null) {
                    history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "A"))
                    obj("data" to JSONArray().put(obj("turnId" to "turn", "item" to message("A"))), "nextCursor" to "native-next")
                } else {
                    history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "B"))
                    obj("data" to JSONArray().put(obj("turnId" to "turn", "item" to obj("type" to "reasoning", "id" to "reason", "summary" to JSONArray().put("Native thought")), "completedAtMs" to 1)))
                }
                else -> error("Mutation or unsupported call: $method")
            }
        }, { it })
        val snapshot = history.refresh()
        assertEquals("AB", snapshot.messages.first().text)
        assertEquals("Native thought", snapshot.messages.last().detail)
        assertEquals("succeeded", snapshot.messages.last().outcome)
        assertEquals("native-child-model", snapshot.model); assertEquals("high", snapshot.effort)
        assertEquals(2, methods.count { it == "thread/items/list" })
        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "C"))
        assertEquals("ABC", history.messages.first().text)
        assertFalse(history.event(event("item/agentMessage/delta", "foreign", "itemId" to "output", "delta" to "FOREIGN")))
        assertFalse(history.event(event("item/tool/requestUserInput").put("id", 12)))
        assertEquals("ABC", history.messages.first().text)
        assertTrue(methods.all { it in setOf("thread/read", "thread/items/list", "thread/turns/list") })
    }
    @Test fun legacyHistoryIsReadWithoutResumingAndNativeReasoningContentRemainsVisible() = runBlocking {
        val calls = mutableListOf<String>()
        val history = CodexSubAgentHistory("parent", "child", { method, params ->
            calls += method
            if (method == "thread/turns/list") obj("data" to JSONArray()) else {
            assertEquals("thread/read", method)
            val thread = metadata(legacy = true)
            if (params.optBoolean("includeTurns")) thread.put("turns", JSONArray().put(obj("id" to "turn", "status" to "completed", "items" to JSONArray().put(
                obj("type" to "reasoning", "id" to "reason", "summary" to JSONArray().put("Summary"), "content" to JSONArray().put("Native visible content"))))))
            obj("thread" to thread)
            }
        }, { it })
        val snapshot = history.refresh()
        assertEquals(listOf("thread/read", "thread/turns/list", "thread/read"), calls)
        assertEquals("Summary\nNative visible content", snapshot.messages.single().detail)
        assertEquals("succeeded", snapshot.messages.single().outcome)
    }
    @Test fun wrongNativeParentStopsBeforeReadingMessages() = runBlocking {
        val calls = mutableListOf<String>()
        val history = CodexSubAgentHistory("parent", "child", { method, _ -> calls += method; obj("thread" to metadata(parent = "another-parent")) }, { it })
        try { history.refresh(); fail("Mismatched parent was accepted") } catch (_: IllegalStateException) {}
        assertEquals(listOf("thread/read"), calls); assertTrue(history.messages.isEmpty())
    }
    @Test fun newerLiveEventsWinOverPolledPersistedSnapshotsAndRequestsNeverBecomeControls() = runBlocking {
        val history = CodexSubAgentHistory("parent", "child", { method, _ -> when (method) {
            "thread/read" -> obj("thread" to metadata())
            "thread/items/list" -> obj("data" to JSONArray().put(obj("turnId" to "turn", "item" to message("stored"))))
            "thread/turns/list" -> obj("data" to JSONArray())
            else -> error(method)
        } }, { it })
        history.refresh()
        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to " LIVE"))
        history.refresh()
        assertEquals("stored LIVE", history.messages.single().text)
        history.event(event("thread/settings/updated", "child", "threadSettings" to obj("model" to "changed-child", "effort" to "low")))
        assertEquals("changed-child", history.modelId); assertEquals("low", history.reasoningEffort)
        history.event(event("item/completed", "child", "item" to message("What?").put("delivery", "async").put("questions", JSONArray().put(obj("title" to "Choose", "options" to JSONArray(listOf("One", "Two")))))))
        assertTrue(history.messages.all { it.asyncQuestions.isEmpty() })
    }
    @Test fun nativeTurnCursorAvoidsFullRescansAndPersistedCompletionSettlesAMissingLiveCompletion() = runBlocking {
        var final = false
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val history = CodexSubAgentHistory("parent", "child", { method, params ->
            calls += method to params
            when (method) {
                "thread/read" -> obj("thread" to metadata())
                "thread/items/list" -> obj("data" to JSONArray().put(obj("turnId" to "turn", "item" to message("first"))))
                "thread/turns/list" -> if (params.optString("sortDirection") == "desc") obj("data" to JSONArray().put(obj("id" to "turn", "status" to if (final) "completed" else "inProgress")), "backwardsCursor" to "official-tail")
                    else { assertEquals("official-tail", params.getString("cursor")); obj("data" to JSONArray().put(obj("id" to "turn", "status" to if (final) "completed" else "inProgress", "items" to JSONArray().put(message(if (final) "final native output" else "first"))))) }
                else -> error(method)
            }
        }, { it })
        history.refresh()
        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to " partial"))
        history.refresh()
        assertEquals("first partial", history.messages.single().text)
        final = true; history.refresh()
        assertEquals("final native output", history.messages.single().text)
        assertEquals("succeeded", history.messages.single().outcome)
        assertEquals(1, calls.count { it.first == "thread/items/list" })
    }

    @Test fun inFlightOverlapLongerRunningSnapshotsAndUserClientIdStayConsistent() = runBlocking {
        var snapshotText = "Hello wo"
        lateinit var history: CodexSubAgentHistory
        var firstHydrate = true
        history = CodexSubAgentHistory("parent", "child", { method, _ ->
            when (method) {
                "thread/read" -> obj("thread" to metadata())
                "thread/turns/list" -> obj("data" to JSONArray().put(obj("id" to "turn", "status" to "inProgress")))
                "thread/items/list" -> {
                    if (firstHydrate) {
                        firstHydrate = false
                        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "wo"))
                        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "rld0"))
                    }
                    obj("data" to JSONArray()
                        .put(obj("turnId" to "turn", "item" to obj("type" to "userMessage", "id" to "u-native", "content" to JSONArray().put(obj("type" to "text", "text" to "prompt")))))
                        .put(obj("turnId" to "turn", "item" to message(snapshotText).put("status", "inProgress"))))
                }
                else -> error(method)
            }
        }, { it })
        val first = history.refresh()
        assertEquals("Hello world0", first.messages.last().text)
        history.event(event("item/agentMessage/delta", "child", "itemId" to "output", "delta" to "0"))
        assertEquals("Hello world00", history.messages.last().text)
        snapshotText = "Hello world00 extended in progress"
        val advanced = history.refresh()
        assertEquals("Hello world00 extended in progress", advanced.messages.last().text)
        history.event(event("item/completed", "child", "turnId" to "turn", "item" to obj("type" to "userMessage", "id" to "u-native", "clientId" to "u-client", "content" to JSONArray().put(obj("type" to "text", "text" to "prompt")))))
        assertEquals(listOf("u-client", "output"), history.messages.map { it.id })
    }
}
