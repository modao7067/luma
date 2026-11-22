package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONArray
import org.json.JSONObject

internal enum class ProcessCategory(private val zh: String, private val en: String) {
    Terminal("终端", "Terminal"), Edit("编辑", "Edit"), Read("读取", "Read"), Search("搜索", "Search"), WebSearch("网页搜索", "Web search"), Browse("浏览", "Browse"), Image("图片", "Image"), Tool("工具", "Tool"),
    ;
    val caption: String get() = tr(zh, en)
}

private val piToolCategories = mapOf(
    "bash" to ProcessCategory.Terminal, "powershell" to ProcessCategory.Terminal,
    "edit" to ProcessCategory.Edit, "write" to ProcessCategory.Edit,
    "read" to ProcessCategory.Read, "ls" to ProcessCategory.Read,
    "grep" to ProcessCategory.Search, "find" to ProcessCategory.Search,
)
private val openCodeToolCategories = mapOf(
    "shell" to ProcessCategory.Terminal,
    "edit" to ProcessCategory.Edit, "write" to ProcessCategory.Edit, "patch" to ProcessCategory.Edit,
    "read" to ProcessCategory.Read,
    "grep" to ProcessCategory.Search, "glob" to ProcessCategory.Search, "websearch" to ProcessCategory.WebSearch,
    "webfetch" to ProcessCategory.Browse,
    "experimental_image_generate" to ProcessCategory.Image,
)
private val claudeToolCategories = mapOf(
    "Bash" to ProcessCategory.Terminal, "PowerShell" to ProcessCategory.Terminal,
    "Read" to ProcessCategory.Read,
    "Write" to ProcessCategory.Edit, "Edit" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit,
    "NotebookEdit" to ProcessCategory.Edit,
    "Glob" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "WebSearch" to ProcessCategory.WebSearch,
    "WebFetch" to ProcessCategory.Browse,
)
private val antigravityToolCategories = mapOf(
    "run_command" to ProcessCategory.Terminal,
    "send_command_input" to ProcessCategory.Terminal,
    "read_file" to ProcessCategory.Read,
    "view_file" to ProcessCategory.Read,
    "list_dir" to ProcessCategory.Read,
    "edit_file" to ProcessCategory.Edit,
    "write_file" to ProcessCategory.Edit,
    "grep_search" to ProcessCategory.Search,
    "find_by_name" to ProcessCategory.Search,
    "search_web" to ProcessCategory.WebSearch,
    "read_url_content" to ProcessCategory.Browse,
)
private val kimiToolCategories = mapOf(
    "Bash" to ProcessCategory.Terminal, "Shell" to ProcessCategory.Terminal,
    "Read" to ProcessCategory.Read, "ListDir" to ProcessCategory.Read,
    "Write" to ProcessCategory.Edit, "Edit" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit, "NotebookEdit" to ProcessCategory.Edit,
    "Glob" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "WebSearch" to ProcessCategory.WebSearch,
    "WebFetch" to ProcessCategory.Browse,
)
private val deepSeekToolCategories = mapOf(
    "bash" to ProcessCategory.Terminal, "pwsh" to ProcessCategory.Terminal, "shell" to ProcessCategory.Terminal, "run_code" to ProcessCategory.Terminal,
    "read" to ProcessCategory.Read, "ls" to ProcessCategory.Read, "list_dir" to ProcessCategory.Read,
    "edit" to ProcessCategory.Edit, "write" to ProcessCategory.Edit, "patch" to ProcessCategory.Edit, "multi_edit" to ProcessCategory.Edit,
    "grep" to ProcessCategory.Search, "glob" to ProcessCategory.Search, "find" to ProcessCategory.Search,
    "web_search" to ProcessCategory.WebSearch,
    "web_fetch" to ProcessCategory.Browse,
)
private val grokToolCategories = mapOf(
    "bash" to ProcessCategory.Terminal, "shell" to ProcessCategory.Terminal, "run_command" to ProcessCategory.Terminal,
    "Bash" to ProcessCategory.Terminal, "Shell" to ProcessCategory.Terminal,
    "read" to ProcessCategory.Read, "read_file" to ProcessCategory.Read, "Read" to ProcessCategory.Read, "ls" to ProcessCategory.Read, "list_dir" to ProcessCategory.Read,
    "edit" to ProcessCategory.Edit, "edit_file" to ProcessCategory.Edit, "write" to ProcessCategory.Edit, "write_file" to ProcessCategory.Edit,
    "Edit" to ProcessCategory.Edit, "Write" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit,
    "grep" to ProcessCategory.Search, "glob" to ProcessCategory.Search, "search" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "Glob" to ProcessCategory.Search,
    "web_search" to ProcessCategory.WebSearch, "WebSearch" to ProcessCategory.WebSearch,
    "web_fetch" to ProcessCategory.Browse, "WebFetch" to ProcessCategory.Browse,
)
private val codeBuddyToolCategories = mapOf(
    "Bash" to ProcessCategory.Terminal, "Shell" to ProcessCategory.Terminal, "Terminal" to ProcessCategory.Terminal,
    "Read" to ProcessCategory.Read, "ListDir" to ProcessCategory.Read,
    "Write" to ProcessCategory.Edit, "Edit" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit, "NotebookEdit" to ProcessCategory.Edit,
    "Glob" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "WebSearch" to ProcessCategory.WebSearch,
    "WebFetch" to ProcessCategory.Browse,
)
private val qoderToolCategories = mapOf(
    "Bash" to ProcessCategory.Terminal, "Shell" to ProcessCategory.Terminal, "bash" to ProcessCategory.Terminal, "shell" to ProcessCategory.Terminal, "run_command" to ProcessCategory.Terminal,
    "Read" to ProcessCategory.Read, "read" to ProcessCategory.Read, "read_file" to ProcessCategory.Read, "list_dir" to ProcessCategory.Read,
    "Write" to ProcessCategory.Edit, "Edit" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit,
    "write" to ProcessCategory.Edit, "edit" to ProcessCategory.Edit, "edit_file" to ProcessCategory.Edit, "write_file" to ProcessCategory.Edit,
    "Glob" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "glob" to ProcessCategory.Search, "grep" to ProcessCategory.Search, "search" to ProcessCategory.Search,
    "WebSearch" to ProcessCategory.WebSearch, "web_search" to ProcessCategory.WebSearch,
    "WebFetch" to ProcessCategory.Browse, "web_fetch" to ProcessCategory.Browse,
)
private val antigravitySwarmTools = setOf("swarm", "post_message", "read_board", "swarm_wait")

private fun JSONObject.safeTrimmedString(key: String): String {
    val value = opt(key)
    return if (value == null || value == JSONObject.NULL) "" else value.toString().trim()
}

private fun antigravitySwarmContext(item: ConversationItem): String? {
    if (item.input.isBlank()) return null
    val obj = runCatching { JSONObject(item.input) }.getOrNull() ?: return null
    return when (item.toolName) {
        "swarm" -> when (val action = obj.safeTrimmedString("action")) {
            "add" -> {
                val name = obj.safeTrimmedString("name")
                val tasksCount = obj.optJSONArray("tasks")?.length() ?: 0
                val n = obj.optInt("n", 0)
                val count = if (tasksCount > 0) tasksCount else if (n > 1) n else 0
                when {
                    count > 0 && name.isNotEmpty() -> tr("添加 $name ×$count", "Add $name ×$count")
                    count > 0 -> tr("添加 $count 个成员", "Add $count workers")
                    name.isNotEmpty() -> tr("添加 $name", "Add $name")
                    else -> tr("添加成员", "Add worker")
                }
            }
            "status" -> tr("查看状态", "Status")
            "models" -> tr("模型列表", "Models")
            "model" -> {
                val name = obj.safeTrimmedString("name")
                val model = obj.safeTrimmedString("model")
                val suffix = listOfNotNull(name.takeIf { it.isNotEmpty() }, model.takeIf { it.isNotEmpty() }).joinToString(" · ")
                if (suffix.isEmpty()) tr("切换模型", "Switch model") else tr("切换模型 · $suffix", "Switch model · $suffix")
            }
            "kill" -> {
                val name = obj.safeTrimmedString("name")
                if (name.isEmpty()) tr("结束成员", "Kill worker") else tr("结束 $name", "Kill $name")
            }
            "title" -> {
                val title = obj.safeTrimmedString("title")
                if (title.isEmpty()) tr("设置标题", "Set title") else tr("标题 · $title", "Title · $title")
            }
            else -> action.takeIf { it.isNotEmpty() }
        }
        "post_message" -> {
            val singleLine = obj.safeTrimmedString("text").replace(Regex("\\s+"), " ").trim()
            when {
                singleLine.isEmpty() -> null
                singleLine.length > 48 -> safeUtf16Prefix(singleLine, 48).trimEnd() + "…"
                else -> singleLine
            }
        }
        "read_board" -> when {
            obj.optBoolean("status", false) -> tr("成员状态", "Roster status")
            else -> {
                val seqs = obj.optJSONArray("seqs")
                if (seqs != null && seqs.length() > 0) {
                    (0 until seqs.length()).mapNotNull { i -> seqs.optInt(i, -1).takeIf { it >= 0 }?.let { "#$it" } }
                        .joinToString(", ").takeIf { it.isNotEmpty() }
                } else if (obj.has("since")) {
                    "since #${obj.optInt("since")}"
                } else null
            }
        }
        "swarm_wait" -> if (obj.has("since")) {
            val base = "since #${obj.optInt("since")}"
            if (obj.has("mentions_only") && !obj.optBoolean("mentions_only")) {
                tr("$base · 全部消息", "$base · all messages")
            } else base
        } else null
        else -> null
    }
}

internal fun processCategory(item: ConversationItem, cli: String = "Codex"): ProcessCategory = when (item.kind) {
    "commandExecution" -> processCommandCategory(item)
    "fileChange" -> ProcessCategory.Edit
    "webSearch" -> ProcessCategory.WebSearch
    "imageView", "imageGeneration" -> ProcessCategory.Image
    "mcpToolCall" -> ProcessCategory.Tool
    "tool" -> when {
        item.toolKind.isNotEmpty() -> when (item.toolKind) {
            "execute" -> ProcessCategory.Terminal
            "edit", "delete", "move" -> ProcessCategory.Edit
            "read" -> ProcessCategory.Read
            "search" -> nativeToolCategory(item, cli)?.takeIf { it == ProcessCategory.WebSearch } ?: ProcessCategory.Search
            "fetch" -> ProcessCategory.Browse
            else -> ProcessCategory.Tool
        }
        else -> nativeToolCategory(item, cli) ?: ProcessCategory.Tool
    }
    else -> ProcessCategory.Tool
}

private fun nativeToolCategory(item: ConversationItem, cli: String): ProcessCategory? = when (cli) {
    "pi" -> piToolCategories[item.toolName]
    "OpenCode" -> openCodeToolCategories[item.toolName]
    "Claude Code" -> claudeToolCategories[item.toolName]
    "Antigravity" -> antigravityToolCategories[item.toolName]
    "Kimi Code" -> kimiToolCategories[item.toolName]
    "DeepSeek Harness" -> deepSeekToolCategories[item.toolName]
    "Grok Build" -> grokToolCategories[item.toolName]
    "CodeBuddy Code" -> codeBuddyToolCategories[item.toolName]
    "Qoder CLI" -> qoderToolCategories[item.toolName]
    else -> null
}

internal val processInvocationKinds = setOf(
    "tool", "commandExecution", "fileChange", "webSearch", "imageView", "imageGeneration",
    "mcpToolCall", "dynamicToolCall", "collabAgentToolCall",
)

internal fun processToolInvocations(entries: List<ConversationItem>): List<ConversationItem> {
    val calls = linkedMapOf<Triple<String, String, Int>, ConversationItem>()
    entries.forEachIndexed { index, item ->
        if (item.role != "tool" || item.kind !in processInvocationKinds) return@forEachIndexed
        calls[Triple(item.turnId, item.id, if (item.id.isBlank()) index else -1)] = item
    }
    return calls.values.toList()
}

internal fun processToolName(item: ConversationItem): String = item.toolName.takeIf { it.isNotBlank() }
    ?: when (item.kind) {
        "commandExecution" -> "shell"
        "tool" -> item.toolKind.ifBlank { tr("工具调用", "Tool call") }
        else -> item.kind.ifBlank { tr("工具事件", "Tool event") }
    }

private val agySystemActionDirectiveRegex = Regex(
    "(?m)^[ \\t]*YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:[\\s\\S]*?(?:Never mix two or more languages in the same response\\.?|\\z)",
)
private val agyBgMetaLineRegex = Regex(
    "^(?:(?:Created|Completed|Updated) At:\\s+\\d{4}-\\d{2}-\\d{2}T\\S+|Task Description:\\s+.*|Tool is running as a background task with task id:\\s*\\S+.*|Task logs are available at:\\s*file://\\S+.*)$",
)
private val agyLeadTimestampLineRegex = Regex(
    "^(?:Created|Completed|Updated) At:\\s+\\d{4}-\\d{2}-\\d{2}T\\S+$",
)
private val agyCommandWrapperHeaderRegex = Regex(
    "^Command exited with code\\s+(-?\\d+)\\.?\\s*\\n+Command output:\\s*\\n?",
)

internal fun sanitizeProcessToolDetail(detail: String): String {
    if (detail.isBlank()) return ""
    val normalized = detail.replace("\r\n", "\n").replace("\r", "\n")
    val hasBgWrapper = normalized.contains("Tool is running as a background task with task id:") ||
        normalized.contains("Task logs are available at: file://") ||
        normalized.contains("YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:")
    var cleaned = if (hasBgWrapper) {
        val withoutDirective = agySystemActionDirectiveRegex.replace(normalized, "")
        withoutDirective.lines()
            .filterNot { line -> agyBgMetaLineRegex.matches(line.trim()) }
            .joinToString("\n")
    } else {
        normalized
    }
    val lines = cleaned.lines()
    var leadIdx = 0
    while (leadIdx < lines.size && (lines[leadIdx].isBlank() || agyLeadTimestampLineRegex.matches(lines[leadIdx].trim()))) {
        leadIdx++
    }
    if (leadIdx > 0 && leadIdx < lines.size && agyCommandWrapperHeaderRegex.containsMatchIn(lines.drop(leadIdx).joinToString("\n"))) {
        cleaned = lines.drop(leadIdx).joinToString("\n")
    }
    agyCommandWrapperHeaderRegex.find(cleaned)?.let { match ->
        val exitCode = match.groupValues[1]
        val rest = cleaned.substring(match.range.last + 1)
        cleaned = if (exitCode == "0") rest else "$rest\n[exit $exitCode]".trimStart('\n')
    }
    return if (cleaned.isBlank()) "" else cleaned.trimEnd()
}

internal fun processLiveExcerpt(detail: String, maxChars: Int = 480): String {
    val sanitized = sanitizeProcessToolDetail(detail)
    if (sanitized.isEmpty()) return ""
    val kept = ArrayDeque<String>()
    var length = 0
    for (line in sanitized.lines().asReversed()) {
        if (length >= maxChars) break
        val clean = line.trimEnd()
        if (clean.isBlank()) continue
        kept.addFirst(clean)
        length += clean.length + 1
    }
    val text = kept.joinToString("\n")
    if (text.length <= maxChars) return text
    val step = if (maxChars >= 256) 64 else 1
    val rawStart = (text.length - maxChars).coerceAtLeast(0)
    val quantizedStart = if (step > 1) ((rawStart + step - 1) / step) * step else rawStart
    val sliceStart = safeUtf16SliceStart(text, quantizedStart.coerceIn(rawStart, text.length))
    return "…" + text.substring(sliceStart).trimStart()
}

internal fun processEntryLabel(item: ConversationItem, cli: String): String = when {
    item.kind == "commandExecution" -> when (processCommandCategory(item)) {
        ProcessCategory.Read -> "read"
        ProcessCategory.Search -> "search"
        ProcessCategory.Edit -> "edit"
        else -> processToolName(item)
    }
    item.toolName.isNotBlank() -> item.toolName
    item.kind == "reasoning" -> "thought"
    item.kind == "subAgentActivity" -> "Subagent activity"
    item.kind == "compaction" -> "Compaction"
    item.kind == "tool" && item.toolKind.isBlank() -> "Tool call"
    item.kind.isBlank() && cli == "OpenCode" && item.outcome == "failed" -> "Error"
    item.kind.isBlank() -> "Tool event"
    else -> processToolName(item)
}

private data class ShellCommandPresentation(val shell: String, val script: String)

private fun shellWord(command: String, start: Int): Pair<String, Int>? {
    var at = start
    while (at < command.length && command[at].isWhitespace()) at++
    if (at == command.length) return null
    val word = StringBuilder()
    var quote: Char? = null
    var active = false
    while (at < command.length) {
        val ch = command[at++]
        when {
            quote == '\'' -> if (ch == '\'') quote = null else word.append(ch)
            ch == '\\' -> {
                if (at == command.length) return null
                val next = command[at++]
                if (quote == '"' && next !in "\"\\$`\n") word.append('\\')
                if (next != '\n') word.append(next)
                active = true
            }
            quote == '"' -> if (ch == '"') quote = null else word.append(ch)
            ch == '\'' || ch == '"' -> {
                quote = ch
                active = true
            }
            ch in ";|&<>" -> return null
            ch.isWhitespace() -> if (active) return word.toString() to at
            else -> {
                word.append(ch)
                active = true
            }
        }
    }
    if (quote != null || !active) return null
    return word.toString() to at
}

private fun unwrapShellCommand(command: String): ShellCommandPresentation? {
    if (command.length > LIBRARY_HIGHLIGHT_LIMIT) return null
    val first = shellWord(command, 0) ?: return null
    val shell = first.first.substringAfterLast('/')
    if (shell !in setOf("bash", "zsh", "sh")) return null
    val second = shellWord(command, first.second) ?: return null
    if (second.first !in setOf("-c", "-lc")) return null
    val third = shellWord(command, second.second) ?: return null
    var rest = third.second
    while (rest < command.length && command[rest].isWhitespace()) rest++
    if (rest != command.length) return null
    return ShellCommandPresentation(shell, third.first)
}

private data class TerminalJsonInput(val command: String?)

private val terminalSummaryKeys = listOf("displaySummary", "toolSummary", "description", "summary")
private val explicitToolSummaryKeys = listOf("displaySummary", "toolSummary")
private val terminalCommandKeys = listOf("command", "CommandLine", "cmd")
private val searchPrimaryKeys = listOf("pattern", "Pattern", "Query", "query", "glob", "include")
private val searchScopeKeys = listOf("path", "SearchPath", "SearchDirectory", "directory", "dir")
private val searchInputKeys = searchPrimaryKeys + searchScopeKeys
private val webSearchQueryKeys = listOf("query", "Query", "q")
private val browseUrlKeys = listOf("url", "Url", "URL", "uri")
private val filePathKeys = listOf("file_path", "filePath", "Path", "path", "TargetFile", "DirectoryPath", "AbsolutePath")
private val toolSummaryControlRegex = Regex(
    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][\\s\\S]*?(?:\\u0007|\\u001B\\\\)|[()][ -~]|[78=>])|\\u009B[0-?]*[ -/]*[@-~]|\\u009D[\\s\\S]*?(?:\\u0007|\\u009C)|[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]",
)
private val toolSummarySpaceRegex = Regex("\\s+")

private fun cleanToolSummary(raw: String): String =
    toolSummarySpaceRegex.replace(toolSummaryControlRegex.replace(raw, ""), " ").trim()

private fun parseToolInputObject(input: String, keyHints: List<String>): JSONObject? {
    if (input.length > LIBRARY_HIGHLIGHT_LIMIT) return null
    val trimmed = input.trim()
    if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
    if (keyHints.none { trimmed.contains("\"$it\"") }) return null
    return runCatching { JSONObject(trimmed) }.getOrNull()
}

private fun toolInputSummary(input: String, keys: List<String>, skipLabel: String = ""): String? {
    val obj = parseToolInputObject(input, keys) ?: return null
    for (key in keys) {
        val value = obj.opt(key)
        if (value is String) {
            val cleaned = cleanToolSummary(value)
            if (cleaned.isNotEmpty() && !cleaned.equals(skipLabel, ignoreCase = true)) return cleaned
        }
    }
    return null
}

private fun toolInputCategoryContext(input: String, category: ProcessCategory, skipLabel: String): String? {
    val target = when (category) {
        ProcessCategory.Search -> {
            val obj = parseToolInputObject(input, searchInputKeys) ?: return null
            val primary = searchPrimaryKeys.firstNotNullOfOrNull { key ->
                (obj.opt(key) as? String)?.let(::cleanToolSummary)?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            val scope = searchScopeKeys.firstNotNullOfOrNull { key ->
                (obj.opt(key) as? String)?.let(::cleanToolSummary)?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            when {
                primary.isNotEmpty() && scope.isNotEmpty() -> "$primary ($scope)"
                primary.isNotEmpty() -> primary
                scope.isNotEmpty() -> scope
                else -> ""
            }
        }
        ProcessCategory.WebSearch -> toolInputSummary(input, webSearchQueryKeys, skipLabel = skipLabel).orEmpty()
        ProcessCategory.Browse -> toolInputSummary(input, browseUrlKeys, skipLabel = skipLabel).orEmpty()
        ProcessCategory.Read, ProcessCategory.Edit -> toolInputSummary(input, filePathKeys, skipLabel = skipLabel).orEmpty()
        else -> ""
    }
    return target.takeIf { it.isNotEmpty() && !it.equals(skipLabel, ignoreCase = true) }
}

private fun toolInputCommandFirstLine(input: String): String? {
    val obj = parseToolInputObject(input, terminalCommandKeys) ?: return null
    for (key in terminalCommandKeys) {
        val value = obj.opt(key)
        if (value is String && value.isNotBlank()) {
            val unwrapped = unwrapShellCommand(value.trim())?.script ?: value
            val firstLine = unwrapped.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (!firstLine.isNullOrEmpty()) return firstLine
        }
    }
    return null
}

private fun terminalJsonInput(input: String): TerminalJsonInput? {
    if (input.length > LIBRARY_HIGHLIGHT_LIMIT) return null
    val trimmed = input.trim()
    if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    var command: String? = null
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        when {
            key == "command" || key == "CommandLine" || key == "cmd" -> if (value is String && value.isNotEmpty()) command = value
            key == "displaySummary" || key == "description" || key == "toolSummary" || key == "summary" || key == "WaitMsBeforeAsync" || key == "IsDaemon" -> Unit
            (key == "run_in_background" || key == "background" || key == "is_background") && value == false -> Unit
            value == null || value == JSONObject.NULL || value is String && value.isEmpty() -> Unit
            else -> return TerminalJsonInput(null)
        }
    }
    return TerminalJsonInput(command)
}

private fun jsonValueHasMultilineString(value: Any?): Boolean = when (value) {
    is String -> '\n' in value || '\r' in value
    is JSONObject -> {
        var found = false
        val keys = value.keys()
        while (keys.hasNext() && !found) {
            found = jsonValueHasMultilineString(value.opt(keys.next()))
        }
        found
    }
    is JSONArray -> (0 until value.length()).any { jsonValueHasMultilineString(value.opt(it)) }
    else -> false
}

private fun appendFormattedJsonValue(out: StringBuilder, value: Any?, indent: String) {
    when (value) {
        null, JSONObject.NULL -> out.append("null")
        is String -> {
            val normalized = value.replace("\r\n", "\n").replace("\r", "\n")
            if ('\n' in normalized) {
                val childIndent = "$indent  "
                normalized.split('\n').forEach { line ->
                    out.append('\n').append(childIndent).append(line)
                }
            } else {
                out.append(normalized)
            }
        }
        is JSONObject -> {
            if (value.length() == 0) {
                out.append("{}")
            } else if (!jsonValueHasMultilineString(value)) {
                out.append(value.toString())
            } else {
                val childIndent = "$indent  "
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    out.append('\n').append(childIndent).append(key).append(':')
                    if (child is String && ('\n' in child || '\r' in child)) {
                        appendFormattedJsonValue(out, child, childIndent)
                    } else if ((child is JSONObject || child is JSONArray) && jsonValueHasMultilineString(child)) {
                        appendFormattedJsonValue(out, child, childIndent)
                    } else {
                        out.append(' ')
                        appendFormattedJsonValue(out, child, childIndent)
                    }
                }
            }
        }
        is JSONArray -> {
            if (value.length() == 0) {
                out.append("[]")
            } else if (!jsonValueHasMultilineString(value)) {
                out.append(value.toString())
            } else {
                val childIndent = "$indent  "
                for (i in 0 until value.length()) {
                    val elem = value.opt(i)
                    out.append('\n').append(childIndent).append('-')
                    if (elem is String && ('\n' in elem || '\r' in elem)) {
                        appendFormattedJsonValue(out, elem, childIndent)
                    } else if ((elem is JSONObject || elem is JSONArray) && jsonValueHasMultilineString(elem)) {
                        appendFormattedJsonValue(out, elem, childIndent)
                    } else {
                        out.append(' ')
                        appendFormattedJsonValue(out, elem, childIndent)
                    }
                }
            }
        }
        else -> out.append(value.toString())
    }
}

internal fun formatMultilineJsonObject(obj: JSONObject, maxChars: Int = LIBRARY_HIGHLIGHT_LIMIT): String? {
    if (obj.length() == 0 || !jsonValueHasMultilineString(obj)) return null
    val rawCheck = obj.toString()
    if (rawCheck.length > maxChars) return null
    val out = StringBuilder(rawCheck.length)
    val keys = obj.keys()
    var first = true
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        if (!first) out.append('\n')
        first = false
        out.append(key).append(':')
        if (value is String && ('\n' in value || '\r' in value)) {
            appendFormattedJsonValue(out, value, "")
        } else if ((value is JSONObject || value is JSONArray) && jsonValueHasMultilineString(value)) {
            appendFormattedJsonValue(out, value, "")
        } else {
            out.append(' ')
            appendFormattedJsonValue(out, value, "")
        }
    }
    return out.toString()
}

internal fun formatMultilineJsonParameters(input: String): String? {
    if (input.length > LIBRARY_HIGHLIGHT_LIMIT) return null
    val trimmed = input.trim()
    if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    return formatMultilineJsonObject(obj, LIBRARY_HIGHLIGHT_LIMIT)
}

internal data class ProcessInputPresentation(
    val code: String,
    val language: String,
    val caption: String,
)

internal fun processInputPresentation(item: ConversationItem, cli: String): ProcessInputPresentation {
    val category = processCategory(item, cli)
    if (category != ProcessCategory.Terminal && (item.kind != "commandExecution" || category == ProcessCategory.Read)) {
        formatMultilineJsonParameters(item.input)?.let { expanded ->
            return ProcessInputPresentation(expanded, "config", "Parameters")
        }
        return ProcessInputPresentation(item.input, tr("参数", "params"), "Parameters")
    }
    val trimmed = item.input.trim()
    val objectInput = trimmed.length >= 2 && trimmed.first() == '{' && trimmed.last() == '}'
    if (item.input.length > LIBRARY_HIGHLIGHT_LIMIT && objectInput) {
        return ProcessInputPresentation(item.input, "json", "Parameters")
    }
    val json = terminalJsonInput(item.input)
    if (json != null && json.command == null) {
        formatMultilineJsonParameters(item.input)?.let { expanded ->
            return ProcessInputPresentation(expanded, "config", "Parameters")
        }
        return ProcessInputPresentation(item.input, "json", "Parameters")
    }
    val command = json?.command ?: item.input
    val wrapped = unwrapShellCommand(command)
    return ProcessInputPresentation(wrapped?.script ?: command, "shell", wrapped?.shell ?: "shell")
}

internal fun processEntryContext(item: ConversationItem, cli: String): String {
    val label = processEntryLabel(item, cli)
    val raw = if (item.kind == "reasoning") {
        val lines = item.detail.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val first = lines.firstOrNull().orEmpty()
        val chosen = if (first.equals("Thinking Process:", ignoreCase = true)) {
            lines.drop(1).firstOrNull { it.startsWith("**") && it.endsWith("**") && it.length > 4 } ?: first
        } else first
        chosen.replace("**", "")
    } else item.text
    if (cli == "Antigravity" && item.toolName in antigravitySwarmTools) {
        antigravitySwarmContext(item)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (raw == "Run $label") return ""
    }
    val category = processCategory(item, cli)
    val isTerminalCommand = category == ProcessCategory.Terminal || (item.kind == "commandExecution" && category != ProcessCategory.Read)
    if (isTerminalCommand) {
        toolInputSummary(item.input, terminalSummaryKeys, skipLabel = label)?.let { return it }
    }
    fun terminalSummary(value: String): String =
        if (!isTerminalCommand) value else unwrapShellCommand(value)?.script ?: value
    val baseContext = when {
        raw == label || raw in setOf("工具", "工具调用", "Tool", "Tool call") -> ""
        item.kind == "mcpToolCall" && raw.trim() == "·" -> ""
        raw.startsWith("$label · ") -> terminalSummary(raw.removePrefix("$label · ").trim())
        raw.startsWith("$label:") -> terminalSummary(raw.removePrefix("$label:").trim())
        raw.endsWith(" · $label") -> terminalSummary(raw.removeSuffix(" · $label").trim())
        else -> when (item.kind) {
            "webSearch" -> raw.removePrefix("搜索 · ").removePrefix("Search · ")
            "fileChange" -> raw.takeUnless { it == "文件变更" || it == "File changes" }.orEmpty()
            "imageView" -> raw.takeUnless { it == "查看图片" || it == "View image" }.orEmpty()
            "imageGeneration" -> raw.takeUnless { it == "生成图片" || it == "Generate image" }.orEmpty()
            "subAgentActivity" -> if (cli == "Codex" && (raw == "子代理活动" || raw == "Sub-agent activity")) "" else raw
            "compaction" -> if (cli == "OpenCode" && (raw == "上下文压缩" || raw == "Context compaction")) "" else raw
            "commandExecution" -> if (cli == "Codex" && item.input.isBlank() && (raw == "命令" || raw == "Command")) "" else raw
            "collabAgentToolCall" -> if (cli == "Codex") when (raw) {
                "分派子代理" -> "Spawn subagent"
                "等待子代理" -> "Wait for subagent"
                "联系子代理" -> "Contact subagent"
                "继续子代理" -> "Resume subagent"
                "关闭子代理" -> "Close subagent"
                "中断子代理" -> "Interrupt subagent"
                "查看子代理" -> "List subagents"
                "子代理操作" -> "Subagent operation"
                else -> raw
            } else raw
            "" -> if ((cli == "OpenCode" && item.outcome == "failed" && (raw == "执行失败" || raw == "Execution failed")) ||
                (cli == "Codex" && (raw == "工具事件" || raw == "Tool event"))) "" else raw
            else -> raw
        }.let(::terminalSummary)
    }
    if (isTerminalCommand) {
        return baseContext.ifBlank { toolInputCommandFirstLine(item.input).orEmpty() }
    }
    if (item.kind != "reasoning" && (baseContext.isBlank() || baseContext == "Run $label")) {
        toolInputSummary(item.input, explicitToolSummaryKeys, skipLabel = label)?.let { return it }
        toolInputCategoryContext(item.input, category, skipLabel = label)?.let { return it }
    }
    return baseContext
}

internal fun processInputLanguage(item: ConversationItem, cli: String): String = processInputPresentation(item, cli).language

internal fun processOutputLanguage(item: ConversationItem): String = when {
    item.outcome == "failed" -> tr("output · 错误", "output · error")
    item.kind == "fileChange" -> "diff"
    else -> "output"
}

internal fun processInputCaption(item: ConversationItem, cli: String): String = processInputPresentation(item, cli).caption

internal fun processOutputCaption(item: ConversationItem): String = when {
    item.outcome == "failed" -> "Error"
    item.kind == "fileChange" -> "Diff"
    else -> "Output"
}

internal data class ReadCodeLine(val lineNumber: Int, val text: String)

internal data class ProcessReadPreview(
    val filePath: String,
    val fileName: String,
    val parentDir: String,
    val language: String,
    val rangeLabel: String,
    val cleanText: String,
    val lines: List<ReadCodeLine>,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val resolveFromFile: Boolean = true,
    val sourceLinesKnown: Boolean = true,
)

internal enum class DiffLineKind { CONTEXT, DELETION, ADDITION }

internal data class DiffLine(
    val kind: DiffLineKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

internal data class ProcessEditPreview(
    val filePath: String,
    val fileName: String,
    val parentDir: String,
    val language: String,
    val addedCount: Int,
    val removedCount: Int,
    val diffLines: List<DiffLine>,
    val hunks: List<List<DiffLine>> = if (diffLines.isEmpty()) emptyList() else listOf(diffLines),
    val hasRealLineNumbers: Boolean = false,
)

internal data class ProcessEditHunk(
    val headerLabel: String,
    val diffLines: List<DiffLine>,
)

internal data class MergedProcessEdit(
    val id: String,
    val entries: List<ConversationItem>,
    val toolLabel: String,
    val filePath: String,
    val fileName: String,
    val parentDir: String,
    val language: String,
    val addedCount: Int,
    val removedCount: Int,
    val hunks: List<ProcessEditHunk>,
    val editCount: Int,
    val outcome: String,
)

internal const val MAX_PROCESS_PREVIEW_CHARS = 512 * 1024
internal const val MAX_PROCESS_DIFF_LINES = 5000
internal const val MAX_PROCESS_DISPLAY_ROWS = 400

private val showingLinesRegex = Regex("""^Showing lines (\d+)-(\d+) of (\d+)""")
private val bracketShowingLinesRegex = Regex("""^\[Showing lines (\d+)-(\d+) of (\d+).*]$""")
private val piMoreLinesFooterRegex = Regex("""^\[\d+ more lines in file\. Use offset=\d+ to continue\.]$""")
private val openCodeReadFileRegex = Regex("""^Read file .+, lines? (\d+)(?:-(\d+))?$""")
private val openCodeReadDirRegex = Regex("""^Read directory .+, entries? (\d+)(?:-(\d+))?$""")
private val numberedLineRegex = Regex("""^\s*(\d+)(?::\s?|\t|→)(.*)$""")
private val unifiedHunkRegex = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
private val startingAtLineRegex = Regex("""starting at line (\d+)""")

private fun isReadWrapperHeaderLine(trimmed: String): Boolean =
    trimmed.startsWith("Created At:") ||
        trimmed.startsWith("Completed At:") ||
        trimmed == "<file>" ||
        trimmed == "<content>" ||
        trimmed == "<entries>" ||
        (trimmed.startsWith("<path>") && trimmed.endsWith("</path>")) ||
        (trimmed.startsWith("<type>") && trimmed.endsWith("</type>"))

private fun isReadWrapperFooterLine(trimmed: String): Boolean =
    trimmed.startsWith("[Output truncated") ||
        trimmed.startsWith("(End of file") ||
        trimmed.startsWith("(Results are truncated") ||
        bracketShowingLinesRegex.matches(trimmed) ||
        piMoreLinesFooterRegex.matches(trimmed) ||
        trimmed == "</file>" ||
        trimmed == "</content>" ||
        trimmed == "</entries>"

private fun splitFileDisplay(rawPath: String, fallback: String): Triple<String, String, String> {
    val clean = rawPath.trim().removePrefix("file://").trimEnd('/')
    if (clean.isBlank()) return Triple("", fallback, "")
    val name = clean.substringAfterLast('/').ifBlank { clean }
    val parentRaw = clean.substringBeforeLast('/', "")
    val parent = if (parentRaw.isBlank()) "" else {
        val segs = parentRaw.trim('/').split('/').filter { it.isNotBlank() }
        when {
            segs.isEmpty() -> "/"
            segs.size == 1 -> "${segs[0]}/"
            else -> "${segs.takeLast(2).joinToString("/")}/"
        }
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    return Triple(clean, name, parent to ext).let { Triple(clean, name, parent) }
}

private fun extractPathFromItem(item: ConversationItem): String {
    if (item.input.isNotBlank()) {
        runCatching {
            val json = JSONObject(item.input)
            for (key in listOf("Path", "path", "file_path", "filePath", "target_file", "file")) {
                val v = json.optString(key, "").trim()
                if (v.isNotBlank()) return v
            }
        }
    }
    val text = item.text.trim()
    for (prefix in listOf("File ", "Read ", "Edit ", "Write ", "读取 ", "编辑 ")) {
        if (text.startsWith(prefix)) {
            val candidate = text.removePrefix(prefix).trim()
            if (candidate.isNotBlank()) return candidate
        }
    }
    if (text.contains('/') || text.contains('.')) {
        val token = text.split(Regex("\\s+")).lastOrNull { it.contains('/') || it.contains('.') }.orEmpty()
        if (token.isNotBlank()) return token
    }
    return ""
}

internal data class ProcessReadTabHeader(
    val fileName: String,
    val rangeLabel: String,
)

private fun countLinesInRange(text: String, start: Int, end: Int, maxLines: Int): Int {
    if (end <= start) return 0
    var count = 1
    var pos = start
    while (pos < end && count < maxLines) {
        val ch = text[pos]
        if (ch == '\n') {
            count++
        } else if (ch == '\r') {
            count++
            if (pos + 1 < end && text[pos + 1] == '\n') pos++
        }
        pos++
    }
    return count
}

private fun nextLineBounds(text: String, pos: Int, end: Int): Triple<Int, Int, Int> {
    var i = pos
    while (i < end && text[i] != '\n' && text[i] != '\r') i++
    var next = i
    if (next < end && text[next] == '\r') next++
    if (next < end && text[next] == '\n') next++
    return Triple(pos, i, next)
}

private fun prevLineBounds(text: String, start: Int, end: Int): Triple<Int, Int, Int> {
    var lineEnd = end
    var prevEnd = end
    if (lineEnd > start && text[lineEnd - 1] == '\n') {
        lineEnd--
        prevEnd = lineEnd
        if (lineEnd > start && text[lineEnd - 1] == '\r') {
            lineEnd--
            prevEnd = lineEnd
        }
    } else if (lineEnd > start && text[lineEnd - 1] == '\r') {
        lineEnd--
        prevEnd = lineEnd
    }
    var lineStart = lineEnd
    while (lineStart > start && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
    return Triple(lineStart, lineEnd, if (lineStart < prevEnd) lineStart else prevEnd)
}

internal fun parseProcessReadTabHeader(item: ConversationItem, cli: String): ProcessReadTabHeader {
    if (item.kind == "commandExecution") {
        val source = commandReadSource(item)
            ?: return ProcessReadTabHeader(
                fileName = if (item.commandActions.size > 1) tr("合并读取输出", "Combined read output") else tr("读取输出", "Read output"),
                rangeLabel = "",
            )
        val fileName = source.path.substringAfterLast('/')
        var end = minOf(item.detail.length, MAX_PROCESS_PREVIEW_CHARS)
        if (end > 0 && item.detail[end - 1] == '\n') end--
        if (end <= 0) return ProcessReadTabHeader(fileName, "")
        val lineCount = countLinesInRange(item.detail, 0, end, MAX_PROCESS_DIFF_LINES)
        var firstNum = source.startLine
        var lastNum = source.startLine + lineCount - 1
        if (source.numbered) {
            val numberedTab = Regex("""^\s*([0-9]+)\t.*$""")
            val (fs, fe, _) = nextLineBounds(item.detail, 0, end)
            numberedTab.matchEntire(item.detail.substring(fs, fe))?.groupValues?.get(1)?.toIntOrNull()?.let { firstNum = it }
            if (lineCount < MAX_PROCESS_DIFF_LINES) {
                val (ls, le, _) = prevLineBounds(item.detail, 0, end)
                numberedTab.matchEntire(item.detail.substring(ls, le))?.groupValues?.get(1)?.toIntOrNull()?.let { lastNum = it }
            }
        }
        return ProcessReadTabHeader(fileName, "L$firstNum-$lastNum")
    }
    val rawPath = extractPathFromItem(item)
    val fileName = splitFileDisplay(rawPath, processEntryLabel(item, cli)).second
    var startLine: Int? = null
    var endLine: Int? = null
    if (item.input.isNotBlank()) {
        runCatching {
            val json = JSONObject(item.input)
            val s = json.optInt("StartLine", json.optInt("start_line", json.optInt("offset", -1)))
            val e = json.optInt("EndLine", json.optInt("end_line", -1))
            val limit = json.optInt("limit", -1)
            if (s > 0) startLine = s
            if (e > 0) endLine = e else if (s > 0 && limit > 0) endLine = (s.toLong() + limit.toLong() - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    val detail = item.detail
    val detailLen = minOf(detail.length, MAX_PROCESS_PREVIEW_CHARS)
    var bodyStart = 0
    var strippedHeader = false
    var headerSteps = 0
    while (bodyStart < detailLen && headerSteps < 16) {
        headerSteps++
        val (ls, le, next) = nextLineBounds(detail, bodyStart, detailLen)
        val trimmed = detail.substring(ls, le).trim()
        if (strippedHeader && trimmed.isEmpty()) {
            bodyStart = next
            continue
        }
        if (isReadWrapperHeaderLine(trimmed)) {
            strippedHeader = true
            bodyStart = next
            continue
        }
        val m = showingLinesRegex.find(trimmed)
            ?: openCodeReadFileRegex.find(trimmed)
            ?: openCodeReadDirRegex.find(trimmed)
        if (m != null) {
            val parsedStart = m.groupValues[1].toIntOrNull()
            val parsedEnd = m.groupValues[2].toIntOrNull() ?: parsedStart
            startLine = parsedStart ?: startLine
            endLine = parsedEnd ?: endLine
            strippedHeader = true
            bodyStart = next
            continue
        }
        break
    }
    var bodyEnd = detailLen
    var strippedFooter = false
    var footerSteps = 0
    while (bodyEnd > bodyStart && footerSteps < 8) {
        footerSteps++
        val (ls, le, prev) = prevLineBounds(detail, bodyStart, bodyEnd)
        val tail = detail.substring(ls, le).trim()
        bracketShowingLinesRegex.find(tail)?.let { m ->
            val parsedStart = m.groupValues[1].toIntOrNull()
            val parsedEnd = m.groupValues[2].toIntOrNull() ?: parsedStart
            startLine = parsedStart ?: startLine
            endLine = parsedEnd ?: endLine
        }
        if (isReadWrapperFooterLine(tail)) {
            strippedFooter = true
            bodyEnd = prev
            continue
        }
        if (strippedFooter && tail.isEmpty()) {
            bodyEnd = prev
            continue
        }
        break
    }
    if (startLine == null && bodyEnd > bodyStart) {
        val (fls, fle, _) = nextLineBounds(detail, bodyStart, bodyEnd)
        val firstLine = detail.substring(fls, fle)
        val firstMatch = numberedLineRegex.matchEntire(firstLine)
        val lineCount = countLinesInRange(detail, bodyStart, bodyEnd, MAX_PROCESS_DIFF_LINES)
        if (firstMatch != null) {
            val firstNum = firstMatch.groupValues[1].toIntOrNull() ?: 1
            startLine = firstNum
            var lastNum = firstNum + (lineCount - 1).coerceAtLeast(0)
            if (lineCount < MAX_PROCESS_DIFF_LINES) {
                val (lls, lle, _) = prevLineBounds(detail, bodyStart, bodyEnd)
                numberedLineRegex.matchEntire(detail.substring(lls, lle))?.groupValues?.get(1)?.toIntOrNull()?.let { lastNum = it }
            }
            endLine = lastNum
        } else {
            startLine = 1
            endLine = lineCount.coerceAtLeast(1)
        }
    }
    val rangeLabel = when {
        startLine != null && endLine != null && endLine >= startLine -> "L$startLine-$endLine"
        startLine != null -> "L$startLine"
        else -> ""
    }
    return ProcessReadTabHeader(fileName, rangeLabel)
}

internal fun parseProcessReadFileName(item: ConversationItem, cli: String): String {
    if (item.kind == "commandExecution") return commandReadSource(item)?.path?.substringAfterLast('/').orEmpty()
    val rawPath = extractPathFromItem(item)
    return splitFileDisplay(rawPath, processEntryLabel(item, cli)).second
}

internal fun parseProcessReadPreview(item: ConversationItem, cli: String): ProcessReadPreview {
    if (item.kind == "commandExecution") {
        return commandReadPreview(item) ?: ProcessReadPreview("", "", "", "", "", "", emptyList(), resolveFromFile = false)
    }
    val rawPath = extractPathFromItem(item)
    val (cleanPath, fileName, parentDir) = splitFileDisplay(rawPath, processEntryLabel(item, cli))
    val ext = resolveFileCodeLanguage(cleanPath.ifBlank { fileName })
    var startLine: Int? = null
    var endLine: Int? = null
    if (item.input.isNotBlank()) {
        runCatching {
            val json = JSONObject(item.input)
            val s = json.optInt("StartLine", json.optInt("start_line", json.optInt("offset", -1)))
            val e = json.optInt("EndLine", json.optInt("end_line", -1))
            val limit = json.optInt("limit", -1)
            if (s > 0) startLine = s
            if (e > 0) endLine = e else if (s > 0 && limit > 0) endLine = (s.toLong() + limit.toLong() - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    val boundedDetail = safeUtf16Prefix(item.detail, MAX_PROCESS_PREVIEW_CHARS)
    val rawLines = boundedDetail.lines()
    var bodyStartIndex = 0
    var strippedHeader = false
    while (bodyStartIndex < rawLines.size) {
        val line = rawLines[bodyStartIndex].trim()
        if (strippedHeader && line.isEmpty()) {
            bodyStartIndex++
            continue
        }
        if (isReadWrapperHeaderLine(line)) {
            strippedHeader = true
            bodyStartIndex++
            continue
        }
        val m = showingLinesRegex.find(line)
            ?: openCodeReadFileRegex.find(line)
            ?: openCodeReadDirRegex.find(line)
        if (m != null) {
            val parsedStart = m.groupValues[1].toIntOrNull()
            val parsedEnd = m.groupValues[2].toIntOrNull() ?: parsedStart
            startLine = parsedStart ?: startLine
            endLine = parsedEnd ?: endLine
            strippedHeader = true
            bodyStartIndex++
            continue
        }
        break
    }
    var bodyEndIndex = rawLines.size
    var strippedFooter = false
    while (bodyEndIndex > bodyStartIndex) {
        val tail = rawLines[bodyEndIndex - 1].trim()
        bracketShowingLinesRegex.find(tail)?.let { m ->
            val parsedStart = m.groupValues[1].toIntOrNull()
            val parsedEnd = m.groupValues[2].toIntOrNull() ?: parsedStart
            startLine = parsedStart ?: startLine
            endLine = parsedEnd ?: endLine
        }
        if (isReadWrapperFooterLine(tail)) {
            strippedFooter = true
            bodyEndIndex--
            continue
        }
        if (strippedFooter && tail.isEmpty()) {
            bodyEndIndex--
            continue
        }
        break
    }
    val rawBodyLines = rawLines.subList(bodyStartIndex, bodyEndIndex)
    val bodyLines = if (rawBodyLines.size > MAX_PROCESS_DIFF_LINES) rawBodyLines.subList(0, MAX_PROCESS_DIFF_LINES) else rawBodyLines
    val nonBlank = bodyLines.filter { it.isNotBlank() }
    val numberedCount = nonBlank.count { numberedLineRegex.matches(it) }
    val hasNumberPrefix = numberedCount > 0 && (
        numberedCount == nonBlank.size ||
            (numberedLineRegex.matches(nonBlank.first()) && numberedCount * 5 >= nonBlank.size * 4)
        )
    val parsedLines = if (hasNumberPrefix) {
        var lastNum = (startLine ?: 1) - 1
        bodyLines.map { line ->
            val m = numberedLineRegex.matchEntire(line)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: (lastNum + 1)
                lastNum = n
                ReadCodeLine(n, m.groupValues[2])
            } else {
                lastNum += 1
                ReadCodeLine(lastNum, line)
            }
        }
    } else {
        val base = startLine ?: 1
        bodyLines.mapIndexed { idx, line -> ReadCodeLine(base + idx, line) }
    }
    if (startLine == null && parsedLines.isNotEmpty()) {
        startLine = parsedLines.first().lineNumber
        endLine = parsedLines.last().lineNumber
    }
    val rangeLabel = when {
        startLine != null && endLine != null && endLine >= startLine -> "L$startLine-$endLine"
        startLine != null -> "L$startLine"
        else -> ""
    }
    val cleanText = parsedLines.joinToString("\n") { it.text }
    val resolvedLang = resolveFileCodeLanguage(cleanPath.ifBlank { fileName }, cleanText).ifBlank { ext }
    return ProcessReadPreview(
        filePath = cleanPath,
        fileName = fileName,
        parentDir = parentDir,
        language = resolvedLang,
        rangeLabel = rangeLabel,
        cleanText = cleanText,
        lines = parsedLines,
        startLine = startLine,
        endLine = endLine,
    )
}

internal fun hasProcessReadPreview(item: ConversationItem): Boolean =
    item.kind != "commandExecution" || processCommandCategory(item) == ProcessCategory.Read

internal fun hasSingleFileReadPreview(item: ConversationItem): Boolean =
    item.kind != "commandExecution" || commandReadSource(item) != null

internal fun populateReadPreviewFromText(preview: ProcessReadPreview, rawFileText: String): ProcessReadPreview {
    if (rawFileText.isEmpty()) return preview
    val allLines = rawFileText.lines()
    if (allLines.isEmpty()) return preview
    val s = (preview.startLine ?: 1).coerceIn(1, allLines.size)
    val e = (preview.endLine ?: (s + 399)).coerceIn(s, allLines.size)
    val sliced = allLines.subList(s - 1, e)
    val codeLines = sliced.mapIndexed { idx, line -> ReadCodeLine(s + idx, line) }
    val clean = sliced.joinToString("\n")
    val range = preview.rangeLabel.ifBlank {
        if (preview.startLine != null || preview.endLine != null || e < allLines.size) "L$s-$e" else ""
    }
    val resolvedLang = preview.language.ifBlank {
        resolveFileCodeLanguage(preview.filePath.ifBlank { preview.fileName }, rawFileText)
    }
    return preview.copy(
        language = resolvedLang,
        rangeLabel = range,
        cleanText = clean,
        lines = codeLines,
        startLine = s,
        endLine = e,
    )
}

private fun findPatienceAnchors(
    oldLines: List<String>,
    oStart: Int,
    oEnd: Int,
    newLines: List<String>,
    nStart: Int,
    nEnd: Int,
): List<Pair<Int, Int>> {
    val oldUnique = HashMap<String, Int>((oEnd - oStart).coerceAtLeast(16))
    for (i in oStart until oEnd) {
        val s = oldLines[i]
        if (s.isBlank()) continue
        oldUnique[s] = if (oldUnique.containsKey(s)) -1 else i
    }
    val newUnique = HashMap<String, Int>((nEnd - nStart).coerceAtLeast(16))
    for (j in nStart until nEnd) {
        val s = newLines[j]
        if (s.isBlank() || oldUnique[s] ?: -1 < 0) continue
        newUnique[s] = if (newUnique.containsKey(s)) -1 else j
    }
    val pairs = ArrayList<Pair<Int, Int>>()
    for (i in oStart until oEnd) {
        val s = oldLines[i]
        if (oldUnique[s] == i) {
            val j = newUnique[s] ?: -1
            if (j >= 0) pairs += (i to j)
        }
    }
    if (pairs.isEmpty()) return emptyList()

    val k = pairs.size
    val tails = IntArray(k)
    val tailIdx = IntArray(k)
    val prevIdx = IntArray(k) { -1 }
    var len = 0
    for (idx in 0 until k) {
        val nVal = pairs[idx].second
        var lo = 0
        var hi = len
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tails[mid] < nVal) lo = mid + 1 else hi = mid
        }
        tails[lo] = nVal
        tailIdx[lo] = idx
        if (lo > 0) prevIdx[idx] = tailIdx[lo - 1]
        if (lo == len) len++
    }
    val lis = ArrayList<Pair<Int, Int>>(len)
    var cur = tailIdx[len - 1]
    while (cur >= 0) {
        lis += pairs[cur]
        cur = prevIdx[cur]
    }
    lis.reverse()
    return lis
}

private fun computeFullLineDiff(
    oldLines: List<String>,
    newLines: List<String>,
    baseLineOffset: Int = 0,
): List<DiffLine> {
    if (oldLines.isEmpty() && newLines.isEmpty()) return emptyList()
    val out = ArrayList<DiffLine>(oldLines.size + newLines.size)

    fun diffSlice(oStart: Int, oEnd: Int, nStart: Int, nEnd: Int, depth: Int) {
        var os = oStart
        var ns = nStart
        while (os < oEnd && ns < nEnd && oldLines[os] == newLines[ns]) {
            out += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + os + 1, baseLineOffset + ns + 1, oldLines[os])
            os++
            ns++
        }
        var oe = oEnd
        var ne = nEnd
        while (oe > os && ne > ns && oldLines[oe - 1] == newLines[ne - 1]) {
            oe--
            ne--
        }

        val rN = oe - os
        val rM = ne - ns
        if (rN == 0) {
            for (j in ns until ne) {
                out += DiffLine(DiffLineKind.ADDITION, null, baseLineOffset + j + 1, newLines[j])
            }
        } else if (rM == 0) {
            for (i in os until oe) {
                out += DiffLine(DiffLineKind.DELETION, baseLineOffset + i + 1, null, oldLines[i])
            }
        } else if (rN.toLong() * rM.toLong() <= 200_000L) {
            val dp = Array(rN + 1) { IntArray(rM + 1) }
            for (i in rN - 1 downTo 0) {
                val oLine = oldLines[os + i]
                val row = dp[i]
                val nextRow = dp[i + 1]
                for (j in rM - 1 downTo 0) {
                    row[j] = if (oLine == newLines[ns + j]) 1 + nextRow[j + 1] else maxOf(nextRow[j], row[j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < rN && j < rM) {
                if (oldLines[os + i] == newLines[ns + j]) {
                    out += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + os + i + 1, baseLineOffset + ns + j + 1, oldLines[os + i])
                    i++
                    j++
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    out += DiffLine(DiffLineKind.DELETION, baseLineOffset + os + i + 1, null, oldLines[os + i])
                    i++
                } else {
                    out += DiffLine(DiffLineKind.ADDITION, null, baseLineOffset + ns + j + 1, newLines[ns + j])
                    j++
                }
            }
            while (i < rN) {
                out += DiffLine(DiffLineKind.DELETION, baseLineOffset + os + i + 1, null, oldLines[os + i])
                i++
            }
            while (j < rM) {
                out += DiffLine(DiffLineKind.ADDITION, null, baseLineOffset + ns + j + 1, newLines[ns + j])
                j++
            }
        } else {
            val anchors = if (depth < 12) findPatienceAnchors(oldLines, os, oe, newLines, ns, ne) else emptyList()
            if (anchors.isNotEmpty()) {
                var curO = os
                var curN = ns
                for ((ao, an) in anchors) {
                    diffSlice(curO, ao, curN, an, depth + 1)
                    out += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + ao + 1, baseLineOffset + an + 1, oldLines[ao])
                    curO = ao + 1
                    curN = an + 1
                }
                diffSlice(curO, oe, curN, ne, depth + 1)
            } else {
                for (i in os until oe) {
                    out += DiffLine(DiffLineKind.DELETION, baseLineOffset + i + 1, null, oldLines[i])
                }
                for (j in ns until ne) {
                    out += DiffLine(DiffLineKind.ADDITION, null, baseLineOffset + j + 1, newLines[j])
                }
            }
        }

        var so = oe
        var sn = ne
        while (so < oEnd && sn < nEnd) {
            out += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + so + 1, baseLineOffset + sn + 1, oldLines[so])
            so++
            sn++
        }
    }

    diffSlice(0, oldLines.size, 0, newLines.size, 0)
    return out
}

private fun computeLineDiffHunks(
    oldText: String,
    newText: String,
    baseLineOffset: Int = 0,
    contextRadius: Int = 3,
): List<List<DiffLine>> {
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
    val newLines = if (newText.isEmpty()) emptyList() else newText.lines()
    if (oldText.length + newText.length > MAX_PROCESS_PREVIEW_CHARS ||
        oldLines.size > MAX_PROCESS_DIFF_LINES ||
        newLines.size > MAX_PROCESS_DIFF_LINES
    ) {
        var os = 0
        var ns = 0
        while (os < oldLines.size && ns < newLines.size && oldLines[os] == newLines[ns]) {
            os++
            ns++
        }
        var oe = oldLines.size
        var ne = newLines.size
        while (oe > os && ne > ns && oldLines[oe - 1] == newLines[ne - 1]) {
            oe--
            ne--
        }
        if (os == oe && ns == ne) {
            val head = oldLines.take(24).mapIndexed { idx, line ->
                DiffLine(DiffLineKind.CONTEXT, baseLineOffset + idx + 1, baseLineOffset + idx + 1, line)
            }
            return if (head.isEmpty()) emptyList() else listOf(head)
        }
        val fallback = ArrayList<DiffLine>((oe - os) + (ne - ns) + 6)
        val ctxBefore = (os - contextRadius).coerceAtLeast(0)
        for (k in ctxBefore until os) {
            fallback += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + k + 1, baseLineOffset + k + 1, oldLines[k])
        }
        for (i in os until oe) {
            fallback += DiffLine(DiffLineKind.DELETION, baseLineOffset + i + 1, null, oldLines[i])
        }
        for (j in ns until ne) {
            fallback += DiffLine(DiffLineKind.ADDITION, null, baseLineOffset + j + 1, newLines[j])
        }
        val ctxAfterEnd = (oe + contextRadius).coerceAtMost(oldLines.size)
        for (k in oe until ctxAfterEnd) {
            val nIdx = ne + (k - oe)
            fallback += DiffLine(DiffLineKind.CONTEXT, baseLineOffset + k + 1, baseLineOffset + nIdx + 1, oldLines[k])
        }
        return listOf(fallback)
    }
    val full = computeFullLineDiff(oldLines, newLines, baseLineOffset)
    if (full.isEmpty()) return emptyList()

    val changedIndices = full.indices.filter { full[it].kind != DiffLineKind.CONTEXT }
    if (changedIndices.isEmpty()) return listOf(full.take(24))

    val ranges = ArrayList<IntRange>()
    var curStart = (changedIndices[0] - contextRadius).coerceAtLeast(0)
    var curEnd = (changedIndices[0] + contextRadius).coerceAtMost(full.lastIndex)
    for (k in 1 until changedIndices.size) {
        val c = changedIndices[k]
        val s = (c - contextRadius).coerceAtLeast(0)
        val e = (c + contextRadius).coerceAtMost(full.lastIndex)
        if (s <= curEnd + 1) {
            curEnd = maxOf(curEnd, e)
        } else {
            ranges += (curStart..curEnd)
            curStart = s
            curEnd = e
        }
    }
    ranges += (curStart..curEnd)
    return ranges.map { r -> full.subList(r.first, r.last + 1) }
}

private data class ParsedUnifiedDiff(
    val path: String,
    val flatLines: List<DiffLine>,
    val hunks: List<List<DiffLine>>,
    val hasHunkHeaders: Boolean,
    val addedCount: Int,
    val removedCount: Int,
)

private fun parseUnifiedDiff(raw: String): ParsedUnifiedDiff {
    var path = ""
    val hunks = mutableListOf<MutableList<DiffLine>>()
    var currentHunk = mutableListOf<DiffLine>()
    var sawHunkHeader = false
    var oldLine = 1
    var newLine = 1
    var addedCount = 0
    var removedCount = 0
    var keptLines = 0
    val boundedRaw = safeUtf16Prefix(raw, MAX_PROCESS_PREVIEW_CHARS * 4)
    for (line in boundedRaw.lineSequence()) {
        when {
            line.startsWith("Created At:") || line.startsWith("Completed At:") -> continue
            line.startsWith("Index: ") || line.startsWith("===") -> continue
            line.startsWith("+++ b/") -> path = line.removePrefix("+++ b/").substringBefore('\t').trim()
            line.startsWith("+++ ") && path.isBlank() -> path = line.removePrefix("+++ ").substringBefore('\t').trim()
            line.startsWith("*** Update File:") -> path = line.removePrefix("*** Update File:").trim()
            line.startsWith("*** Add File:") -> path = line.removePrefix("*** Add File:").trim()
            line.startsWith("--- ") || line.startsWith("diff --git") || line.startsWith("index ") -> continue
            line.startsWith("@@") -> {
                if (keptLines < MAX_PROCESS_DIFF_LINES) {
                    if (currentHunk.isNotEmpty()) {
                        hunks += currentHunk
                        currentHunk = mutableListOf()
                    }
                    val m = unifiedHunkRegex.find(line)
                    if (m != null) {
                        sawHunkHeader = true
                        oldLine = m.groupValues[1].toIntOrNull() ?: oldLine
                        newLine = m.groupValues[2].toIntOrNull() ?: newLine
                    }
                } else if (unifiedHunkRegex.containsMatchIn(line)) {
                    sawHunkHeader = true
                }
            }
            line.startsWith("+") -> {
                addedCount++
                if (keptLines < MAX_PROCESS_DIFF_LINES) {
                    currentHunk += DiffLine(DiffLineKind.ADDITION, null, newLine++, line.substring(1))
                    keptLines++
                }
            }
            line.startsWith("-") -> {
                removedCount++
                if (keptLines < MAX_PROCESS_DIFF_LINES) {
                    currentHunk += DiffLine(DiffLineKind.DELETION, oldLine++, null, line.substring(1))
                    keptLines++
                }
            }
            line.startsWith(" ") -> {
                if (keptLines < MAX_PROCESS_DIFF_LINES) {
                    currentHunk += DiffLine(DiffLineKind.CONTEXT, oldLine++, newLine++, line.substring(1))
                    keptLines++
                }
            }
        }
    }
    if (currentHunk.isNotEmpty()) hunks += currentHunk
    return ParsedUnifiedDiff(path, hunks.flatten(), hunks, sawHunkHeader, addedCount, removedCount)
}

internal fun hasProcessEditPreview(item: ConversationItem): Boolean {
    if (item.kind == "commandExecution") return false
    val input = item.input
    if (input.contains("\"Target\"") || input.contains("\"Replacement\"") ||
        input.contains("\"old_string\"") || input.contains("\"new_string\"") ||
        input.contains("\"oldString\"") || input.contains("\"newString\"") ||
        input.contains("\"oldText\"") || input.contains("\"newText\"") ||
        input.contains("\"Content\"") || input.contains("\"content\"")
    ) return true
    val detail = item.detail
    if (detail.contains("@@ ") || detail.contains("*** Begin Patch") || detail.contains("diff --git") ||
        input.contains("@@ ") || input.contains("*** Begin Patch") || input.contains("diff --git")
    ) return true
    val raw = if (detail.contains("@@") || detail.contains("+++")) detail else "$input\n$detail"
    var hasPatchHeader = false
    var hasPlusHeader = false
    var hasMinusHeader = false
    var hasChangeLine = false
    for (line in raw.lineSequence()) {
        if (line.startsWith("@@") && unifiedHunkRegex.containsMatchIn(line)) return true
        when {
            line.startsWith("*** Update File:") || line.startsWith("*** Add File:") || line.startsWith("*** Delete File:") -> hasPatchHeader = true
            line.startsWith("+++ ") -> hasPlusHeader = true
            line.startsWith("--- ") -> hasMinusHeader = true
            (line.startsWith("+") && !line.startsWith("+++")) || (line.startsWith("-") && !line.startsWith("---")) -> hasChangeLine = true
        }
        if ((hasPatchHeader || (hasPlusHeader && hasMinusHeader)) && hasChangeLine) return true
    }
    return false
}

private data class EditPreviewCacheKey(
    val id: String,
    val outcome: String,
    val kind: String,
    val toolName: String,
    val text: String,
    val input: String,
    val detail: String,
    val cli: String,
    val langCode: String,
)

private val editPreviewCache = object : LinkedHashMap<EditPreviewCacheKey, ProcessEditPreview>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EditPreviewCacheKey, ProcessEditPreview>?) = size > 64
}

internal fun parseProcessEditPreview(item: ConversationItem, cli: String): ProcessEditPreview {
    val cacheKey = EditPreviewCacheKey(
        id = item.id,
        outcome = item.outcome,
        kind = item.kind,
        toolName = item.toolName,
        text = item.text,
        input = item.input,
        detail = item.detail,
        cli = cli,
        langCode = io.github.modao7067.luma.gui.i18n.LumaI18n.langCode(),
    )
    synchronized(editPreviewCache) { editPreviewCache[cacheKey] }?.let { return it }
    val computed = computeProcessEditPreview(item, cli)
    synchronized(editPreviewCache) { editPreviewCache[cacheKey] = computed }
    return computed
}

private fun computeProcessEditPreview(item: ConversationItem, cli: String): ProcessEditPreview {
    var rawPath = extractPathFromItem(item)
    var diffLines = emptyList<DiffLine>()
    var hunks = emptyList<List<DiffLine>>()
    var hasRealLineNumbers = false
    var exactAdded: Int? = null
    var exactRemoved: Int? = null
    val detail = item.detail
    if (detail.contains("@@ ") || detail.contains("*** Begin Patch") || detail.contains("diff --git")) {
        val parsed = parseUnifiedDiff(detail)
        if (rawPath.isBlank() && parsed.path.isNotBlank()) rawPath = parsed.path
        diffLines = parsed.flatLines
        hunks = parsed.hunks
        hasRealLineNumbers = parsed.hasHunkHeaders
        exactAdded = parsed.addedCount
        exactRemoved = parsed.removedCount
    }
    if (diffLines.isEmpty() && item.input.isNotBlank()) {
        runCatching {
            val json = JSONObject(item.input)
            val editsArray = json.optJSONArray("edits")
            val oldStr = when {
                json.has("Target") -> json.optString("Target", "")
                json.has("old_string") -> json.optString("old_string", "")
                json.has("oldString") -> json.optString("oldString", "")
                json.has("oldText") -> json.optString("oldText", "")
                else -> null
            }
            val newStr = when {
                json.has("Replacement") -> json.optString("Replacement", "")
                json.has("new_string") -> json.optString("new_string", "")
                json.has("newString") -> json.optString("newString", "")
                json.has("newText") -> json.optString("newText", "")
                else -> null
            }
            val startLineFromDetail = startingAtLineRegex.find(detail)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            val baseOffset = (startLineFromDetail ?: 1) - 1
            if (editsArray != null && editsArray.length() > 0) {
                val builtHunks = mutableListOf<List<DiffLine>>()
                for (idx in 0 until editsArray.length()) {
                    val entry = editsArray.optJSONObject(idx) ?: continue
                    val entryOld = when {
                        entry.has("oldText") -> entry.optString("oldText", "")
                        entry.has("old_string") -> entry.optString("old_string", "")
                        entry.has("oldString") -> entry.optString("oldString", "")
                        entry.has("old_str") -> entry.optString("old_str", "")
                        entry.has("Target") -> entry.optString("Target", "")
                        else -> null
                    }
                    val entryNew = when {
                        entry.has("newText") -> entry.optString("newText", "")
                        entry.has("new_string") -> entry.optString("new_string", "")
                        entry.has("newString") -> entry.optString("newString", "")
                        entry.has("new_str") -> entry.optString("new_str", "")
                        entry.has("Replacement") -> entry.optString("Replacement", "")
                        else -> null
                    }
                    if (entryOld != null || entryNew != null) {
                        builtHunks += computeLineDiffHunks(entryOld.orEmpty(), entryNew.orEmpty())
                    }
                }
                hunks = builtHunks
                diffLines = builtHunks.flatten()
            } else if (oldStr != null || newStr != null) {
                hunks = computeLineDiffHunks(oldStr.orEmpty(), newStr.orEmpty(), baseLineOffset = baseOffset)
                diffLines = hunks.flatten()
                if (startLineFromDetail != null) hasRealLineNumbers = true
            } else {
                val content = when {
                    json.has("Content") -> json.optString("Content", "")
                    json.has("content") -> json.optString("content", "")
                    else -> null
                }
                if (content != null) {
                    diffLines = content.lines().take(200).mapIndexed { idx, line ->
                        DiffLine(DiffLineKind.ADDITION, null, idx + 1, line)
                    }
                    hunks = if (diffLines.isEmpty()) emptyList() else listOf(diffLines)
                    hasRealLineNumbers = true
                }
            }
        }
    }
    if (diffLines.isEmpty()) {
        val parsed = parseUnifiedDiff(if (detail.contains("@@") || detail.contains("+++")) detail else item.input + "\n" + detail)
        if (rawPath.isBlank() && parsed.path.isNotBlank()) rawPath = parsed.path
        diffLines = parsed.flatLines
        hunks = parsed.hunks
        hasRealLineNumbers = parsed.hasHunkHeaders
        exactAdded = parsed.addedCount
        exactRemoved = parsed.removedCount
    }
    val (cleanPath, fileName, parentDir) = splitFileDisplay(rawPath, processEntryLabel(item, cli))
    val ext = resolveFileCodeLanguage(cleanPath.ifBlank { fileName })
    val added = exactAdded ?: diffLines.count { it.kind == DiffLineKind.ADDITION }
    val removed = exactRemoved ?: diffLines.count { it.kind == DiffLineKind.DELETION }
    return ProcessEditPreview(
        filePath = cleanPath,
        fileName = fileName,
        parentDir = parentDir,
        language = ext,
        addedCount = added,
        removedCount = removed,
        diffLines = diffLines,
        hunks = hunks,
        hasRealLineNumbers = hasRealLineNumbers,
    )
}

internal fun formatProcessReadBatchSummary(entries: List<ConversationItem>, cli: String): String {
    val counts = linkedMapOf<String, Int>()
    for (entry in entries) {
        val name = parseProcessReadFileName(entry, cli)
        if (name.isNotBlank()) counts[name] = (counts[name] ?: 0) + 1
    }
    return counts.entries.joinToString(", ") { (name, count) ->
        if (count > 1) "$name ×$count" else name
    }
}

internal fun mergeConsecutiveProcessEdits(entries: List<ConversationItem>, cli: String): List<MergedProcessEdit> {
    if (entries.isEmpty()) return emptyList()
    val parsed = entries.map { it to parseProcessEditPreview(it, cli) }
    val groups = mutableListOf<MutableList<Pair<ConversationItem, ProcessEditPreview>>>()
    for (pair in parsed) {
        val last = groups.lastOrNull()
        val prevPath = last?.first()?.second?.filePath.orEmpty()
        val currPath = pair.second.filePath
        if (last != null && prevPath.isNotBlank() && prevPath == currPath) {
            last += pair
        } else {
            groups += mutableListOf(pair)
        }
    }
    return groups.map { group ->
        val items = group.map { it.first }
        val firstPreview = group.first().second
        val labels = items.map { processEntryLabel(it, cli) }.distinct()
        val toolLabel = if (labels.size == 1) labels.single() else "edit"
        val rawHunks = mutableListOf<Pair<Boolean, List<DiffLine>>>()
        for ((_, preview) in group) {
            for (hunk in preview.hunks) {
                if (hunk.isNotEmpty()) rawHunks += (preview.hasRealLineNumbers to hunk)
            }
        }
        val total = rawHunks.size
        val hunks = rawHunks.mapIndexed { idx, (realLines, lines) ->
            val firstLine = lines.firstNotNullOfOrNull { it.newLine } ?: lines.firstNotNullOfOrNull { it.oldLine }
            val lastLine = lines.asReversed().firstNotNullOfOrNull { it.newLine } ?: lines.asReversed().firstNotNullOfOrNull { it.oldLine }
            val rangePrefix = if (realLines && firstLine != null) {
                if (lastLine != null && lastLine > firstLine) "L$firstLine–$lastLine · " else "L$firstLine · "
            } else ""
            val label = if (total > 1) tr("${rangePrefix}第 ${idx + 1}/$total 处", "${rangePrefix}Edit ${idx + 1}/$total") else rangePrefix.removeSuffix(" · ")
            ProcessEditHunk(label, lines)
        }
        val outcome = when {
            items.any { it.outcome == "running" } -> "running"
            items.any { it.outcome == "failed" } -> "failed"
            items.all { it.outcome == "cancelled" } -> "cancelled"
            else -> items.last().outcome
        }
        MergedProcessEdit(
            id = items.first().id,
            entries = items,
            toolLabel = toolLabel,
            filePath = firstPreview.filePath,
            fileName = firstPreview.fileName,
            parentDir = firstPreview.parentDir,
            language = firstPreview.language,
            addedCount = group.sumOf { it.second.addedCount },
            removedCount = group.sumOf { it.second.removedCount },
            hunks = hunks,
            editCount = maxOf(items.size, total),
            outcome = outcome,
        )
    }
}
