package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.ModelOption
import io.github.modao7067.luma.gui.session.SessionRecord
import org.json.JSONObject

object NativeCliStatePolicy {
    fun ready(s: JSONObject): Boolean = s.optBoolean("connected") &&
        (s.optJSONObject("lifecycle")?.optString("phase")?.let { it == "ready" && !s.optBoolean("connecting") } ?: true)

    fun loading(s: JSONObject): Boolean = s.optJSONObject("lifecycle")?.optString("phase")?.let { it == "connecting" }
        ?: s.optBoolean("connecting")

    fun retryable(s: JSONObject): Boolean = s.optJSONObject("lifecycle")?.let {
        !it.has("retryable") || it.optBoolean("retryable")
    } ?: true

    fun catalogReady(s: JSONObject): Boolean = if (s.has("catalogState")) s.optString("catalogState") == "ready" else ready(s) && !s.optBoolean("connecting")

    fun models(s: JSONObject, previous: List<ModelOption>, scopeChanged: Boolean = false): List<ModelOption> =
        if (catalogReady(s)) NativeCliProjection.models(s) else if (scopeChanged) emptyList() else previous

    fun recoverableAbsence(record: SessionRecord, submissionId: String): Boolean = submissionId !in record.receiptRecoveryRequired &&
        record.submissions.find { it.id == submissionId }?.let { it.takebackId == null && !it.dismissed } != false

    fun serviceUpdateImpact(affectedClis: List<String>, runningClis: List<String>): String =
        "共享 CLI 桥范围：${affectedClis.joinToString("、")}。\n本 App 当前已连接且执行中：${runningClis.size} 个会话" +
            (if (runningClis.isEmpty()) "。" else "（${runningClis.distinct().joinToString("、")}）。")

    fun settings(s: JSONObject, model: String, effort: String): Pair<String, String> {
        if (!catalogReady(s)) return model to effort
        return (s.stringOrNull("model")?.takeIf { it.isNotBlank() } ?: model) to
            (if (s.has("effort") && !s.isNull("effort")) s.getString("effort") else effort)
    }

    fun error(s: JSONObject): String? {
        val lifecycle = s.optJSONObject("lifecycle")
        val message = s.stringOrNull("error") ?: s.optJSONObject("errorInfo")?.stringOrNull("message")
            ?: lifecycle?.stringOrNull("message") ?: return null
        if (lifecycle?.optString("phase") != "blocked") return message
        val recovery = when (lifecycle.optString("reason")) {
            "authentication" -> "请在原生 CLI 完成登录后重新连接；未发送的输入仍保留。"
            "model" -> "请在原生 CLI 核对账户与可用模型后重新连接；不会自动重发。"
            else -> "请修正原生 CLI 配置后重新连接；未发送的输入仍保留。"
        }
        return "$message\n$recovery"
    }

    fun connection(s: JSONObject): String = when (s.optJSONObject("lifecycle")?.optString("phase")) {
        "blocked" -> when (s.optJSONObject("lifecycle")?.optString("reason")) {
            "authentication" -> "需要原生 CLI 登录 · 请处理后重连"
            "model" -> "模型暂不可用 · 请核对后重连"
            else -> "CLI 配置未就绪 · 请修正后重连"
        }
        "connecting" -> "连接 CLI…"
        else -> if (s.optBoolean("connecting")) "连接 CLI…" else if (ready(s)) "已连接" else "连接中断 · 请核对后重连"
    }
}

internal data class NativeCliCatalogIdentity(val environment: String, val cli: String, val cwd: String, val protocol: String?, val socket: String?) {
    companion object {
        fun of(record: SessionRecord) = NativeCliCatalogIdentity(record.environment, record.cli, record.cwd, record.nativeProtocol, record.externalSocket)
    }
}
