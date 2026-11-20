// Adapted from antfu/vscode-theme-vitesse (Vitesse Light, MIT) and enkia/tokyo-night-vscode-theme (Tokyo Night, MIT).
// Source details and notices: THIRD_PARTY_NOTICES.md
package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal data class CodePalette(
    val keyword: Color,
    val function: Color,
    val command: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val property: Color,
    val variable: Color,
    val comment: Color,
    val annotation: Color,
    val escape: Color,
    val operator: Color,
    val added: Color,
    val removed: Color,
    val hunk: Color,
)

internal val LightCodePalette = CodePalette(
    keyword = Color(0xFF1E754F),
    function = Color(0xFF3E5696),
    command = Color(0xFF3E5696),
    type = Color(0xFF7A6316),
    string = Color(0xFF9B4B3B),
    number = Color(0xFF206B7A),
    property = Color(0xFF52606E),
    variable = Color(0xFF96455A),
    comment = Color(0xFF727B71),
    annotation = Color(0xFF3B6F2E),
    escape = Color(0xFF914E1E),
    operator = Color(0xFF605F58),
    added = Color(0xFF1E754F),
    removed = Color(0xFFA33A3A),
    hunk = Color(0xFF3E5696),
)

internal val DarkCodePalette = CodePalette(
    keyword = Color(0xFFBB9AF7),
    function = Color(0xFF7AA2F7),
    command = Color(0xFF7AA2F7),
    type = Color(0xFF2AC3DE),
    string = Color(0xFF9ECE6A),
    number = Color(0xFFFF9E64),
    property = Color(0xFFA9B1D6),
    variable = Color(0xFFF7768E),
    comment = Color(0xFF8B94BA),
    annotation = Color(0xFF73DACA),
    escape = Color(0xFFE0AF68),
    operator = Color(0xFF89DDFF),
    added = Color(0xFF9ECE6A),
    removed = Color(0xFFF7768E),
    hunk = Color(0xFF7AA2F7),
)

internal fun codePaletteDark(colors: ColorScheme): Boolean = colors.surface.luminance() < 0.5f
internal fun codePalette(dark: Boolean): CodePalette = if (dark) DarkCodePalette else LightCodePalette

internal const val LIBRARY_HIGHLIGHT_LIMIT = 24_000

internal const val IMMEDIATE_HIGHLIGHT_LIMIT = 2_000

private val plainLanguages = setOf("text", "txt", "plain", "plaintext", "log")
private val jsonLanguages = setOf("json", "jsonc", "json5")
private val shellLanguages = setOf(
    "sh", "bash", "zsh", "shell", "fish", "ksh", "console", "shellsession",
    "bashrc", "zshrc", "profile", "env", "dotenv", "dockerfile", "makefile", "mk",
)
private val configLanguages = setOf("yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "gitignore", "gitattributes", "cmake")
private val markupLanguages = setOf("xml", "html", "htm", "xhtml", "svg", "vue", "svelte", "plist")
private val styleLanguages = setOf("css", "scss", "sass", "less")
private val markdownLanguages = setOf("md", "markdown")

internal fun resolveFileCodeLanguage(filePath: String, content: String = ""): String {
    val clean = filePath.trim().substringBefore('?').substringBefore('#').trimEnd('/')
    val name = clean.substringAfterLast('/').ifBlank { clean }
    val lowerName = name.lowercase()
    when {
        lowerName.contains("diff") && lowerName.endsWith(".diff") -> return "diff"
        lowerName == "dockerfile" || lowerName.startsWith("dockerfile.") -> return "dockerfile"
        lowerName == "makefile" || lowerName == "gnumakefile" -> return "makefile"
        lowerName == "cmakelists.txt" -> return "cmake"
        lowerName == "gradlew" -> return "sh"
        lowerName in setOf(".bashrc", ".bash_profile", ".bash_aliases", ".zshrc", ".zprofile", ".profile") -> return "bash"
        lowerName == ".env" || lowerName.startsWith(".env.") -> return "env"
        lowerName in setOf(".gitignore", ".gitattributes", ".dockerignore") -> return "gitignore"
        lowerName.endsWith(".gradle.kts") -> return "kts"
    }
    if (name.contains('.') && !name.startsWith('.')) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) return ext
    } else if (name.startsWith('.') && name.indexOf('.', 1) > 0) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) return ext
    }
    if (content.startsWith("#!")) {
        val firstLine = content.lineSequence().firstOrNull().orEmpty().lowercase()
        return when {
            "python" in firstLine -> "py"
            "node" in firstLine || "deno" in firstLine || "bun" in firstLine -> "js"
            "bash" in firstLine || "zsh" in firstLine || "/sh" in firstLine || " sh" in firstLine -> "bash"
            "ruby" in firstLine -> "rb"
            "perl" in firstLine -> "pl"
            else -> ""
        }
    }
    return ""
}

private fun codeLanguageFamily(normalized: String): Int = when (normalized) {
    "kotlin", "kt", "kts", "gradle", "groovy" -> CodeTokens.LANG_KOTLIN
    "java" -> CodeTokens.LANG_JAVA
    "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx", "objc", "m", "mm", "cs", "csharp", "c#" -> CodeTokens.LANG_C_FAMILY
    "rust", "rs" -> CodeTokens.LANG_RUST
    "go", "golang" -> CodeTokens.LANG_GO
    "swift", "dart" -> CodeTokens.LANG_SWIFT_DART
    "python", "py", "pyi", "python3" -> CodeTokens.LANG_PYTHON
    "javascript", "js", "mjs", "cjs", "jsx", "typescript", "ts", "tsx", "mts", "cts" -> CodeTokens.LANG_JS_TS
    "ruby", "rb", "perl", "pl", "pm" -> CodeTokens.LANG_RUBY_PERL
    "php" -> CodeTokens.LANG_PHP
    "sql", "sqlite", "postgres", "postgresql", "mysql" -> CodeTokens.LANG_SQL
    "zig" -> CodeTokens.LANG_ZIG
    else -> CodeTokens.LANG_DEFAULT
}

private const val MAX_HIGHLIGHT_CACHE_ENTRIES = 96
private const val MAX_HIGHLIGHT_CACHE_CHARS = 512 * 1024
private const val MAX_CACHED_CODE_CHARS = 128 * 1024
private const val MAX_DIFF_CACHE_ENTRIES = 64
private const val MAX_DIFF_CACHE_CHARS = 256 * 1024

private class CharBudgetLruCache<K, V>(
    private val maxEntries: Int,
    private val maxChars: Int,
) {
    private class Entry<V>(val value: V, val chars: Int)
    private val map = LinkedHashMap<K, Entry<V>>(32, 0.75f, true)
    private var totalChars = 0

    @Synchronized
    fun get(key: K): V? = map[key]?.value

    @Synchronized
    fun put(key: K, value: V, chars: Int) {
        if (chars > maxChars) return
        map.remove(key)?.let { totalChars -= it.chars }
        map[key] = Entry(value, chars)
        totalChars += chars
        val it = map.entries.iterator()
        while ((map.size > maxEntries || totalChars > maxChars) && it.hasNext()) {
            val eldest = it.next()
            totalChars -= eldest.value.chars
            it.remove()
        }
    }
}

private data class HighlightKey(val code: String, val language: String, val dark: Boolean)
private data class DiffHighlightKey(val lines: List<DiffLine>, val language: String, val dark: Boolean)

private val highlightCache = CharBudgetLruCache<HighlightKey, AnnotatedString>(
    maxEntries = MAX_HIGHLIGHT_CACHE_ENTRIES,
    maxChars = MAX_HIGHLIGHT_CACHE_CHARS,
)
private val diffHighlightCache = CharBudgetLruCache<DiffHighlightKey, AnnotatedString>(
    maxEntries = MAX_DIFF_CACHE_ENTRIES,
    maxChars = MAX_DIFF_CACHE_CHARS,
)

internal fun cachedHighlight(code: String, language: String, dark: Boolean): AnnotatedString? =
    highlightCache.get(HighlightKey(code, language, dark))

internal fun cachedDiffHighlight(lines: List<DiffLine>, language: String, dark: Boolean): AnnotatedString? =
    diffHighlightCache.get(DiffHighlightKey(lines, language, dark))

internal fun highlightedCode(
    code: String,
    language: String,
    colors: ColorScheme,
    checkpoint: () -> Unit = {},
): AnnotatedString = highlightedCode(code, language, codePaletteDark(colors), checkpoint)

private fun jsonContainer(code: String): Boolean {
    var start = 0
    var end = code.length
    while (start < end && code[start].isWhitespace()) start++
    while (end > start && code[end - 1].isWhitespace()) end--
    return end - start >= 2 && ((code[start] == '{' && code[end - 1] == '}') || (code[start] == '[' && code[end - 1] == ']'))
}

internal fun highlightedCode(
    code: String,
    language: String,
    dark: Boolean,
    checkpoint: () -> Unit = {},
): AnnotatedString {
    val normalized = language.trim().substringBefore(' ').lowercase()
    if (normalized in plainLanguages) return AnnotatedString(code)
    val json = normalized in jsonLanguages || (normalized in setOf("params", "参数", "output") && jsonContainer(code))
    if (normalized == "output" && !json) return AnnotatedString(code)
    cachedHighlight(code, language, dark)?.let { return it }
    val palette = codePalette(dark)
    val spanLimit = if (code.length <= LIBRARY_HIGHLIGHT_LIMIT) CodeTokens.RICH_SPAN_LIMIT else CodeTokens.DISPLAY_SPAN_LIMIT
    val result = when {
        json -> jsonHighlight(code, normalized == "jsonc" || normalized == "json5", spanLimit, palette, checkpoint)
        normalized in shellLanguages -> shellHighlight(code, spanLimit, palette, checkpoint)
        normalized in configLanguages -> configHighlight(code, spanLimit, palette, checkpoint)
        normalized in markupLanguages -> markupHighlight(code, spanLimit, palette, checkpoint)
        normalized in styleLanguages -> styleHighlight(code, normalized != "css", spanLimit, palette, checkpoint)
        normalized in markdownLanguages -> markdownHighlight(code, spanLimit, palette, checkpoint)
        normalized in setOf("diff", "patch") -> scannerHighlight(code, true, spanLimit, palette, checkpoint)
        code.length <= LIBRARY_HIGHLIGHT_LIMIT -> codeHighlight(code, codeLanguageFamily(normalized), CodeTokens.RICH_SPAN_LIMIT, palette, checkpoint)
        else -> scannerHighlight(code, false, CodeTokens.DISPLAY_SPAN_LIMIT, palette, checkpoint)
    }
    if (code.length <= MAX_CACHED_CODE_CHARS) {
        highlightCache.put(HighlightKey(code, language, dark), result, code.length)
    }
    return result
}

internal fun highlightedDiffHunk(
    lines: List<DiffLine>,
    language: String,
    colors: ColorScheme,
    checkpoint: () -> Unit = {},
): AnnotatedString = highlightedDiffHunk(lines, language, codePaletteDark(colors), checkpoint)

internal fun highlightedDiffHunk(
    lines: List<DiffLine>,
    language: String,
    dark: Boolean,
    checkpoint: () -> Unit = {},
): AnnotatedString {
    if (lines.isEmpty()) return AnnotatedString("")
    cachedDiffHighlight(lines, language, dark)?.let { return it }
    val cleanLineStarts = IntArray(lines.size)
    val hunkLineStarts = IntArray(lines.size)
    val cleanBuilder = StringBuilder()
    val hunkBuilder = StringBuilder()
    lines.forEachIndexed { idx, line ->
        if (idx > 0) {
            cleanBuilder.append('\n')
            hunkBuilder.append('\n')
        }
        cleanLineStarts[idx] = cleanBuilder.length
        hunkLineStarts[idx] = hunkBuilder.length
        val prefix = when (line.kind) {
            DiffLineKind.ADDITION -> "+ "
            DiffLineKind.DELETION -> "- "
            DiffLineKind.CONTEXT -> "  "
        }
        cleanBuilder.append(line.text)
        hunkBuilder.append(prefix).append(line.text)
    }
    val cleanCode = cleanBuilder.toString()
    val hunkCode = hunkBuilder.toString()
    val palette = codePalette(dark)
    val normalized = language.trim().substringBefore(' ').lowercase()
    val base = highlightedCode(cleanCode, language, dark, checkpoint)
    val cleanHighlighted = if (normalized in jsonLanguages && base.spanStyles.isEmpty() && cleanCode.isNotBlank()) {
        val limit = if (cleanCode.length <= LIBRARY_HIGHLIGHT_LIMIT) CodeTokens.RICH_SPAN_LIMIT else CodeTokens.DISPLAY_SPAN_LIMIT
        jsonFragmentHighlight(cleanCode, limit, palette, checkpoint)
    } else {
        base
    }
    val builder = AnnotatedString.Builder(hunkCode)
    lines.forEachIndexed { idx, line ->
        val prefixColor = when (line.kind) {
            DiffLineKind.ADDITION -> palette.added
            DiffLineKind.DELETION -> palette.removed
            DiffLineKind.CONTEXT -> null
        }
        if (prefixColor != null) {
            val hStart = hunkLineStarts[idx]
            addTokenSpan(builder, hStart, hStart + 1, prefixColor)
        }
    }
    for (span in cleanHighlighted.spanStyles) {
        val spanStart = span.start.coerceIn(0, cleanCode.length)
        val spanEnd = span.end.coerceIn(spanStart, cleanCode.length)
        if (spanEnd <= spanStart) continue
        var idx = cleanLineStarts.binarySearch(spanStart).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        while (idx < lines.size) {
            val lineStart = cleanLineStarts[idx]
            if (lineStart >= spanEnd) break
            val lineEnd = lineStart + lines[idx].text.length
            val segStart = maxOf(spanStart, lineStart)
            val segEnd = minOf(spanEnd, lineEnd)
            if (segEnd > segStart) {
                val targetStart = hunkLineStarts[idx] + 2 + (segStart - lineStart)
                val targetEnd = hunkLineStarts[idx] + 2 + (segEnd - lineStart)
                builder.addStyle(span.item, targetStart, targetEnd)
            }
            idx++
        }
    }
    val result = builder.toAnnotatedString()
    if (hunkCode.length <= MAX_CACHED_CODE_CHARS) {
        diffHighlightCache.put(DiffHighlightKey(lines, language, dark), result, hunkCode.length)
    }
    return result
}

private fun tokenColor(kind: Int, palette: CodePalette): Color? = when (kind) {
    CodeTokens.STRING -> palette.string
    CodeTokens.COMMENT -> palette.comment
    CodeTokens.NUMBER -> palette.number
    CodeTokens.KEYWORD -> palette.keyword
    CodeTokens.ADDED -> palette.added
    CodeTokens.REMOVED -> palette.removed
    CodeTokens.HUNK -> palette.hunk
    CodeTokens.JSON_KEY -> palette.annotation
    CodeTokens.COMMAND -> palette.command
    CodeTokens.FLAG -> palette.annotation
    CodeTokens.VARIABLE -> palette.variable
    CodeTokens.OPERATOR -> palette.operator
    CodeTokens.FUNCTION -> palette.function
    CodeTokens.TYPE -> palette.type
    CodeTokens.PROPERTY -> palette.property
    CodeTokens.ANNOTATION -> palette.annotation
    CodeTokens.ESCAPE -> palette.escape
    else -> null
}

private fun addTokenSpan(builder: AnnotatedString.Builder, start: Int, end: Int, color: Color?) {
    if (color != null && end > start) {
        builder.addStyle(SpanStyle(color = color), start, end)
    }
}

private fun jsonHighlight(code: String, extensions: Boolean, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    val valid = CodeTokens.scanJson(code, extensions, limit, { start, end, kind ->
        addTokenSpan(builder, start, end, tokenColor(kind, palette))
    }, checkpoint)
    return if (valid) builder.toAnnotatedString() else AnnotatedString(code)
}

private fun jsonFragmentHighlight(code: String, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanJsonFragment(code, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun shellHighlight(code: String, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanShell(code, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun configHighlight(code: String, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanConfig(code, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun markupHighlight(code: String, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanMarkup(code, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun styleHighlight(code: String, scss: Boolean, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanStyle(code, scss, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun markdownHighlight(code: String, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanMarkdown(code, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun codeHighlight(code: String, lang: Int, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanCode(code, lang, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}

private fun scannerHighlight(code: String, diff: Boolean, limit: Int, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scan(code, diff, limit, { start, end, kind ->
        addTokenSpan(this, start, end, tokenColor(kind, palette))
    }, checkpoint)
}
