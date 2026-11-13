package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OpenCodeClientRecoveryTest {
    @Test fun truncatedSnapshotRetriesWithoutReplayingAnyMutation() = runBlocking {
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        Fixture { exchange ->
            if (exchange.path == "/api/event") holdEvents(exchange, release)
            else if (reads.incrementAndGet() == 1) truncate(exchange)
            else json(exchange)
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val api = OpenCodeClient(fixture.url, "fixture")
            val healthy = CompletableDeferred<Unit>()
            val states = CopyOnWriteArrayList<OpenCodeSync.State>()
            val sync = OpenCodeSync(scope, { api.connected }, { disconnect -> api.subscribe(scope, {}, disconnect) },
                { _, _, _ -> api.call("/api/session/fixture") }, { states += it; if (it.connected) healthy.complete(Unit) })
            try {
                sync.start(); withTimeout(6_000) { healthy.await() }
                assertTrue(states.any { !it.connected && it.recovering && it.notice == null })
                assertTrue(states.last().connected)
                assertEquals(2, reads.get())
                assertEquals(listOf("GET /api/event", "GET /api/session/fixture", "GET /api/session/fixture"), fixture.requests.toList())
            } finally { sync.close(); api.close(); scope.cancel(); release.countDown() }
        }
    }

    @Test fun eventEofReattachesAndRequiresANewFullSnapshot() = runBlocking {
        val eventStreams = AtomicInteger()
        val firstRelease = CountDownLatch(1)
        val finalRelease = CountDownLatch(1)
        Fixture { exchange ->
            if (exchange.path == "/api/event") holdEvents(exchange, if (eventStreams.incrementAndGet() == 1) firstRelease else finalRelease)
            else json(exchange)
        }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val api = OpenCodeClient(fixture.url, "fixture")
            val healthy = kotlinx.coroutines.channels.Channel<Unit>(2)
            val states = CopyOnWriteArrayList<OpenCodeSync.State>()
            val snapshots = CopyOnWriteArrayList<Boolean>()
            val sync = OpenCodeSync(scope, { api.connected }, { disconnect -> api.subscribe(scope, {}, disconnect) },
                { full, _, _ -> snapshots += full; api.call("/api/session/fixture") }, { states += it; if (it.connected) healthy.trySend(Unit) })
            try {
                sync.start(); withTimeout(5_000) { healthy.receive() }
                firstRelease.countDown()
                withTimeout(5_000) { healthy.receive() }
                assertEquals(2, eventStreams.get())
                assertEquals(listOf(true, true), snapshots.toList())
                assertTrue(states.any { !it.connected && it.recovering && it.notice == null })
                assertTrue(states.last().connected)
                assertTrue(fixture.requests.all { it.startsWith("GET ") })
            } finally { sync.close(); api.close(); scope.cancel(); firstRelease.countDown(); finalRelease.countDown() }
        }
    }

    @Test fun restAndEventAuthRejectionsKeepTheirHttpStatus() = runBlocking {
        Fixture { exchange -> exchange.respond(401) }.use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val api = OpenCodeClient(fixture.url, "fixture")
            try {
                try { api.call("/api/session/fixture"); fail("must reject REST auth") }
                catch (failure: OpenCodeRejected) { assertEquals(401, failure.status) }
                try { api.subscribe(scope, {}, {}); fail("must reject SSE auth") }
                catch (failure: OpenCodeRejected) { assertEquals(401, failure.status) }
                assertFalse(api.connected)
                assertEquals(2, fixture.requests.size)
            } finally { api.close(); scope.cancel() }
        }
    }

    @Test fun lostPostResponseIsNotRetried() = runBlocking {
        Fixture { truncate(it) }.use { fixture ->
            val api = OpenCodeClient(fixture.url, "fixture")
            try {
                try { api.call("/api/session/fixture/prompt", "POST"); fail("must keep the result uncertain") }
                catch (_: IOException) { }
                assertEquals(listOf("POST /api/session/fixture/prompt"), fixture.requests.toList())
            } finally { api.close() }
        }
    }

    @Test fun probeFailsWithinBoundedTimeoutWhenAcceptQueueNeverDrains() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { wedged ->
            val api = OpenCodeClient("http://127.0.0.1:${wedged.localPort}", "fixture")
            val started = System.nanoTime()
            try {
                try { api.probe("/api/info", 250L); fail("wedged listener must time out") }
                catch (_: IOException) { }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertTrue("probe took ${elapsedMs}ms", elapsedMs < 2_000L)
            } finally { api.close() }
        }
    }

    private class Fixture(handler: (Exchange) -> Unit) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val executor = Executors.newCachedThreadPool { Thread(it, "opencode-recovery-fixture").apply { isDaemon = true } }
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val acceptor = thread(isDaemon = true, name = "opencode-recovery-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets += socket
                executor.execute {
                    socket.use {
                        try {
                            val input = socket.getInputStream().bufferedReader()
                            val request = input.readLine()?.split(' ') ?: return@use
                            var contentLength = 0
                            while (true) {
                                val header = input.readLine() ?: return@use
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(':').trim().toInt()
                            }
                            repeat(contentLength) { input.read() }
                            requests += "${request[0]} ${request[1]}"
                            handler(Exchange(socket, request[1]))
                        } catch (_: IOException) { }
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}"
        override fun close() { server.close(); sockets.forEach { it.close() }; executor.shutdownNow(); acceptor.join(1_000) }
    }

    private class Exchange(val socket: Socket, val path: String) {
        fun respond(status: Int, text: String = "", length: Int = text.toByteArray().size) {
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: $length\r\nConnection: close\r\n\r\n" + text).toByteArray())
            output.flush()
        }
    }

    companion object {
        private fun json(exchange: Exchange) = exchange.respond(200, "{\"data\":{\"id\":\"fixture\"}}")
        private fun truncate(exchange: Exchange) = exchange.respond(200, "{\"data\":", length = 100)
        private fun holdEvents(exchange: Exchange, release: CountDownLatch) {
            val output = exchange.socket.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
                "data: {\"type\":\"fixture.connected\"}\n\n").toByteArray())
            output.flush()
            try { release.await() } catch (_: InterruptedException) { }
        }
    }
}
