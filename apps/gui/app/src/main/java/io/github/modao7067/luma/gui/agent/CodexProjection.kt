package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Paths

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

object CodexProjection {
    fun error(error: JSONObject): String = CodexCapabilities.redact(listOfNotNull(error.stringOrNull("message"), error.stringOrNull("additionalDetails"))
        .distinct().joinToString("\n").ifBlank { tr("Codex 操作失败", "Codex operation failed") })
    fun item(item: JSONObject, previous: ConversationItem? = null): ConversationItem {
        val type = item.optString("type")
        val id = item.optString("id")
        val previousCommand = previous?.takeIf { it.id == id && it.kind == "commandExecution" && type == "commandExecution" }
        val command = if (type == "commandExecution") item.stringOrNull("command") ?: previousCommand?.input.orEmpty() else ""
        val projected = when (type) {
            "userMessage" -> {
                val inputs = item.optJSONArray("content") ?: JSONArray()
                ConversationItem(clientMessageId(item) ?: id, "user", CodexAsyncQuestions.displayReply(messageText(inputs)), images = inputs.objects().mapNotNull {
                    when (it.optString("type")) { "localImage" -> it.stringOrNull("path"); "image" -> it.stringOrNull("url"); else -> null }
                }, userContent = UserMessageContent(inputs.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") },
                    inputs.objects().mapNotNull { input -> when (input.optString("type")) {
                        "localImage" -> SessionTitlePolicy.attachment(tr("图片", "Image"), input.stringOrNull("path"))
                        "image" -> tr("图片", "Image")
                        "mention" -> SessionTitlePolicy.attachment(tr("文件", "File"), input.stringOrNull("path"), input.stringOrNull("name"))
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
                ConversationItem(id, "tool", tr("推理摘要", "Reasoning summary"), detail, item.optString("status"))
            }
            "plan" -> ConversationItem(id, "assistant", item.optString("text"))
            "commandExecution" -> ConversationItem(id, "tool", command.ifBlank { tr("命令", "Command") },
                item.stringOrNull("aggregatedOutput") ?: previousCommand?.detail.orEmpty(), item.optString("status"))
            "fileChange" -> ConversationItem(id, "tool", tr("文件变更", "File changes"), (item.optJSONArray("changes") ?: JSONArray()).objects().joinToString("\n\n") {
                it.optString("path") + "\n" + it.optString("diff")
            }, item.optString("status"))
            "mcpToolCall" -> ConversationItem(id, "tool", item.optString("server") + " · " + item.optString("tool"), mcpToolDetail(item), item.optString("status"))
            "webSearch" -> ConversationItem(id, "tool", tr("搜索 · ", "Search · ") + item.optString("query"), item.optJSONObject("action")?.toString(2).orEmpty(), item.optString("status"))
            "collabAgentToolCall" -> ConversationItem(id, "tool", collabLabel(item.optString("tool")),
                detail = item.optJSONObject("agentsStates")?.keys()?.asSequence()?.joinToString("\n") { target ->
                    val state = item.getJSONObject("agentsStates").optJSONObject(target)
                    listOf(target, state?.optString("status"), state?.stringOrNull("message")).filterNotNull().joinToString(" · ")
                }.orEmpty(), status = item.optString("status"))
            "subAgentActivity" -> ConversationItem(id, "tool", tr("子代理活动", "Sub-agent activity"), item.optString("agentPath"), item.optString("kind"))
            "imageGeneration" -> ConversationItem(id, "tool", tr("生成图片", "Generate image"), status = item.optString("status"))
            "dynamicToolCall" -> ConversationItem(id, "tool", item.optString("tool", tr("工具", "Tool")), dynamicToolDetail(item), item.optString("status"))
            "imageView" -> ConversationItem(id, "tool", tr("查看图片", "View image"), item.optString("path"), item.optString("status"))
            else -> ConversationItem(id, "tool", type.ifEmpty { tr("工具事件", "Tool event") }, MediaProjection.display(item), item.optString("status"))
        }
        val outcome = when (item.optString("status")) {
            "inProgress", "running" -> "running"
            "failed", "declined" -> "failed"
            "interrupted", "cancelled" -> "cancelled"
            "completed" -> if (type == "commandExecution" && !item.isNull("exitCode") && item.optInt("exitCode") != 0) "failed" else "succeeded"
            else -> "unknown"
        }
        val input = when (type) {
            "commandExecution" -> command
            "mcpToolCall", "dynamicToolCall" -> item.opt("arguments")?.toString().orEmpty()
            "webSearch" -> item.optString("query")
            "fileChange" -> item.optJSONArray("changes")?.objects()?.joinToString("\n") { it.optString("path") }.orEmpty()
            "collabAgentToolCall" -> item.stringOrNull("prompt").orEmpty()
            else -> ""
        }
        val toolName = if (type in setOf("collabAgentToolCall", "mcpToolCall", "dynamicToolCall")) item.stringOrNull("tool")
            ?: previous?.takeIf { it.id == id && it.kind == type }?.toolName.orEmpty() else ""
        val commandActions = if (type == "commandExecution") {
            item.optJSONArray("commandActions")?.objects()?.map {
                CommandAction(it.optString("type"), it.optString("command"), it.stringOrNull("path").orEmpty(), it.stringOrNull("name").orEmpty())
            } ?: previousCommand?.commandActions.orEmpty()
        } else emptyList()
        return projected.copy(kind = type, input = input, outcome = outcome, toolName = toolName, media = MediaProjection.codex(item),
            commandActions = commandActions,
            commandCwd = if (type == "commandExecution") item.stringOrNull("cwd") ?: previousCommand?.commandCwd.orEmpty() else "")
    }

    private fun mcpToolDetail(item: JSONObject): String {
        val textParts = item.optJSONObject("result")?.optJSONArray("content")?.objects().orEmpty()
            .filter { it.optString("type") == "text" || (it.optString("type").isEmpty() && it.has("text")) }
            .mapNotNull { it.stringOrNull("text")?.takeIf { value -> value.isNotEmpty() } }
        val errorText = item.optJSONObject("error")?.stringOrNull("message")?.takeIf { it.isNotBlank() }
        val joined = (textParts + listOfNotNull(errorText)).joinToString("\n")
        return joined.ifBlank { MediaProjection.display(item) }
    }

    private fun dynamicToolDetail(item: JSONObject): String {
        val contentItemTexts = item.optJSONArray("contentItems")?.objects().orEmpty()
            .mapNotNull { block -> (block.stringOrNull("inputText") ?: block.stringOrNull("text"))?.takeIf { it.isNotEmpty() } }
        if (contentItemTexts.isNotEmpty()) return contentItemTexts.joinToString("\n")
        return mcpToolDetail(item)
    }

    private fun collabLabel(tool: String) = when (tool) {
        "spawnAgent" -> tr("分派子代理", "Spawn subagent"); "wait" -> tr("等待子代理", "Wait for subagent"); "sendInput", "sendMessage" -> tr("联系子代理", "Contact subagent")
        "followupTask", "resumeAgent" -> tr("继续子代理", "Resume subagent"); "closeAgent" -> tr("关闭子代理", "Close subagent"); "interruptAgent" -> tr("中断子代理", "Interrupt subagent")
        "listAgents" -> tr("查看子代理", "List subagents"); else -> tr("子代理操作", "Subagent operation")
    }

    fun clientMessageId(item: JSONObject): String? = item.stringOrNull("clientId") ?: item.stringOrNull("clientUserMessageId")

    private fun messageText(input: JSONArray) = inputText(JSONArray(input.objects().filterNot {
        it.optString("type") in setOf("localImage", "image") && MediaProjection.blocks(it).isNotEmpty()
    }))

    fun inputText(input: JSONArray) = input.objects().mapNotNull {
        when (it.optString("type")) {
            "text" -> it.optString("text")
            "mention" -> "@" + it.optString("path")
            "localImage", "image" -> tr("[图片]", "[Image]")
            else -> null
        }
    }.joinToString("\n")
    fun models(values: JSONArray) = values.objects().filterNot { it.optBoolean("hidden") }.map {
        ModelOption(it.optString("model", it.optString("id")), it.optString("displayName"),
            it.optJSONArray("supportedReasoningEfforts")?.objects()?.map { effort -> effort.optString("reasoningEffort") }.orEmpty(),
            it.optJSONArray("inputModalities")?.strings()?.contains("image") ?: false,
            it.optJSONArray("serviceTiers")?.objects()?.map { tier -> RequestChoice(tier.getString("id"), tier.getString("name")) }.orEmpty().let { tiers ->
                if (tiers.isEmpty()) tiers else listOf(RequestChoice("default", "Default")) + tiers
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
            "item/permissions/requestApproval" -> UserRequest(id, tr("请求额外权限", "Additional permissions requested"), body,
                listOf(RequestChoice("allow", tr("仅本轮允许", "Allow this turn")), RequestChoice("decline", tr("不授予", "Deny"))), answering = answering)
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> UserRequest(id,
                if (method.contains("fileChange")) tr("文件修改审批", "File change approval") else tr("命令执行审批", "Command approval"), body,
                decisions.mapIndexed { i, d -> RequestChoice(i.toString(), decisionLabel(d)) }, answering = answering)
            "mcpServer/elicitation/request" -> UserRequest(id, tr("外部工具需要确认", "External tool confirmation"), params.toString(2), listOf(RequestChoice("decline", tr("拒绝", "Decline")), RequestChoice("cancel", tr("取消", "Cancel"))), answering = answering)
            else -> UserRequest(id, tr("请在官方 TUI 处理此请求", "Handle this request in official TUI"), params.toString(2), answering = answering)
        }
    }
    private fun decisionLabel(value: Any) = when (value) {
        "accept" -> tr("允许这次", "Allow once")
        "acceptForSession" -> if (method.contains("fileChange")) tr("允许这些文件的后续修改", "Allow future edits to these files") else tr("允许本会话的同类命令", "Allow similar commands in this session")
        "decline" -> tr("拒绝，继续任务", "Deny & continue")
        "cancel" -> tr("拒绝并中断", "Deny & abort")
        else -> when {
            value is JSONObject && value.has("acceptWithExecpolicyAmendment") -> tr("允许并保存命令规则：\n", "Allow & save command rule:\n") + value.toString(2)
            value is JSONObject && value.has("applyNetworkPolicyAmendment") -> tr("保存网络规则：\n", "Save network rule:\n") + value.toString(2)
            else -> value.toString()
        }
    }
    fun response(choice: String, answers: Map<String, List<String>>): JSONObject = when (method) {
        "item/tool/requestUserInput", "tool/requestUserInput" -> obj("answers" to JSONObject().apply { (if (choice == "skip") emptyMap() else answers).forEach { (key, answer) -> put(key, obj("answers" to JSONArray(answer))) } })
        "item/permissions/requestApproval" -> obj("permissions" to (if (choice == "allow") params.getJSONObject("permissions") else JSONObject()), "scope" to "turn")
        "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> obj("decision" to decisions[choice.toInt()])
        "mcpServer/elicitation/request" -> obj("action" to choice, "content" to JSONObject.NULL)
        else -> throw IllegalArgumentException(tr("请在官方 TUI 中处理此类请求", "Handle this request in the official TUI"))
    }
}

fun requestKey(id: Any): String = (if (id is String) "string:" else "number:") + id.toString()
