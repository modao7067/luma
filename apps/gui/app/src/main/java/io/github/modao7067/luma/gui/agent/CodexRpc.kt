package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RpcRejected(val code: Int, message: String) : IOException(message)
class RpcDisconnected(message: String = "连接已断开；已提交操作的结果需要重新核对") : IOException(message)

class CodexRpc(
    url: String,
    token: String?,
    private val onEvent: (JSONObject) -> Unit,
    private val onDisconnect: (Throwable) -> Unit,
    transport: CodexTransport? = null,
) {
    private val transport = transport ?: CodexWebSocketTransport(url, token)
    private val sequence = AtomicLong()
    private val failed = AtomicBoolean()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val opened = CompletableDeferred<Unit>()
    @Volatile var connected = false
        private set
    @Volatile private var closed = false

    suspend fun connect() {
        transport.connect(opened = {
            if (!closed && !failed.get()) {
                connected = true
                opened.complete(Unit)
            } else transport.close()
        }, message = { text ->
            try {
                val message = JSONObject(text)
                if (message.has("id") && !message.has("method")) {
                    pending.remove(message.get("id").toString())?.complete(message)
                } else onEvent(message)
            } catch (failure: Exception) {
                fail(IOException("收到无法解析的 Codex 协议消息", failure))
            }
        }, failed = ::fail)
        try {
            withTimeout(20_000) { opened.await() }
            request("initialize", obj("clientInfo" to obj("name" to "luma_gui", "title" to "Luma", "version" to "0.6.0"), "capabilities" to obj("experimentalApi" to true)))
            notify("initialized")
        } catch (_: TimeoutCancellationException) {
            close()
            currentCoroutineContext().ensureActive()
            throw RpcDisconnected("连接 Codex 服务超时")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        if (!connected) throw RpcDisconnected()
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        if (!transport.send(obj("id" to id, "method" to method, "params" to params).toString())) {
            pending.remove(id)
            throw RpcDisconnected()
        }
        val message = try { withTimeout(45_000) { result.await() } } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); throw RpcDisconnected("Codex 响应超时；请重新连接核对操作结果") } finally { pending.remove(id) }
        val error = message.optJSONObject("error")
        if (error != null) throw RpcRejected(error.optInt("code"), CodexProjection.error(error))
        return message.optJSONObject("result") ?: JSONObject()
    }

    fun respond(id: Any, result: JSONObject) {
        if (!connected || !transport.send(obj("id" to id, "result" to result).toString())) throw RpcDisconnected()
    }

    private fun notify(method: String) {
        if (!transport.send(obj("method" to method).toString())) throw RpcDisconnected()
    }
    private fun fail(failure: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        connected = false
        transport.close()
        opened.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        if (!closed) onDisconnect(failure)
    }
    fun close() {
        closed = true
        fail(RpcDisconnected())
    }

}
fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> if (value != null) put(key, value) }
}
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
