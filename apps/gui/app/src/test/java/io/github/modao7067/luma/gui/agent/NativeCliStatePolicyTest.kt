package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCliStatePolicyTest {
    private val cached = listOf(ModelOption("grok-4.7", "Native Grok"))
    private val initial = SessionView(SessionSummary("s", "title", "noroot", "/workspace", cli = "Grok Build"))

    @Test fun reconnectAndCatalogErrorKeepDisplayButNotDispatchAuthority() {
        for (phase in listOf("loading", "error")) {
            val s = JSONObject("""{"connected":true,"connecting":false,"catalogState":"$phase","models":[],"lifecycle":{"phase":"connecting","retryable":true}}""")
            assertEquals(cached, NativeCliStatePolicy.models(s, cached))
            assertTrue(NativeCliStatePolicy.models(s, cached, scopeChanged = true).isEmpty())
            assertFalse(NativeCliStatePolicy.ready(s))
            assertFalse(NativeCliProjection.view(s, initial).connected)
        }
    }

    @Test fun authoritativeEmptyClearsCacheAndReplacementKeepsNativeIds() {
        val empty = JSONObject("""{"connected":true,"catalogState":"ready","models":[],"lifecycle":{"phase":"ready","retryable":true}}""")
        assertTrue(NativeCliStatePolicy.models(empty, cached).isEmpty())
        empty.put("models", org.json.JSONArray("""[{"id":"native/new","label":"New"}]"""))
        assertEquals("native/new", NativeCliStatePolicy.models(empty, cached).single().id)
    }

    @Test fun authenticationModelAndConfigurationBlocksNeverBecomeConnectedOrRetryable() {
        for (reason in listOf("authentication", "model", "configuration")) {
            val s = JSONObject("""{"connected":true,"connecting":true,"catalogState":"ready","model":"","lifecycle":{"phase":"blocked","reason":"$reason","retryable":false,"message":"native rejected"}}""")
            assertFalse(NativeCliStatePolicy.ready(s)); assertFalse(NativeCliStatePolicy.retryable(s))
            val view = NativeCliProjection.view(s, initial)
            assertFalse(view.connected); assertFalse(view.loading)
            assertTrue(view.error.orEmpty().contains("native rejected"))
            assertTrue(view.error.orEmpty().contains("重新连接"))
            assertEquals("grok-4.7" to "high", NativeCliStatePolicy.settings(s, "grok-4.7", "high"))
        }
    }

    @Test fun loadingOrMissingSettingsNeverOverwriteTheSelectedModelAndEffort() {
        assertEquals("chosen" to "high", NativeCliStatePolicy.settings(JSONObject("""{"catalogState":"loading","model":"other","effort":""}"""), "chosen", "high"))
        assertEquals("chosen" to "high", NativeCliStatePolicy.settings(JSONObject("""{"catalogState":"ready"}"""), "chosen", "high"))
        assertEquals("native" to "", NativeCliStatePolicy.settings(JSONObject("""{"catalogState":"ready","model":"native","effort":""}"""), "chosen", "high"))
    }

    @Test fun transportRemainsRetryableAndLegacyProvidersKeepTheirContract() {
        val transport = JSONObject("""{"connected":false,"lifecycle":{"phase":"disconnected","reason":"transport","retryable":true}}""")
        assertTrue(NativeCliStatePolicy.retryable(transport)); assertFalse(NativeCliStatePolicy.ready(transport))
        val legacy = JSONObject("""{"connected":true,"models":[]}""")
        assertTrue(NativeCliStatePolicy.ready(legacy)); assertTrue(NativeCliStatePolicy.retryable(legacy))
        assertTrue(NativeCliStatePolicy.models(legacy, cached).isEmpty())
        legacy.put("connecting", true)
        assertTrue(NativeCliProjection.view(legacy, initial).connected)
        assertTrue(NativeCliProjection.view(legacy, initial).loading)
        assertEquals(cached, NativeCliStatePolicy.models(legacy, cached))
    }

    @Test fun nativeModelGateIsVisibleAndTerminatesExecutionWithoutChangingReceiptMeaning() {
        val s = JSONObject("""{"connected":true,"running":false,"catalogState":"ready","model":"","receipts":{"input":"accepted"},"messages":[{"id":"input","role":"user","text":"hello"}],"errorInfo":{"kind":"model","code":"MODEL_UNAVAILABLE","message":"No models available"},"lifecycle":{"phase":"blocked","reason":"model","retryable":false}}""")
        val view = NativeCliProjection.view(s, initial.copy(activeTurnId = "old"))
        assertNull(view.activeTurnId); assertFalse(view.connected)
        assertTrue(view.error.orEmpty().contains("No models available"))
        assertEquals("", view.messages.single().delivery)
        assertEquals("grok-4.7" to "high", NativeCliStatePolicy.settings(s, "grok-4.7", "high"))
    }

    @Test fun carrierMaintenanceDoesNotAuthorizeUnknownReplayOrInferAnUpdateButtonFromText() {
        val record = SessionRecord("s", "title", "noroot", "/workspace", cli = "Grok Build")
        record.receiptRecoveryRequired += "pre-restart"
        assertFalse(NativeCliStatePolicy.recoverableAbsence(record, "pre-restart"))
        assertTrue(NativeCliStatePolicy.recoverableAbsence(record, "new-carrier-write"))
        assertFalse(initial.cliServiceUpdateRequired)
        val error = JSONObject("""{"error":"需更新CLI服务；当前任务结束后重启"}""")
        assertFalse(NativeCliProjection.view(error, initial).cliServiceUpdateRequired)
    }

    @Test fun maintenanceImpactListsSharedProvidersAndOnlySuppliedKnownRunningSessions() {
        val clis = listOf("Grok Build", "Qoder CLI")
        val impact = NativeCliStatePolicy.serviceUpdateImpact(clis, listOf("Grok Build", "Qoder CLI", "Grok Build"))
        clis.forEach { assertTrue(impact.contains(it)) }
        assertTrue(impact.contains("3 个会话")); assertTrue(impact.contains("（Grok Build、Qoder CLI）"))
        assertTrue(NativeCliStatePolicy.serviceUpdateImpact(clis, emptyList()).contains("0 个会话"))
    }

    @Test fun displayScopeNeverCrossesEnvironmentProviderProtocolOrSocket() {
        val r = SessionRecord("s", "title", "noroot", "/workspace", cli = "Grok Build", nativeProtocol = "grok-acp")
        val scope = NativeCliCatalogIdentity.of(r)
        assertNotEquals(scope, NativeCliCatalogIdentity.of(r.copy(environment = "root")))
        assertNotEquals(scope, NativeCliCatalogIdentity.of(r.copy(cli = "pi")))
        assertNotEquals(scope, NativeCliCatalogIdentity.of(r.copy(nativeProtocol = "grok-private-leader-v1")))
        assertNotEquals(scope, NativeCliCatalogIdentity.of(r.copy(externalSocket = "/different.sock")))
    }

    @Test fun turnStartFastPollStopsWhenBlockedByUnknownOrBurstExpires() {
        assertTrue(NativeCliStatePolicy.shouldFastPollTurnStart(
            connected = true, loading = false, activeTurnId = null,
            admitting = false, sending = false, awaitingHandshake = false,
            hasPending = true, hasUnsettledUnknown = false,
            nowNs = 1_000L, burstDeadlineNs = 5_000L,
        ))
        assertFalse(NativeCliStatePolicy.shouldFastPollTurnStart(
            connected = true, loading = false, activeTurnId = null,
            admitting = false, sending = false, awaitingHandshake = false,
            hasPending = true, hasUnsettledUnknown = true,
            nowNs = 1_000L, burstDeadlineNs = 5_000L,
        ))
        assertFalse(NativeCliStatePolicy.shouldFastPollTurnStart(
            connected = true, loading = false, activeTurnId = null,
            admitting = false, sending = false, awaitingHandshake = false,
            hasPending = true, hasUnsettledUnknown = false,
            nowNs = 5_000L, burstDeadlineNs = 5_000L,
        ))
        assertTrue(NativeCliStatePolicy.shouldFastPollTurnStart(
            connected = true, loading = false, activeTurnId = null,
            admitting = true, sending = false, awaitingHandshake = false,
            hasPending = true, hasUnsettledUnknown = true,
            nowNs = 9_000L, burstDeadlineNs = 5_000L,
        ))
    }
}
