package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportCodeRendererTest {
    private fun AnnotatedString.colorAt(token: String, from: Int = 0): Color? {
        val start = text.indexOf(token, from)
        assertTrue("missing $token in $text", start >= 0)
        val end = start + token.length
        return spanStyles.lastOrNull { it.start <= start && it.end >= end }?.item?.color
    }

    @Test fun `token index slices preserve multiline comments strings and streaming prefixes`() {
        val code = buildString {
            appendLine("/* start block comment")
            repeat(30) { appendLine("comment line $it") }
            appendLine("end block comment */")
            appendLine("val answer = 42")
            appendLine("val text = \"\"\"")
            repeat(20) { appendLine("string body $it") }
            appendLine("\"\"\"")
            appendLine("fun finish(): Int = answer")
        }
        val index = codeTokenIndex(code, "kotlin")
        val table = buildCodeLineTable(code)
        assertFalse(table.isShort)

        val midCommentChunk = table.chunks[1]
        val commentSlice = index.slice(code, midCommentChunk.startChar, midCommentChunk.endChar, LightCodePalette)
        assertEquals(code.substring(midCommentChunk.startChar, midCommentChunk.endChar), commentSlice.text)
        assertEquals(LightCodePalette.comment, commentSlice.colorAt("comment line 18"))

        val tailChunk = table.chunks.last()
        val tailSlice = index.slice(code, tailChunk.startChar, tailChunk.endChar, DarkCodePalette)
        assertEquals(DarkCodePalette.keyword, tailSlice.colorAt("fun"))
        assertEquals(DarkCodePalette.function, tailSlice.colorAt("finish"))
        assertEquals(DarkCodePalette.type, tailSlice.colorAt("Int"))

        val streamed = code + "\nval extra = 99"
        val streamedSlice = index.slice(streamed, tailChunk.startChar, streamed.length, LightCodePalette)
        assertEquals(streamed.substring(tailChunk.startChar, streamed.length), streamedSlice.text)
        assertEquals(LightCodePalette.keyword, streamedSlice.colorAt("fun"))
    }

    @Test fun `diff token index slices keep multiline syntax state across chunk boundaries`() {
        val lines = buildList {
            add(DiffLine(DiffLineKind.CONTEXT, 1, 1, "/* open comment"))
            repeat(24) { idx ->
                add(DiffLine(DiffLineKind.ADDITION, null, idx + 2, "still in comment $idx"))
            }
            add(DiffLine(DiffLineKind.ADDITION, null, 26, "close comment */"))
            add(DiffLine(DiffLineKind.DELETION, 2, null, "val oldVal = 1"))
            add(DiffLine(DiffLineKind.ADDITION, null, 27, "val newVal = 42"))
        }
        val diffIndex = diffTokenIndex(lines, "kotlin")
        val secondChunk = diffIndex.sliceLines(16, lines.size, LightCodePalette)
        assertEquals(LightCodePalette.added, secondChunk.colorAt("+"))
        assertEquals(LightCodePalette.comment, secondChunk.colorAt("still in comment 18"))
        assertEquals(LightCodePalette.comment, secondChunk.colorAt("close comment */"))
        assertEquals(LightCodePalette.removed, secondChunk.colorAt("-"))
        assertEquals(LightCodePalette.keyword, secondChunk.colorAt("val", secondChunk.text.indexOf("val newVal")))
        assertEquals(LightCodePalette.number, secondChunk.colorAt("42"))
    }

    @Test fun `wrapped chunk layout culls offscreen chunks and preserves measured prefix on append`() {
        val baseCode = (0 until 200).joinToString("\n") { "val line$it = \"value-$it\"" }
        val baseTable = buildCodeLineTable(baseCode)
        assertFalse(baseTable.isShort)
        val state = WrappedChunkLayoutState(baseTable, colsPerLine = 40, lineStepPx = 20, previous = null)
        val topRange = state.visibleRange(0f, 300f, baseCode.length)
        val firstTop = unpackInt1(topRange)
        val lastTop = unpackInt2(topRange)
        assertEquals(0, firstTop)
        assertTrue("top window must cull trailing chunks", lastTop < baseTable.chunks.lastIndex)

        state.recordMeasured(0, 360, fullChunk = true)
        state.recordMeasured(1, 340, fullChunk = true)
        assertEquals(360, state.chunkTopPx(1))
        assertEquals(700, state.chunkTopPx(2))

        val appended = baseCode + "\nval line200 = \"tail\""
        val appendedTable = buildCodeLineTable(appended)
        val nextState = WrappedChunkLayoutState(appendedTable, colsPerLine = 40, lineStepPx = 20, previous = state)
        assertEquals(360, nextState.chunkTopPx(1))
        assertEquals(700, nextState.chunkTopPx(2))

        val partialEnd = baseTable.chunks[2].startChar + 10
        assertEquals(2, state.maxRevealedChunk(partialEnd))
        val partialRange = state.visibleRange(0f, 10_000f, partialEnd)
        assertEquals(2, unpackInt2(partialRange))
    }

    @Test fun `uniform chunk range culls offscreen viewports`() {
        val totalLines = 400
        val chunkCount = chunkLineCountOf(totalLines)
        val midRange = computeUniformVisibleChunkRange(
            totalLines = totalLines,
            chunkCount = chunkCount,
            lineStepPx = 20,
            padTopPx = 10f,
            visTop = 2000f,
            visBottom = 2600f,
        )
        val first = unpackInt1(midRange)
        val last = unpackInt2(midRange)
        assertTrue(first > 0)
        assertTrue(last < chunkCount - 1)
        assertTrue(last - first <= 4)

        val aboveRange = computeUniformVisibleChunkRange(
            totalLines = totalLines,
            chunkCount = chunkCount,
            lineStepPx = 20,
            padTopPx = 10f,
            visTop = -500f,
            visBottom = -10f,
        )
        assertEquals(-1, unpackInt1(aboveRange))

        val belowRange = computeUniformVisibleChunkRange(
            totalLines = totalLines,
            chunkCount = chunkCount,
            lineStepPx = 20,
            padTopPx = 10f,
            visTop = 9000f,
            visBottom = 9500f,
        )
        assertEquals(-1, unpackInt1(belowRange))
    }

    @Test fun `wrapped chunk layout increments layoutVersion monotonically on real height changes`() {
        val code = (0 until 80).joinToString("\n") { "val item$it = $it" }
        val table = buildCodeLineTable(code)
        val state = WrappedChunkLayoutState(table, colsPerLine = 40, lineStepPx = 20, previous = null)
        val initialVersion = state.layoutVersion
        state.recordMeasured(0, 500, fullChunk = true)
        val afterChange = state.layoutVersion
        assertTrue(afterChange > initialVersion)
        state.recordMeasured(0, 500, fullChunk = true)
        assertEquals(afterChange, state.layoutVersion)
    }

    @Test fun `visual column estimation treats box drawing and arrows as single width`() {
        assertEquals(6, estimateVisualColumns("┌──→─┐", 0, 6))
        assertEquals(4, estimateVisualColumns("中文", 0, 2))
        assertEquals(4, estimateVisualColumns("한글", 0, 2))
        val emoji = "🚀"
        assertEquals(2, estimateVisualColumns(emoji, 0, emoji.length))
    }

    @Test fun `oversized single line splits safely into multiple cullable chunks without breaking surrogates`() {
        val prefix = "a".repeat(CODE_CHUNK_MAX_CHARS - 1)
        val singleLine = prefix + "😀" + "b".repeat(CODE_CHUNK_MAX_CHARS * 2)
        val table = buildCodeLineTable(singleLine)
        assertFalse(table.isShort)
        assertTrue(table.chunks.size >= 3)
        table.chunks.forEach { chunk ->
            assertTrue(chunk.endChar - chunk.startChar <= CODE_CHUNK_MAX_CHARS)
            if (chunk.endChar < singleLine.length) {
                assertFalse(
                    Character.isHighSurrogate(singleLine[chunk.endChar - 1]) &&
                        Character.isLowSurrogate(singleLine[chunk.endChar]),
                )
            }
        }
        assertEquals(0, table.chunks.first().startChar)
        assertEquals(singleLine.length, table.chunks.last().endChar)
        val state = WrappedChunkLayoutState(table, colsPerLine = 80, lineStepPx = 20, previous = null)
        val topRange = state.visibleRange(0f, 300f, singleLine.length)
        assertEquals(0, unpackInt1(topRange))
        assertTrue(unpackInt2(topRange) < table.chunks.lastIndex)
    }
}
