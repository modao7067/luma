package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class NativeCliClientTest {
    private class Fixture(val handler: (String, Map<String, String>, String) -> Pair<Int, String>?) : AutoCloseable {
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val calls = AtomicInteger()
        val url = "http://127.0.0.1:${socket.localPort}"
        val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val peer = try { socket.accept() } catch (_: java.io.IOException) { break }
                peer.use {
                    val input = it.getInputStream().buffered()
                    fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == 10) return b.toString().trimEnd('\r'); b.append(c.toChar()) } }
                    val request = line(); val headers = mutableMapOf<String, String>()
                    while (true) { val row = line(); if (row.isEmpty()) break; headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var at = 0; while (at < body.size) { val n = input.read(body, at, body.size - at); if (n < 0) break; at += n }
                    calls.incrementAndGet()
                    val response = handler(request.substringAfter(' ').substringBefore(' '), headers, String(body, Charsets.UTF_8))
                    if (response != null) {
                        val bytes = response.second.toByteArray()
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 ${response.first} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes); output.flush()
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
    @Test fun privateBearerAndNativeJsonRoundtrip() = runBlocking {
        var authorization = ""; var body = ""
        val server = Fixture { _, headers, text -> authorization = headers["authorization"].orEmpty(); body = text; 200 to """{"revision":1,"receipts":{"one":"accepted"}}""" }
        val api = NativeCliClient(server.url, "fixture-private-token")
        try {
            val result = api.call("/sessions/x/send", JSONObject("""{"submissionId":"one","text":"中文"}"""))
            assertEquals("Bearer fixture-private-token", authorization)
            assertEquals("中文", JSONObject(body).getString("text"))
            assertEquals("accepted", result.getJSONObject("receipts").getString("one"))
        } finally { api.close(); server.close() }
    }
    @Test fun onlyExplicitRejectionUnlocksSubmission() = runBlocking {
        val server = Fixture { path, _, _ -> val rejected = path == "/rejected"; (if (rejected) 422 else 500) to """{"message":"fixture","rejected":$rejected}""" }
        val api = NativeCliClient(server.url, "fixture")
        try {
            val definite = runCatching { api.call("/rejected", JSONObject()) }.exceptionOrNull()
            val uncertain = runCatching { api.call("/unknown", JSONObject()) }.exceptionOrNull()
            assertTrue(definite is NativeCliRejected); assertTrue(uncertain is java.io.IOException)
            assertFalse(uncertain is NativeCliRejected)
        } finally { api.close(); server.close() }
    }
    @Test fun droppedMutationIsNotRetried() = runBlocking {
        val server = Fixture { _, _, _ -> null }; val api = NativeCliClient(server.url, "fixture")
        try {
            assertTrue(runCatching { api.call("/send", JSONObject("""{"submissionId":"unique"}""")) }.isFailure)
            assertEquals(1, server.calls.get())
        } finally { api.close(); server.close() }
    }
    @Test fun cancellingCoroutineAbortsInFlightNativeCliCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = Fixture { _, _, _ ->
            entered.countDown()
            try { release.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            200 to "{}"
        }
        val api = NativeCliClient(server.url, "fixture")
        try {
            val job = launch(Dispatchers.IO) { api.call("/slow") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000L)
        } finally {
            release.countDown()
            api.close()
            server.close()
        }
    }
}
