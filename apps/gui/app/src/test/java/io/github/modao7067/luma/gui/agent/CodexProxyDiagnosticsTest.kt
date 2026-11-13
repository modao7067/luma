package io.github.modao7067.luma.gui.agent

import java.io.IOException
import java.net.ProtocolException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CodexProxyDiagnosticsTest {
    @Test fun safeReasonsDistinguishRootSocketProxyAndHandshakeWithoutLoggingRawStderr() {
        val cases = listOf(
            "su: Permission denied fixture-secret" to "root-denied",
            "Error: failed to connect to socket at /private/fixture-secret\nNo such file or directory (os error 2)" to "socket-missing",
            "Error: socket permission denied (os error 13) fixture-secret" to "socket-permission",
            "unrecognized subcommand 'proxy' fixture-secret" to "proxy-startup",
        )
        for ((stderr, reason) in cases) {
            val lines = mutableListOf<String>(); val diagnostics = CodexProxyDiagnostics { lines += it }
            diagnostics.started(); stderr.chunked(7).forEach(diagnostics::stderr)
            val failure = IOException("fixture-secret")
            val projected = diagnostics.failure(failure, 1)
            assertSame(failure, projected.cause)
            assertTrue(lines.last().contains("reason=$reason")); assertTrue(lines.last().contains("proxyExit=1"))
            assertFalse(lines.joinToString().contains("fixture-secret")); assertFalse(projected.message!!.contains("fixture-secret"))
        }
        val lines = mutableListOf<String>(); val diagnostics = CodexProxyDiagnostics { lines += it }; diagnostics.started()
        diagnostics.failure(ProtocolException("Expected HTTP 101 response but was '403 fixture-secret'"), null)
        assertTrue(lines.last().contains("reason=websocket-handshake")); assertTrue(lines.last().contains("http=403"))
        assertFalse(lines.joinToString().contains("fixture-secret"))
        diagnostics.failure(IOException("fixture-secret"), 7)
        assertTrue(lines.last().contains("reason=proxy-exit")); assertTrue(lines.last().contains("proxyExit=7"))
    }
    @Test fun privateLogRemainsBounded() {
        val file = Files.createTempFile("luma-external-diagnostic-", ".log").toFile()
        try {
            val log = CodexProxyLog(file)
            repeat(200) { log.append("x".repeat(1024)) }
            assertTrue(file.length() < 66 * 1024)
            log.append("event=fixture-complete")
            assertTrue(file.readText().endsWith("event=fixture-complete\n"))
        } finally { file.delete() }
    }
}
