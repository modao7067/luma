package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OpenCodeClientLifecycleTest {
    @Test fun eachRestRequestUsesFreshSocketAndClosingOneClientPreservesOthersActiveWork() = runBlocking {
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val generation = AtomicInteger()
        val events = AtomicInteger()
        val holdEntered = CountDownLatch(1); val releaseHold = CountDownLatch(1)
        val releaseSse = CountDownLatch(1); val sseClosed = CountDownLatch(1)
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                val identity = generation.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        try {
                            val input = it.getInputStream().bufferedReader()
                            val output = it.getOutputStream()
                            while (true) {
                                val line = input.readLine() ?: break
                                while (input.readLine()?.isNotEmpty() == true) { }
                                if (line.contains("/api/event")) {
                                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: keep-alive\r\n\r\ndata: {\"type\":\"first\"}\n\n".toByteArray()); output.flush()
                                    releaseSse.await(5, TimeUnit.SECONDS)
                                    output.write("data: {\"type\":\"second\"}\n\n".toByteArray()); output.flush()
                                    sseClosed.await(5, TimeUnit.SECONDS); break
                                }
                                if (line.contains("/held")) { holdEntered.countDown(); releaseHold.await(5, TimeUnit.SECONDS) }
                                val body = "{\"connection\":$identity}".toByteArray()
                                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray())
                                output.write(body); output.flush()
                            }
                        } catch (_: java.io.IOException) { }
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}"
        val first = OpenCodeClient(url, "isolated-fixture")
        val other = OpenCodeClient(url, "isolated-fixture")
        var reconnect: OpenCodeClient? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = first.call("/api/session/fixture").getInt("connection")
            val b = other.call("/api/session/fixture").getInt("connection")
            assertEquals(1, a)
            assertEquals(2, b)
            other.subscribe(scope, { events.incrementAndGet() }, { error -> throw AssertionError(error) })
            val held = async(Dispatchers.IO) { other.call("/held").getInt("connection") }
            assertTrue(holdEntered.await(5, TimeUnit.SECONDS))
            first.close(); releaseHold.countDown(); releaseSse.countDown()
            val activeConnection = held.await()
            val end = System.nanoTime() + 5_000_000_000L
            while (events.get() < 2 && System.nanoTime() < end) delay(10)
            assertEquals(2, events.get()); assertTrue(other.connected)
            reconnect = OpenCodeClient(url, "isolated-fixture")
            val c = reconnect.call("/api/session/fixture").getInt("connection")
            val d = other.call("/api/session/fixture").getInt("connection")
            assertEquals(4, setOf(a, b, c, d).size)
            assertTrue(c != activeConnection && d != activeConnection)
            assertTrue(other.connected)
        } finally {
            releaseHold.countDown(); releaseSse.countDown(); sseClosed.countDown()
            first.close(); other.close(); reconnect?.close(); scope.cancel()
            server.close(); sockets.forEach { it.close() }; acceptor.join(1000)
        }
    }
}
