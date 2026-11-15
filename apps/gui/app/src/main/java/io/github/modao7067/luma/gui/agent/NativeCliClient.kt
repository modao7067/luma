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
    suspend fun call(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        check(!closed) { tr("界面连接已关闭", "UI connection closed") }
        val request = Request.Builder().url(url + path)
            .header("Authorization", "Bearer $token")
            .header("X-Luma-Lang", LumaI18n.langCode())
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build()); calls.add(call)
        if (closed) call.cancel()
        try {
            call.execute().use { response ->
                val source = response.body?.source() ?: throw IOException(tr("CLI 服务响应为空", "Empty response from CLI service"))
                check(!source.request(64L * 1024 * 1024 + 1)) { tr("会话响应超过 64 MiB，请在原生 CLI 查看", "Session response exceeds 64 MiB; inspect it in native CLI") }
                val result = JSONObject(source.readUtf8())
                if (!response.isSuccessful) {
                    val message = result.optString("message", "CLI HTTP ${response.code}")
                    if (result.optBoolean("rejected")) throw NativeCliRejected(message)
                    throw IOException(message)
                }
                result
            }
        } finally { calls.remove(call) }
    }
    fun close() { closed = true; calls.forEach { it.cancel() } }
    companion object {
        private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(310, TimeUnit.SECONDS).build()
    }
}
class NativeCliRejected(message: String) : IOException(message)
