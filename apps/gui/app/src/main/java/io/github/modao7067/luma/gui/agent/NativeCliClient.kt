package io.github.modao7067.luma.gui.agent

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
        check(!closed) { "界面连接已关闭" }
        val request = Request.Builder().url(url + path).header("Authorization", "Bearer $token")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build()); calls.add(call)
        if (closed) call.cancel()
        try {
            call.execute().use { response ->
                val source = response.body?.source() ?: throw IOException("CLI 服务响应为空")
                check(!source.request(64L * 1024 * 1024 + 1)) { "会话响应超过 64 MiB，请在原生 CLI 查看" }
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
