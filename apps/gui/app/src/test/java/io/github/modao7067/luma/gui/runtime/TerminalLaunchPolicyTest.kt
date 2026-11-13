package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.agent.NativeCliProjection
import io.github.modao7067.luma.gui.session.SessionSummary
import io.github.modao7067.luma.gui.session.SessionView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalLaunchPolicyTest {
    @Test fun sessionIdentityIsStableAndDoesNotIncludeServerAddressOrCwd() {
        val first = TerminalLaunchPolicy.sessionWindowId("noroot", "Codex", "native-thread-123")
        assertTrue(first.matches(Regex("[a-f0-9]{32}")))
        assertEquals(first, TerminalLaunchPolicy.sessionWindowId("noroot", "Codex", "native-thread-123"))
    }
    @Test fun differentNativeSessionCliOrEnvironmentNeverShareTheWindowKey() {
        val ids = listOf(
            Triple("noroot", "Codex", "native-1"), Triple("noroot", "Codex", "native-2"),
            Triple("noroot", "OpenCode", "native-1"), Triple("root", "Codex", "native-1"),
        ).map { (environment, cli, thread) -> TerminalLaunchPolicy.sessionWindowId(environment, cli, thread) }
        assertEquals(ids.size, ids.toSet().size)
    }
    @Test fun newDomainMetadataIsNoRootOnlyAndRootKeepsTheLegacyTicketContract() {
        assertEquals("session", TerminalLaunchPolicy.mode("noroot", shell = false))
        assertEquals("ordinary", TerminalLaunchPolicy.mode("noroot", shell = true))
        assertNull(TerminalLaunchPolicy.mode("root", shell = false))
        assertNull(TerminalLaunchPolicy.mode("root", shell = true))
    }
    @Test fun rootGuiOptsIntoManagedDomainsWithoutChangingLegacyRootTickets() {
        assertEquals("session", TerminalLaunchPolicy.mode("root", shell = false, managedRoot = true))
        assertEquals("ordinary", TerminalLaunchPolicy.mode("root", shell = true, managedRoot = true))
        assertNull(TerminalLaunchPolicy.mode("root", shell = false))
    }
    @Test fun nativeCliWithoutSharedTuiRemainsDisabledEvenWhenConnected() {
        for (cli in listOf("pi", "DeepSeek Harness", "Kimi Code", "Claude Code", "Antigravity")) {
            val initial = SessionView(SessionSummary("local", "title", "noroot", "/workspace", cli = cli))
            val view = NativeCliProjection.view(JSONObject("""{"connected":true,"nativeId":"native"}"""), initial)
            assertTrue(view.connected)
            assertFalse("$cli must not offer a fake shell or second-process TUI", view.canOpenTui)
        }
    }
}
