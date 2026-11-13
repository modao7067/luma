package io.github.modao7067.luma.gui.session

import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalCodexAttachmentTest {
    private val socket = "/tmp/codex-daemon-0/fixture.sock"
    private fun record() = ManagedSessionPolicy.newRecord("local", "Codex", "Local alias", "root", "/work", "", "native", externalSocket = socket)

    @Test fun explicitExternalOwnerSurvivesReloadWithoutHistoryOrInitialization() {
        val r = record()
        assertFalse(r.needsName); assertTrue(r.requireLoaded)
        val encoded = encodeSessionIndex(listOf(r))
        val loaded = decodeSessionIndex(JSONObject(encoded.toString())).single()
        assertEquals(socket, loaded.externalSocket); assertEquals("native", loaded.threadId)
        assertEquals("Local alias", loaded.title); assertFalse(loaded.needsName)
        assertFalse(encoded.toString().contains("messages"))
        val legacy = SessionRecord("old", "Old", "root", "/work", threadId = "native")
        assertNull(decodeSessionIndex(encodeSessionIndex(listOf(legacy))).single().externalSocket)
    }
    @Test fun externalAttachmentDoesNotRegisterSkillsReadConfigOrEvaluateBrowserBinding() = runBlocking {
        val r = record(); val calls = mutableListOf<String>(); var saves = 0
        suspend fun request(method: String, params: JSONObject): JSONObject {
            calls += method
            assertEquals("thread/loaded/list", method)
            return obj("data" to JSONArray(listOf("native")))
        }
        val config = CodexConnectionSetup.prepare(r, "/unused", { error("external owner must not bind config") }, ::request, { saves++ })
        assertNull(config); assertEquals(listOf("thread/loaded/list"), calls); assertEquals(1, saves)
        assertFalse(r.requireLoaded)
        assertThrows(IllegalStateException::class.java) { runBlocking {
            CodexConnectionSetup.prepare(r, "/unused", { error("unused") }, { _, _ -> obj("data" to JSONArray()) }, {})
        } }
        val resume = CodexConnectionSetup.resumeParams(r, obj("mustNotLeak" to true))
        assertEquals(setOf("threadId", "excludeTurns"), resume.keys().asSequence().toSet())
        assertEquals("native", resume.getString("threadId"))
    }
    @Test fun explicitRefreshResumesOnlyExactExternalIdWithoutChangingItsConfigOrCreatingThread() = runBlocking {
        val r = record()
        val config = CodexConnectionSetup.prepare(r, "/unused", { error("must not change external config") },
            { _, _ -> error("explicit recovery must not require loaded or start a replacement") }, {}, recoverExternal = true)
        assertNull(config)
        assertEquals("native", CodexConnectionSetup.resumeParams(r, config).getString("threadId"))
        assertEquals(socket, r.externalSocket)
        assertTrue(r.requireLoaded)
    }
    @Test fun loadedListPagesBeforeJoiningExactThread() = runBlocking {
        val r = record(); val cursors = mutableListOf<String?>()
        CodexConnectionSetup.prepare(r, "/unused", { error("unused") }, { _, params ->
            cursors += params.optString("cursor").takeIf { it.isNotEmpty() }
            if (cursors.size == 1) obj("data" to JSONArray(listOf("other")), "nextCursor" to "next")
            else obj("data" to JSONArray(listOf("native")))
        }, {})
        assertEquals(listOf(null, "next"), cursors)
    }
    @Test fun existingManagedConfigAndOfflineReconnectContractRemainAvailable() = runBlocking {
        val r = SessionRecord("old", "Old", "noroot", "/work", threadId = "native")
        val calls = mutableListOf<String>()
        val config = CodexConnectionSetup.prepare(r, "/skills", { "/browser/config" }, { method, params ->
            calls += method
            if (method == "skills/extraRoots/set") assertEquals("/skills", params.getJSONArray("extraRoots").getString(0))
            obj("config" to obj("shell_environment_policy" to obj("set" to obj("KEEP" to "original"))))
        }, { fail("ordinary reconnect does not require loaded") })!!
        assertEquals(listOf("skills/extraRoots/set", "config/read"), calls)
        val vars = config.getJSONObject("shell_environment_policy.set")
        assertEquals("original", vars.getString("KEEP")); assertEquals("/browser/config", vars.getString("LUMA_BROWSER_CONFIG"))
        assertTrue(CodexConnectionSetup.resumeParams(r, config).has("config"))
    }
    @Test fun attachmentOnlyAcceptsRootCodexAndExplicitAbsoluteSocket() {
        SessionManagementProtocol.validate(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "native", "externalSocket" to socket))
        for (path in listOf("", "unix://$socket", "relative.sock", "/tmp/a\nother", "/" + "a".repeat(107))) {
            assertThrows(IllegalArgumentException::class.java) { ExternalCodexPolicy.validateSocket(path) }
        }
        for ((cli, env, thread) in listOf(Triple("Codex", "noroot", "native"), Triple("pi", "root", "native"), Triple("Codex", "root", null))) {
            assertThrows(IllegalArgumentException::class.java) { ExternalCodexPolicy.validateAttachment(cli, env, thread, socket) }
        }
        for (request in listOf(
            obj("action" to "create", "cli" to "Codex", "cwd" to "/work", "requestId" to "new", "externalSocket" to socket),
            obj("action" to "attach", "cli" to "pi", "cwd" to "/work", "nativeId" to "native", "externalSocket" to socket))) {
            assertThrows(IllegalArgumentException::class.java) { SessionManagementProtocol.validate(request) }
        }
    }
    @Test fun explicitRepairRejectsWrongTargetBoundSessionsBusyConnectionsAndOutbox() {
        val failed = ManagedSessionPolicy.newRecord("failed", "Codex", "", "root", "/work", "", "native")
        ManagedSessionPolicy.validateExternalRepair(failed, "Codex", "native", socket, connected = false, loading = false)
        assertNull(failed.externalSocket)
        for ((cli, native) in listOf("pi" to "native", "Codex" to "different")) {
            assertThrows(IllegalArgumentException::class.java) {
                ManagedSessionPolicy.validateExternalRepair(failed, cli, native, socket, false, false)
            }
        }
        for ((connected, loading) in listOf(true to false, false to true)) {
            assertThrows(IllegalStateException::class.java) {
                ManagedSessionPolicy.validateExternalRepair(failed, "Codex", "native", socket, connected, loading)
            }
        }
        failed.requireLoaded = false
        assertThrows(IllegalStateException::class.java) { ManagedSessionPolicy.validateExternalRepair(failed, "Codex", "native", socket, false, false) }
        failed.requireLoaded = true; failed.submissions += StoredSubmission("pending", "unsent")
        assertThrows(IllegalStateException::class.java) { ManagedSessionPolicy.validateExternalRepair(failed, "Codex", "native", socket, false, false) }
    }
    @Test fun terminalAndProxyTargetSameSocketWithoutManagedTokenOrNewOwner() {
        val command = ExternalCodexPolicy.remoteCommand(socket, "native", "/work's dir")
        assertTrue(command.contains("--remote 'unix://$socket'"))
        assertFalse(command.contains("auth")); assertFalse(command.contains("TOKEN")); assertFalse(command.contains("cat "))
        assertTrue(command.contains("cd '/work'\"'\"'s dir'"))
        val proxy = ExternalCodexPolicy.proxyCommand(socket)
        assertEquals(listOf("/system/bin/su", "-c"), proxy.take(2))
        assertTrue(proxy.last().contains("'app-server' 'proxy' '--sock' '$socket'"))
        assertFalse(proxy.last().contains("--listen"))
    }
}
