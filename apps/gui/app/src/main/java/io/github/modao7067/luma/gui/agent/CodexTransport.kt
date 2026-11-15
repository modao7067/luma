package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

interface CodexTransport {
    fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit)
    fun send(message: String): Boolean
    fun close()
}

internal class CodexWebSocketTransport(
    private val url: String,
    private val token: String?,
    private val client: OkHttpClient = defaultClient,
) : CodexTransport {
    private var socket: WebSocket? = null
    override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) {
        val request = Request.Builder().url(url).apply {
            if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened()
            override fun onMessage(webSocket: WebSocket, text: String) = message(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(RpcDisconnected())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = if (response?.code == 401 || response?.code == 403) tr("Codex 服务认证失败，请重新连接运行环境", "Codex authentication failed; reconnect the runtime") else tr("无法连接 Codex 服务，请检查终端中的服务与登录状态", "Unable to connect to Codex service; check service and sign-in status in terminal")
                failed(RpcDisconnected(reason).also { it.initCause(t) })
            }
        })
    }
    override fun send(message: String) = socket?.send(message) == true
    override fun close() { socket?.close(1000, "Client detached") }
    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    }
}

class CodexProcessTransport(diagnostic: (String) -> Unit = {}, start: () -> Process) : CodexTransport {
    private val diagnostics = CodexProxyDiagnostics(diagnostic)
    private val sockets = ProcessSocketFactory(start, diagnostics)
    private val delegate = CodexWebSocketTransport("ws://127.0.0.1/", null,
        CodexWebSocketTransport.defaultClient.newBuilder().socketFactory(sockets).proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false).build())
    override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) =
        delegate.connect(opened, message) { cause -> failed(diagnostics.failure(cause, sockets.exitCode())) }
    override fun send(message: String) = delegate.send(message)
    override fun close() {
        delegate.close()
        sockets.close()
    }
}

private class ProcessSocketFactory(private val start: () -> Process, private val diagnostics: CodexProxyDiagnostics) : SocketFactory() {
    private val lock = Any()
    private var closed = false
    private val sockets = mutableListOf<ProcessSocket>()
    override fun createSocket(): Socket = synchronized(lock) {
        if (closed) throw IOException("Proxy detached")
        ProcessSocket(start, diagnostics).also { sockets += it }
    }
    fun exitCode(): Int? = synchronized(lock) { sockets.lastOrNull() }?.exitCode()
    fun close() = synchronized(lock) {
        closed = true
        sockets.forEach { it.close() }
        sockets.clear()
    }
    override fun createSocket(host: String, port: Int): Socket = throw IOException("Only private proxy pipes are supported")
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("Only private proxy pipes are supported")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("Only private proxy pipes are supported")
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("Only private proxy pipes are supported")
}

private class ProcessSocket(private val start: () -> Process, private val diagnostics: CodexProxyDiagnostics) : Socket() {
    private val lock = Any()
    private var child: Process? = null
    private var output: OutputStream? = null
    private var stopped = false
    private var timeout = 0
    private val stderrDone = CountDownLatch(1)
    override fun connect(endpoint: SocketAddress, timeout: Int) = synchronized(lock) {
        if (stopped) throw IOException("Proxy detached")
        check(child == null)
        val process = start()
        child = process
        diagnostics.started()
        output = object : FilterOutputStream(process.outputStream) {
            override fun write(value: Int) { out.write(value); out.flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); out.flush() }
        }
        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { input ->
                    val block = CharArray(1024)
                    while (true) { val size = input.read(block); if (size < 0) break; diagnostics.stderr(String(block, 0, size)) }
                }
            } catch (_: IOException) {  }
            finally { stderrDone.countDown() }
        }, "LumaCodexProxyErrors").apply { isDaemon = true; start() }
        Unit
    }
    fun exitCode(): Int? {
        val process = synchronized(lock) { child } ?: return null
        if (process.isAlive) return null
        stderrDone.await(100, TimeUnit.MILLISECONDS)
        return process.exitValue()
    }
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun getInputStream(): InputStream = synchronized(lock) { child?.inputStream ?: throw IOException("Proxy not connected") }
    override fun getOutputStream(): OutputStream = synchronized(lock) { output ?: throw IOException("Proxy not connected") }
    override fun setSoTimeout(value: Int) { timeout = value }
    override fun getSoTimeout() = timeout
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(on: Boolean) {}
    override fun getKeepAlive() = false
    override fun isConnected() = synchronized(lock) { child != null && !stopped }
    override fun isClosed() = synchronized(lock) { stopped }
    override fun close() = synchronized(lock) {
        stopped = true
        val process = child
        child = null; output = null
        if (process != null) Thread({
            process.destroy()
            try { process.outputStream.close() } catch (_: IOException) {}
            try { process.inputStream.close() } catch (_: IOException) {}
            try { process.errorStream.close() } catch (_: IOException) {}
        }, "LumaCodexProxyClose").apply { isDaemon = true; start() }
        super.close()
    }
}
