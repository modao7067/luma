package io.github.modao7067.luma.gui.session

import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexThreadInitializationTest {
    private fun record(title: String = "", threadId: String? = null) =
        ManagedSessionPolicy.newRecord("local", "Codex", title, "noroot", "/workspace", "", threadId)

    private fun nativeThread(section: Any = JSONObject.NULL) = obj("id" to "native", "section" to section)

    @Test fun newUnnamedThreadPersistsWithoutNameOrTurn() = runBlocking {
        val r = record().also { it.threadId = "native" }
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var persisted = false
        CodexThreadInitialization.finish(r, nativeThread(), { method, params ->
            calls += method to params; JSONObject()
        }, { persisted = true })
        assertEquals(listOf("thread/section/move"), calls.map { it.first })
        assertEquals("native", calls.single().second.getString("threadId"))
        assertTrue(calls.single().second.has("sectionId"))
        assertTrue(calls.single().second.isNull("sectionId"))
        assertEquals("未命名会话", r.title)
        assertTrue(r.useNativeTitle); assertFalse(r.needsName); assertTrue(persisted)
    }

    @Test fun explicitTitleKeepsLegacyNamingContract() = runBlocking {
        val r = record("  My title  ").also { it.threadId = "native" }
        val calls = mutableListOf<Pair<String, JSONObject>>()
        CodexThreadInitialization.finish(r, null, { method, params ->
            calls += method to params; JSONObject()
        }, {})
        assertEquals(listOf("thread/name/set"), calls.map { it.first })
        assertEquals("My title", calls.single().second.getString("name"))
        assertFalse(r.useNativeTitle); assertFalse(r.needsName)
    }

    @Test fun ordinaryReconnectAndUnnamedAttachmentNeverInitializeExistingThread() = runBlocking {
        val attached = record(threadId = "native")
        val complete = record().also { it.threadId = "native"; it.needsName = false }
        for (r in listOf(attached, complete)) {
            CodexThreadInitialization.finish(r, null, { _, _ -> error("Existing thread must not be changed") }, { fail("No write needed") })
        }
        assertTrue(attached.requireLoaded)
    }

    @Test fun manualAliasNeverBecomesAnInitialNativeName() = runBlocking {
        val automatic = record().also { it.threadId = "native" }
        SessionTitlePolicy.rename(automatic, "Local alias")
        val calls = mutableListOf<String>()
        CodexThreadInitialization.finish(automatic, nativeThread(), { method, _ -> calls += method; JSONObject() }, {})
        assertEquals(listOf("thread/section/move"), calls)
        val explicit = record("Original explicit title").also { it.threadId = "native" }
        SessionTitlePolicy.rename(explicit, "Later local alias")
        val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(explicit)).toString())).single()
        CodexThreadInitialization.finish(restored, null, { method, params ->
            assertEquals("thread/name/set", method); assertEquals("Original explicit title", params.getString("name")); JSONObject()
        }, {})
        assertEquals("Later local alias", restored.title)
    }

    @Test fun failedInitializationRetainsSameIdAndRetryReadsBeforePersisting() = runBlocking {
        val r = record().also { it.threadId = "native" }
        try {
            CodexThreadInitialization.finish(r, nativeThread(), { _, _ -> error("Response lost") }, {})
            fail("Must surface the failure")
        } catch (_: IllegalStateException) { }
        assertTrue(r.needsName); assertEquals("native", r.threadId)
        val calls = mutableListOf<String>()
        CodexThreadInitialization.finish(r, null, { method, params ->
            calls += method
            assertEquals("native", params.getString("threadId"))
            if (method == "thread/read") obj("thread" to nativeThread()) else JSONObject()
        }, {})
        assertEquals(listOf("thread/read", "thread/section/move"), calls)
        assertFalse(r.needsName); assertEquals("native", r.threadId)
    }

    @Test fun retryNeverClearsSectionSetByNativeClient() = runBlocking {
        val r = record().also { it.threadId = "native" }
        val calls = mutableListOf<String>()
        CodexThreadInitialization.finish(r, null, { method, _ ->
            calls += method
            obj("thread" to nativeThread(obj("id" to "user-section")))
        }, {})
        assertEquals(listOf("thread/read"), calls)
        assertFalse(r.needsName)
    }

    @Test fun mismatchedThreadCannotBeInitialized() = runBlocking {
        val r = record().also { it.threadId = "native" }
        try {
            CodexThreadInitialization.finish(r, obj("id" to "other", "section" to JSONObject.NULL),
                { _, _ -> error("No mutation expected") }, {})
            fail("Must reject another native ID")
        } catch (_: IllegalStateException) { }
        assertTrue(r.needsName); assertEquals("native", r.threadId)
    }

    @Test fun loadedThreadsStopsOnRepeatedPaginationCursor() = runBlocking {
        var calls = 0
        val loaded = CodexConnectionSetup.loadedThreads { _, _ ->
            calls++
            assertTrue(calls <= 3)
            obj("data" to org.json.JSONArray(listOf("t-$calls")), "nextCursor" to "loop")
        }
        assertEquals(2, calls)
        assertEquals(setOf("t-1", "t-2"), loaded)
    }
}
