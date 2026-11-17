package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.appendInlineContent
import kotlinx.coroutines.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MarkdownPipelineOffscreenBenchmark {
    @get:Rule val rule = createComposeRule()
    private val out = File(System.getProperty("java.io.tmpdir"), "luma-verification/markdown-pipeline-20261105").apply { mkdirs() }
    private val source = buildString {
        append("# Synthetic stable heading\n\n[reference][id]\n\n")
        repeat(20) { append("Paragraph $it with **bold**, `code`, 中文 and emoji 🦋. More synthetic words.\n\n") }
        append("| A | B |\n|---|---|\n| one | two |\n\n```kotlin\nval answer = 42\nprintln(answer)\n```\n\n")
        append("[id]: https://example.invalid/final\n")
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { var n = 0; while (true) { (tasks.poll() ?: break).run(); check(++n < 1000) } }
    }
    @Test fun sameSnapshotsDifferentRevealWorkAndBoundedQueue() = runBlocking {
        val snapshots = (256 until source.length step 256).map { source.substring(0, it) } + source
        val frames = buildList {
            var shown = 0
            snapshots.forEach { target ->
                while (shown < target.length) {
                    shown = (shown + ((target.length - shown + 17) / 18).coerceAtLeast(1)).coerceAtMost(target.length)
                    add(target.substring(0, markdownVisibleEnd(target, shown)))
                }
            }
        }
        val baseline = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
        fun old(): Pair<Long, Int> {
            var calls = 0; val begin = System.nanoTime()
            frames.forEach { input -> markdownSourceParts(input).filterNot { it.math }.forEach { baseline.parse(it.source); calls++ } }
            return System.nanoTime() - begin to calls
        }
        fun candidate(): Triple<Long, Long, Long> {
            val parser = MarkdownDocumentParser(); var previous: MarkdownDocument? = null
            val begin = System.nanoTime()
            snapshots.forEachIndexed { i, input -> previous = parser.parse(input, i == snapshots.lastIndex, previous) }
            assertEquals(source, previous?.source)
            assertEquals(MarkdownDocumentParser().parse(source, true).blocks, previous?.blocks)
            return Triple(System.nanoTime() - begin, parser.parses, parser.reusedBlocks)
        }
        repeat(2) { old(); candidate() }
        val lines = mutableListOf("runtime=Ubuntu ARM64 JVM21 Robolectric35 LEGACY; synthetic snapshots=${snapshots.size}; revealFrames=${frames.size}",
            "baseline=parse only; candidate=parse+immutable conversion+semantic reuse; nanoseconds; alternating AB/BA; no Android frame-time inference")
        repeat(6) { sample ->
            if (sample % 2 == 0) { val a=old(); lines += "$sample,baseline,${a.first},${a.second},0"; val b=candidate(); lines += "$sample,candidate,${b.first},${b.second},${b.third}" }
            else { val b=candidate(); lines += "$sample,candidate,${b.first},${b.second},${b.third}"; val a=old(); lines += "$sample,baseline,${a.first},${a.second},0" }
        }
        val dispatcher = QueuedDispatcher()
        val coordinator = MarkdownParseCoordinator(capacity = 2, dispatcher = dispatcher)
        val session = coordinator.session("synthetic-session/message", "")
        repeat(160) { session.submit(MarkdownParseTarget("prefix $it", false)) }
        session.submit(MarkdownParseTarget(source, true))
        assertEquals(1, coordinator.usage().pending)
        dispatcher.drain()
        assertEquals(source, session.snapshot.value!!.source)
        assertEquals(1L, coordinator.parser.parses)
        assertTrue(coordinator.usage().coalesced >= 160)
        val obsolete = MarkdownParseTarget("obsolete", false)
        session.submit(MarkdownParseTarget("replacement", true))
        session.submit(obsolete)
        dispatcher.drain()
        assertEquals("replacement", session.snapshot.value!!.source)
        val other = coordinator.session("other", "")
        session.submit(MarkdownParseTarget("a", false)); other.submit(MarkdownParseTarget("b", false))
        val third = coordinator.session("third", "")
        assertFalse(coordinator.offer(third, MarkdownParseTarget("c", false)))
        dispatcher.drain()
        assertTrue(coordinator.usage().peakPending <= 2); assertEquals(1, coordinator.usage().peakActive)
        assertTrue(markdownResultBelongsTo("hello plus", "hello"))
        assertFalse(markdownResultBelongsTo("replacement", "hello"))
        assertEquals(0, coordinator.usage().pending)
        val cacheBefore = coordinator.parser.parses
        session.submit(MarkdownParseTarget(source, true)); dispatcher.drain()
        assertEquals(cacheBefore, coordinator.parser.parses)
        assertTrue(coordinator.cache.usage().hits > 0)
        lines += "queue=${coordinator.usage()} cache=${coordinator.cache.usage()} (capacity2, 161 snapshots collapsed before worker start)"
        session.close(); other.close(); third.close(); coordinator.close()
        val parser = MarkdownDocumentParser()
        val a = parser.parse("stable\n\n[link][id]\n", false)
        val b = parser.parse("stable\n\n[link][id]\n\n[id]: https://example.invalid/one\n", true, a)
        assertSame(a.blocks.first(), b.blocks.first())
        assertNotSame(a.blocks[1], b.blocks[1])
        val c = parser.parse("stable\n\n[link][id]\n\n[id]: https://example.invalid/two\n", true, b)
        assertSame(b.blocks.first(), c.blocks.first()); assertNotSame(b.blocks[1], c.blocks[1])
        val cache = MarkdownDocumentCache(sourceBudget=128,maxEntries=2,maxSource=64,nodeBudget=32,maxNodes=16)
        repeat(10) { cache.put(parser.parse("item $it", true)) }
        val huge = parser.parse("x".repeat(65), true); cache.put(huge)
        assertEquals(65, huge.source.length); assertNull(cache.get(huge.source))
        assertTrue(cache.usage().entries <= 2 && cache.usage().sourceUnits <= 128 && cache.usage().nodes <= 32)
        assertEquals(1L, cache.usage().rejected)
        assertEquals(1, markdownVisibleEnd("a🦋b", 2))
        val math = buildAnnotatedString { append("a"); appendInlineContent("formula", "abcdef"); append("z") }
        assertEquals("a", visibleMarkdownText(math, 3).text)
        assertEquals(math, visibleMarkdownText(math, Int.MAX_VALUE))
        val real = MarkdownParseCoordinator()
        val live = real.session("real-worker/synthetic", "")
        snapshots.forEachIndexed { index, input -> live.submit(MarkdownParseTarget(input, index == snapshots.lastIndex)); delay(1) }
        withTimeout(15_000) { live.snapshot.first { it?.source == source && it.complete } }
        assertTrue(real.usage().peakActive == 1 && real.usage().peakPending <= 24)
        lines += "actualDefaultWorker=${real.usage()} parses=${real.parser.parses} parseNanos=${real.parser.parseNanos} cache=${real.cache.usage()}"
        live.submit(MarkdownParseTarget("replacement final", true))
        withTimeout(15_000) { live.snapshot.first { it?.source == "replacement final" && it.complete } }
        live.close(); real.close()
        File(out, "markdown-benchmark.txt").writeText(lines.joinToString("\n") + "\n")
    }
    @Test fun visiblePrefixIsTheActualSelectableAndAccessibleText() {
        val text = "visible SECRET_FUTURE"
        var document by mutableStateOf(MarkdownDocumentParser().parse(text, true))
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
            override fun getText() = copied
        }
        var budget by androidx.compose.runtime.mutableIntStateOf(7)
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalClipboardManager provides clipboard) {
            Column { NativeMarkdown(document.source, presentation = MarkdownPresentation(document, budget)) }
        } } }
        rule.onNodeWithText("visible").assertExists()
        rule.onNodeWithText("SECRET_FUTURE", substring = true).assertDoesNotExist()
        assertEquals("visible", rule.onNodeWithText("visible").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text)
        rule.runOnIdle { budget = Int.MAX_VALUE }
        rule.onNodeWithText(text).assertExists()
        val code = "val answer = \"SECRET_FUTURE\""
        rule.runOnIdle { document = MarkdownDocumentParser().parse("```kotlin\n$code\n```", true); budget = 4 }
        rule.onNodeWithText("val ").assertExists()
        rule.onNodeWithText("SECRET_FUTURE", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals("val ", copied!!.text)
        rule.runOnIdle { budget = Int.MAX_VALUE }
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(code, copied?.text)
        rule.runOnIdle { document = MarkdownDocumentParser().parse("| A | B |\n|---|---|\n| shown | SECRET_CELL |", true); budget = 1 }
        rule.onNodeWithText("A").assertExists()
        rule.onNodeWithText("SECRET_CELL").assertDoesNotExist()
        rule.runOnIdle { budget = Int.MAX_VALUE }
        rule.onNodeWithText("SECRET_CELL").assertExists()
        File(out, "markdown-visible-tree.txt").writeText(rule.onRoot().printToString())
    }
    @Test fun markdownDocumentCardCollapsesByDefaultAndExpandsSmoothlyWithoutMovingViewport() {
        val handover = buildString {
            appendLine("下面是一份示例说明：")
            appendLine()
            appendLine("```markdown")
            appendLine("# 示例文档：配置模型菜单")
            appendLine()
            appendLine("## 1. 背景与现状")
            appendLine("工作目录：`/workspace/project`")
            appendLine("- UI 入口：`ModelMenu.kt`")
            appendLine("  ```kotlin")
            appendLine("  val speed = view.speed")
            appendLine("  ```")
            appendLine("- 构建命令：")
            appendLine("  ```bash")
            appendLine("  ./luma assets check")
            appendLine("  ```")
            appendLine("```")
        }.trimEnd()
        val parsed = MarkdownDocumentParser().parse(handover, true)
        var manualToggles = 0
        val knownHeights = HashMap<String, Int>()
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        rule.setContent {
            MaterialTheme {
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 2, initialFirstVisibleItemScrollOffset = 18)
                CompositionLocalProvider(LocalManualLayoutToggle provides { manualToggles++ }) {
                    LazyColumn(state = listState, modifier = Modifier.width(360.dp).height(460.dp)) {
                        items(4, key = { "history-$it" }) { idx ->
                            Box(Modifier.fillMaxWidth().height(44.dp)) { Text("历史消息 $idx") }
                        }
                        item(key = "assistant-markdown") {
                            SmoothTailGrowth(
                                rowId = "message:assistant-md",
                                contentKey = parsed.revealUnits,
                                knownHeights = knownHeights,
                                animateArrival = false,
                            ) {
                                NativeMarkdown(handover, presentation = MarkdownPresentation(parsed, Int.MAX_VALUE))
                            }
                        }
                        items(20, key = { "tail-$it" }) { idx ->
                            Box(Modifier.fillMaxWidth().height(44.dp)) { Text("后续消息 $idx") }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val initialAnchor = rule.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        rule.runOnIdle { assertTrue("Fixture must leave scrollable content below the document", listState.canScrollForward) }
        rule.onNodeWithText("示例文档：配置模型菜单").assertExists()
        rule.onNodeWithText("Markdown 文档 · 12 行 · 含 2 个代码块").assertExists()
        rule.onNodeWithContentDescription("展开").assertExists()
        rule.onNodeWithContentDescription("复制").assertExists()
        fun cardHeight() = rule.onNodeWithTag("markdown-document-card").fetchSemanticsNode().size.height.toFloat()
        val collapsedHeight = cardHeight()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("展开").performClick()
        val expandHeights = mutableListOf(collapsedHeight)
        repeat(35) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            expandHeights += cardHeight()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val expandedHeight = expandHeights.last()
        assertTrue("Expanded card must be taller than collapsed card ($collapsedHeight -> $expandedHeight)", expandedHeight > collapsedHeight + 80f)
        assertTrue("Card expansion must animate smoothly through intermediate heights: $expandHeights", expandHeights.any { it > collapsedHeight + 4f && it < expandedHeight - 4f })
        assertEquals("Expanding card must not shift LazyColumn viewport anchor", initialAnchor, rule.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
        assertEquals(1, manualToggles)
        rule.onNodeWithText("val speed = view.speed").assertExists()
        rule.onNodeWithText("./luma assets check").assertExists()
        rule.onNodeWithText("源码").performClick()
        rule.waitForIdle()
        assertEquals(2, manualToggles)
        assertEquals("Switching to source mode must not shift LazyColumn viewport anchor", initialAnchor, rule.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("收起").performClick()
        val collapseHeights = mutableListOf<Float>()
        repeat(35) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            collapseHeights += cardHeight()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("Card collapse must animate smoothly through intermediate heights: $collapseHeights", collapseHeights.any { it > collapsedHeight + 4f && it < expandedHeight - 4f })
        assertEquals("Collapsed height must return to initial Scheme 2 height", collapsedHeight, collapseHeights.last(), 1.5f)
        assertEquals("Collapsing card must not shift LazyColumn viewport anchor", initialAnchor, rule.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
        assertEquals(3, manualToggles)
    }
}
