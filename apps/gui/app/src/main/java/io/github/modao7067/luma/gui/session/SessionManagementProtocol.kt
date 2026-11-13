package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.json.JSONArray

internal object SessionManagementProtocol {
    private val keys = mapOf(
        "list" to setOf("action"),
        "create" to setOf("action", "cli", "cwd", "requestId", "model", "title", "nativeProtocol"),
        "attach" to setOf("action", "cli", "cwd", "nativeId", "title", "nativeProtocol", "externalSocket", "id"),
        "send" to setOf("action", "id", "text", "requestId"),
        "status" to setOf("action", "id", "requestId"),
        "read" to setOf("action", "id", "limit", "before"),
        "rename" to setOf("action", "id", "title"),
    )
    fun validate(request: JSONObject) {
        val action = text(request, "action")
        val allowed = keys[action] ?: error("未知 action；使用 list/create/attach/send/status/read/rename")
        require(request.keys().asSequence().all { it in allowed }) { "请求含不支持的字段" }
        if (action in setOf("create", "attach")) {
            require(text(request, "cli") in CliCatalog.ids) { "未知 CLI；先用 list 获取名称" }
            val cwd = text(request, "cwd")
            require(cwd.startsWith('/') && '\u0000' !in cwd) { "cwd 必须是当前环境的绝对目录" }
            if (request.has("title")) text(request, "title", blank = true)
            val protocols = CliCatalog.nativeProtocols[request.getString("cli")]
            if (request.has("nativeProtocol")) require(text(request, "nativeProtocol") in protocols.orEmpty()) { "此 CLI 未提供该 nativeProtocol；先用 list 获取可用协议" }
            if (action == "attach" && request.getString("cli") == "Antigravity") require(request.has("nativeProtocol")) { "此 CLI 有多种原生协议；attach 必须显式指定 nativeProtocol" }
        }
        if (action == "attach" && request.has("id")) {
            text(request, "id")
            require(request.has("externalSocket")) { "attach.id 仅用于显式 externalSocket 引用修复" }
        }
        if (request.has("externalSocket")) {
            require(action == "attach" && request.getString("cli") == "Codex") { "externalSocket 仅用于 attach Codex" }
            io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy.validateSocket(text(request, "externalSocket"))
        }
        if (action == "create" && request.has("model")) text(request, "model", blank = true)
        if (action == "attach") require(text(request, "nativeId").let { id -> id.length <= 4096 && id.none { it.isISOControl() } }) { "原生 ID 过长或包含控制字符" }
        if (action in setOf("create", "send") || (action == "status" && request.has("requestId"))) {
            require(text(request, "requestId").matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "requestId 必须是 1–128 位字母、数字、_ 或 -" }
        }
        if (action in setOf("send", "read", "rename") || (action == "status" && request.has("id"))) text(request, "id")
        if (action == "status") require(request.has("id") != request.has("requestId")) { "status 必须且只能传 id 或 requestId" }
        if (action == "send") require(text(request, "text").length <= 65536) { "消息超过 65536 字符" }
        if (action == "rename") text(request, "title")
        if (action == "read") {
            if (request.has("limit")) require(request.get("limit") is Int && request.getInt("limit") in 1..100) { "limit 必须是 1–100 的整数" }
            if (request.has("before")) text(request, "before")
        }
    }
    private fun text(request: JSONObject, key: String, blank: Boolean = false): String {
        val value = request.get(key)
        require(value is String && (blank || value.isNotBlank()) && '\u0000' !in value) { "$key 必须是${if (blank) "" else "非空"}字符串" }
        return value
    }
    fun canonical(request: JSONObject): String = request.keys().asSequence().toList().sorted()
        .joinToString(prefix = "{", postfix = "}") { key ->
            val encoded = JSONArray().put(request.get(key)).toString()
            JSONObject.quote(key) + ":" + encoded.substring(1, encoded.length - 1)
        }
}

internal data class ManagedSubmission(val id: String, val text: String, val images: List<StoredImage> = emptyList(), val skills: List<DraftSkill> = emptyList(), val prepared: Boolean = false)
