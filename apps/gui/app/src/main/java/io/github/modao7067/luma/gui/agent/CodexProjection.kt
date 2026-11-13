package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Paths

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

object CodexProjection {
    fun error(error: JSONObject): String = CodexCapabilities.redact(listOfNotNull(error.stringOrNull("message"), error.stringOrNull("additionalDetails"))
        .distinct().joinToString("\n").ifBlank { "Codex 操作失败" })
    fun item(item: JSONObject, previous: ConversationItem? = null): ConversationItem {
        val type = item.optString("type")
        val id = item.optString("id")
        val projected = when (type) {
            "userMessage" -> {
                val inputs = item.optJSONArray("content") ?: JSONArray()
                ConversationItem(clientMessageId(item) ?: id, "user", CodexAsyncQuestions.displayReply(messageText(inputs)), images = inputs.objects().mapNotNull {
                    when (it.optString("type")) { "localImage" -> it.stringOrNull("path"); "image" -> it.stringOrNull("url"); else -> null }
                }, userContent = UserMessageContent(inputs.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") },
                    inputs.objects().mapNotNull { input -> when (input.optString("type")) {
                        "localImage" -> SessionTitlePolicy.attachment("图片", input.stringOrNull("path"))
                        "image" -> "图片"
                        "mention" -> SessionTitlePolicy.attachment("文件", input.stringOrNull("path"), input.stringOrNull("name"))
                        "skill" -> SessionTitlePolicy.attachment("Skill", input.stringOrNull("path"), input.stringOrNull("name"))
                        else -> null
                    } }))
            }
            "agentMessage" -> ConversationItem(id, "assistant", item.optString("text"), asyncQuestions = CodexAsyncQuestions.questions(item))
            "reasoning" -> {
                val detail = (item.optJSONArray("summary") ?: JSONArray()).strings()
                    .filter { it.isNotBlank() }.joinToString("\n").ifBlank {
                        previous?.takeIf { it.id == id && it.kind == "reasoning" }?.detail.orEmpty()
                    }
                ConversationItem(id, "tool", "推理摘要", detail, item.optString("status"))
            }
            "plan" -> ConversationItem(id, "assistant", item.optString("text"))
            "commandExecution" -> ConversationItem(id, "tool", item.optString("command", "命令"), item.optString("aggregatedOutput"), item.optString("status"))
            "fileChange" -> ConversationItem(id, "tool", "文件变更", (item.optJSONArray("changes") ?: JSONArray()).objects().joinToString("\n\n") {
                it.optString("path") + "\n" + it.optString("diff")
            }, item.optString("status"))
            "mcpToolCall" -> ConversationItem(id, "tool", item.optString("server") + " · " + item.optString("tool"), MediaProjection.display(item), item.optString("status"))
            "webSearch" -> ConversationItem(id, "tool", "搜索 · " + item.optString("query"), item.optJSONObject("action")?.toString(2).orEmpty(), item.optString("status"))
            "collabAgentToolCall" -> ConversationItem(id, "tool", collabLabel(item.optString("tool")),
                detail = item.optJSONObject("agentsStates")?.keys()?.asSequence()?.joinToString("\n") { target ->
                    val state = item.getJSONObject("agentsStates").optJSONObject(target)
                    listOf(target, state?.optString("status"), state?.stringOrNull("message")).filterNotNull().joinToString(" · ")
                }.orEmpty(), status = item.optString("status"))
            "subAgentActivity" -> ConversationItem(id, "tool", "子代理活动", item.optString("agentPath"), item.optString("kind"))
            "imageGeneration" -> ConversationItem(id, "tool", "生成图片", status = item.optString("status"))
            "dynamicToolCall" -> ConversationItem(id, "tool", item.optString("tool", "工具"), MediaProjection.display(item), item.optString("status"))
            "imageView" -> ConversationItem(id, "tool", "查看图片", item.optString("path"), item.optString("status"))
            else -> ConversationItem(id, "tool", type.ifEmpty { "工具事件" }, MediaProjection.display(item), item.optString("status"))
        }
        val outcome = when (item.optString("status")) {
            "inProgress", "running" -> "running"
            "failed", "declined" -> "failed"
            "interrupted", "cancelled" -> "cancelled"
            "completed" -> if (type == "commandExecution" && !item.isNull("exitCode") && item.optInt("exitCode") != 0) "failed" else "succeeded"
            else -> "unknown"
        }
        val input = when (type) {
            "commandExecution" -> item.optString("command")
            "mcpToolCall" -> item.opt("arguments")?.toString().orEmpty()
            "webSearch" -> item.optString("query")
            "fileChange" -> item.optJSONArray("changes")?.objects()?.joinToString("\n") { it.optString("path") }.orEmpty()
            "collabAgentToolCall" -> item.stringOrNull("prompt").orEmpty()
            else -> ""
        }
        val toolName = if (type in setOf("collabAgentToolCall", "mcpToolCall", "dynamicToolCall")) item.stringOrNull("tool")
            ?: previous?.takeIf { it.id == id && it.kind == type }?.toolName.orEmpty() else ""
        return projected.copy(kind = type, input = input, outcome = outcome, toolName = toolName, media = MediaProjection.codex(item))
    }

    private fun collabLabel(tool: String) = when (tool) {
        "spawnAgent" -> "分派子代理"; "wait" -> "等待子代理"; "sendInput", "sendMessage" -> "联系子代理"
        "followupTask", "resumeAgent" -> "继续子代理"; "closeAgent" -> "关闭子代理"; "interruptAgent" -> "中断子代理"
        "listAgents" -> "查看子代理"; else -> "子代理操作"
    }

    fun clientMessageId(item: JSONObject): String? = item.stringOrNull("clientId") ?: item.stringOrNull("clientUserMessageId")

    private fun messageText(input: JSONArray) = inputText(JSONArray(input.objects().filterNot {
        it.optString("type") in setOf("localImage", "image") && MediaProjection.blocks(it).isNotEmpty()
    }))

    fun inputText(input: JSONArray) = input.objects().mapNotNull {
        when (it.optString("type")) {
            "text" -> it.optString("text")
            "mention" -> "@" + it.optString("path")
            "localImage", "image" -> "[图片]"
            else -> null
        }
    }.joinToString("\n")
    fun models(values: JSONArray) = values.objects().filterNot { it.optBoolean("hidden") }.map {
        ModelOption(it.optString("model", it.optString("id")), it.optString("displayName"),
            it.optJSONArray("supportedReasoningEfforts")?.objects()?.map { effort -> effort.optString("reasoningEffort") }.orEmpty(),
            it.optJSONArray("inputModalities")?.strings()?.contains("image") ?: false,
            it.optJSONArray("serviceTiers")?.objects()?.map { tier -> RequestChoice(tier.getString("id"), tier.getString("name")) }.orEmpty().let { tiers ->
                if (tiers.isEmpty()) tiers else listOf(RequestChoice("default", "默认速度")) + tiers
            }, it.stringOrNull("defaultReasoningEffort").orEmpty())
    }
    private val mentionRegex = Regex("@(?:\"([^\"]+)\"|(/[^\\s@\"]+))")
    fun mentionedPaths(text: String, cwd: String): List<String> {
        if ('\u0000' in cwd) return emptyList()
        val base = cwd.ifBlank { "/" }
        val seen = LinkedHashSet<String>()
        mentionRegex.findAll(text).forEach { match ->
            val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (raw.isBlank() || '\u0000' in raw) return@forEach
            val candidate = if (raw.startsWith('/')) raw else "$base/$raw"
            val normalized = runCatching { Paths.get(candidate).normalize().toString() }.getOrNull()
            if (!normalized.isNullOrBlank()) seen.add(normalized)
        }
        return seen.toList()
    }
    fun inputs(text: String, images: List<String>, cwd: String): JSONArray = JSONArray().apply {
        if (text.isNotBlank()) put(obj("type" to "text", "text" to text))
        mentionedPaths(text, cwd).forEach { path ->
            put(obj("type" to "mention", "name" to path.substringAfterLast('/'), "path" to path))
        }
        images.forEach { put(obj("type" to "localImage", "path" to it)) }
    }
}

class PendingApproval(val rawId: Any, val method: String, val params: JSONObject) {
    val id = requestKey(rawId)
    val threadId = params.stringOrNull("threadId").orEmpty()
    val turnId = params.optString("turnId")
    var answering = false
    private val decisions: List<Any> = params.optJSONArray("availableDecisions")?.let { a -> (0 until a.length()).map { a.get(it) } }
        ?: listOf("accept", "acceptForSession", "decline", "cancel")

    fun view(): UserRequest {
        val body = listOf(params.stringOrNull("reason"), params.stringOrNull("command"), params.stringOrNull("cwd"),
            params.optJSONObject("permissions")?.toString(2), params.optJSONObject("networkApprovalContext")?.toString(2)).filterNotNull().joinToString("\n\n")
        return when (method) {
            "item/tool/requestUserInput", "tool/requestUserInput" -> UserRequest(id, "Answer", body,
                questions = params.optJSONArray("questions")?.objects()?.map { q -> RequestQuestion(q.optString("id"), q.optString("header"), q.optString("question"), q.optJSONArray("options")?.objects()?.map { it.optString("label") }.orEmpty(), secret = q.optBoolean("isSecret"), allowOther = q.optBoolean("isOther")) }.orEmpty(), answering = answering, skipChoice = "skip")
            "item/permissions/requestApproval" -> UserRequest(id, "请求额外权限", body,
                listOf(RequestChoice("allow", "仅本轮允许"), RequestChoice("decline", "不授予")), answering = answering)
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> UserRequest(id,
                if (method.contains("fileChange")) "文件修改审批" else "命令执行审批", body,
                decisions.mapIndexed { i, d -> RequestChoice(i.toString(), decisionLabel(d)) }, answering = answering)
            "mcpServer/elicitation/request" -> UserRequest(id, "外部工具需要确认", params.toString(2), listOf(RequestChoice("decline", "拒绝"), RequestChoice("cancel", "取消")), answering = answering)
            else -> UserRequest(id, "请在官方 TUI 处理此请求", params.toString(2), answering = answering)
        }
    }
    private fun decisionLabel(value: Any) = when (value) {
        "accept" -> "允许这次"
        "acceptForSession" -> if (method.contains("fileChange")) "允许这些文件的后续修改" else "允许本会话的同类命令"
        "decline" -> "拒绝，继续任务"
        "cancel" -> "拒绝并中断"
        else -> when {
            value is JSONObject && value.has("acceptWithExecpolicyAmendment") -> "允许并保存命令规则：\n" + value.toString(2)
            value is JSONObject && value.has("applyNetworkPolicyAmendment") -> "保存网络规则：\n" + value.toString(2)
            else -> value.toString()
        }
    }
    fun response(choice: String, answers: Map<String, List<String>>): JSONObject = when (method) {
        "item/tool/requestUserInput", "tool/requestUserInput" -> obj("answers" to JSONObject().apply { (if (choice == "skip") emptyMap() else answers).forEach { (key, answer) -> put(key, obj("answers" to JSONArray(answer))) } })
        "item/permissions/requestApproval" -> obj("permissions" to (if (choice == "allow") params.getJSONObject("permissions") else JSONObject()), "scope" to "turn")
        "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> obj("decision" to decisions[choice.toInt()])
        "mcpServer/elicitation/request" -> obj("action" to choice, "content" to JSONObject.NULL)
        else -> throw IllegalArgumentException("请在官方 TUI 中处理此类请求")
    }
}

fun requestKey(id: Any): String = (if (id is String) "string:" else "number:") + id.toString()
