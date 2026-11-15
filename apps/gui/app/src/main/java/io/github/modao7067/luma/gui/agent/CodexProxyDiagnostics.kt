package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import java.io.File
import java.net.ProtocolException

internal class CodexProxyDiagnostics(private val record: (String) -> Unit) {
    private var tail = ""
    private var stderrReason: String? = null
    private var started = false

    @Synchronized fun started() { started = true; record("event=proxy-started") }
    @Synchronized fun stderr(text: String) {
        tail = (tail + text.lowercase()).takeLast(2048)
        stderrReason = when {
            "socket" in tail && ("no such file" in tail || "os error 2" in tail || "enoent" in tail) -> "socket-missing"
            "socket" in tail && ("permission denied" in tail || "os error 13" in tail || "eacces" in tail) -> "socket-permission"
            "permission denied" in tail || "access denied" in tail || "not allowed" in tail || "root access denied" in tail -> "root-denied"
            "unrecognized subcommand" in tail || "unexpected argument" in tail || "command not found" in tail -> "proxy-startup"
            else -> stderrReason
        }
    }
    @Synchronized fun failure(failure: Throwable, exit: Int?): RpcDisconnected {
        val causes = generateSequence(failure) { it.cause }.take(8).toList()
        val handshake = causes.any { it is ProtocolException || it.message?.startsWith("Expected HTTP 101") == true }
        val reason = stderrReason ?: when {
            handshake -> "websocket-handshake"
            !started -> "proxy-startup"
            exit != null -> "proxy-exit"
            else -> "transport-disconnected"
        }
        val status = causes.firstNotNullOfOrNull { Regex("was\\D{0,8}([1-5][0-9]{2})").find(it.message.orEmpty())?.groupValues?.get(1) }
        val type = causes.last().javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(80)
        record("event=connection-failed reason=$reason proxyExit=${exit ?: "unknown"} cause=$type" + if (handshake && status != null) " http=$status" else "")
        val message = when (reason) {
            "root-denied" -> tr("Root 拒绝启动 Codex 连接代理，请检查 Root 授权", "Root denied starting Codex proxy; check Root grant")
            "socket-missing" -> tr("外部 Codex socket 不存在，请确认原客户端仍在运行", "External Codex socket missing; verify the original client is still running")
            "socket-permission" -> tr("无法访问外部 Codex socket，请检查服务权限", "Cannot access external Codex socket; check permissions")
            "proxy-startup" -> tr("无法启动 Codex proxy，请检查 Root 授权、Ubuntu 入口和 CLI 版本", "Failed to start Codex proxy; check Root grant, Ubuntu launcher, and CLI version")
            "proxy-exit" -> tr("Codex 连接代理已退出（${exit ?: "未知"}），请重连核对状态", "Codex proxy exited (${exit ?: "unknown"}); reconnect to verify status")
            "websocket-handshake" -> tr("外部 Codex WebSocket 握手失败", "External Codex WebSocket handshake failed") + if (status != null) tr("（HTTP $status）", " (HTTP $status)") else ""
            else -> tr("外部 Codex 连接已中断，请重连核对状态", "External Codex connection lost; reconnect to verify status")
        }
        return RpcDisconnected(message).also { it.initCause(failure) }
    }
}

class CodexProxyLog(private val file: File) {
    fun append(event: String) = synchronized(ioLock) {
        if (file.length() > 64 * 1024) file.writeText(file.readText().takeLast(32 * 1024))
        file.appendText("${System.currentTimeMillis()} ${event.take(512)}\n")
    }
    companion object { private val ioLock = Any() }
}
