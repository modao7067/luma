package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

internal data class CodePalette(
    val keyword: Color,
    val command: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val operator: Color,
    val added: Color,
    val removed: Color,
    val hunk: Color,
)

internal val LightCodePalette = CodePalette(
    keyword = Color(0xFF7A5B7E),
    command = Color(0xFF4A6984),
    string = Color(0xFF527657),
    number = Color(0xFF8C5E42),
    comment = Color(0xFF898781),
    annotation = Color(0xFF467176),
    operator = Color(0xFF6D6B67),
    added = Color(0xFF527657),
    removed = Color(0xFF99544F),
    hunk = Color(0xFF4A6984),
)

internal val DarkCodePalette = CodePalette(
    keyword = Color(0xFFD496DC),
    command = Color(0xFF7DB2E8),
    string = Color(0xFF8ED198),
    number = Color(0xFFE6A575),
    comment = Color(0xFF97958D),
    annotation = Color(0xFF76C7D0),
    operator = Color(0xFFB4B3A8),
    added = Color(0xFF8ED198),
    removed = Color(0xFFE88E88),
    hunk = Color(0xFF7DB2E8),
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
private val configLanguages = setOf("yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "gitignore", "gitattributes")

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

internal fun syntaxLanguage(language: String): SyntaxLanguage? = when (language.trim().substringBefore(' ').lowercase()) {
    "kotlin", "kt", "kts", "gradle", "groovy" -> SyntaxLanguage.KOTLIN
    "java" -> SyntaxLanguage.JAVA
    "c", "h" -> SyntaxLanguage.C
    "cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx", "objc", "m", "mm" -> SyntaxLanguage.CPP
    "cs", "csharp", "c#" -> SyntaxLanguage.CSHARP
    "rust", "rs" -> SyntaxLanguage.RUST
    "go", "golang" -> SyntaxLanguage.GO
    "swift" -> SyntaxLanguage.SWIFT
    "dart" -> SyntaxLanguage.DART
    "python", "py", "pyi", "python3" -> SyntaxLanguage.PYTHON
    "ruby", "rb" -> SyntaxLanguage.RUBY
    "perl", "pl", "pm" -> SyntaxLanguage.PERL
    "php" -> SyntaxLanguage.PHP
    "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
    "javascript", "js", "mjs", "cjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
    "typescript", "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
    in plainLanguages, "output", "md", "markdown", "diff", "patch",
    in jsonLanguages, in shellLanguages, in configLanguages -> null
    else -> SyntaxLanguage.DEFAULT
}

private const val TOKEN_KEYWORD = 1
private const val TOKEN_STRING = 2
private const val TOKEN_LITERAL = 3
private const val TOKEN_COMMENT = 4
private const val TOKEN_METADATA = 5
private val SENTINEL_THEME = SyntaxTheme("luma", 0, TOKEN_KEYWORD, TOKEN_STRING, TOKEN_LITERAL, TOKEN_COMMENT, TOKEN_METADATA, TOKEN_COMMENT, 0, 0)

private data class HighlightKey(val code: String, val language: String, val dark: Boolean)
private val highlightCache = object : LinkedHashMap<HighlightKey, AnnotatedString>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightKey, AnnotatedString>?) = size > 48
}

internal fun cachedHighlight(code: String, language: String, dark: Boolean): AnnotatedString? =
    synchronized(highlightCache) { highlightCache[HighlightKey(code, language, dark)] }

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
    val syntax = syntaxLanguage(normalized)
    val result = when {
        json -> jsonHighlight(code, normalized == "jsonc" || normalized == "json5", palette, checkpoint)
        normalized in shellLanguages -> shellHighlight(code, palette, checkpoint)
        normalized in configLanguages -> configHighlight(code, palette, checkpoint)
        syntax != null && code.length <= LIBRARY_HIGHLIGHT_LIMIT -> libraryHighlight(code, syntax, palette, checkpoint)
        else -> scannerHighlight(code, normalized in setOf("diff", "patch"), palette, checkpoint)
    }
    if (code.length <= LIBRARY_HIGHLIGHT_LIMIT) synchronized(highlightCache) { highlightCache[HighlightKey(code, language, dark)] = result }
    return result
}

private fun addTokenSpan(builder: AnnotatedString.Builder, start: Int, end: Int, color: Color) {
    if (end > start) {
        builder.addStyle(
            SpanStyle(color = color, fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS),
            start,
            end,
        )
    }
}

private fun jsonHighlight(code: String, extensions: Boolean, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    val valid = CodeTokens.scanJson(code, extensions, CodeTokens.DISPLAY_SPAN_LIMIT, { start, end, kind ->
        val color = when (kind) {
            CodeTokens.JSON_KEY -> palette.annotation
            CodeTokens.NUMBER -> palette.number
            CodeTokens.COMMENT -> palette.comment
            else -> palette.keyword
        }
        addTokenSpan(builder, start, end, color)
    }, checkpoint)
    return if (valid) builder.toAnnotatedString() else AnnotatedString(code)
}

private fun shellHighlight(code: String, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanShell(code, CodeTokens.DISPLAY_SPAN_LIMIT, { start, end, kind ->
        val color = when (kind) {
            CodeTokens.COMMAND -> palette.command
            CodeTokens.FLAG -> palette.annotation
            CodeTokens.VARIABLE, CodeTokens.NUMBER -> palette.number
            CodeTokens.STRING -> palette.string
            CodeTokens.COMMENT -> palette.comment
            CodeTokens.OPERATOR -> palette.operator
            else -> palette.keyword
        }
        addTokenSpan(this, start, end, color)
    }, checkpoint)
}

private fun configHighlight(code: String, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scanConfig(code, CodeTokens.DISPLAY_SPAN_LIMIT, { start, end, kind ->
        val color = when (kind) {
            CodeTokens.JSON_KEY -> palette.annotation
            CodeTokens.STRING -> palette.string
            CodeTokens.NUMBER -> palette.number
            CodeTokens.COMMENT -> palette.comment
            CodeTokens.OPERATOR -> palette.operator
            else -> palette.keyword
        }
        addTokenSpan(this, start, end, color)
    }, checkpoint)
}

private fun libraryHighlight(code: String, syntax: SyntaxLanguage, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString {
    checkpoint()
    val spans = Highlights.Builder().code(code).language(syntax).theme(SENTINEL_THEME).build().getHighlights()
    checkpoint()
    val ordered = spans.filterIsInstance<ColorHighlight>().sortedBy {
        when (it.rgb) { TOKEN_COMMENT -> 2; TOKEN_STRING -> 1; else -> 0 }
    }
    return buildAnnotatedString {
        append(code)
        ordered.forEach { span ->
            val color = when (span.rgb) {
                TOKEN_KEYWORD -> palette.keyword
                TOKEN_STRING -> palette.string
                TOKEN_LITERAL -> palette.number
                TOKEN_COMMENT -> palette.comment
                TOKEN_METADATA -> palette.annotation
                else -> null
            } ?: return@forEach
            val start = span.location.start.coerceIn(0, code.length)
            val end = span.location.end.coerceIn(start, code.length)
            addTokenSpan(this, start, end, color)
        }
    }
}

private fun scannerHighlight(code: String, diff: Boolean, palette: CodePalette, checkpoint: () -> Unit): AnnotatedString = buildAnnotatedString {
    append(code)
    CodeTokens.scan(code, diff, CodeTokens.DISPLAY_SPAN_LIMIT, { start, end, kind ->
        val color = when (kind) {
            CodeTokens.STRING -> palette.string
            CodeTokens.COMMENT -> palette.comment
            CodeTokens.NUMBER -> palette.number
            CodeTokens.ADDED -> palette.added
            CodeTokens.REMOVED -> palette.removed
            CodeTokens.HUNK -> palette.hunk
            else -> palette.keyword
        }
        addTokenSpan(this, start, end, color)
    }, checkpoint)
}
