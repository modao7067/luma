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
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val added: Color,
    val removed: Color,
    val hunk: Color,
)

internal val LightCodePalette = CodePalette(
    keyword = Color(0xFF8A5A7E),
    string = Color(0xFF5B7F5C),
    number = Color(0xFF94683A),
    comment = Color(0xFF8D8982),
    annotation = Color(0xFF4F7593),
    added = Color(0xFF5B7F5C),
    removed = Color(0xFFA35D5A),
    hunk = Color(0xFF4F7593),
)

internal val DarkCodePalette = CodePalette(
    keyword = Color(0xFFD09CC4),
    string = Color(0xFF9DC69B),
    number = Color(0xFFDDB27C),
    comment = Color(0xFF8F948E),
    annotation = Color(0xFF8DB5D8),
    added = Color(0xFF9DC69B),
    removed = Color(0xFFE39A94),
    hunk = Color(0xFF8DB5D8),
)

internal fun codePaletteDark(colors: ColorScheme): Boolean = colors.surface.luminance() < 0.5f
internal fun codePalette(dark: Boolean): CodePalette = if (dark) DarkCodePalette else LightCodePalette

internal const val LIBRARY_HIGHLIGHT_LIMIT = 24_000

internal const val IMMEDIATE_HIGHLIGHT_LIMIT = 2_000

internal fun syntaxLanguage(language: String): SyntaxLanguage? = when (language.trim().substringBefore(' ').lowercase()) {
    "kotlin", "kt", "kts" -> SyntaxLanguage.KOTLIN
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
    "javascript", "js", "mjs", "cjs", "jsx", "json", "jsonc", "json5" -> SyntaxLanguage.JAVASCRIPT
    "typescript", "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
    "sh", "bash", "zsh", "shell", "fish", "ksh", "console", "shellsession",
    "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env", "dockerfile", "makefile", "mk" -> SyntaxLanguage.SHELL
    "gradle", "groovy" -> SyntaxLanguage.KOTLIN
    "text", "txt", "plain", "plaintext", "output", "log", "md", "markdown", "diff", "patch" -> null
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

internal fun highlightedCode(
    code: String,
    language: String,
    dark: Boolean,
    checkpoint: () -> Unit = {},
): AnnotatedString {
    cachedHighlight(code, language, dark)?.let { return it }
    val palette = codePalette(dark)
    val syntax = syntaxLanguage(language)
    val result = if (syntax != null && code.length <= LIBRARY_HIGHLIGHT_LIMIT) {
        libraryHighlight(code, syntax, palette, checkpoint)
    } else scannerHighlight(code, language.trim().substringBefore(' ').lowercase() in setOf("diff", "patch"), palette, checkpoint)
    if (code.length <= LIBRARY_HIGHLIGHT_LIMIT) synchronized(highlightCache) { highlightCache[HighlightKey(code, language, dark)] = result }
    return result
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
            if (end > start) addStyle(SpanStyle(color = color, fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS), start, end)
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
        addStyle(SpanStyle(color = color, fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS), start, end)
    }, checkpoint)
}
