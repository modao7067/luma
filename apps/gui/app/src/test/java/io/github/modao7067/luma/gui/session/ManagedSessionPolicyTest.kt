package io.github.modao7067.luma.gui.session

import org.junit.Assert.*
import org.junit.Test

class ManagedSessionPolicyTest {
    @Test fun blankCreationUsesNativeTitleForEveryCli() {
        CliCatalog.ids.forEach { cli ->
            val r = ManagedSessionPolicy.newRecord("local", cli, "", "noroot", "/workspace", "", null)
            assertTrue(r.useNativeTitle); assertTrue(r.needsName); assertNull(r.threadId)
            assertEquals("未命名会话", r.title)
            assertEquals("", r.model); assertEquals("noroot", r.environment); assertEquals("/workspace", r.cwd)
        }
    }
    @Test fun explicitTitleStillUsesInitialNativeNamingForEveryCli() {
        CliCatalog.ids.forEach { cli ->
            val r = ManagedSessionPolicy.newRecord("local", cli, " User title ", "noroot", "/workspace", "", null)
            assertFalse(r.useNativeTitle); assertTrue(r.needsName); assertEquals("User title", r.title)
        }
    }
    @Test fun unnamedAttachmentAdoptsExistingTitleWithoutRenamingIt() {
        val r = ManagedSessionPolicy.newRecord("local", "Codex", "", "noroot", "/workspace", "", "native")
        assertFalse(r.needsName); assertTrue(r.requireLoaded)
        ManagedSessionPolicy.nativeTitle(r, "Native title")
        assertEquals("Native title", r.title)
    }
    @Test fun nativeNameRemovalKeepsLastConfirmedTitle() {
        val r = SessionRecord("local", "old native title", "noroot", "/workspace", threadId = "native", needsName = false, useNativeTitle = true)
        ManagedSessionPolicy.nativeTitle(r, null)
        assertEquals("old native title", r.title)
    }
    @Test fun externalUnloadedThreadCannotBeTakenOver() {
        val r = SessionRecord("local", "title", "root", "/work", threadId = "external", requireLoaded = true)
        try { ManagedSessionPolicy.acceptLoaded(r, setOf("owned")); fail("must reject external server threads") } catch (_: IllegalStateException) { }
        assertTrue(r.requireLoaded); assertEquals("external", r.threadId)
    }
    @Test fun explicitLoadedAttachmentRetainsOriginalId() {
        val r = SessionRecord("local", "title", "root", "/work", threadId = "owned", requireLoaded = true)
        ManagedSessionPolicy.acceptLoaded(r, setOf("owned"))
        assertFalse(r.requireLoaded); assertEquals("owned", r.threadId)
    }
    @Test fun ordinaryReconnectAdoptsTuiRenameWithoutSettingOldName() {
        val r = SessionRecord("local", "old local", "root", "/work", threadId = "owned", needsName = false, useNativeTitle = true)
        ManagedSessionPolicy.nativeTitle(r, "Renamed in TUI")
        assertEquals("Renamed in TUI", r.title); assertFalse(r.needsName)
    }
    @Test fun unfinishedInitialNameKeepsUserIntentUntilConfirmed() {
        val r = SessionRecord("local", "user title", "root", "/work", threadId = "owned", needsName = true)
        ManagedSessionPolicy.nativeTitle(r, "old server name")
        assertEquals("user title", r.title); assertTrue(r.needsName)
    }
    @Test fun freshAntigravityChoosesCliInTheSharedCreationPolicyOnly() {
        CliCatalog.ids.forEach { cli ->
            val r = ManagedSessionPolicy.newRecord("new", cli, "", "noroot", "/work", "", null)
            assertEquals(if (cli == "Antigravity") "antigravity-agy-acp" else null, r.nativeProtocol)
        }
    }
    @Test fun explicitProtocolChoiceAndLegacyAttachmentKeepTheirOriginalContract() {
        val explicit = ManagedSessionPolicy.newRecord("explicit", "Antigravity", "", "root", "/work", "", null, "antigravity-acp")
        assertEquals("antigravity-acp", explicit.nativeProtocol)
        val explicitStream = ManagedSessionPolicy.newRecord("stream", "Antigravity", "", "root", "/work", "", null, "antigravity-cli-stream-json")
        assertEquals("antigravity-cli-stream-json", explicitStream.nativeProtocol)
        val oldAttach = ManagedSessionPolicy.newRecord("old", "Antigravity", "", "root", "/work", "", "old-native")
        assertNull(oldAttach.nativeProtocol)
        ManagedSessionPolicy.acceptNativeProtocol(oldAttach, org.json.JSONObject())
        assertThrows(IllegalArgumentException::class.java) {
            ManagedSessionPolicy.newRecord("invalid", "Codex", "", "root", "/work", "", null, "antigravity-cli-stream-json")
        }
    }
    @Test fun existingIdsUnknownCreationsAndFailedEmptyReferencesAreNeverMigratedOnLoad() {
        val old = listOf(
            SessionRecord("native", "旧ACP", "root", "/work", threadId = "old-native", cli = "Antigravity"),
            SessionRecord("unknown", "旧未知", "root", "/work", creationUnknown = true, cli = "Antigravity"),
            SessionRecord("empty", "未命名会话", "root", "/work", needsName = true, cli = "Antigravity"),
            SessionRecord("acp", "旧标记", "root", "/work", threadId = "marked-native", cli = "Antigravity", nativeProtocol = "antigravity-acp"),
        )
        val restored = decodeSessionIndex(org.json.JSONObject(encodeSessionIndex(old).toString()))
        assertEquals(listOf(null, null, null, "antigravity-acp"), restored.map { it.nativeProtocol })
        assertEquals("old-native", restored.first().threadId); assertTrue(restored[1].creationUnknown)
        assertNull(restored[2].threadId); assertTrue(restored[2].needsName)
    }
}
