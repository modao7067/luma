package io.github.modao7067.luma.gui.session

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SessionManagementProtocolTest {
    @Test fun exactCliTargetsAndStableRequestIdsAreRequired() {
        SessionManagementProtocol.validate(JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"job_1"}"""))
        for (raw in listOf(
            """{"action":"create","cli":"Codex","cwd":"/work"}""",
            """{"action":"create","cli":"Codex","cwd":"/work","requestId":"job","environment":"root"}""",
            """{"action":"send","text":"task","requestId":"job"}""",
            """{"action":"send","id":"target","text":"task","requestId":"job; rm"}""",
            """{"action":"status"}""", """{"action":"status","id":"x","requestId":"job"}""",
            """{"action":"rename","id":"target","title":"  "}""",
            """{"action":"answer","id":"target"}""", """{"action":"read","id":"target","limit":101}""",
        )) assertThrows(Exception::class.java) { SessionManagementProtocol.validate(JSONObject(raw)) }
    }
    @Test fun payloadIdentityIgnoresJsonOrderingButNotChangedTextOrTarget() {
        val a = JSONObject("""{"action":"send","id":"a","text":"one","requestId":"job"}""")
        val b = JSONObject("""{"requestId":"job","text":"one","id":"a","action":"send"}""")
        assertEquals(SessionManagementProtocol.canonical(a), SessionManagementProtocol.canonical(b))
        b.put("id", "b")
        assertNotEquals(SessionManagementProtocol.canonical(a), SessionManagementProtocol.canonical(b))
    }
    @Test fun nativeIdentityIsOpaqueAtTransportBoundaryAndNativeAdaptersOwnTheirFormat() {
        SessionManagementProtocol.validate(JSONObject("""{"action":"attach","cli":"pi","cwd":"/work","nativeId":"/work/pi sessions/native.jsonl"}"""))
        assertThrows(Exception::class.java) { SessionManagementProtocol.validate(JSONObject()
            .put("action", "attach").put("cli", "pi").put("cwd", "/work").put("nativeId", "x\nother")) }
    }
    @Test fun multiProtocolCliRequiresExplicitAttachmentIdentityAndDoesNotAffectOtherClis() {
        val request = JSONObject("""{"action":"attach","cli":"Antigravity","cwd":"/work","nativeId":"same-uuid"}""")
        assertThrows(Exception::class.java) { SessionManagementProtocol.validate(request) }
        request.put("nativeProtocol", "antigravity-cli-stream-json"); SessionManagementProtocol.validate(request)
        request.put("nativeProtocol", "antigravity-acp"); SessionManagementProtocol.validate(request)
        request.put("cli", "pi")
        assertThrows(Exception::class.java) { SessionManagementProtocol.validate(request) }
        request.remove("nativeProtocol"); SessionManagementProtocol.validate(request)
    }
    @Test fun realLoopbackAuthenticatesBeforeDispatchAndNeverReplaysAfterDisconnect() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = AtomicInteger()
        val server = SessionManagementServer("fixture-secret", { request ->
            assertFalse(request.has("token")); calls.incrementAndGet()
            JSONObject().put("ok", true).put("id", request.getString("id"))
        }, scope, Dispatchers.Unconfined)
        fun call(token: String): JSONObject = Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write((JSONObject().put("action", "status").put("id", "target").put("token", token).toString() + "\n").toByteArray())
            JSONObject(socket.getInputStream().bufferedReader().readLine())
        }
        try {
            assertFalse(call("wrong").getBoolean("ok")); assertEquals(0, calls.get())
            assertTrue(call("fixture-secret").getBoolean("ok")); assertEquals(1, calls.get())
            assertFalse(call("").getBoolean("ok")); assertEquals(1, calls.get())
        } finally { server.close(); scope.cancel() }
    }
    @Test fun cwdRejectsIsoControlCharacters() {
        for (badCwd in listOf("/work\nnext", "/work\rnext", "/work\tnext", "/work\u0000next")) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionManagementProtocol.validate(JSONObject()
                    .put("action", "create").put("cli", "Codex").put("cwd", badCwd).put("requestId", "req_1"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                SessionManagementProtocol.validate(JSONObject()
                    .put("action", "attach").put("cli", "Codex").put("cwd", badCwd).put("nativeId", "thread-1"))
            }
        }
    }
    @Test fun serverEnforcesTotalReadDeadlineAndRethrowsCancellation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = SessionManagementServer("secret-token", { request ->
            if (request.optString("id") == "cancel-me") throw CancellationException("cancelled")
            JSONObject().put("ok", true).put("id", request.getString("id"))
        }, scope, Dispatchers.Unconfined)
        try {
            val started = System.nanoTime()
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("{\"action\":\"status\"".toByteArray())
                socket.getOutputStream().flush()
                val line = socket.getInputStream().bufferedReader().readLine()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertTrue("Expected bounded timeout, elapsed=${elapsedMs}ms", elapsedMs in 2_000L..4_800L)
                if (line != null) assertFalse(JSONObject(line).getBoolean("ok"))
            }
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write((JSONObject().put("action", "status").put("id", "cancel-me").put("token", "secret-token").toString() + "\n").toByteArray())
                assertNull(socket.getInputStream().bufferedReader().readLine())
            }
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write((JSONObject().put("action", "status").put("id", "healthy").put("token", "secret-token").toString() + "\n").toByteArray())
                val reply = JSONObject(socket.getInputStream().bufferedReader().readLine())
                assertTrue(reply.getBoolean("ok"))
                assertEquals("healthy", reply.getString("id"))
            }
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
