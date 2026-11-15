package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenCodeClient(private val url: String, password: String) {
    private val authorization = Credentials.basic("opencode", password)
    private val httpClient = client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private val eventClient = httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val streamLock = Any()
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var stream: Call? = null
    @Volatile private var closed = false
    @Volatile var connected = false; private set
    private fun request(path: String) = Request.Builder().url(url + path).header("Authorization", authorization)
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = execute(path, method, body, null)
    suspend fun probe(path: String = "/api/info", timeoutMillis: Long = 3_000L): JSONObject = execute(path, "GET", null, timeoutMillis)
    private suspend fun execute(path: String, method: String, body: JSONObject?, timeoutMillis: Long?): JSONObject = withContext(Dispatchers.IO) {
        check(!closed) { tr("OpenCode 界面连接已关闭", "OpenCode UI connection closed") }
        val payload = if (method in listOf("GET", "DELETE")) null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        val runner = if (timeoutMillis == null) httpClient else httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val call = runner.newCall(request(path).method(method, payload).build())
        calls.add(call)
        if (closed) { call.cancel(); calls.remove(call); error(tr("OpenCode 界面连接已关闭", "OpenCode UI connection closed")) }
        try {
            call.execute().use { response ->
                if (response.code == 401 || response.code == 403) throw OpenCodeRejected(response.code, tr("OpenCode 服务认证失败：HTTP ${response.code}", "OpenCode authentication failed: HTTP ${response.code}"))
                val source = response.body?.source()
                val text = if (source == null) "" else {
                    require(!source.request(8L * 1024 * 1024 + 1)) { tr("OpenCode 响应超过 8 MiB，请在终端查看", "OpenCode response exceeds 8 MiB; inspect it in terminal") }
                    source.readUtf8()
                }
                if (!response.isSuccessful) throw OpenCodeRejected(response.code, runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "OpenCode HTTP ${response.code}")
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } finally { calls.remove(call) }
    }
    suspend fun subscribe(scope: CoroutineScope, onEvent: (JSONObject) -> Unit, onDisconnect: (Throwable) -> Unit) {
        val ready = CompletableDeferred<Unit>()
        val call = eventClient.newCall(request("/api/event").build())
        synchronized(streamLock) {
            check(!closed) { tr("OpenCode 界面连接已关闭", "OpenCode UI connection closed") }
            stream?.cancel(); stream = call; connected = false
        }
        scope.launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw OpenCodeRejected(response.code, tr("OpenCode 事件连接失败：HTTP ${response.code}", "OpenCode event stream failed: HTTP ${response.code}"))
                    synchronized(streamLock) {
                        if (closed || stream !== call) throw CancellationException(tr("OpenCode 事件连接已替换", "OpenCode event stream replaced"))
                        connected = true
                    }
                    ready.complete(Unit)
                    val source = response.body?.source() ?: throw IOException(tr("OpenCode 事件响应为空", "Empty OpenCode event stream"))
                    val data = StringBuilder()
                    while (isActive && !closed && stream === call) {
                        val line = source.readUtf8Line() ?: throw IOException(tr("OpenCode 事件连接已断开，请重新连接核对", "OpenCode event stream disconnected; reconnect to verify"))
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val raw = JSONTokener(data.toString()).nextValue()
                                onEvent(if (raw is JSONObject) raw else JSONObject(raw.toString()))
                                data.setLength(0)
                            }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
            } catch (e: Exception) {
                val current = synchronized(streamLock) { (stream === call).also { if (it) connected = false } }
                ready.completeExceptionally(e)
                if (current && !closed && isActive && e !is CancellationException) onDisconnect(e)
            }
        }
        try { withTimeout(20000) { ready.await() } }
        catch (e: TimeoutCancellationException) { call.cancel(); throw java.net.SocketTimeoutException(tr("OpenCode 事件连接超时", "OpenCode event stream timed out")) }
        catch (e: Exception) { call.cancel(); throw e }
    }
    fun close() {
        synchronized(streamLock) { closed = true; connected = false; stream?.cancel() }
        calls.forEach { it.cancel() }; calls.clear()
        httpClient.connectionPool.evictAll()
    }
    companion object {
        private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    }
}
class OpenCodeRejected(val status: Int, message: String) : IOException(message)
