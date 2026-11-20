package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
        val code = "@Composable\nfun render(item: RenderItem): String {\n    val label = \"step-\$index: \${item.title}\\n\" // note\n    Modifier.fillMaxWidth().padding(horizontal = 12.dp)\n    return label + 42\n}\n"
        val highlighted = highlightedCode(code, "kotlin", colors)
        assertEquals(code, highlighted.text)
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("fun"))
        assertEquals(LightCodePalette.function, highlighted.colorAt("render"))
        assertEquals(LightCodePalette.type, highlighted.colorAt("RenderItem"))
        assertEquals(LightCodePalette.type, highlighted.colorAt("String"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("val"))
        assertNull(highlighted.colorAt("label"))
        assertNull(highlighted.colorAt("item"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("step-"))
        assertEquals(LightCodePalette.variable, highlighted.colorAt("\$index"))
        assertEquals(LightCodePalette.property, highlighted.colorAt("title"))
        assertEquals(LightCodePalette.escape, highlighted.colorAt("\\n"))
        assertEquals(LightCodePalette.comment, highlighted.colorAt("note"))
        assertEquals(LightCodePalette.type, highlighted.colorAt("Modifier"))
        assertEquals(LightCodePalette.function, highlighted.colorAt("fillMaxWidth"))
        assertEquals(LightCodePalette.function, highlighted.colorAt("padding"))
        assertEquals(LightCodePalette.property, highlighted.colorAt("horizontal"))
        assertEquals(LightCodePalette.number, highlighted.colorAt("12"))
        assertEquals(LightCodePalette.property, highlighted.colorAt("dp"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("return"))
        assertEquals(LightCodePalette.operator, highlighted.colorAt("+"))
        assertEquals(LightCodePalette.number, highlighted.colorAt("42"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("Composable"))
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test fun `editorial syntax palettes keep readable contrast and balanced saturation`() {
        val lightBg = contentBlockColors(lightColorScheme()).surface
        val darkBg = contentBlockColors(darkColorScheme()).surface
        val nonCommentRoles = listOf(
            "keyword" to (LightCodePalette.keyword to DarkCodePalette.keyword),
            "function" to (LightCodePalette.function to DarkCodePalette.function),
            "command" to (LightCodePalette.command to DarkCodePalette.command),
            "type" to (LightCodePalette.type to DarkCodePalette.type),
            "string" to (LightCodePalette.string to DarkCodePalette.string),
            "number" to (LightCodePalette.number to DarkCodePalette.number),
            "property" to (LightCodePalette.property to DarkCodePalette.property),
            "variable" to (LightCodePalette.variable to DarkCodePalette.variable),
            "annotation" to (LightCodePalette.annotation to DarkCodePalette.annotation),
            "escape" to (LightCodePalette.escape to DarkCodePalette.escape),
            "operator" to (LightCodePalette.operator to DarkCodePalette.operator),
            "added" to (LightCodePalette.added to DarkCodePalette.added),
            "removed" to (LightCodePalette.removed to DarkCodePalette.removed),
            "hunk" to (LightCodePalette.hunk to DarkCodePalette.hunk),
        )
        nonCommentRoles.forEach { (name, pair) ->
            val (light, dark) = pair
            val lightContrast = contrast(light, lightBg)
            val darkContrast = contrast(dark, darkBg)
            assertTrue("light $name contrast $lightContrast < 4.5", lightContrast >= 4.5f)
            assertTrue("dark $name contrast $darkContrast < 4.5", darkContrast >= 4.5f)
            assertTrue("dark $name saturation ${hslSaturation(dark)} > 0.68", hslSaturation(dark) <= 0.68f)
        }
        val lightCommentContrast = contrast(LightCodePalette.comment, lightBg)
        val darkCommentContrast = contrast(DarkCodePalette.comment, darkBg)
        assertTrue("light comment contrast $lightCommentContrast out of 3.5..5.5", lightCommentContrast in 3.5f..5.5f)
        assertTrue("dark comment contrast $darkCommentContrast out of 3.5..5.5", darkCommentContrast in 3.5f..5.5f)
        assertTrue("dark comment saturation ${hslSaturation(DarkCodePalette.comment)} > 0.68", hslSaturation(DarkCodePalette.comment) <= 0.68f)

        val primaryLight = listOf(
            "keyword" to LightCodePalette.keyword,
            "function" to LightCodePalette.function,
            "type" to LightCodePalette.type,
            "string" to LightCodePalette.string,
            "number" to LightCodePalette.number,
            "annotation" to LightCodePalette.annotation,
            "added" to LightCodePalette.added,
            "removed" to LightCodePalette.removed,
        )
        primaryLight.forEach { (name, color) ->
            val sat = hslSaturation(color)
            assertTrue("light $name saturation $sat < 0.38", sat >= 0.38f)
        }
        assertTrue(hslSaturation(LightCodePalette.property) < hslSaturation(LightCodePalette.function))
        assertTrue(hslSaturation(DarkCodePalette.property) < hslSaturation(DarkCodePalette.function))
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
        assertEquals(DarkCodePalette.property, highlighted.colorAt("/workspace/docs"))
        assertEquals(DarkCodePalette.operator, highlighted.colorAt(";"))
        assertEquals(DarkCodePalette.command, highlighted.colorAt("readlink"))
        assertEquals(DarkCodePalette.annotation, highlighted.colorAt("-f"))
        assertEquals(DarkCodePalette.command, highlighted.colorAt("stat"))

        val complex = "PYTHONPATH=src env -i python3 -m unittest https://example.test#frag 2>&1 | tee \"\$OUT\" # note\nif [ -f a ]; then\npython3 - <<'PY'\nprint(\"don't bleed\")\nPY\necho \$HOME 42\ngit status -sb\nfi"
        val script = highlightedCode(complex, "shell", colors)
        assertEquals(complex, script.text)
        assertEquals(LightCodePalette.variable, script.colorAt("PYTHONPATH"))
        assertEquals(LightCodePalette.command, script.colorAt("env"))
        assertEquals(LightCodePalette.annotation, script.colorAt("-i"))
        assertEquals(LightCodePalette.command, script.colorAt("python3"))
        assertEquals(LightCodePalette.annotation, script.colorAt("-m"))
        assertEquals(LightCodePalette.property, script.colorAt("https://example.test#frag"))
        assertNotEquals(LightCodePalette.comment, script.colorAt("#frag"))
        assertEquals(LightCodePalette.operator, script.colorAt("2>&1"))
        assertEquals(LightCodePalette.command, script.colorAt("tee"))
        assertEquals(LightCodePalette.variable, script.colorAt("\$OUT"))
        assertEquals(LightCodePalette.comment, script.colorAt("# note"))
        assertEquals(LightCodePalette.keyword, script.colorAt("if"))
        assertEquals(LightCodePalette.keyword, script.colorAt("then"))
        assertEquals(LightCodePalette.string, script.colorAt("print(\"don't bleed\")"))
        assertEquals(LightCodePalette.command, script.colorAt("echo"))
        assertEquals(LightCodePalette.variable, script.colorAt("\$HOME"))
        assertEquals(LightCodePalette.number, script.colorAt("42"))
        assertEquals(LightCodePalette.command, script.colorAt("git"))
        assertEquals(LightCodePalette.type, script.colorAt("status"))
        assertEquals(LightCodePalette.annotation, script.colorAt("-sb"))
        assertEquals(LightCodePalette.keyword, script.colorAt("fi"))
    }

    @Test fun `config files highlight sections keys inline tables literals numbers and comments`() {
        val toml = "[versions]\nagp = \"9.5.0\" # pin\nenabled = true\ncount = 21\nandroidx-core = { group = \"androidx.core\", version.ref = \"core\" }\n"
        val highlighted = highlightedCode(toml, "toml", colors)
        assertEquals(toml, highlighted.text)
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("[versions]"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("agp"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("\"9.5.0\""))
        assertEquals(LightCodePalette.comment, highlighted.colorAt("# pin"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("true"))
        assertEquals(LightCodePalette.number, highlighted.colorAt("21"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("androidx-core"))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("group"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("\"androidx.core\""))
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("version.ref"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("\"core\""))
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

    @Test fun `json fields values escapes numbers literals and comments use their own roles`() {
        val code = """{"url":"https://example.com","count":-1.25e+3,"ok":true,"none":null}"""
        val highlighted = highlightedCode(code, "json", colors)
        assertEquals(code, highlighted.text)
        assertEquals(LightCodePalette.annotation, highlighted.colorAt("\"url\""))
        assertEquals(LightCodePalette.operator, highlighted.colorAt(":"))
        assertEquals(LightCodePalette.string, highlighted.colorAt("\"https://example.com\""))
        assertEquals(LightCodePalette.number, highlighted.colorAt("-1.25e+3"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("true"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("null"))
        assertEquals(LightCodePalette.annotation, highlightedCode(code, "params", colors).colorAt("\"count\""))
        assertEquals(LightCodePalette.annotation, highlightedCode(code, "output · error", colors).colorAt("\"ok\""))

        val escaped = """{"msg":"line1\nline2"}"""
        val escapedHighlighted = highlightedCode(escaped, "json", colors)
        assertEquals(LightCodePalette.string, escapedHighlighted.colorAt("line1"))
        assertEquals(LightCodePalette.escape, escapedHighlighted.colorAt("\\n"))
        assertEquals(LightCodePalette.string, escapedHighlighted.colorAt("line2"))

        val relaxed = "{\n// note\n\"key\": [1, 2,],\n/* tail */}"
        val relaxedHighlighted = highlightedCode(relaxed, "jsonc", colors)
        assertEquals(relaxed, relaxedHighlighted.text)
        assertEquals(LightCodePalette.comment, relaxedHighlighted.colorAt("// note"))
        assertEquals(LightCodePalette.comment, relaxedHighlighted.colorAt("/* tail */"))
        assertEquals(LightCodePalette.annotation, relaxedHighlighted.colorAt("\"key\""))
    }

    @Test fun `markup and stylesheet languages highlight tags attributes selectors and values`() {
        val xml = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.app\">"
        val xmlHighlighted = highlightedCode(xml, "xml", colors)
        assertEquals(xml, xmlHighlighted.text)
        assertEquals(LightCodePalette.keyword, xmlHighlighted.colorAt("manifest"))
        assertEquals(LightCodePalette.annotation, xmlHighlighted.colorAt("xmlns"))
        assertEquals(LightCodePalette.property, xmlHighlighted.colorAt("android"))
        assertEquals(LightCodePalette.property, xmlHighlighted.colorAt("package"))
        assertEquals(LightCodePalette.string, xmlHighlighted.colorAt("\"com.example.app\""))

        val css = ".card:hover { --accent: #2A78D6; padding: 12px; }"
        val cssHighlighted = highlightedCode(css, "css", colors)
        assertEquals(css, cssHighlighted.text)
        assertEquals(LightCodePalette.type, cssHighlighted.colorAt(".card"))
        assertEquals(LightCodePalette.annotation, cssHighlighted.colorAt(":hover"))
        assertEquals(LightCodePalette.variable, cssHighlighted.colorAt("--accent"))
        assertEquals(LightCodePalette.number, cssHighlighted.colorAt("#2A78D6"))
        assertEquals(LightCodePalette.property, cssHighlighted.colorAt("padding"))
        assertEquals(LightCodePalette.number, cssHighlighted.colorAt("12px"))
    }

    @Test fun `diff hunk highlights prefixes and inner language syntax cleanly`() {
        val kotlinHunk = listOf(
            DiffLine(DiffLineKind.CONTEXT, 10, 10, "fun apply(item: Item) {"),
            DiffLine(DiffLineKind.DELETION, 11, null, "    val old = \"raw\""),
            DiffLine(DiffLineKind.ADDITION, null, 11, "    Modifier.padding(horizontal = 12.dp)"),
        )
        val ktHighlighted = highlightedDiffHunk(kotlinHunk, "kotlin", colors)
        assertEquals("  fun apply(item: Item) {\n-     val old = \"raw\"\n+     Modifier.padding(horizontal = 12.dp)", ktHighlighted.text)
        assertEquals(LightCodePalette.keyword, ktHighlighted.colorAt("fun"))
        assertEquals(LightCodePalette.function, ktHighlighted.colorAt("apply"))
        assertEquals(LightCodePalette.removed, ktHighlighted.colorAt("-"))
        assertEquals(LightCodePalette.string, ktHighlighted.colorAt("\"raw\""))
        assertEquals(LightCodePalette.added, ktHighlighted.colorAt("+"))
        assertEquals(LightCodePalette.type, ktHighlighted.colorAt("Modifier"))
        assertEquals(LightCodePalette.function, ktHighlighted.colorAt("padding"))
        assertEquals(LightCodePalette.property, ktHighlighted.colorAt("horizontal"))
        assertEquals(LightCodePalette.number, ktHighlighted.colorAt("12"))
        assertEquals(LightCodePalette.property, ktHighlighted.colorAt("dp"))

        val shellHunk = listOf(
            DiffLine(DiffLineKind.ADDITION, null, 1, "git status -sb"),
        )
        val shHighlighted = highlightedDiffHunk(shellHunk, "bash", colors)
        assertEquals(LightCodePalette.added, shHighlighted.colorAt("+"))
        assertEquals(LightCodePalette.command, shHighlighted.colorAt("git"))
        assertEquals(LightCodePalette.type, shHighlighted.colorAt("status"))
        assertEquals(LightCodePalette.annotation, shHighlighted.colorAt("-sb"))

        val jsonHunk = listOf(
            DiffLine(DiffLineKind.DELETION, 4, null, "  \"version\": \"1.0.0\","),
            DiffLine(DiffLineKind.ADDITION, null, 4, "  \"version\": \"1.1.0\","),
        )
        val jsonHighlighted = highlightedDiffHunk(jsonHunk, "json", colors)
        assertEquals(LightCodePalette.removed, jsonHighlighted.colorAt("-"))
        assertEquals(LightCodePalette.added, jsonHighlighted.colorAt("+"))
        assertEquals(LightCodePalette.annotation, jsonHighlighted.colorAt("\"version\""))
        assertEquals(LightCodePalette.string, jsonHighlighted.colorAt("\"1.0.0\""))
        assertEquals(LightCodePalette.string, jsonHighlighted.colorAt("\"1.1.0\""))
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

    @Test fun `rust macro lookahead handles trailing whitespace without out of bounds`() {
        listOf("macro! ", "macro!\t", "println! ", "foo!\r\n").forEach { code ->
            val highlighted = highlightedCode(code, "rust", colors)
            assertEquals(code, highlighted.text)
        }
        val valid = highlightedCode("println!(\"ok\"); vec! [1, 2];", "rust", colors)
        assertEquals(LightCodePalette.function, valid.colorAt("println!"))
        assertEquals(LightCodePalette.function, valid.colorAt("vec!"))
    }

    @Test fun `plain identifiers and package segments stay unstyled instead of keyword color`() {
        val code = "package io.github.example.ui\nimport androidx.compose.ui.Modifier\nval phase = (preview ?: phaseState.value)\n"
        val highlighted = highlightedCode(code, "kotlin", colors)
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("package"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("import"))
        assertEquals(LightCodePalette.keyword, highlighted.colorAt("val"))
        assertEquals(LightCodePalette.type, highlighted.colorAt("Modifier"))
        assertEquals(LightCodePalette.property, highlighted.colorAt("value"))
        listOf("io", "github", "example", "androidx", "compose", "phase", "preview", "phaseState").forEach { ident ->
            assertNull("plain identifier $ident must stay default color", highlighted.colorAt(ident))
        }
    }
}
