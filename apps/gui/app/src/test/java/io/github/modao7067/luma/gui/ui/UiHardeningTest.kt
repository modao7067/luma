package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHardeningTest {
    @Test fun uniqueProcessRenderKeysHandleEmptyDuplicatesAndCollisions() {
        val keys = uniqueProcessRenderKeys(listOf("", "a", "a", "a#2", "", "b"))
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals("a", keys[1])
        assertEquals("b", keys[5])
    }

    @Test fun plainBulletListsAreNotMisclassifiedAsEditDiffs() {
        val bullets = ConversationItem(
            id = "p1",
            role = "tool",
            kind = "tool",
            toolName = "notes",
            detail = "- first bullet\n- second bullet\n- third bullet",
        )
        assertFalse(hasProcessEditPreview(bullets))

        val unifiedWithIndentedDeletion = ConversationItem(
            id = "p2",
            role = "tool",
            kind = "fileChange",
            detail = "--- a/App.kt\n+++ b/App.kt\n-    val old = 1\n+    val next = 2",
        )
        assertTrue(hasProcessEditPreview(unifiedWithIndentedDeletion))
    }

    @Test fun readPreviewEndLineDoesNotOverflowInt() {
        val item = ConversationItem(
            id = "r1",
            role = "tool",
            kind = "tool",
            toolName = "read_file",
            toolKind = "read",
            input = """{"Path":"/project/Demo.kt","StartLine":2147483640,"limit":100}""",
            detail = "val x = 1",
        )
        val preview = parseProcessReadPreview(item, "Codex")
        assertEquals(2147483640, preview.startLine)
        assertEquals(Int.MAX_VALUE, preview.endLine)
    }

    @Test fun typesetAssignsCharMetricsViaBatchAdvancesInsteadOfQuadraticPrefixes() {
        var batchCalls = 0
        var prefixWidthCalls = 0
        val measurer = object : TypesetMeasurer {
            override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
                if (end - start > 1) prefixWidthCalls++
                return (end - start) * 10f
            }

            override fun measureCharAdvances(text: String, start: Int, end: Int, styleId: Int, outAdvances: FloatArray) {
                batchCalls++
                for (i in 0 until (end - start)) {
                    outAdvances[i] = 10f
                }
            }

            override fun measureSpaceWidth(styleId: Int): Float = 5f
            override fun measureHyphenWidth(styleId: Int): Float = 6f
            override fun measureEmWidth(styleId: Int): Float = 16f
            override fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim = PunctuationTrim()
            override fun baseLineMetrics(): TypesetLineMetrics = TypesetLineMetrics(24f, 18f, 18f, 6f)
        }
        val text = "0123456789".repeat(5)
        val layout = TypesetEngine.layout(
            text = text,
            runs = listOf(TypesetRunInput(0, text.length, 0, TypesetSpanKind.NORMAL)),
            maxWidthPx = 800f,
            mode = TypesetMode.BALANCED_LEFT,
            measurer = measurer,
        )
        assertTrue(batchCalls > 0)
        assertTrue(prefixWidthCalls <= 4)
        assertEquals(0f, layout.charLeft[0], 0.01f)
        assertEquals(text.length * 10f, layout.charRight[text.lastIndex], 0.01f)
    }

    @Test fun formulaBitmapByteBudgetDoesNotOverflow() {
        assertEquals(2048 * 1024 * 4, formulaBitmapSizeBytes(2048, 1024))
        assertEquals(Int.MAX_VALUE, formulaBitmapSizeBytes(100_000, 100_000))
        assertEquals(1, formulaBitmapSizeBytes(0, 0))
    }

    @Test fun markdownCoordinatorPublishesMultipleSessionsConcurrently() = runBlocking {
        val coordinator = MarkdownParseCoordinator()
        try {
            val s1 = coordinator.session("s1", "")
            val s2 = coordinator.session("s2", "")
            s1.submit(MarkdownParseTarget("# First\n\nParagraph one", complete = true))
            s2.submit(MarkdownParseTarget("# Second\n\nParagraph two", complete = true))
            repeat(100) {
                if (s1.snapshot.value != null && s2.snapshot.value != null) return@repeat
                delay(10)
            }
            assertEquals("# First\n\nParagraph one", s1.snapshot.value?.source)
            assertEquals("# Second\n\nParagraph two", s2.snapshot.value?.source)
            s1.close()
            s2.close()
        } finally {
            coordinator.close()
        }
    }
}
