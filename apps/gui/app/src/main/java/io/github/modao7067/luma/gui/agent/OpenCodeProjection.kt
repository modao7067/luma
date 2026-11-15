package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.file.Paths

object OpenCodeProjection {
    fun cwd(session: JSONObject, fallback: String): String = session.optJSONObject("location")?.stringOrNull("directory") ?: session.stringOrNull("cwd") ?: fallback
    fun modelKey(ref: JSONObject) = ref.getString("providerID") + "/" + ref.getString("id")
    fun modelRef(id: String, variant: String = ""): JSONObject {
        require(id.contains('/')) { tr("请选择 OpenCode 提供的模型", "Select a model provided by OpenCode") }
        return obj("providerID" to id.substringBefore('/'), "id" to id.substringAfter('/')).apply { if (variant.isNotBlank()) put("variant", variant) }
    }
    fun models(rows: JSONArray): List<ModelOption> = rows.objects().filter { it.optBoolean("enabled") }.map { m ->
        ModelOption(modelKey(m), m.optString("name", m.getString("id")),
            m.optJSONArray("variants")?.objects().orEmpty().map { it.getString("id") },
            "image" in m.optJSONObject("capabilities")?.optJSONArray("input")?.strings().orEmpty())
    }
    private fun fileResource(file: JSONObject): MediaResource? {
        val mime = file.optString("mime", file.optString("mimeType"))
        val source = file.stringOrNull("uri")
            ?: file.optJSONObject("source")?.stringOrNull("uri")
            ?: file.optJSONObject("source")?.stringOrNull("path")
            ?: file.stringOrNull("url")
            ?: file.stringOrNull("path")
            ?: file.stringOrNull("data")?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("data:")) it else "data:${mime.ifBlank { "application/octet-stream" }};base64,$it"
            }
            ?: return null
        if (!MediaProjection.isAllowedProjectedSource(source)) return null
        return MediaResource(source, file.optString("name"), mime)
    }
    private fun extractMetadataPatch(metadata: JSONObject?): String {
        val files = metadata?.optJSONArray("files") ?: return ""
        val patches = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val patch = files.optJSONObject(i)?.optString("patch").orEmpty().trim()
            if (patch.isNotEmpty()) patches.add(patch)
        }
        return patches.joinToString("\n\n")
    }
    fun message(m: JSONObject): List<ConversationItem> {
        val id = m.getString("id")
        return when (m.optString("type")) {
            "user" -> {
                val files = m.optJSONArray("files")?.objects().orEmpty()
                listOf(ConversationItem(id, "user", m.optString("text"), media = files.mapNotNull(::fileResource),
                    userContent = if (m.optJSONObject("metadata")?.optString("source") == "subagent") UserMessageContent()
                        else UserMessageContent(m.optString("text"), files.map {
                            val resolved = fileResource(it)
                            val kind = if (resolved?.mimeType?.startsWith("image/") == true) tr("图片", "Image") else tr("文件", "File")
                            SessionTitlePolicy.attachment(kind, resolved?.source, it.stringOrNull("name"))
                        })))
            }
            "assistant" -> m.optJSONArray("content")?.objects().orEmpty().mapIndexed { index, part ->
                val key = "$id:$index"
                val done = m.optJSONObject("time")?.has("completed") == true
                when (part.optString("type")) {
                    "text" -> ConversationItem(key, "assistant", part.optString("text"))
                    "reasoning" -> ConversationItem(key, "tool", tr("思考", "Thought"), part.optString("text"), kind = "reasoning", outcome = if (part.optJSONObject("time")?.has("completed") == true || done) "succeeded" else "running")
                    "tool" -> {
                        val state = part.optJSONObject("state") ?: JSONObject()
                        val status = state.optString("status")
                        val output = state.optJSONArray("content")?.objects().orEmpty().joinToString("\n") { it.stringOrNull("text") ?: MediaProjection.display(it) }
                        val metadataPatch = extractMetadataPatch(state.optJSONObject("metadata"))
                        val primaryDetail = metadataPatch.ifBlank { output }
                        ConversationItem("$id:tool:${part.getString("id")}", "tool", part.optString("name"),
                            detail = listOf(primaryDetail, state.optJSONObject("error")?.optString("message").orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
                            status = status, media = MediaProjection.blocks(state.opt("content")), kind = "tool", input = state.optJSONObject("input")?.toString(2).orEmpty(),
                            toolName = part.stringOrNull("name").orEmpty(),
                            outcome = when (status) { "completed" -> "succeeded"; "error" -> "failed"; else -> "running" })
                    }
                    else -> ConversationItem(key, "assistant", "", media = MediaProjection.blocks(part), detail = if (MediaProjection.blocks(part).isEmpty()) MediaProjection.display(part) else "", kind = "attachment")
                }
            } + listOfNotNull(m.optJSONObject("error")?.let { ConversationItem("$id:error", "tool", tr("执行失败", "Execution failed"), it.optString("message", it.toString()), outcome = "failed") })
            "idle" -> listOfNotNull(m.optJSONObject("error")?.let { ConversationItem(id, "tool", tr("执行失败", "Execution failed"), it.optString("message", it.toString()), outcome = "failed") })
            "compaction" -> listOf(ConversationItem(id, "tool", tr("上下文压缩", "Context compaction"), m.optString("summary"), kind = "compaction"))
            else -> emptyList()
        }
    }
    fun subAgents(messages: List<JSONObject>, childSnapshots: Map<String, JSONObject> = emptyMap(),
        activeIds: Set<String> = emptySet(), parentId: String? = null): List<SubAgentState> {
        val lastCallAt = mutableMapOf<String, Long>()
        val terminalAt = mutableMapOf<String, Long>()
        val agents = linkedMapOf<String, SubAgentState>()
        messages.forEach { message ->
            val messageId = message.optString("id")
            message.optJSONArray("content")?.objects().orEmpty().filter { it.optString("type") == "tool" && it.optString("name") == "subagent" }.forEach partLoop@ { part ->
                val state = part.optJSONObject("state") ?: JSONObject()
                val metadata = state.optJSONObject("metadata") ?: JSONObject()
                val id = metadata.stringOrNull("sessionID") ?: return@partLoop
                val old = agents[id] ?: SubAgentState(id)
                val input = state.optJSONObject("input") ?: JSONObject()
                val native = metadata.optString("status")
                val time = part.optJSONObject("time")
                lastCallAt[id] = time?.optLong("ran", time.optLong("created")) ?: message.optJSONObject("time")?.optLong("created") ?: 0L
                if (native == "completed" && state.optString("status") != "error") terminalAt[id] = time?.optLong("completed", time.optLong("created")) ?: 0L
                agents[id] = old.copy(itemId = old.itemId.ifEmpty { "$messageId:tool:${part.getString("id")}" },
                    role = input.stringOrNull("agent") ?: old.role, task = input.stringOrNull("prompt") ?: old.task,
                    name = input.stringOrNull("description") ?: old.name,
                    status = if (state.optString("status") == "error") "unknown" else when (native) { "running" -> "running"; "completed" -> "succeeded"; else -> old.status },
                    nativeStatus = native.ifEmpty { old.nativeStatus },
                    detail = (state.optJSONArray("content")?.objects().orEmpty().mapNotNull { it.stringOrNull("text") } +
                        listOfNotNull(state.optJSONObject("error")?.stringOrNull("message"))).joinToString("\n"))
            }
            val meta = message.optJSONObject("metadata")
            if (meta?.optString("source") == "subagent") {
                val id = meta.stringOrNull("childID") ?: return@forEach
                val old = agents[id] ?: SubAgentState(id, itemId = messageId)
                val native = meta.optString("state")
                if (native in setOf("completed", "error", "cancelled")) terminalAt[id] = message.optJSONObject("time")?.optLong("created") ?: 0L
                agents[id] = old.copy(role = meta.stringOrNull("agent") ?: old.role,
                    nativeStatus = native, status = when (native) { "completed" -> "succeeded"; "error" -> "failed"; "cancelled" -> "cancelled"; "running" -> "running"; else -> "unknown" })
            }
        }
        if (parentId != null) {
            childSnapshots.values.forEach { child ->
                val id = child.stringOrNull("id") ?: return@forEach
                if (child.optString("parentID") == parentId && id !in agents) {
                    agents[id] = SubAgentState(
                        id = id,
                        name = child.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: tr("子代理", "Sub-agent"),
                        role = child.stringOrNull("agent")?.takeIf { it.isNotBlank() } ?: "subagent",
                    )
                }
            }
        }
        return agents.values.map { agent ->
            val child = childSnapshots[agent.id]?.takeIf { it.optString("id") == agent.id && (parentId == null || it.optString("parentID") == parentId) }
                ?: return@map agent
            val enriched = agent.copy(
                name = agent.name.ifBlank { child.stringOrNull("title").orEmpty() },
                role = agent.role.ifBlank { child.stringOrNull("agent").orEmpty() },
            )
            if (agent.id in activeIds) return@map enriched.copy(status = "running", nativeStatus = "active", detail = if (agent.status in setOf("succeeded", "failed", "cancelled")) "" else agent.detail)
            val time = child.optJSONObject("time")
            val busyAt = time?.let { if (it.has("busy")) it.optLong("busy") else null }
            val idleAt = time?.let { if (it.has("idle")) it.optLong("idle") else null }
            if (busyAt != null && (idleAt == null || busyAt > idleAt)) return@map enriched.copy(status = "running", nativeStatus = "active", detail = if (agent.status in setOf("succeeded", "failed", "cancelled")) "" else agent.detail)
            if (agent.status in setOf("succeeded", "failed", "cancelled") && (idleAt == null || idleAt <= (terminalAt[agent.id] ?: 0L))) return@map enriched
            if (idleAt == null || idleAt < (lastCallAt[agent.id] ?: 0L)) return@map enriched.copy(status = "unknown")
            val native = child.optString("outcome")
            enriched.copy(status = when (native) { "succeeded" -> "succeeded"; "failed" -> "failed"; "interrupted" -> "cancelled"; else -> "unknown" }, nativeStatus = native)
        }
    }
    fun childLifecycleEvent(event: JSONObject, childIds: Set<String>, parentId: String? = null): Boolean {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: return false
        if (parentId != null && type in setOf("session.created", "session.forked") && data.optString("parentID") == parentId) {
            return true
        }
        return data.optString("sessionID") in childIds && type in setOf(
            "session.execution.started", "session.execution.succeeded", "session.execution.failed",
            "session.execution.interrupted", "session.idle", "session.renamed", "session.deleted",
        )
    }

    fun queue(rows: JSONArray): List<QueueEntry> = rows.objects().map { row ->
        val user = row.optString("type") == "user"
        QueueEntry(row.getString("id"), row.optJSONObject("payload")?.optString("text")?.takeIf { it.isNotBlank() } ?: when(row.optString("type")) { "compaction" -> tr("压缩上下文", "Compact context"); "move" -> tr("切换工作目录", "Change working directory"); else -> tr("原生待发任务", "Native queued task") },
            if (row.optString("delivery") == "steer") tr("等待追加", "Pending steer") else tr("已排队", "Queued"), canEdit = false, canSteer = user, canRemove = user,
            clientUserMessageId = row.getString("id").takeIf { user })
    }
    fun permission(row: JSONObject) = UserRequest("permission:" + row.getString("id"), tr("OpenCode 权限请求", "OpenCode permission request"),
        listOf(row.optString("action"), row.optJSONArray("resources")?.strings().orEmpty().joinToString("\n"), row.optString("message")).filter { it.isNotBlank() }.joinToString("\n\n"),
        listOf(RequestChoice("once", tr("允许一次", "Allow once")), RequestChoice("always", tr("记住并允许", "Always allow")), RequestChoice("reject", tr("拒绝", "Reject"))))
    fun supportsForm(row: JSONObject) = row.getJSONArray("fields").objects().all {
        it.optString("type") in listOf("string", "number", "integer", "boolean", "multiselect") && !it.optBoolean("hidden") && it.optJSONArray("when").let { a -> a == null || a.length() == 0 }
    }
    fun promptFiles(text: String, images: List<StoredImage>, cwd: String): JSONArray {
        val files = JSONArray()
        if ('\u0000' in cwd) return files
        val base = cwd.ifBlank { "/" }
        val seen = LinkedHashSet<String>()
        for (img in images) {
            if (img.path.isBlank() || '\u0000' in img.path) continue
            val raw = if (img.path.startsWith('/')) img.path else "$base/${img.path}"
            val norm = runCatching { Paths.get(raw).normalize().toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            if (seen.add(norm)) {
                val uri = URI("file", "", norm, null).toASCIIString()
                files.put(obj("uri" to uri, "name" to img.name.ifBlank { norm.substringAfterLast('/') }))
            }
        }
        for (norm in CodexProjection.mentionedPaths(text, cwd)) {
            if (seen.add(norm)) {
                val uri = URI("file", "", norm, null).toASCIIString()
                files.put(obj("uri" to uri, "name" to norm.substringAfterLast('/')))
            }
        }
        return files
    }
    fun form(row: JSONObject): UserRequest {
        if (!supportsForm(row)) return UserRequest("form:" + row.getString("id"), row.optString("title", "Answer"), tr("该原生表单包含条件或外部字段，请在官方终端回答。", "This native form has conditional or external fields; answer it in the official terminal."))
        return UserRequest("form:" + row.getString("id"), row.optString("title", "Answer"), "", questions = row.getJSONArray("fields").objects().map { f ->
            val isBoolean = f.optString("type") == "boolean"
            val opts = if (isBoolean) emptyList() else f.optJSONArray("options")?.objects().orEmpty()
            RequestQuestion(
                id = f.getString("key"),
                header = f.optString("title", f.getString("key")),
                prompt = f.optString("description"),
                options = if (isBoolean) listOf(tr("是", "Yes"), tr("否", "No")) else opts.map { it.optString("label", it.getString("value")) },
                multiple = f.optString("type") == "multiselect",
                allowOther = !isBoolean && (f.optBoolean("custom") || !f.has("options")),
                required = f.optBoolean("required"),
                optionValues = if (opts.isEmpty()) emptyList() else List(opts.size) { "\u0000opt:$it" },
            )
        }, skipChoice = "skip")
    }
    fun formReply(row: JSONObject, answers: Map<String, List<String>>): JSONObject {
        check(supportsForm(row))
        val answer = JSONObject()
        row.getJSONArray("fields").objects().forEach { f ->
            val key = f.getString("key"); val values = answers[key].orEmpty()
            val options = f.optJSONArray("options")?.objects().orEmpty()
            val mapped = values.map { value ->
                when {
                    value.startsWith("\u0000opt:") -> {
                        val idx = value.removePrefix("\u0000opt:").toIntOrNull()
                        require(idx != null && idx in options.indices) { tr("选项索引无效", "Invalid option index") }
                        options[idx].getString("value")
                    }
                    value.startsWith("\u0000raw:") -> value.removePrefix("\u0000raw:")
                    else -> {
                        val matches = options.filter { it.optString("label", it.getString("value")) == value }
                        when {
                            matches.size > 1 -> throw IllegalArgumentException(tr("选项标签重复，无法按标签唯一定位", "Duplicate option labels; cannot disambiguate by label"))
                            matches.size == 1 -> matches[0].getString("value")
                            else -> value
                        }
                    }
                }
            }
            if (mapped.isEmpty() && !f.optBoolean("required")) return@forEach
            require(mapped.isNotEmpty()) { tr("请填写 ${f.optString("title", key)}", "Please fill in ${f.optString("title", key)}") }
            answer.put(key, when (f.getString("type")) {
                "boolean" -> when (mapped.first()) { "是", "Yes", "yes", "true" -> true; "否", "No", "no", "false" -> false; else -> error(tr("请选择是或否", "Please select Yes or No")) }
                "integer" -> mapped.first().toLongOrNull() ?: error(tr("请输入整数", "Please enter an integer"))
                "number" -> mapped.first().toDoubleOrNull()?.takeIf { it.isFinite() } ?: error(tr("请输入有效数字", "Please enter a valid number"))
                "multiselect" -> JSONArray(mapped)
                else -> mapped.first()
            })
        }
        return obj("answer" to answer)
    }
    fun metrics(session: JSONObject, messages: List<JSONObject> = emptyList(), models: JSONArray = JSONArray()): List<Metric> =
        OpenCodeUsage.metrics(session, messages, models)
}
