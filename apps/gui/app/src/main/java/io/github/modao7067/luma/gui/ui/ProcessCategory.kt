package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.ConversationItem
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
    "Bash" to ProcessCategory.Terminal,
    "Read" to ProcessCategory.Read,
    "Write" to ProcessCategory.Edit, "Edit" to ProcessCategory.Edit, "MultiEdit" to ProcessCategory.Edit,
    "NotebookEdit" to ProcessCategory.Edit,
    "Glob" to ProcessCategory.Search, "Grep" to ProcessCategory.Search, "WebSearch" to ProcessCategory.WebSearch,
    "WebFetch" to ProcessCategory.Browse,
)
private val antigravityToolCategories = mapOf(
    "run_command" to ProcessCategory.Terminal,
    "read_file" to ProcessCategory.Read,
    "edit_file" to ProcessCategory.Edit,
    "write_file" to ProcessCategory.Edit,
    "search_web" to ProcessCategory.WebSearch,
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
                singleLine.length > 48 -> singleLine.take(48).trimEnd() + "…"
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
    "commandExecution" -> ProcessCategory.Terminal
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
    else -> null
}

private val invocationKinds = setOf(
    "tool", "commandExecution", "fileChange", "webSearch", "imageView", "imageGeneration",
    "mcpToolCall", "dynamicToolCall", "collabAgentToolCall",
)

internal fun processToolInvocations(entries: List<ConversationItem>): List<ConversationItem> {
    val calls = linkedMapOf<Triple<String, String, Int>, ConversationItem>()
    entries.forEachIndexed { index, item ->
        if (item.role != "tool" || item.kind !in invocationKinds) return@forEachIndexed
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
private val agyCommandWrapperHeaderRegex = Regex(
    "^Command exited with code\\s+(-?\\d+)\\s*\\nCommand output:\\s*\\n?",
)

internal fun sanitizeProcessToolDetail(detail: String): String {
    if (detail.isBlank()) return ""
    val hasBgWrapper = detail.contains("Tool is running as a background task with task id:") ||
        detail.contains("Task logs are available at: file://") ||
        detail.contains("YOU MUST TAKE ONE OF THE FOLLOWING TWO ACTIONS:")
    var cleaned = if (hasBgWrapper) {
        val withoutDirective = agySystemActionDirectiveRegex.replace(detail, "")
        withoutDirective.lines()
            .filterNot { line -> agyBgMetaLineRegex.matches(line.trim()) }
            .joinToString("\n")
    } else {
        detail
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
    val sliceStart = quantizedStart.coerceIn(rawStart, text.length)
    return "…" + text.substring(sliceStart).trimStart()
}

internal fun processEntryLabel(item: ConversationItem, cli: String): String = when {
    item.toolName.isNotBlank() -> item.toolName
    item.kind == "reasoning" -> if (item.outcome == "running") "Thinking" else "Reasoning"
    item.kind == "subAgentActivity" -> "Subagent activity"
    item.kind == "compaction" -> "Compaction"
    item.kind == "tool" && item.toolKind.isBlank() -> "Tool call"
    item.kind.isBlank() && cli == "OpenCode" && item.outcome == "failed" -> "Error"
    item.kind.isBlank() -> "Tool event"
    else -> processToolName(item)
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
    if (raw == label || raw in setOf("工具", "工具调用", "Tool", "Tool call")) return ""
    if (item.kind == "mcpToolCall" && raw.trim() == "·") return ""
    if (raw.startsWith("$label · ")) return raw.removePrefix("$label · ").trim()
    if (raw.startsWith("$label:")) return raw.removePrefix("$label:").trim()
    if (raw.endsWith(" · $label")) return raw.removeSuffix(" · $label").trim()
    return when (item.kind) {
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
    }
}

internal fun processInputLanguage(item: ConversationItem, cli: String): String =
    if (item.kind == "commandExecution" ||
        (processCategory(item, cli) == ProcessCategory.Terminal && item.toolName in setOf("bash", "powershell", "shell", "Bash"))) "shell" else tr("参数", "params")

internal fun processOutputLanguage(item: ConversationItem): String = when {
    item.outcome == "failed" -> tr("output · 错误", "output · error")
    item.kind == "fileChange" -> "diff"
    else -> "output"
}

internal fun processInputCaption(item: ConversationItem, cli: String): String = "Parameters"

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

internal fun parseProcessReadFileName(item: ConversationItem, cli: String): String {
    val rawPath = extractPathFromItem(item)
    return splitFileDisplay(rawPath, processEntryLabel(item, cli)).second
}

internal fun parseProcessReadPreview(item: ConversationItem, cli: String): ProcessReadPreview {
    val rawPath = extractPathFromItem(item)
    val (cleanPath, fileName, parentDir) = splitFileDisplay(rawPath, processEntryLabel(item, cli))
    val ext = fileName.substringAfterLast('.', "").lowercase()
    var startLine: Int? = null
    var endLine: Int? = null
    if (item.input.isNotBlank()) {
        runCatching {
            val json = JSONObject(item.input)
            val s = json.optInt("StartLine", json.optInt("start_line", json.optInt("offset", -1)))
            val e = json.optInt("EndLine", json.optInt("end_line", -1))
            val limit = json.optInt("limit", -1)
            if (s > 0) startLine = s
            if (e > 0) endLine = e else if (s > 0 && limit > 0) endLine = s + limit - 1
        }
    }
    val boundedDetail = if (item.detail.length > MAX_PROCESS_PREVIEW_CHARS) item.detail.substring(0, MAX_PROCESS_PREVIEW_CHARS) else item.detail
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
    return ProcessReadPreview(
        filePath = cleanPath,
        fileName = fileName,
        parentDir = parentDir,
        language = ext,
        rangeLabel = rangeLabel,
        cleanText = cleanText,
        lines = parsedLines,
        startLine = startLine,
        endLine = endLine,
    )
}

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
    return preview.copy(
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
    val boundedRaw = if (raw.length > MAX_PROCESS_PREVIEW_CHARS * 4) raw.substring(0, MAX_PROCESS_PREVIEW_CHARS * 4) else raw
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
    val input = item.input
    if (input.contains("\"Target\"") || input.contains("\"Replacement\"") ||
        input.contains("\"old_string\"") || input.contains("\"new_string\"") ||
        input.contains("\"oldString\"") || input.contains("\"newString\"") ||
        input.contains("\"oldText\"") || input.contains("\"newText\"") ||
        input.contains("\"Content\"") || input.contains("\"content\"")
    ) return true
    val detail = item.detail
    if (detail.contains("@@ ") || detail.contains("*** Begin Patch") || detail.contains("diff --git")) return true
    val raw = if (detail.contains("@@") || detail.contains("+++")) detail else "$input\n$detail"
    return raw.lineSequence().any { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
}

internal fun parseProcessEditPreview(item: ConversationItem, cli: String): ProcessEditPreview {
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
                    if (entry.has("oldText") || entry.has("newText")) {
                        builtHunks += computeLineDiffHunks(entry.optString("oldText", ""), entry.optString("newText", ""))
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
    val ext = fileName.substringAfterLast('.', "").lowercase()
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
