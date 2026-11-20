package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject

object NativeCliProjection {
    private val piImageHintTailRegex = Regex(
        "(?:\\s*(?:\\[Image: original \\d+x\\d+, displayed at \\d+x\\d+\\. Multiply coordinates by \\d+(?:\\.\\d+)? to map to original image\\.\\]|\\[Image converted from [^\\r\\n\\]]+ to [^\\r\\n\\]]+\\.\\]|\\[Image omitted: [^\\r\\n\\]]+\\]))+\\s*$"
    )
    internal fun stripPiImageHints(cli: String, text: String): String {
        if (!cli.equals("pi", ignoreCase = true)) return text
        if (!text.contains("[Image")) return text
        return piImageHintTailRegex.replace(text, "")
    }
    private fun JSONObject.rows(key: String) = optJSONArray(key)?.objects().orEmpty()
    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    fun models(s: JSONObject) = s.rows("models").map { ModelOption(it.getString("id"), it.getString("label"), it.strings("efforts"), it.optBoolean("supportsImages", false)) }
    fun skills(s: JSONObject) = s.rows("skills").map { DraftSkill(it.getString("id"), it.getString("name"), it.optString("path")) }
    fun mcp(s: JSONObject) = s.rows("mcp").map { ReadOnlyEntry(it.getString("name"), it.optString("detail")) }
    private fun JSONObject.conversationItem(snapshot: JSONObject): ConversationItem {
        val reasoning = optString("kind") == "reasoning"
        val status = optString("status")
        val role = if (reasoning) "tool" else getString("role")
        val body = if (reasoning) tr("思考", "Thought") else if (role == "user") stripPiImageHints(snapshot.optString("cli"), optString("text")) else optString("text")
        return ConversationItem(getString("id"), role,
            body,
            detail = if (reasoning) optString("text") else stringOrNull("outcome")?.takeIf { it.isNotBlank() } ?: optString("detail"),
            status = status, media = rows("media").map { media -> MediaResource(media.getString("source"), media.optString("name"), media.optString("mimeType"), sizeBytes = if (media.has("sizeBytes") && !media.isNull("sizeBytes")) media.optLong("sizeBytes").takeIf { it >= 0 } else null) }, kind = optString("kind"), input = optString("input"),
            delivery = if (optString("role") == "user") when (snapshot.optJSONObject("receipts")?.optString(optString("id"))) { "unknown" -> "pending"; "rejected" -> "rejected"; else -> "" } else "",
            turnId = optString("turnId"), toolName = stringOrNull("toolName").orEmpty(), toolKind = stringOrNull("toolKind").orEmpty(),
            nativeInputEvidence = snapshot.optInt("inputEvidenceProtocol") >= 1 && optString("origin") != "local" && snapshot.optJSONObject("receipts")?.optString(optString("id")) !in setOf("unknown", "pending", "rejected"),
            outcome = when (status) { "completed" -> "succeeded"; "in_progress", "pending" -> "running"; "failed", "running", "cancelled" -> status; else -> "unknown" },
            timestampMs = optLong("timestampMs", 0L),
            completedAtMs = optLong("completedAtMs", 0L))
    }
    fun streamedSubAgentDetail(agent: SubAgentState, previous: SubAgentDetailView, models: List<ModelOption>): SubAgentDetailView =
        previous.copy(agent = agent, messages = agent.messages, loading = false, effort = "",
            modelLabel = when (agent.model) {
                "inherit" -> tr("继承父代理", "Inherit parent")
                else -> models.firstOrNull { it.id == agent.model }?.label ?: agent.model
            })
    private fun projectMessages(rows: List<JSONObject>, snapshot: JSONObject, previous: List<ConversationItem>): List<ConversationItem> {
        if (rows.isEmpty()) return if (previous.isEmpty()) previous else emptyList()
        var prevById: Map<String, ConversationItem>? = null
        var same = rows.size == previous.size
        val next = ArrayList<ConversationItem>(rows.size)
        for (index in rows.indices) {
            val fresh = rows[index].conversationItem(snapshot)
            val positional = previous.getOrNull(index)
            val prev = if (positional != null && positional.id == fresh.id) positional
            else (prevById ?: previous.associateBy { it.id }.also { prevById = it })[fresh.id]
            val item = if (prev != null && prev == fresh) prev else fresh
            if (item !== positional) same = false
            next.add(item)
        }
        return if (same) previous else next
    }
    private fun projectSubAgents(rows: List<JSONObject>, snapshot: JSONObject, previous: List<SubAgentState>): List<SubAgentState> {
        if (rows.isEmpty()) return if (previous.isEmpty()) previous else emptyList()
        var prevById: Map<String, SubAgentState>? = null
        var same = rows.size == previous.size
        val next = ArrayList<SubAgentState>(rows.size)
        for (index in rows.indices) {
            val row = rows[index]
            val id = row.getString("id")
            val positional = previous.getOrNull(index)
            val prev = if (positional != null && positional.id == id) positional
            else (prevById ?: previous.associateBy { it.id }.also { prevById = it })[id]
            val fresh = SubAgentState(id, row.optString("name"), row.optString("role"),
                row.optString("turnId"), row.optString("itemId"), row.optString("task"), row.optString("status", "unknown"), row.optString("detail"), row.optString("nativeStatus"),
                messages = projectMessages(row.rows("messages"), snapshot, prev?.messages.orEmpty()), model = row.stringOrNull("model").orEmpty())
            val state = if (prev != null && prev == fresh) prev else fresh
            if (state !== positional) same = false
            next.add(state)
        }
        return if (same) previous else next
    }
    fun view(s: JSONObject, previous: SessionView): SessionView {
        val caps = s.optJSONObject("capabilities") ?: JSONObject()
        return previous.copy(connected = NativeCliStatePolicy.ready(s), loading = NativeCliStatePolicy.loading(s),
            connection = NativeCliStatePolicy.connection(s),
            activeTurnId = if (s.optBoolean("running")) s.getString("nativeId") else null,
            canOpenTui = s.optBoolean("canOpenTui"), error = NativeCliStatePolicy.error(s), capabilityNotice = s.stringOrNull("permissionNotice"),
            permission = s.optString("permission"), permissions = s.rows("permissions").map {
                RequestChoice(it.getString("id"), it.getString("label"), it.optString("description"), it.optString("sandbox"), it.optString("approval"),
                    it.optString("configId"), it.optString("configName"), if (it.has("selected") && !it.isNull("selected")) it.getBoolean("selected") else null)
            },
            planModeSupported = s.optBoolean("planModeSupported"),
            planMode = s.optBoolean("planMode"),
            skipPermissionsActive = s.optBoolean("skipPermissionsActive"),
            metrics = s.rows("metrics").map { Metric(it.getString("label"), it.getString("value")) },
            messages = projectMessages(s.rows("messages"), s, previous.messages),
            subAgents = projectSubAgents(s.rows("subAgents"), s, previous.subAgents),
            queue = (s.rows("queue") + s.rows("hostQueue")).map { QueueEntry(it.getString("id"), stripPiImageHints(s.optString("cli"), it.getString("text")), it.optString("status", tr("待发", "Queued")),
                canEdit = it.optBoolean("canEdit", false), canSteer = it.optBoolean("canSteer", false), canRemove = it.optBoolean("canRemove", false),
                clientUserMessageId = it.stringOrNull("clientUserMessageId"), hostOwned = it.optBoolean("hostOwned")) },
            requests = s.rows("requests").map { r ->
                val questionRows = r.rows("questions")
                val choices = r.rows("choices").map {
                    val id = it.getString("id")
                    val label = if (s.optString("cli") == "Kimi Code" && questionRows.isNotEmpty() && id == "cancel") tr("终止提问", "Cancel prompt") else it.getString("label")
                    RequestChoice(id, label)
                }
                val ids = choices.map { it.id }.toSet()
                val legacy = when (s.optString("cli")) {
                    "Claude Code" -> ("allow" to "deny").takeIf { ids.containsAll(listOf("allow", "deny")) }
                    "Grok Build" -> ("accept" to "cancel").takeIf { ids.containsAll(listOf("accept", "cancel")) }
                    "Kimi Code" -> ("accept" to "decline").takeIf { ids.containsAll(listOf("accept", "decline")) }
                    "pi" -> ("submit" to "cancel").takeIf { "cancel" in ids }
                    else -> null to null
                }
                val nativeTitle = r.getString("title")
                val legacyDefaultTitles = when (s.optString("cli")) {
                    "Claude Code" -> setOf("Claude Code 提问", "Claude Code question")
                    "Kimi Code" -> setOf("Kimi Code 提问", "Kimi Code question")
                    "Grok Build" -> setOf("问题", "Question")
                    "pi" -> setOf("CLI 请求", "CLI request")
                    else -> emptySet()
                }
                val title = if (questionRows.isNotEmpty() && (nativeTitle == "需要你的回答" || nativeTitle == "Response required" ||
                    (!r.has("submitChoice") && nativeTitle in legacyDefaultTitles))) "Answer" else nativeTitle
                UserRequest(r.getString("id"), title, r.optString("body"), choices,
                    questions = questionRows.map { RequestQuestion(it.getString("id"), it.optString("header"), it.optString("prompt"),
                        it.strings("options"), it.optBoolean("multiple"), it.optBoolean("secret"),
                        allowOther = it.optBoolean("allowOther", true), required = it.optBoolean("required", true),
                        optionValues = it.strings("optionValues")) }, externalUrl = r.stringOrNull("externalUrl"),
                    submitChoice = if (r.has("submitChoice")) r.stringOrNull("submitChoice") else legacy?.first,
                    skipChoice = if (r.has("skipChoice")) r.stringOrNull("skipChoice") else legacy?.second)
            },
            capabilities = CliCapabilities(queue = caps.optBoolean("queue"), steer = caps.optBoolean("steer"),
                stop = caps.optBoolean("stop"), images = caps.optBoolean("images"), skills = caps.optBoolean("skills"),
                maxSkills = caps.optInt("maxSkills", 0), rename = caps.optBoolean("rename"), compact = caps.optBoolean("compact"), diff = false))
    }
}
