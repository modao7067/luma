package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CodeHighlightTest {
    private val colors = lightColorScheme()

    private fun AnnotatedString.colorAt(token: String, from: Int = 0): Color? {
        val start = text.indexOf(token, from)
        assertTrue("missing $token", start >= 0)
        val end = start + token.length
        return spanStyles.lastOrNull { it.start <= start && it.end >= end }?.item?.color
    }

    @Test fun `language aware kotlin uses fixed palette`() {
        val code = "@Composable\nfun render(): String {\n    val label = \"text\" // note\n    return label + 42\n}\n"
        val highlighted = highlightedCode(code, "kotlin", colors)
        assertEquals(code, highlighted.text)
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("fun"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("val"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("return"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("text"))
        assertEquals(LightCodePalette.comment, highlighted.colorAt("note"))
        assertEquals(LightCodePalette.number, highlighted.colorAt("42"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("Composable"))
    }

    @Test fun `syntax colours ignore dynamic scheme and follow dark mode`() {
        val code = "def main():\n    return 'x'  # done\n"
        val red = highlightedCode(code, "python", lightColorScheme(primary = Color.Red, tertiary = Color.Red))
        val blue = highlightedCode(code, "python", lightColorScheme(primary = Color.Blue, tertiary = Color.Blue))
        assertEquals(red.spanStyles, blue.spanStyles)
        assertEquals(LightCodePalette.keyword, red.colorAt("def"))
        val dark = highlightedCode(code, "python", darkColorScheme())
        assertEquals(DarkCodePalette.keyword, dark.colorAt("def"))
        assertEquals(DarkCodePalette.comment, dark.colorAt("done"))
    }

    @Test fun `fence info and file extensions map to languages`() {
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.KOTLIN, syntaxLanguage("kt"))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.TYPESCRIPT, syntaxLanguage("tsx title=\"a.tsx\""))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.SHELL, syntaxLanguage("Bash"))
        assertNull(syntaxLanguage("diff"))
        assertNull(syntaxLanguage("text"))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.DEFAULT, syntaxLanguage(""))
    }

    @Test fun `syntax preserves exact unicode and escaped source`() {
        val code = "val 中文 = \"quote \\\"x\\\"\"; return 12.34 // 备注\r\n# 注释\nNone true identifier123"
        assertEquals(code, highlightedCode(code, "kotlin", colors).text)
        assertEquals(code, highlightedCode(code, "text", colors).text)
    }

    @Test fun `diff CRLF offsets preserve complete text`() {
        val code = "@@ heading\r\n+added\r\n-removed\n unchanged\n"
        val highlighted = highlightedCode(code, "diff", colors)
        assertEquals(code, highlighted.text)
        assertEquals(listOf("@@ heading", "+added", "-removed"), highlighted.spanStyles.map { code.substring(it.start, it.end) })
        assertEquals(LightCodePalette.removed, highlighted.spanStyles[2].item.color)
    }

    @Test fun `long token dense output has bounded styling without truncation`() {
        val code = "val x = \"text\"; return 123 // comment\n".repeat(10000) + "最后一行🙂"
        val before = System.nanoTime()
        val highlighted = highlightedCode(code, "output", colors)
        println("production annotations chars=${code.length} spans=${highlighted.spanStyles.size} ms=${(System.nanoTime()-before)/1e6}")
        assertEquals(code, highlighted.text)
        assertEquals(CodeTokens.DISPLAY_SPAN_LIMIT, highlighted.spanStyles.size)
    }

    @Test fun `oversized source falls back to the linear scanner`() {
        val code = "val x = \"text\"; return 123 // comment\n".repeat(LIBRARY_HIGHLIGHT_LIMIT / 30)
        assertTrue(code.length > LIBRARY_HIGHLIGHT_LIMIT)
        val highlighted = highlightedCode(code, "kotlin", colors)
        assertEquals(code, highlighted.text)
        assertEquals(CodeTokens.DISPLAY_SPAN_LIMIT, highlighted.spanStyles.size)
    }

    @Test fun `unfinished escape sequence scans once and cooperatively cancels`() {
        val code = "\"" + "\\x".repeat(500000)
        assertEquals(code, highlightedCode(code, "text", colors).text)
        var checks = 0
        try {
            highlightedCode(code, "text", colors) { if (++checks == 3) throw CancellationException("superseded") }
            fail("scan ignored cancellation")
        } catch (_: CancellationException) { assertEquals(3, checks) }
    }
}
