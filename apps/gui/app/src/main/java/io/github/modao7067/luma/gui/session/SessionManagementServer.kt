package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore

internal class SessionManagementServer(
    private val token: String,
    private val execute: suspend (JSONObject) -> JSONObject,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = Semaphore(8)
    val port: Int get() = server.localPort
    init {
        scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    if (server.isClosed || !isActive) break
                    delay(100)
                    continue
                }
                if (!clients.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                launch {
                    try { serve(socket) }
                    catch (_: java.io.IOException) {  }
                    finally { clients.release() }
                }
            }
        }
    }
    private fun readRequestLine(socket: Socket, maxBytes: Int = 131072, timeoutMs: Long = 3_000L): String {
        val input = socket.getInputStream()
        val raw = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) throw java.net.SocketTimeoutException(tr("请求读取超时", "Request read timed out"))
            val remainingMs = ((remainingNs + 999_999L) / 1_000_000L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            socket.soTimeout = remainingMs
            val read = input.read(buffer)
            if (read < 0) throw IllegalArgumentException(tr("请求未完成", "Incomplete request"))
            for (i in 0 until read) {
                val byte = buffer[i].toInt() and 0xFF
                if (byte == 10) return raw.toString("UTF-8")
                require(raw.size() < maxBytes) { tr("请求超过 128 KiB", "Request exceeds 128 KiB") }
                raw.write(byte)
            }
        }
    }
    private suspend fun serve(socket: Socket) = socket.use {
        val result = try {
            val line = readRequestLine(socket)
            val request = JSONObject(line)
            require(request.optString("token") == token) { tr("会话管理接口未授权", "Unauthorized session management request") }
            request.remove("token")
            withContext(dispatcher) { execute(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: tr("会话管理失败", "Session management failed"))
        }
        socket.getOutputStream().write((result.toString() + "\n").toByteArray(Charsets.UTF_8))
    }
    fun close() { server.close() }
}
