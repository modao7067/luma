package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SessionSummary
import io.github.modao7067.luma.gui.session.SessionView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.ConscryptMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StreamTailFollowTest {
    @get:Rule val rule = createComposeRule()
    @Test fun toolOutputExcerptAndStreamingMessageGlideWithoutOneFrameHeightJumps() {
        val knownHeights = HashMap<String, Int>()
        var streamText by mutableStateOf("第一行正文")
        var toolItem by mutableStateOf(ConversationItem("t1", "tool", text = "执行命令", kind = "commandExecution", input = "ls", detail = "", outcome = "running"))
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        ProcessGroup(listOf(toolItem))
                    }
                    SmoothTailGrowth(
                        rowId = "message:stream",
                        contentKey = streamText.length,
                        knownHeights = knownHeights,
                        animateArrival = false,
                    ) {
                        Text(streamText, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        rule.waitForIdle()
        val initialTextHeight = rule.runOnIdle { knownHeights["message:stream"] ?: 0 }
        assertTrue("initial message height should be measured: $initialTextHeight", initialTextHeight > 0)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            toolItem = toolItem.copy(detail = "line 1\nline 2\nline 3")
            streamText = (1..8).joinToString("\n") { "第 $it 行流式生成正文" }
        }
        val textHeights = mutableListOf(initialTextHeight)
        repeat(24) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            textHeights += rule.runOnIdle { knownHeights["message:stream"] ?: 0 }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val finalTextHeight = rule.runOnIdle { knownHeights["message:stream"] ?: 0 }
        assertTrue("Streaming text must grow through intermediate spring frames: $textHeights", textHeights.any { it in (initialTextHeight + 1) until finalTextHeight })
        assertTrue("Streaming text height must be monotone non-decreasing: $textHeights", textHeights.zipWithNext().all { (a, b) -> b >= a })
        println("PASS streaming text & tool excerpt intermediate heights: $textHeights -> $finalTextHeight")
    }

    @Test fun lockedSentUserRowDoesNotReverseScrollOnUpwardDragOrTwitchBeforeReachingBottom() {
        val history = (1..24).map { i ->
            ConversationItem("h-$i", if (i % 2 == 1) "user" else "assistant", "历史消息 $i：用于占满历史滚动空间")
        }
        var view by mutableStateOf(
            SessionView(
                summary = SessionSummary("tail-lock-session", "测试会话", "noroot", "/workspace", cli = "Codex"),
                connected = true,
                loading = false,
                messages = history,
            ),
        )
        rule.setContent {
            MaterialTheme {
                SessionDetail(CockpitState(selected = view), view, previewActions, onPickImages = {})
            }
        }
        rule.waitForIdle()

        rule.runOnIdle {
            view = view.copy(
                activeTurnId = "turn-1",
                messages = history + ConversationItem("u-new", "user", "新发送的锁定消息"),
            )
        }
        rule.waitForIdle()

        val lockedUserBounds = rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center, center - Offset(0f, 220f), 160)
        }
        rule.waitForIdle()
        val afterUpSwipe = rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot
        assertEquals("Upward swipe at locked tail must not reverse-scroll user row", lockedUserBounds.top, afterUpSwipe.top, 2f)

        rule.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center, center + Offset(0f, 220f), 160)
        }
        rule.waitForIdle()
        val afterDownSwipe = rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot
        assertTrue("Downward swipe must scroll history down (user row moves down): ${afterDownSwipe.top} vs ${lockedUserBounds.top}",
            afterDownSwipe.top > lockedUserBounds.top + 40f)
        rule.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center, center - Offset(0f, 260f), 160)
        }
        rule.waitForIdle()
        val afterReturnSwipe = rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot
        assertEquals("Return upward swipe must bring user row back to locked top", lockedUserBounds.top, afterReturnSwipe.top, 2f)

        rule.mainClock.autoAdvance = false
        val userTopSamples = mutableListOf<Float>()
        val steps = listOf(
            history + listOf(
                ConversationItem("u-new", "user", "新发送的锁定消息"),
                ConversationItem("t-1", "tool", text = "读取文件", kind = "commandExecution", input = "cat a", detail = "l1\nl2", outcome = "running"),
            ),
            history + listOf(
                ConversationItem("u-new", "user", "新发送的锁定消息"),
                ConversationItem("t-1", "tool", text = "读取文件", kind = "commandExecution", input = "cat a", detail = "l1\nl2\nl3\nl4", outcome = "succeeded"),
                ConversationItem("a-1", "assistant", "第一行回复"),
            ),
            history + listOf(
                ConversationItem("u-new", "user", "新发送的锁定消息"),
                ConversationItem("t-1", "tool", text = "读取文件", kind = "commandExecution", input = "cat a", detail = "l1\nl2\nl3\nl4", outcome = "succeeded"),
                ConversationItem("t-2", "tool", text = "搜索代码", kind = "commandExecution", input = "rg x", detail = "hit 1\nhit 2\nhit 3", outcome = "running"),
                ConversationItem("a-1", "assistant", "第一行回复\n\n第二行流式生成内容"),
            ),
            history + listOf(
                ConversationItem("u-new", "user", "新发送的锁定消息"),
                ConversationItem("t-1", "tool", text = "读取文件", kind = "commandExecution", input = "cat a", detail = "l1\nl2\nl3\nl4", outcome = "succeeded"),
                ConversationItem("t-2", "tool", text = "搜索代码", kind = "commandExecution", input = "rg x", detail = "hit 1\nhit 2\nhit 3", outcome = "succeeded"),
                ConversationItem("a-1", "assistant", "第一行回复\n\n第二行流式生成内容\n\n第三行继续生成"),
            ),
        )
        for (msgs in steps) {
            rule.runOnIdle { view = view.copy(messages = msgs) }
            repeat(6) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                userTopSamples += rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot.top
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        userTopSamples += rule.onNodeWithText("新发送的锁定消息").fetchSemanticsNode().boundsInRoot.top
        val maxDrift = userTopSamples.maxOf { abs(it - lockedUserBounds.top) }
        assertTrue("User row top must stay locked without twitching while generating before bottom: maxDrift=$maxDrift samples=$userTopSamples",
            maxDrift <= 2f)

        val longReply = (1..36).joinToString("\n\n") { "第 $it 段较长流式回复内容，用于验证触底后平滑跟随。" }
        rule.runOnIdle {
            view = view.copy(
                messages = history + listOf(
                    ConversationItem("u-new", "user", "新发送的锁定消息"),
                    ConversationItem("a-1", "assistant", longReply),
                ),
            )
        }
        rule.waitForIdle()
        val tailBounds = rule.onNodeWithText("第 36 段较长流式回复内容，用于验证触底后平滑跟随。", substring = true).fetchSemanticsNode().boundsInRoot
        val listBounds = rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Post-bottom streaming must scroll tail into view: tail=$tailBounds list=$listBounds",
            tailBounds.bottom <= listBounds.bottom)
    }

    @Test fun userRowSentDuringReconnectStillAnchorsWhileHydratedHistoryDoesNot() {
        val history = (1..24).map { i ->
            ConversationItem("h-$i", if (i % 2 == 1) "user" else "assistant", "历史消息 $i：用于占满历史滚动空间")
        }
        var view by mutableStateOf(
            SessionView(
                summary = SessionSummary("reconnect-anchor-session", "测试会话", "noroot", "/workspace", cli = "Antigravity"),
                connected = true,
                loading = false,
                messages = history,
            ),
        )
        rule.setContent {
            MaterialTheme {
                SessionDetail(CockpitState(selected = view), view, previewActions, onPickImages = {})
            }
        }
        rule.waitForIdle()
        val listBounds = rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
        val upperBand = listBounds.top + listBounds.height * 0.3f

        rule.runOnIdle {
            view = view.copy(connected = false, loading = true,
                messages = history + ConversationItem("h-ext", "user", "其它客户端写入的历史"))
        }
        rule.waitForIdle()
        rule.runOnIdle { view = view.copy(connected = true, loading = false) }
        rule.waitForIdle()
        val hydrated = rule.onNodeWithText("其它客户端写入的历史").fetchSemanticsNode().boundsInRoot
        assertTrue("Hydrated history must not be anchored: $hydrated band=$upperBand", hydrated.top > upperBand)

        val base = history + ConversationItem("h-ext", "user", "其它客户端写入的历史")
        rule.runOnIdle {
            view = view.copy(connected = false, loading = true,
                messages = base + ConversationItem("u-retry", "user", "重连期间发送", delivery = "pending"))
        }
        rule.waitForIdle()
        val duringLoading = rule.onNodeWithText("重连期间发送").fetchSemanticsNode().boundsInRoot
        assertTrue("Row sent during reconnect must anchor while still loading: $duringLoading band=$upperBand", duringLoading.top <= upperBand)
        rule.runOnIdle {
            view = view.copy(connected = true, loading = false, messages = base + listOf(
                ConversationItem("u-retry", "user", "重连期间发送"),
                ConversationItem("a-fail", "assistant", "Operation aborted"),
            ))
        }
        rule.waitForIdle()
        val anchored = rule.onNodeWithText("重连期间发送").fetchSemanticsNode().boundsInRoot
        assertTrue("Row sent during reconnect must anchor near the top: $anchored band=$upperBand", anchored.top <= upperBand)
    }

    @Test fun readingSpaceBelowUserRowSurvivesSwitchingToAnotherSessionAndBack() {
        val history = (1..24).map { i ->
            ConversationItem("h-$i", if (i % 2 == 1) "user" else "assistant", "历史消息 $i：用于占满历史滚动空间")
        }
        var sessionA by mutableStateOf(
            SessionView(
                summary = SessionSummary("session-a", "会话 A", "noroot", "/workspace", cli = "Codex"),
                connected = true,
                loading = false,
                messages = history,
            ),
        )
        val sessionB = SessionView(
            summary = SessionSummary("session-b", "会话 B", "noroot", "/workspace", cli = "Codex"),
            connected = true,
            loading = false,
            messages = listOf(ConversationItem("b-1", "assistant", "会话 B 的消息")),
        )
        var activeId by mutableStateOf("session-a")
        rule.setContent {
            MaterialTheme {
                val savedScreens = rememberSaveableStateHolder()
                val activeView = if (activeId == "session-a") sessionA else sessionB
                savedScreens.SaveableStateProvider(activeView.summary.id) {
                    SessionDetail(CockpitState(selected = activeView), activeView, previewActions, onPickImages = {})
                }
            }
        }
        rule.waitForIdle()
        val listBounds = rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
        val upperBand = listBounds.top + listBounds.height * 0.3f

        rule.runOnIdle {
            sessionA = sessionA.copy(
                messages = history + listOf(
                    ConversationItem("u-new", "user", "新发消息保持顶部留白", delivery = "pending"),
                    ConversationItem("a-short", "assistant", "简短回复"),
                ),
            )
        }
        rule.waitForIdle()
        val anchoredBefore = rule.onNodeWithText("新发消息保持顶部留白").fetchSemanticsNode().boundsInRoot
        assertTrue("User row must anchor near top before switching: $anchoredBefore band=$upperBand",
            anchoredBefore.top <= upperBand)

        rule.runOnIdle { activeId = "session-b" }
        rule.waitForIdle()
        rule.onNodeWithText("会话 B 的消息").fetchSemanticsNode()

        rule.runOnIdle { activeId = "session-a" }
        rule.waitForIdle()
        val anchoredAfter = rule.onNodeWithText("新发消息保持顶部留白").fetchSemanticsNode().boundsInRoot
        val replyAfter = rule.onNodeWithText("简短回复").fetchSemanticsNode().boundsInRoot
        assertTrue("User row and reading space below must stay preserved after switching back: $anchoredAfter band=$upperBand",
            anchoredAfter.top <= upperBand)
        assertTrue("Reading space below reply must remain open after switching back: reply=$replyAfter list=$listBounds",
            replyAfter.bottom < listBounds.bottom - listBounds.height * 0.4f)
    }

    @Test fun collapsingExpandedToolAtBottomTriggersSmoothSecondStageSettleInsteadOfTeleport() {
        val history = (1..24).map { i ->
            ConversationItem("h-$i", if (i % 2 == 1) "user" else "assistant", "历史消息 $i：用于占满历史滚动空间")
        }
        val toolOutput = (1..18).joinToString("\n") { "line $it: output body for collapse test" }
        val view = SessionView(
            summary = SessionSummary("bottom-collapse-session", "测试会话", "noroot", "/workspace", cli = "Codex"),
            connected = true,
            loading = false,
            messages = history + listOf(
                ConversationItem("t-collapse", "tool", text = "执行检查", kind = "commandExecution", input = "echo check", detail = toolOutput, outcome = "succeeded"),
            ),
        )
        rule.setContent {
            MaterialTheme {
                SessionDetail(CockpitState(selected = view), view, previewActions, onPickImages = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 步", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center, center - Offset(0f, 240f), 140)
        }
        rule.waitForIdle()
        rule.onNodeWithText("执行检查", substring = true).performClick()
        rule.waitForIdle()

        repeat(4) {
            rule.onNodeWithTag("conversation-list").performTouchInput {
                swipe(topCenter + Offset(0f, 220f), topCenter + Offset(0f, 40f), 140)
            }
            rule.waitForIdle()
        }

        val beforeCollapseTop = rule.onNodeWithText("执行检查", substring = true).fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("执行检查", substring = true).performClick()

        val tops = mutableListOf<Float>()
        repeat(80) {
            rule.mainClock.advanceTimeBy(16L)
            rule.waitForIdle()
            tops += rule.onNodeWithText("执行检查", substring = true).fetchSemanticsNode().boundsInRoot.top
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val finalTop = rule.onNodeWithText("执行检查", substring = true).fetchSemanticsNode().boundsInRoot.top
        val totalTravel = finalTop - beforeCollapseTop
        assertTrue("Collapsing at bottom must pull tool header downward toward new tail: before=$beforeCollapseTop final=$finalTop", totalTravel > 80f)
        val maxSingleFrameJump = (listOf(beforeCollapseTop) + tops).zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue("Second-stage settle must glide across frames instead of teleporting in one frame: maxJump=$maxSingleFrameJump total=$totalTravel tops=$tops",
            maxSingleFrameJump < totalTravel * 0.45f)
    }

    @Test fun collapsingToolInAnchoredActiveTurnWhileScrolledPartwayDoesNotTwitchAcrossViewportThreshold() {
        val history = (1..20).map { i ->
            ConversationItem("h-$i", if (i % 2 == 1) "user" else "assistant", "历史消息 $i：用于占满历史滚动空间")
        }
        val bigToolOutput = (1..26).joinToString("\n") { "todo line $it: expanded content exceeding viewport" }
        var view by mutableStateOf(
            SessionView(
                summary = SessionSummary("active-turn-collapse", "测试会话", "noroot", "/workspace", cli = "Kimi Code"),
                connected = true,
                loading = false,
                messages = history,
            ),
        )
        rule.setContent {
            MaterialTheme {
                SessionDetail(CockpitState(selected = view), view, previewActions, onPickImages = {})
            }
        }
        rule.waitForIdle()

        rule.runOnIdle {
            view = view.copy(
                activeTurnId = "turn-live",
                messages = history + listOf(
                    ConversationItem("u-anchor", "user", "新发送的吸顶用户消息"),
                    ConversationItem("a-mid", "assistant", "中间阶段说明正文"),
                    ConversationItem("t-done", "tool", toolName = "TodoList", text = "TodoList", kind = "tool", input = "{\"todos\":[]}", detail = bigToolOutput, outcome = "succeeded"),
                    ConversationItem("t-live", "tool", toolName = "Bash", text = "gradlew test", kind = "tool", toolKind = "execute", input = "{\"command\":\"./gradlew test\"}", detail = "running step 1", outcome = "running"),
                ),
            )
        }
        rule.waitForIdle()

        rule.onNodeWithText("正在执行命令…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("TodoList").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center, center - Offset(0f, 180f), 180)
        }
        rule.waitForIdle()

        val startTop = rule.onNodeWithText("中间阶段说明正文").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("TodoList").performClick()

        val tops = mutableListOf(startTop)
        repeat(75) { frame ->
            if (frame == 8 || frame == 20 || frame == 34) {
                rule.runOnIdle {
                    val extra = (1..(frame / 4)).joinToString("\n") { "stream chunk $it" }
                    view = view.copy(
                        messages = view.messages.dropLast(1) + view.messages.last().copy(detail = "running step 1\n$extra"),
                    )
                }
            }
            rule.mainClock.advanceTimeBy(16L)
            rule.waitForIdle()
            tops += rule.onNodeWithText("中间阶段说明正文").fetchSemanticsNode().boundsInRoot.top
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val endTop = rule.onNodeWithText("中间阶段说明正文").fetchSemanticsNode().boundsInRoot.top
        tops += endTop

        val deltas = tops.zipWithNext().map { (a, b) -> b - a }
        val maxStep = deltas.maxOf { abs(it) }
        val reversals = deltas.filter { abs(it) > 1.5f }.zipWithNext().count { (d1, d2) -> (d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f) }
        assertTrue("Mid-collapse across reading-space threshold must not jump in one frame: maxStep=$maxStep tops=$tops", maxStep < 80f)
        assertEquals("Collapse + settle must not bounce/twitch direction: deltas=$deltas", 0, reversals)
    }
}
