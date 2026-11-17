package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CodeHighlightTest {
    private val colors = lightColorScheme()
    private val darkColors = darkColorScheme()

    private fun AnnotatedString.colorAt(token: String, from: Int = 0): Color? {
        val start = text.indexOf(token, from)
        assertTrue("missing $token", start >= 0)
        val end = start + token.length
        return spanStyles.lastOrNull { it.start <= start && it.end >= end }?.item?.color
    }

    private fun hslSaturation(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        if (d == 0f) return 0f
        val l = (hi + lo) * 0.5f
        return d / (1f - abs(2f * l - 1f))
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

    @Test fun `dark palette boosts saturation over morandi light palette`() {
        val pairs = listOf(
            LightCodePalette.keyword to DarkCodePalette.keyword,
            LightCodePalette.command to DarkCodePalette.command,
            LightCodePalette.string to DarkCodePalette.string,
            LightCodePalette.number to DarkCodePalette.number,
            LightCodePalette.annotation to DarkCodePalette.annotation,
            LightCodePalette.removed to DarkCodePalette.removed,
        )
        pairs.forEach { (light, dark) ->
            val lightSat = hslSaturation(light)
            val darkSat = hslSaturation(dark)
            assertTrue("expected morandi light saturation <= 0.40, got $lightSat", lightSat <= 0.40f)
            assertTrue("expected dark saturation ($darkSat) > light saturation ($lightSat)", darkSat > lightSat + 0.15f)
        }
    }

    @Test fun `syntax colours ignore dynamic scheme and follow dark mode`() {
        val code = "def main():\n    return 'x'  # done\n"
        val red = highlightedCode(code, "python", lightColorScheme(primary = Color.Red, tertiary = Color.Red))
        val blue = highlightedCode(code, "python", lightColorScheme(primary = Color.Blue, tertiary = Color.Blue))
        assertEquals(red.spanStyles, blue.spanStyles)
        assertEquals(LightCodePalette.keyword, red.colorAt("def"))
        val dark = highlightedCode(code, "python", darkColors)
        assertEquals(DarkCodePalette.keyword, dark.colorAt("def"))
        assertEquals(DarkCodePalette.comment, dark.colorAt("done"))
    }

    @Test fun `fence info and file paths map to languages`() {
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.KOTLIN, syntaxLanguage("kt"))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.TYPESCRIPT, syntaxLanguage("tsx title=\"a.tsx\""))
        assertNull(syntaxLanguage("Bash"))
        assertNull(syntaxLanguage("toml"))
        assertNull(syntaxLanguage("diff"))
        assertNull(syntaxLanguage("text"))
        assertEquals(dev.snipme.highlights.model.SyntaxLanguage.DEFAULT, syntaxLanguage(""))

        assertEquals("dockerfile", resolveFileCodeLanguage("/workspace/app/Dockerfile"))
        assertEquals("makefile", resolveFileCodeLanguage("/workspace/app/GNUmakefile"))
        assertEquals("sh", resolveFileCodeLanguage("/workspace/app/gradlew"))
        assertEquals("bash", resolveFileCodeLanguage("/root/.bashrc"))
        assertEquals("bash", resolveFileCodeLanguage("/workspace/app/tools/luma", "#!/usr/bin/env bash\nset -e"))
        assertEquals("py", resolveFileCodeLanguage("/workspace/app/bin/helper", "#!/usr/bin/env python3\nimport sys"))
        assertEquals("kts", resolveFileCodeLanguage("/workspace/app/build.gradle.kts"))
    }

    @Test fun `shell commands flags variables heredocs and comments highlight cleanly`() {
        val oneLiner = "ls -ld /workspace/docs /workspace/docs/example; readlink -f /workspace/docs/example; stat /workspace/docs/example"
        val highlighted = highlightedCode(oneLiner, "bash", darkColors)
        assertEquals(oneLiner, highlighted.text)
        assertEquals(DarkCodePalette.command, highlighted.colorAt("ls"))
        assertEquals(DarkCodePalette.annotation, highlighted.colorAt("-ld"))
        assertNull(highlighted.colorAt("/workspace/docs"))
        assertEquals(DarkCodePalette.operator, highlighted.colorAt(";"))
        assertEquals(DarkCodePalette.command, highlighted.colorAt("readlink"))
        assertEquals(DarkCodePalette.annotation, highlighted.colorAt("-f"))
        assertEquals(DarkCodePalette.command, highlighted.colorAt("stat"))

        val complex = "PYTHONPATH=src env -i python3 -m unittest https://example.test#frag 2>&1 | tee \"\$OUT\" # note\nif [ -f a ]; then\npython3 - <<'PY'\nprint(\"don't bleed\")\nPY\necho \$HOME 42\nfi"
        val script = highlightedCode(complex, "shell", colors)
        assertEquals(complex, script.text)
        assertEquals(LightCodePalette.number, script.colorAt("PYTHONPATH"))
        assertEquals(LightCodePalette.command, script.colorAt("env"))
        assertEquals(LightCodePalette.annotation, script.colorAt("-i"))
        assertEquals(LightCodePalette.command, script.colorAt("python3"))
        assertEquals(LightCodePalette.annotation, script.colorAt("-m"))
        assertNull(script.colorAt("#frag"))
        assertEquals(LightCodePalette.operator, script.colorAt("2>&1"))
        assertEquals(LightCodePalette.command, script.colorAt("tee"))
        assertEquals(LightCodePalette.comment, script.colorAt("# note"))
        assertEquals(LightCodePalette.keyword, script.colorAt("if"))
        assertEquals(LightCodePalette.keyword, script.colorAt("then"))
        assertEquals(LightCodePalette.string, script.colorAt("print(\"don't bleed\")"))
        assertEquals(LightCodePalette.command, script.colorAt("echo"))
        assertEquals(LightCodePalette.number, script.colorAt("\$HOME"))
        assertEquals(LightCodePalette.number, script.colorAt("42"))
        assertEquals(LightCodePalette.keyword, script.colorAt("fi"))
    }

    @Test fun `config files highlight sections keys literals numbers and comments`() {
        val toml = "[versions]\nagp = \"9.5.0\" # pin\nenabled = true\ncount = 21\n"
        val highlighted = highlightedCode(toml, "toml", colors)
        assertEquals(toml, highlighted.text)
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("[versions]"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("agp"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("\"9.5.0\""))
        assertEquals(LightCodePalette.comment, highlighted.colorAt("# pin"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("true"))
        assertEquals(LightCodePalette.number, highlighted.colorAt("21"))
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

    @Test fun `json fields values numbers literals and comments use their own roles`() {
        val code = """{"url":"https://example.com","count":-1.25e+3,"ok":true,"none":null}"""
        val highlighted = highlightedCode(code, "json", colors)
        assertEquals(code, highlighted.text)
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("\"url\""))
        assertNull(highlighted.colorAt("\"https://example.com\""))
        assertEquals(LightCodePalette.number, highlighted.colorAt("-1.25e+3"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("true"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("null"))
        assertEquals(LightCodePalette.annotation, highlightedCode(code, "params", colors).colorAt("\"count\""))
        assertEquals(LightCodePalette.annotation, highlightedCode(code, "output · error", colors).colorAt("\"ok\""))

        val relaxed = "{\n// note\n\"key\": [1, 2,],\n/* tail */}"
        val relaxedHighlighted = highlightedCode(relaxed, "jsonc", colors)
        assertEquals(relaxed, relaxedHighlighted.text)
        assertEquals(LightCodePalette.comment, relaxedHighlighted.colorAt("// note"))
        assertEquals(LightCodePalette.comment, relaxedHighlighted.colorAt("/* tail */"))
        assertEquals(LightCodePalette.annotation, relaxedHighlighted.colorAt("\"key\""))
    }

    @Test fun `plain output and invalid json never receive partial styling`() {
        val plain = "don't scan user's quotes or in for from data\n\"unfinished"
        listOf("text", "txt", "plain", "plaintext", "output", "log").forEach { language ->
            var checks = 0
            val highlighted = highlightedCode(plain, language, colors) { checks++ }
            assertEquals(plain, highlighted.text)
            assertTrue(highlighted.spanStyles.isEmpty())
            assertEquals(0, checks)
        }
        listOf("{2026-11-16}", "{\"a\":1,\"b\":invalid}", "{\"a\":1,}", "{\n// comment\n\"a\":1}", "[".repeat(300) + "]".repeat(300)).forEach { code ->
            val highlighted = highlightedCode(code, "output", colors)
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.isEmpty())
        }
        assertEquals(LightCodePalette.string, highlightedCode("val x = \"green\"", "kotlin", colors).colorAt("green"))
    }

    @Test fun `oversized source falls back to the linear scanner`() {
        val code = "val x = \"text\"; return 123 // comment\n".repeat(LIBRARY_HIGHLIGHT_LIMIT / 30)
        assertTrue(code.length > LIBRARY_HIGHLIGHT_LIMIT)
        val highlighted = highlightedCode(code, "kotlin", colors)
        assertEquals(code, highlighted.text)
        assertEquals(CodeTokens.DISPLAY_SPAN_LIMIT, highlighted.spanStyles.size)
    }

    @Test fun `oversized json stays complete bounded and cooperatively cancels`() {
        val code = (0 until 30000).joinToString(",", "[", "]")
        assertTrue(code.length > LIBRARY_HIGHLIGHT_LIMIT)
        val highlighted = highlightedCode(code, "json", colors)
        assertEquals(code, highlighted.text)
        assertEquals(CodeTokens.DISPLAY_SPAN_LIMIT, highlighted.spanStyles.size)
        var checks = 0
        try {
            highlightedCode(code, "json", colors) { if (++checks == 3) throw CancellationException("superseded") }
            fail("scan ignored cancellation")
        } catch (_: CancellationException) { assertEquals(3, checks) }
    }
}
