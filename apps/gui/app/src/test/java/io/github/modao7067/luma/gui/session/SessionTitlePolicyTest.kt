package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.NativeCliProjection
import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionTitlePolicyTest {
    private fun record(cli: String = "Codex") = ManagedSessionPolicy.newRecord("local", cli, "", "noroot", "/workspace", "", null)
    private fun restart(record: SessionRecord) = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(record)).toString())).single()

    @Test fun everyCliUsesOnePriorityPolicyAndRemembersTheFirstMessage() {
        CliCatalog.ids.forEach { cli ->
            val r = record(cli)
            SessionTitlePolicy.firstMessage(r, UserMessageContent("  修复   终端\n里的链接预览  "))
            assertEquals("修复 终端 里的链接预览", r.title)
            SessionTitlePolicy.firstMessage(r, UserMessageContent("later message"))
            assertEquals("修复 终端 里的链接预览", r.title)
            SessionTitlePolicy.nativeTitle(r, "Native name")
            assertEquals("Native name", r.title)
            SessionTitlePolicy.rename(r, "  My name  ")
            SessionTitlePolicy.nativeTitle(r, "Later native name")
            assertEquals("My name", r.title)
            val restored = restart(r)
            assertEquals("My name", restored.title)
            assertEquals("Later native name", restored.nativeTitle)
            assertEquals("修复 终端 里的链接预览", restored.messageTitle)
            SessionTitlePolicy.nativeTitle(restored, "After restart")
            SessionTitlePolicy.nativeTitle(restored, null)
            assertEquals("My name", restored.title)
            SessionTitlePolicy.rename(restored, "Another manual name")
            assertEquals("Another manual name", restart(restored).title)
            assertNull(restored.threadId)
        }
    }

    @Test fun emptyNativeTitlesNeverClearAConfirmedNativeOrMessageTitle() {
        val r = record()
        SessionTitlePolicy.firstMessage(r, UserMessageContent("Message name"))
        listOf(null, "", " \n ").forEach { assertFalse(SessionTitlePolicy.nativeTitle(r, it)); assertEquals("Message name", r.title) }
        SessionTitlePolicy.nativeTitle(r, "Native name")
        listOf(null, "", " \n ").forEach { SessionTitlePolicy.nativeTitle(r, it); assertEquals("Native name", r.title) }
        assertEquals("Native name", restart(r).title)
    }

    @Test fun openCodeOnlyIgnoresItsPreciselyDefinedDefaultNames() {
        val r = record("OpenCode")
        listOf("New session - 2026-11-03T09:05:01.123Z", "Child session - 2026-11-03T09:05:01.123Z").forEach {
            assertFalse(SessionTitlePolicy.nativeTitle(r, it))
        }
        assertEquals(SessionTitlePolicy.unnamed, r.title)
        listOf("New session", "Untitled", "新会话", "New session - project plan").forEach {
            assertTrue(SessionTitlePolicy.nativeTitle(r, it)); assertEquals(it, r.title)
        }
        val codex = record()
        val actual = "New session - 2026-11-03T09:05:01.123Z"
        SessionTitlePolicy.nativeTitle(codex, actual)
        assertEquals(actual, codex.title)
        SessionTitlePolicy.nativeTitle(r, "New session - 2026-11-03T09:05:01.123Z")
        assertEquals("New session - project plan", r.title)
    }

    @Test fun onlyCompleteNativeHistoryCanBackfillTheFirstRealUserMessage() {
        val r = record()
        val messages = listOf(ConversationItem("system", "system", "instructions"),
            ConversationItem("assistant", "assistant", "answer"), ConversationItem("tool", "tool", "result"),
            ConversationItem("blank", "user"), ConversationItem("draft", "user", "not admitted", delivery = "unknown"),
            ConversationItem("first", "user", "First real user message"), ConversationItem("later", "user", "Later"))
        assertFalse(SessionTitlePolicy.history(r, messages, ready = false))
        assertFalse(SessionTitlePolicy.history(r, emptyList(), ready = true))
        assertNull(restart(r).messageTitle)
        assertTrue(SessionTitlePolicy.history(r, messages, ready = true))
        assertEquals("First real user message", r.title)
        assertFalse(SessionTitlePolicy.history(r, emptyList(), ready = true))
        assertEquals("First real user message", restart(r).title)
    }

    @Test fun streamingUserChunksAreNotFrozenIntoPartialTitles() {
        val r = record("DeepSeek Harness")
        val partial = listOf(ConversationItem("first", "user", "Fix", status = "running"))
        assertFalse(SessionTitlePolicy.history(r, partial, ready = true))
        assertTrue(SessionTitlePolicy.history(r, listOf(partial.single().copy(text = "Fix the terminal", status = "completed")), ready = true))
        assertEquals("Fix the terminal", r.title)
    }

    @Test fun fallbackIsShortWhitespaceNormalizedAndNeverCutsASurrogatePair() {
        val r = record()
        SessionTitlePolicy.firstMessage(r, UserMessageContent("😀".repeat(45)))
        assertEquals("😀".repeat(40) + "…", r.title)
        val whitespace = record()
        assertFalse(SessionTitlePolicy.firstMessage(whitespace, UserMessageContent(" \n\t")))
        SessionTitlePolicy.firstMessage(whitespace, UserMessageContent("one\t two\u0000three\u00a0four"))
        assertEquals("one two three four", whitespace.title)
    }

    @Test fun codexAttachmentOnlyHistoryUsesTypeAndFilenameNotDisplayCaption() {
        val item = CodexProjection.item(JSONObject("""{"id":"user","type":"userMessage","content":[{"type":"localImage","path":"/work/diagram.png"}]}"""))
        assertEquals("", item.text)
        assertEquals("/work/diagram.png", item.media.single().source)
        val r = record()
        SessionTitlePolicy.history(r, listOf(item), ready = true)
        assertEquals("图片 · diagram.png", r.title)
        val text = record()
        SessionTitlePolicy.history(text, listOf(CodexProjection.item(JSONObject("""{"id":"user","type":"userMessage","content":[{"type":"text","text":"[图片] is literal user text"}]}"""))), ready = true)
        assertEquals("[图片] is literal user text", text.title)
    }

    @Test fun openCodeFileOnlyHistoryUsesItsNativeFilename() {
        val messages = OpenCodeProjection.message(JSONObject("""{"id":"user","type":"user","files":[{"uri":"file:///work/design.pdf","name":"design.pdf"}]}"""))
        val r = record("OpenCode")
        SessionTitlePolicy.history(r, messages, ready = true)
        assertEquals("文件 · design.pdf", r.title)
        assertEquals("图片", SessionTitlePolicy.attachment("图片", "data:image/png;base64,PRIVATE"))
    }

    @Test fun nativeSyntheticUserNoticesDoNotNameTheSession() {
        val notice = OpenCodeProjection.message(JSONObject("""{"id":"notice","type":"user","text":"Subagent finished","metadata":{"source":"subagent","childID":"child"}}"""))
        val user = OpenCodeProjection.message(JSONObject("""{"id":"real","type":"user","text":"Actual user request"}"""))
        val r = record("OpenCode")
        assertFalse(SessionTitlePolicy.history(r, notice, ready = true))
        SessionTitlePolicy.history(r, notice + user, ready = true)
        assertEquals("Actual user request", r.title)
    }

    @Test fun admittedOutboxUsesOriginalInputAndPureAttachmentButNotRejectedOrUnknownInput() {
        val r = record("pi").also { it.draft = "Original user input" }
        SubmissionLedger.begin(r, "submission", 1)
        assertEquals(SessionTitlePolicy.unnamed, r.title)
        val projected = NativeCliProjection.view(JSONObject("""{"messages":[{"id":"submission","role":"user","text":"/skill wrapper Original user input"}]}"""), SessionView(SessionSummary("local", "", "noroot", "/workspace")))
        SessionTitlePolicy.history(r, projected.messages, ready = true)
        SubmissionLedger.accepted(r)
        assertEquals("Original user input", r.title)
        val image = record("pi").also { it.images += StoredImage("i", "content://private", "/work/photo.png", "photo.png") }
        SubmissionLedger.begin(image, "image", 1); SubmissionLedger.accepted(image)
        assertEquals("图片 · photo.png", restart(image).title)
        val rejected = record().also { it.draft = "Not admitted" }
        SubmissionLedger.begin(rejected, "reject", 1); SubmissionLedger.rejected(rejected)
        assertNull(rejected.messageTitle)
        SubmissionLedger.begin(rejected, "unknown", 1); SubmissionLedger.expire(rejected, 31_000)
        assertNull(rejected.messageTitle)
        SubmissionLedger.accepted(rejected, titleEligible = false)
        assertNull(rejected.messageTitle)
    }

    @Test fun legacyAutomaticAndExplicitTitlesMigrateWithoutLosingNames() {
        fun legacy(title: String, native: Boolean) = decodeSessionIndex(JSONObject("""{"version":1,"sessions":[{"id":"local","title":"$title","environment":"noroot","cwd":"/work","threadId":"native","needsName":false,"useNativeTitle":$native}]}""")).single()
        val automatic = legacy("Old native name", true)
        assertNull(automatic.manualTitle); assertEquals("Old native name", automatic.nativeTitle)
        SessionTitlePolicy.nativeTitle(automatic, "TUI rename"); assertEquals("TUI rename", automatic.title)
        val manual = legacy("Old explicit name", false)
        SessionTitlePolicy.nativeTitle(manual, "TUI rename"); assertEquals("Old explicit name", restart(manual).title)
        val unnamed = legacy(SessionTitlePolicy.unnamed, false)
        assertTrue(SessionTitlePolicy.history(unnamed, listOf(ConversationItem("u", "user", "Recovered first message")), ready = true))
        assertEquals("Recovered first message", restart(unnamed).title)
    }

    @Test fun emptyManualRenameIsRejectedAndCannotRestoreAutomaticNaming() {
        val r = record()
        SessionTitlePolicy.rename(r, "Manual name")
        assertThrows(IllegalArgumentException::class.java) { SessionTitlePolicy.rename(r, " \n ") }
        SessionTitlePolicy.nativeTitle(r, "Automatic name")
        assertEquals("Manual name", restart(r).title)
    }

    @Test fun titleSourcesDoNotCopyNativeHistoryIntoTheLocalIndex() {
        val r = record()
        SessionTitlePolicy.history(r, listOf(ConversationItem("u", "user", "First message"), ConversationItem("tool", "tool", "Private command output")), ready = true)
        val json = encodeSessionIndex(listOf(r)).toString()
        assertFalse(json.contains("Private command output")); assertFalse(json.contains("messages")); assertFalse(json.contains("userContent"))
    }
}
