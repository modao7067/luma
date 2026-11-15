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
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                if (!clients.tryAcquire()) { socket.close(); continue }
                launch {
                    try { serve(socket) }
                    catch (_: java.io.IOException) {  }
                    finally { clients.release() }
                }
            }
        }
    }
    private suspend fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 5000
        val result = try {
            val input = socket.getInputStream()
            val raw = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                require(byte >= 0) { tr("请求未完成", "Incomplete request") }
                if (byte == 10) break
                require(raw.size() < 131072) { tr("请求超过 128 KiB", "Request exceeds 128 KiB") }
                raw.write(byte)
            }
            val request = JSONObject(raw.toString("UTF-8"))
            require(request.optString("token") == token) { tr("会话管理接口未授权", "Unauthorized session management request") }
            request.remove("token")
            withContext(dispatcher) { execute(request) }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: tr("会话管理失败", "Session management failed"))
        }
        socket.getOutputStream().write((result.toString() + "\n").toByteArray(Charsets.UTF_8))
    }
    fun close() { server.close() }
}
