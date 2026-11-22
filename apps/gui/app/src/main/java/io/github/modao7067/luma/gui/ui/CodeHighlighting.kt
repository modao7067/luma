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
private const val MAX_TOKEN_INDEX_CACHE_ENTRIES = 96
private const val MAX_TOKEN_INDEX_CACHE_CHARS = 1024 * 1024
private const val MAX_CACHED_TOKEN_INDEX_CHARS = 256 * 1024
private const val MAX_DIFF_TOKEN_INDEX_CACHE_ENTRIES = 64
private const val MAX_DIFF_TOKEN_INDEX_CACHE_CHARS = 512 * 1024
internal const val INDEX_LIBRARY_HIGHLIGHT_LIMIT = 64_000
internal const val INDEX_RICH_SPAN_LIMIT = 16_384
internal const val INDEX_DISPLAY_SPAN_LIMIT = 8_192

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
private data class TokenIndexKey(val code: String, val language: String)
private data class DiffTokenIndexKey(val lines: List<DiffLine>, val language: String)

private val highlightCache = CharBudgetLruCache<HighlightKey, AnnotatedString>(
    maxEntries = MAX_HIGHLIGHT_CACHE_ENTRIES,
    maxChars = MAX_HIGHLIGHT_CACHE_CHARS,
)
private val diffHighlightCache = CharBudgetLruCache<DiffHighlightKey, AnnotatedString>(
    maxEntries = MAX_DIFF_CACHE_ENTRIES,
    maxChars = MAX_DIFF_CACHE_CHARS,
)
private val tokenIndexCache = CharBudgetLruCache<TokenIndexKey, CodeTokenIndex>(
    maxEntries = MAX_TOKEN_INDEX_CACHE_ENTRIES,
    maxChars = MAX_TOKEN_INDEX_CACHE_CHARS,
)
private val diffTokenIndexCache = CharBudgetLruCache<DiffTokenIndexKey, DiffTokenIndex>(
    maxEntries = MAX_DIFF_TOKEN_INDEX_CACHE_ENTRIES,
    maxChars = MAX_DIFF_TOKEN_INDEX_CACHE_CHARS,
)

internal fun normalizeCodeLanguage(language: String): String =
    language.trim().substringBefore(' ').lowercase()

internal fun isPlainCodeLanguage(code: String, language: String): Boolean {
    val normalized = normalizeCodeLanguage(language)
    if (normalized in plainLanguages) return true
    if (normalized == "output" && !jsonContainer(code)) return true
    return false
}

internal class CodeTokenIndex(
    val code: String,
    val language: String,
    val tokenStarts: IntArray,
    val tokenEnds: IntArray,
    val tokenKinds: IntArray,
    val maxEndPrefix: IntArray,
    val tokenCount: Int,
    val isPlain: Boolean,
) {
    fun slice(
        targetCode: String,
        startChar: Int,
        endChar: Int,
        palette: CodePalette,
    ): AnnotatedString {
        val safeStart = startChar.coerceIn(0, targetCode.length)
        val safeEnd = endChar.coerceIn(safeStart, targetCode.length)
        val text = if (safeStart == 0 && safeEnd == targetCode.length) {
            targetCode
        } else {
            targetCode.substring(safeStart, safeEnd)
        }
        if (isPlain || tokenCount == 0 || safeStart >= safeEnd || safeStart >= code.length) {
            return AnnotatedString(text)
        }
        val limitEnd = minOf(safeEnd, code.length)
        val firstCandidate = findFirstTokenCandidate(maxEndPrefix, tokenCount, safeStart)
        if (firstCandidate >= tokenCount || tokenStarts[firstCandidate] >= limitEnd) {
            return AnnotatedString(text)
        }
        val builder = AnnotatedString.Builder(text)
        var i = firstCandidate
        while (i < tokenCount) {
            val tStart = tokenStarts[i]
            if (tStart >= limitEnd) break
            val tEnd = tokenEnds[i]
            if (tEnd > safeStart) {
                val localStart = maxOf(tStart, safeStart) - safeStart
                val localEnd = minOf(tEnd, limitEnd) - safeStart
                if (localEnd > localStart) {
                    addTokenSpan(builder, localStart, localEnd, tokenColor(tokenKinds[i], palette))
                }
            }
            i++
        }
        return builder.toAnnotatedString()
    }
}

internal class DiffTokenIndex(
    val lines: List<DiffLine>,
    val language: String,
    val cleanCode: String,
    val cleanLineStarts: IntArray,
    val cleanIndex: CodeTokenIndex,
) {
    fun sliceLines(
        startLine: Int,
        endLineExclusive: Int,
        palette: CodePalette,
    ): AnnotatedString {
        val safeStart = startLine.coerceIn(0, lines.size)
        val safeEnd = endLineExclusive.coerceIn(safeStart, lines.size)
        val count = safeEnd - safeStart
        if (count <= 0) return AnnotatedString("")
        val chunkLineStarts = IntArray(count)
        val chunkBuilder = StringBuilder()
        for (localIdx in 0 until count) {
            if (localIdx > 0) chunkBuilder.append('\n')
            chunkLineStarts[localIdx] = chunkBuilder.length
            val line = lines[safeStart + localIdx]
            val prefix = when (line.kind) {
                DiffLineKind.ADDITION -> "+ "
                DiffLineKind.DELETION -> "- "
                DiffLineKind.CONTEXT -> "  "
            }
            chunkBuilder.append(prefix).append(line.text)
        }
        val chunkCode = chunkBuilder.toString()
        val builder = AnnotatedString.Builder(chunkCode)
        for (localIdx in 0 until count) {
            val line = lines[safeStart + localIdx]
            val prefixColor = when (line.kind) {
                DiffLineKind.ADDITION -> palette.added
                DiffLineKind.DELETION -> palette.removed
                DiffLineKind.CONTEXT -> null
            }
            if (prefixColor != null) {
                val hStart = chunkLineStarts[localIdx]
                addTokenSpan(builder, hStart, hStart + 1, prefixColor)
            }
        }
        if (!cleanIndex.isPlain && cleanIndex.tokenCount > 0) {
            val cleanRangeStart = cleanLineStarts[safeStart]
            val lastGlobal = safeEnd - 1
            val cleanRangeEnd = cleanLineStarts[lastGlobal] + lines[lastGlobal].text.length
            if (cleanRangeEnd > cleanRangeStart) {
                val firstCandidate = findFirstTokenCandidate(cleanIndex.maxEndPrefix, cleanIndex.tokenCount, cleanRangeStart)
                var ti = firstCandidate
                while (ti < cleanIndex.tokenCount) {
                    val tStart = cleanIndex.tokenStarts[ti]
                    if (tStart >= cleanRangeEnd) break
                    val tEnd = cleanIndex.tokenEnds[ti]
                    if (tEnd > cleanRangeStart) {
                        val color = tokenColor(cleanIndex.tokenKinds[ti], palette)
                        if (color != null) {
                            val spanStart = maxOf(tStart, cleanRangeStart)
                            val spanEnd = minOf(tEnd, cleanRangeEnd)
                            var lineIdx = findLineForCleanOffset(cleanLineStarts, safeStart, safeEnd, spanStart)
                            while (lineIdx < safeEnd) {
                                val lineStart = cleanLineStarts[lineIdx]
                                if (lineStart >= spanEnd) break
                                val lineEnd = lineStart + lines[lineIdx].text.length
                                val segStart = maxOf(spanStart, lineStart)
                                val segEnd = minOf(spanEnd, lineEnd)
                                if (segEnd > segStart) {
                                    val localLine = lineIdx - safeStart
                                    val targetStart = chunkLineStarts[localLine] + 2 + (segStart - lineStart)
                                    val targetEnd = chunkLineStarts[localLine] + 2 + (segEnd - lineStart)
                                    addTokenSpan(builder, targetStart, targetEnd, color)
                                }
                                lineIdx++
                            }
                        }
                    }
                    ti++
                }
            }
        }
        return builder.toAnnotatedString()
    }
}

private fun findFirstTokenCandidate(maxEndPrefix: IntArray, count: Int, targetOffset: Int): Int {
    var lo = 0
    var hi = count
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (maxEndPrefix[mid] <= targetOffset) {
            lo = mid + 1
        } else {
            hi = mid
        }
    }
    return lo
}

private fun findLineForCleanOffset(cleanLineStarts: IntArray, fromLine: Int, toLineExclusive: Int, offset: Int): Int {
    var lo = fromLine
    var hi = toLineExclusive - 1
    var ans = fromLine
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cleanLineStarts[mid] <= offset) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

private class TokenCollector(initialCapacity: Int) {
    var starts = IntArray(initialCapacity.coerceAtLeast(16))
    var ends = IntArray(initialCapacity.coerceAtLeast(16))
    var kinds = IntArray(initialCapacity.coerceAtLeast(16))
    var size = 0
    private var prevStart = Int.MIN_VALUE
    private var needsSort = false

    fun add(start: Int, end: Int, kind: Int) {
        if (end <= start) return
        if (size == starts.size) {
            val nextCap = starts.size * 2
            starts = starts.copyOf(nextCap)
            ends = ends.copyOf(nextCap)
            kinds = kinds.copyOf(nextCap)
        }
        if (start < prevStart) needsSort = true
        prevStart = start
        starts[size] = start
        ends[size] = end
        kinds[size] = kind
        size++
    }

    fun build(code: String, language: String, isPlain: Boolean): CodeTokenIndex {
        if (size == 0) {
            val empty = IntArray(0)
            return CodeTokenIndex(code, language, empty, empty, empty, empty, 0, isPlain)
        }
        if (needsSort) {
            for (i in 1 until size) {
                val s = starts[i]
                val e = ends[i]
                val k = kinds[i]
                var j = i - 1
                while (j >= 0 && starts[j] > s) {
                    starts[j + 1] = starts[j]
                    ends[j + 1] = ends[j]
                    kinds[j + 1] = kinds[j]
                    j--
                }
                starts[j + 1] = s
                ends[j + 1] = e
                kinds[j + 1] = k
            }
        }
        val outStarts = if (size == starts.size) starts else starts.copyOf(size)
        val outEnds = if (size == ends.size) ends else ends.copyOf(size)
        val outKinds = if (size == kinds.size) kinds else kinds.copyOf(size)
        val maxEndPrefix = IntArray(size)
        var runningMax = 0
        for (i in 0 until size) {
            val e = outEnds[i]
            if (e > runningMax) runningMax = e
            maxEndPrefix[i] = runningMax
        }
        return CodeTokenIndex(code, language, outStarts, outEnds, outKinds, maxEndPrefix, size, isPlain)
    }
}

private fun plainCodeTokenIndex(code: String, normalized: String): CodeTokenIndex {
    val empty = IntArray(0)
    return CodeTokenIndex(code, normalized, empty, empty, empty, empty, 0, isPlain = true)
}

internal fun cachedCodeTokenIndex(code: String, language: String): CodeTokenIndex? {
    val normalized = normalizeCodeLanguage(language)
    if (isPlainCodeLanguage(code, normalized)) return plainCodeTokenIndex(code, normalized)
    return tokenIndexCache.get(TokenIndexKey(code, normalized))
}

internal fun codeTokenIndex(
    code: String,
    language: String,
    checkpoint: () -> Unit = {},
): CodeTokenIndex {
    val normalized = normalizeCodeLanguage(language)
    if (normalized in plainLanguages) return plainCodeTokenIndex(code, normalized)
    val json = normalized in jsonLanguages || (normalized in setOf("params", "参数", "output") && jsonContainer(code))
    if (normalized == "output" && !json) return plainCodeTokenIndex(code, normalized)
    val key = TokenIndexKey(code, normalized)
    tokenIndexCache.get(key)?.let { return it }
    val spanLimit = if (code.length <= INDEX_LIBRARY_HIGHLIGHT_LIMIT) INDEX_RICH_SPAN_LIMIT else INDEX_DISPLAY_SPAN_LIMIT
    val collector = TokenCollector((code.length / 10).coerceIn(32, 2048))
    val sink = CodeTokens.Sink { start, end, kind -> collector.add(start, end, kind) }
    val result = when {
        json -> {
            val valid = CodeTokens.scanJson(code, normalized == "jsonc" || normalized == "json5", spanLimit, sink, checkpoint)
            if (valid) collector.build(code, normalized, false) else plainCodeTokenIndex(code, normalized)
        }
        normalized in shellLanguages -> {
            CodeTokens.scanShell(code, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        normalized in configLanguages -> {
            CodeTokens.scanConfig(code, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        normalized in markupLanguages -> {
            CodeTokens.scanMarkup(code, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        normalized in styleLanguages -> {
            CodeTokens.scanStyle(code, normalized != "css", spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        normalized in markdownLanguages && code.length <= INDEX_LIBRARY_HIGHLIGHT_LIMIT -> {
            CodeTokens.scanMarkdown(code, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        normalized in setOf("diff", "patch") -> {
            CodeTokens.scan(code, true, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        code.length <= INDEX_LIBRARY_HIGHLIGHT_LIMIT -> {
            CodeTokens.scanCode(code, codeLanguageFamily(normalized), spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
        else -> {
            CodeTokens.scan(code, false, spanLimit, sink, checkpoint)
            collector.build(code, normalized, false)
        }
    }
    if (code.length <= MAX_CACHED_TOKEN_INDEX_CHARS) {
        tokenIndexCache.put(key, result, code.length)
    }
    return result
}

internal fun cachedDiffTokenIndex(lines: List<DiffLine>, language: String): DiffTokenIndex? =
    diffTokenIndexCache.get(DiffTokenIndexKey(lines, normalizeCodeLanguage(language)))

internal fun diffTokenIndex(
    lines: List<DiffLine>,
    language: String,
    checkpoint: () -> Unit = {},
): DiffTokenIndex {
    val normalized = normalizeCodeLanguage(language)
    val key = DiffTokenIndexKey(lines, normalized)
    diffTokenIndexCache.get(key)?.let { return it }
    if (lines.isEmpty()) {
        return DiffTokenIndex(lines, normalized, "", IntArray(0), plainCodeTokenIndex("", normalized))
    }
    val cleanLineStarts = IntArray(lines.size)
    val cleanBuilder = StringBuilder()
    lines.forEachIndexed { idx, line ->
        if (idx > 0) cleanBuilder.append('\n')
        cleanLineStarts[idx] = cleanBuilder.length
        cleanBuilder.append(line.text)
    }
    val cleanCode = cleanBuilder.toString()
    val base = codeTokenIndex(cleanCode, normalized, checkpoint)
    val cleanIndex = if (normalized in jsonLanguages && base.tokenCount == 0 && cleanCode.isNotBlank()) {
        val limit = if (cleanCode.length <= INDEX_LIBRARY_HIGHLIGHT_LIMIT) INDEX_RICH_SPAN_LIMIT else INDEX_DISPLAY_SPAN_LIMIT
        val collector = TokenCollector((cleanCode.length / 10).coerceIn(32, 2048))
        CodeTokens.scanJsonFragment(cleanCode, limit, { start, end, kind -> collector.add(start, end, kind) }, checkpoint)
        collector.build(cleanCode, normalized, false)
    } else {
        base
    }
    val result = DiffTokenIndex(lines, normalized, cleanCode, cleanLineStarts, cleanIndex)
    if (cleanCode.length <= MAX_CACHED_TOKEN_INDEX_CHARS) {
        diffTokenIndexCache.put(key, result, cleanCode.length)
    }
    return result
}

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
        normalized in markdownLanguages && code.length <= LIBRARY_HIGHLIGHT_LIMIT -> markdownHighlight(code, spanLimit, palette, checkpoint)
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
