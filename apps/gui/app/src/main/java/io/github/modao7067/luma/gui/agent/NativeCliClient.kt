package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NativeCliClient(private val url: String, private val token: String) {
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    suspend fun call(path: String, body: JSONObject? = null): JSONObject = coroutineScope {
        check(!closed) { tr("界面连接已关闭", "UI connection closed") }
        val request = Request.Builder().url(url + path)
            .header("Authorization", "Bearer $token")
            .header("X-Luma-Lang", LumaI18n.langCode())
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build()); calls.add(call)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                if (closed) error(tr("界面连接已关闭", "UI connection closed"))
                call.execute().use { response ->
                    val source = response.body.source()
                    check(!source.request(64L * 1024 * 1024 + 1)) { tr("会话响应超过 64 MiB，请在原生 CLI 查看", "Session response exceeds 64 MiB; inspect it in native CLI") }
                    val result = JSONObject(source.readUtf8())
                    if (!response.isSuccessful) {
                        val message = result.optString("message", "CLI HTTP ${response.code}")
                        if (result.optBoolean("rejected")) throw NativeCliRejected(message)
                        throw IOException(message)
                    }
                    result
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            finished.set(true)
            watcher.cancel()
            if (closed || !isActive) call.cancel()
            calls.remove(call)
        }
    }
    suspend fun watchEvents(onConnected: () -> Unit, onEvent: () -> Unit): Boolean = coroutineScope {
        check(!closed) { tr("界面连接已关闭", "UI connection closed") }
        val request = Request.Builder().url(url + "/watch/events")
            .header("Authorization", "Bearer $token")
            .header("X-Luma-Watch", "1")
            .header("X-Luma-Lang", LumaI18n.langCode())
            .build()
        val call = streamHttp.newCall(request); calls.add(call)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                if (closed) error(tr("界面连接已关闭", "UI connection closed"))
                call.execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (!response.isSuccessful || !contentType.contains("text/event-stream")) {
                        return@withContext false
                    }
                    onConnected()
                    val source = response.body.source()
                    while (!closed && coroutineContext.isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            onEvent()
                        }
                    }
                    true
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            finished.set(true)
            watcher.cancel()
            if (closed || !isActive) call.cancel()
            calls.remove(call)
        }
    }
    fun close() { closed = true; calls.forEach { it.cancel() } }
    companion object {
        private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(310, TimeUnit.SECONDS).build()
        private val streamHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    }
}
class NativeCliRejected(message: String) : IOException(message)
