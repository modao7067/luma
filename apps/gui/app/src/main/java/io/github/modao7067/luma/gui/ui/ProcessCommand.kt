package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.i18n.tr
import java.nio.file.Paths

internal data class CommandReadSource(val path: String, val startLine: Int = 1, val numbered: Boolean = false)

private data class ShellWord(val value: String, val operator: Boolean = false)

private fun shellWords(command: String): List<ShellWord>? {
    val words = mutableListOf<ShellWord>()
    val word = StringBuilder()
    var active = false
    var quote: Char? = null
    var i = 0
    fun flush() {
        if (active) words += ShellWord(word.toString())
        word.setLength(0)
        active = false
    }
    while (i < command.length) {
        val c = command[i++]
        when {
            quote == '\'' -> if (c == '\'') quote = null else word.append(c)
            c == '\\' -> {
                if (i == command.length) return null
                val next = command[i++]
                if (quote == '"' && next !in "\"\\$`\n") word.append('\\')
                if (next != '\n') word.append(next)
                active = true
            }
            c == '$' || c == '`' -> return null
            quote == '"' -> if (c == '"') quote = null else word.append(c)
            c == '\'' || c == '"' -> { quote = c; active = true }
            c == '\n' || c in ";|&<>" -> {
                flush()
                val op = if (i < command.length && command[i] == c && c in "|&<>") "$c${command[i++]}" else c.toString()
                words += ShellWord(op, true)
            }
            c.isWhitespace() -> flush()
            c in "*?[]~#(){}" -> return null
            else -> { word.append(c); active = true }
        }
    }
    if (quote != null) return null
    flush()
    return words
}

private fun commandWords(item: ConversationItem): List<ShellWord>? {
    var words = shellWords(item.input.ifBlank { item.text }.trim()) ?: return null
    if (words.size == 3 && words[0].value.substringAfterLast('/') in setOf("sh", "bash", "zsh") &&
        words[1].value in setOf("-c", "-lc")) {
        words = shellWords(words[2].value.trim()) ?: return null
    }
    return words
}

private fun isShellFileEdit(words: List<ShellWord>): Boolean {
    if (words.isEmpty() || words.any { it.operator && it.value !in setOf(">", ">>") }) return false
    if (words.withIndex().any { (i, word) ->
            word.operator && word.value in setOf(">", ">>") &&
                words.getOrNull(i + 1)?.takeUnless { it.operator }?.value?.let {
                    it !in setOf("/dev/null", "/dev/stdout", "/dev/stderr")
                } == true
        }) return true
    val args = words.drop(1).map { it.value }
    return when (words.first().value.substringAfterLast('/')) {
        "sed" -> args.any { it == "--in-place" || it.startsWith("--in-place=") || it.startsWith("-i") }
        "perl" -> args.takeWhile { it.startsWith('-') }.any { Regex("-[acnplw]*i.*").matches(it) }
        "tee" -> args.any { !it.startsWith('-') && it != "/dev/null" }
        else -> false
    }
}

private val sedPrintRange = Regex("([1-9][0-9]*)(?:,([1-9][0-9]*|\\$))?p")

private fun sedStartLine(expression: String): Int? {
    val range = sedPrintRange.matchEntire(expression) ?: return null
    val start = range.groupValues[1].toIntOrNull() ?: return null
    val endText = range.groupValues[2]
    if (endText.isNotEmpty() && endText != "$") {
        val end = endText.toIntOrNull() ?: return null
        if (end < start) return null
    }
    return start
}

private fun isHeadLineCount(args: List<String>): Boolean = when {
    args.isEmpty() -> true
    args.size == 2 && args[0] == "-n" -> args[1].toIntOrNull()?.let { it > 0 } == true
    args.size == 1 && args[0].startsWith("-n") -> args[0].removePrefix("-n").toIntOrNull()?.let { it > 0 } == true
    else -> false
}

private fun parseCommandReadSource(words: List<ShellWord>): CommandReadSource? {
    val pipe = words.indexOfFirst { it.operator }
    if (pipe >= 0) {
        if (words[pipe].value != "|" || words.drop(pipe + 1).any { it.operator }) return null
        val reader = parseCommandReadSource(words.take(pipe)) ?: return null
        val filter = words.drop(pipe + 1).map { it.value }
        val start = when (filter.firstOrNull()?.substringAfterLast('/')) {
            "sed" -> if (filter.size == 3 && filter[1] == "-n") sedStartLine(filter[2]) else null
            "head" -> if (isHeadLineCount(filter.drop(1))) 1 else null
            else -> null
        } ?: return null
        val offset = reader.startLine.toLong() + start - 1
        return if (offset <= Int.MAX_VALUE) reader.copy(startLine = offset.toInt()) else null
    }
    if (words.isEmpty()) return null
    val args = words.drop(1).map { it.value }.toMutableList()
    fun fileSource(start: Int = 1, numbered: Boolean = false): CommandReadSource? {
        if (args.firstOrNull() == "--") args.removeAt(0)
        val file = args.singleOrNull()?.takeIf { it.isNotBlank() && it != "-" && !it.startsWith('-') } ?: return null
        return CommandReadSource(file, start, numbered)
    }
    return when (words.first().value.substringAfterLast('/')) {
        "cat" -> fileSource()
        "nl" -> if (args.firstOrNull() == "-ba") { args.removeAt(0); fileSource(numbered = true) } else null
        "sed" -> {
            if (args.firstOrNull() != "-n" || args.size < 3) return null
            args.removeAt(0)
            if (args.firstOrNull() == "-e") args.removeAt(0)
            val start = sedStartLine(args.removeAt(0)) ?: return null
            fileSource(start)
        }
        "head", "tail" -> {
            val tail = words.first().value.substringAfterLast('/') == "tail"
            if (args.firstOrNull() == "-n") {
                args.removeAt(0)
                val count = args.removeFirstOrNull() ?: return null
                if (tail && !count.startsWith('+')) return null
                val n = count.removePrefix("+").toIntOrNull()?.takeIf { it > 0 } ?: return null
                fileSource(if (tail) n else 1)
            } else if (!tail) fileSource() else null
        }
        else -> null
    }
}

internal fun processCommandCategory(item: ConversationItem): ProcessCategory {
    val words = commandWords(item)
    if (words != null && isShellFileEdit(words)) return ProcessCategory.Edit
    if (item.commandActions.isNotEmpty()) {
        return when {
            item.commandActions.all { it.type == "read" || it.type == "listFiles" } -> ProcessCategory.Read
            item.commandActions.all { it.type == "search" } -> ProcessCategory.Search
            else -> ProcessCategory.Terminal
        }
    }
    return if (words != null && parseCommandReadSource(words) != null) ProcessCategory.Read else ProcessCategory.Terminal
}

internal fun commandReadSource(item: ConversationItem): CommandReadSource? {
    if (item.kind != "commandExecution" || processCommandCategory(item) != ProcessCategory.Read) return null
    if (item.commandActions.isNotEmpty() && (item.commandActions.size != 1 || item.commandActions.single().type != "read")) return null
    val source = parseCommandReadSource(commandWords(item) ?: return null) ?: return null
    fun resolve(path: String): String? = runCatching {
        val raw = Paths.get(path)
        if (raw.isAbsolute || item.commandCwd.isBlank()) raw.normalize().toString()
        else Paths.get(item.commandCwd).resolve(raw).normalize().toString()
    }.getOrNull()
    val resolved = resolve(source.path) ?: return null
    val nativePath = item.commandActions.singleOrNull()?.path?.takeIf { it.isNotBlank() }
    if (nativePath != null && resolve(nativePath) != resolved) return null
    return source.copy(path = resolved)
}

internal fun commandReadPreview(item: ConversationItem): ProcessReadPreview? {
    if (processCommandCategory(item) != ProcessCategory.Read) return null
    val source = commandReadSource(item) ?: return commandReadTranscript(item)
    val path = source.path
    val name = path.substringAfterLast('/')
    val parent = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "${it.trim('/').split('/').takeLast(2).joinToString("/")}/" }.orEmpty()
    val text = item.detail.take(MAX_PROCESS_PREVIEW_CHARS).removeSuffix("\n")
    val numberedLine = Regex("^\\s*([0-9]+)\\t(.*)$")
    val lines = if (text.isEmpty()) emptyList() else text.lines().take(MAX_PROCESS_DIFF_LINES).mapIndexed { i, line ->
        val match = if (source.numbered) numberedLine.matchEntire(line) else null
        ReadCodeLine(match?.groupValues?.get(1)?.toIntOrNull() ?: (source.startLine + i), match?.groupValues?.get(2) ?: line)
    }
    val end = lines.lastOrNull()?.lineNumber
    val clean = lines.joinToString("\n") { it.text }
    return ProcessReadPreview(path, name, parent, resolveFileCodeLanguage(path.ifBlank { name }, clean),
        if (end == null) "" else "L${lines.first().lineNumber}-$end", clean, lines,
        source.startLine, end, resolveFromFile = false)
}

private fun commandReadTranscript(item: ConversationItem): ProcessReadPreview {
    val names = item.commandActions.mapNotNull { action ->
        action.name.ifBlank { action.path.trimEnd('/').substringAfterLast('/') }.takeIf { it.isNotBlank() }
            ?.let { if (action.type == "listFiles") "$it/" else it }
    }.distinct()
    val text = item.detail.take(MAX_PROCESS_PREVIEW_CHARS).removeSuffix("\n")
    val lines = if (text.isEmpty()) emptyList() else text.lines().take(MAX_PROCESS_DIFF_LINES).mapIndexed { i, line -> ReadCodeLine(i + 1, line) }
    return ProcessReadPreview(
        filePath = "",
        fileName = if (item.commandActions.size > 1) tr("合并读取输出", "Combined read output") else tr("读取输出", "Read output"),
        parentDir = names.joinToString(" · "),
        language = "text",
        rangeLabel = "",
        cleanText = lines.joinToString("\n") { it.text },
        lines = lines,
        resolveFromFile = false,
        sourceLinesKnown = false,
    )
}
