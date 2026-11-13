package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalTestApi::class)
class SidebarAgentsInteractionTest {
    private val scale = object : androidx.compose.ui.MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = scale)

    @Before fun reset() {
        scale.scaleFactor = 1f
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("cockpit-navigation", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun antigravityModelMetadataReachesTheDetailHeader() {
        var model by mutableStateOf("native/child")
        val initial = SessionView(SessionSummary("parent", "parent", "root", "/workspace", cli = "Antigravity"))
        rule.setContent {
            val snapshot = org.json.JSONObject().put("subAgents", org.json.JSONArray().put(
                org.json.JSONObject().put("id", "child").put("name", "模型检查").put("model", model)))
            val agent = io.github.modao7067.luma.gui.agent.NativeCliProjection.view(snapshot, initial).subAgents.single()
            val detail = io.github.modao7067.luma.gui.agent.NativeCliProjection.streamedSubAgentDetail(agent,
                SubAgentDetailView("parent", agent, modelLabel = "Wrong parent model", effort = "high"),
                listOf(ModelOption("native/child", "Native Child Model", emptyList())))
            LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
                SubAgentDetailHeader(detail, onBack = {})
            }
        }
        rule.onNodeWithText("Native Child Model").assertIsDisplayed()
        rule.onNodeWithText("Wrong parent model").assertDoesNotExist()
        rule.runOnIdle { model = "flash" }
        rule.onNodeWithText("flash").assertIsDisplayed()
        rule.onNodeWithText("Native Child Model").assertDoesNotExist()
        rule.runOnIdle { model = "inherit" }
        rule.onNodeWithText("继承父代理").assertIsDisplayed()
    }

    @Test fun sidebarKeepsTwoLevelsLongPressActionsAndZeroScaleMotion() {
        val sessions = listOf(
            SessionSummary("a", "会话甲", "noroot", "/workspace"),
            SessionSummary("b", "会话乙", "noroot", "/workspace"),
        )
        var selected by mutableStateOf("a")
        val opens = mutableListOf<String>()
        val archives = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                SessionSidebar(
                    sessions = sessions,
                    selected = selected,
                    onOpen = { opens += it; selected = it },
                    onNew = {},
                    enabledClis = setOf("Codex"),
                    onArchive = { archives += it },
                )
            }
        }
        rule.onNodeWithText("Codex").performClick()
        rule.onNodeWithText("会话甲").assertIsDisplayed()
        rule.onNodeWithText("会话乙").assertIsDisplayed()
        rule.onNodeWithText("会话乙").performClick()
        rule.runOnIdle { assertEquals(listOf("b"), opens) }
        rule.onNodeWithText("会话甲").performTouchInput { longClick() }
        rule.onNodeWithText("归档").performClick()
        rule.runOnIdle { assertEquals(listOf("a"), archives) }

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Codex").performClick()
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithText("Codex").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("会话甲").assertIsDisplayed()

        scale.scaleFactor = 0f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Codex").performClick()
        repeat(5) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithText("会话甲").assertDoesNotExist()
        rule.onNodeWithText("Codex").performClick()
        repeat(5) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithText("会话甲").assertIsDisplayed()
        rule.mainClock.autoAdvance = true

        assertNotEquals(subAgentIdentityKey("a", "shared"), subAgentIdentityKey("b", "shared"))
        assertNotEquals(subAgentIdentityKey("a:b", "c"), subAgentIdentityKey("a", "b:c"))
        assertEquals("shared", parseSubAgentIdentityKey(subAgentIdentityKey("a:b", "shared"), "a:b"))
        assertNull(parseSubAgentIdentityKey(subAgentIdentityKey("a", "shared"), "b"))
    }

    @Test fun floatingControlsAlignCentersAndMaintainLeftAnchorAcross1To123Agents() {
        var agentCount by mutableIntStateOf(1)
        var dark by mutableStateOf(false)
        val baseMessages = (1..28).map { ConversationItem("m$it", "assistant", "历史消息 $it\n第二行正文\n第三行正文") }
        rule.setContent {
            LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
                CompositionLocalProvider(LocalOverscrollFactory provides null, LocalInspectionMode provides true) {
                    val agents = (1..agentCount).map {
                        SubAgentState("agent-$it", "代理 $it", "explorer", "turn-1", "spawn", "检查任务 $it", if (it == 1) "running" else "succeeded")
                    }
                    val view = sampleView().copy(
                        messages = baseMessages,
                        subAgents = agents,
                        queue = emptyList(),
                        draft = "",
                    )
                    CockpitScreen(CockpitState(selected = view), previewActions)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY - 120f), Offset(centerX, centerY + 180f), 400)
        }
        rule.waitForIdle()

        val guiDensity = GuiDensity(rule.density)
        val expectedDiameterPx = with(guiDensity) { FloatingChromeSize.roundToPx().toFloat() }
        val expectedRadiusPx = expectedDiameterPx / 2f

        val topLeft = rule.onNodeWithContentDescription("打开会话侧边栏").fetchSemanticsNode().boundsInRoot
        val topRight = rule.onNodeWithContentDescription("更多").fetchSemanticsNode().boundsInRoot
        val bottomRight = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot

        assertEquals(expectedDiameterPx, topLeft.width, 0.5f)
        assertEquals(expectedDiameterPx, topLeft.height, 0.5f)
        assertEquals(expectedDiameterPx, topRight.width, 0.5f)
        assertEquals(expectedDiameterPx, topRight.height, 0.5f)
        assertEquals(expectedDiameterPx, bottomRight.width, 0.5f)
        assertEquals(expectedDiameterPx, bottomRight.height, 0.5f)
        assertEquals("Back-to-bottom center X must align with top-right More button", topRight.center.x, bottomRight.center.x, 0.5f)

        val widths = mutableListOf<Float>()
        for (count in listOf(1, 12, 123)) {
            for (themeDark in listOf(false, true)) {
                rule.runOnIdle { agentCount = count; dark = themeDark }
                rule.waitForIdle()
                val capsule = rule.onNodeWithContentDescription("子代理 $count 个").fetchSemanticsNode().boundsInRoot
                val jump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
                rule.onNodeWithText(count.toString(), substring = false).assertIsDisplayed()
                assertEquals(expectedDiameterPx, capsule.height, 0.5f)
                val capsuleLeftCircleCenterX = capsule.left + expectedRadiusPx
                assertEquals("Capsule left circle center must align with top-left button (count=$count, dark=$themeDark)", topLeft.center.x, capsuleLeftCircleCenterX, 0.5f)
                assertEquals("Both bottom controls must share center Y", jump.center.y, capsule.center.y, 0.5f)
                assertEquals("Both bottom controls must share bottom edge", jump.bottom, capsule.bottom, 0.5f)
                if (!themeDark) widths += capsule.width
            }
        }
        assertTrue("Capsule must extend rightward as digits grow 1 -> 12 -> 123: $widths", widths[0] < widths[1] && widths[1] < widths[2])

        rule.runOnIdle { agentCount = 0 }
        rule.waitForIdle()
        val soloJump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        assertEquals(topRight.center.x, soloJump.center.x, 0.5f)

        rule.runOnIdle { agentCount = 3 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("↓ 回到底部").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("↓ 回到底部").assertDoesNotExist()
        val soloCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        assertEquals(topLeft.center.x, soloCapsule.left + expectedRadiusPx, 0.5f)
        println("PASS geometry alignment across 1/12/123 counts, light/dark themes, and solo visibility")
    }

    @Test
    @Config(qualifiers = "w320dp-h760dp")
    fun queueAvoidanceComposerGrowthAndMenuAnchorStayInLockstepOnNarrowScreen() {
        var view by mutableStateOf(
            queueAgentsFixture(queue = false, agents = true).copy(
                messages = (1..28).map { ConversationItem("m$it", "assistant", "消息 $it\n第二行\n第三行") },
            ),
        )
        rule.setContent {
            LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    CockpitScreen(CockpitState(selected = view), previewActions)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY - 120f), Offset(centerX, centerY + 180f), 400)
        }
        rule.waitForIdle()

        val guiDensity = GuiDensity(rule.density)
        val expectedRadiusPx = with(guiDensity) { FloatingChromeSize.roundToPx() } / 2f
        val expectedGapPx = with(guiDensity) { FloatingOverlayBottomGap.toPx() }
        val topLeft = rule.onNodeWithContentDescription("打开会话侧边栏").fetchSemanticsNode().boundsInRoot
        val topRight = rule.onNodeWithContentDescription("更多").fetchSemanticsNode().boundsInRoot
        val noQueueCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        val noQueueJump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        val plusButton = rule.onNodeWithContentDescription("添加附件").fetchSemanticsNode().boundsInRoot
        assertEquals(topLeft.center.x, noQueueCapsule.left + expectedRadiusPx, 0.5f)
        assertEquals(topRight.center.x, noQueueJump.center.x, 0.5f)
        assertEquals(noQueueCapsule.bottom, noQueueJump.bottom, 0.5f)
        assertEquals("Floating controls sit 8dp above compact composer top edge", plusButton.top - expectedGapPx, noQueueCapsule.bottom, 1.5f)

        val popupMenuItem = hasAnyAncestor(isPopup()) and hasText("协议检查")
        rule.onNodeWithContentDescription("子代理 3 个").performClick()
        rule.waitForIdle()
        rule.onNode(popupMenuItem).assertIsDisplayed()
        rule.onNodeWithContentDescription("子代理 3 个").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))

        rule.runOnIdle {
            view = view.copy(queue = listOf(QueueEntry("q1", "单条队列消息", "排队 · 下一轮")))
        }
        rule.waitForIdle()
        val oneQueueCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        val oneQueueJump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        val queueHeader = rule.onNodeWithText("待发消息 · 1").fetchSemanticsNode().boundsInRoot
        assertTrue("Floating controls must rise above single queue item", oneQueueCapsule.bottom < noQueueCapsule.bottom)
        assertEquals(oneQueueCapsule.bottom, oneQueueJump.bottom, 0.5f)
        assertEquals("Floating controls sit 8dp above queue top edge", queueHeader.top - with(guiDensity) { 2.dp.toPx() } - expectedGapPx, oneQueueCapsule.bottom, 1.5f)
        rule.onNode(popupMenuItem).assertIsDisplayed()

        rule.runOnIdle {
            view = view.copy(queue = queueAgentsFixture(queue = true, agents = true).queue)
        }
        rule.waitForIdle()
        val twoQueueCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        val twoQueueJump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        assertTrue("Floating controls must rise further for 2 queue items", twoQueueCapsule.bottom < oneQueueCapsule.bottom)
        assertEquals(twoQueueCapsule.bottom, twoQueueJump.bottom, 0.5f)
        rule.onNode(popupMenuItem).assertIsDisplayed()

        rule.onNodeWithContentDescription("子代理 3 个").performClick()
        rule.waitForIdle()
        rule.onNode(popupMenuItem).assertDoesNotExist()
        rule.onNodeWithContentDescription("子代理 3 个").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { view = view.copy(queue = emptyList()) }
        repeat(8) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val c = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
            val j = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
            assertEquals("Both controls must move in lockstep on frame $it", c.bottom, j.bottom, 0.5f)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val clearedCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        assertEquals("No phantom queue space after queue clears", noQueueCapsule.bottom, clearedCapsule.bottom, 1f)

        rule.runOnIdle { view = view.copy(draft = (1..7).joinToString("\n") { "第 $it 行输入草稿" }) }
        rule.waitForIdle()
        val sevenLineCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        val sevenLineJump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        assertTrue("7-line composer lifts floating controls", sevenLineCapsule.bottom < clearedCapsule.bottom)
        assertEquals(sevenLineCapsule.bottom, sevenLineJump.bottom, 0.5f)

        rule.runOnIdle { view = view.copy(draft = (1..10).joinToString("\n") { "第 $it 行输入草稿" }) }
        rule.waitForIdle()
        val tenLineCapsule = rule.onNodeWithContentDescription("子代理 3 个").fetchSemanticsNode().boundsInRoot
        assertEquals("Beyond 7 lines scrolls internally without growing composer further", sevenLineCapsule.bottom, tenLineCapsule.bottom, 1.5f)
        println("PASS narrow screen queue avoidance, 1..7+ line composer growth, and capsule menu toggle/anchor tracking")
    }

    @Test fun subAgentFullScreenDetailIsReadOnlyPreservesParentStateAndIsolatesSessionIdentity() {
        val longTask = (1..8).joinToString("\n") { "第 $it 步：检查子代理只读全屏详情的布局与代码块滚动边界。" } + "\n" + "补充说明文字".repeat(30)
        val sessionA = sampleView().copy(
            summary = sampleView().summary.copy(id = "A", title = "父会话 A"),
            draft = "父会话 A 的未发送草稿",
            attachments = listOf(Attachment("att-1", "file:///tmp/a.png", "a.png")),
            messages = (1..26).map { ConversationItem("a-$it", "assistant", "父会话 A 消息 $it\n第二行\n第三行") },
            subAgents = listOf(
                SubAgentState("shared", "甲子代理", "explorer", "turn-a", "spawn", longTask, "running", "甲初始输出\n```kotlin\nval x = 1\n```"),
            ),
        )
        val sessionB = sampleView().copy(
            summary = sampleView().summary.copy(id = "B", title = "父会话 B"),
            draft = "父会话 B 的草稿",
            messages = listOf(ConversationItem("b-1", "assistant", "父会话 B 消息 1")),
            subAgents = listOf(
                SubAgentState("shared", "乙子代理", "reviewer", "turn-b", "spawn", "乙短任务", "failed", "乙错误详情"),
                SubAgentState("second", "乙二号代理", "worker", "turn-b", "spawn", "乙二号任务", "succeeded", "乙二号完成"),
            ),
        )
        var currentSession by mutableStateOf(sessionA)
        var detailState by mutableStateOf<SubAgentDetailView?>(null)
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun openSubAgent(parentSessionId: String, agentId: String) {
                calls += "open:$parentSessionId:$agentId"
                if (parentSessionId == "A" && agentId == "shared") {
                    detailState = SubAgentDetailView(
                        parentSessionId = "A",
                        agent = currentSession.subAgents.first(),
                        modelLabel = "GPT-5.4",
                        effort = "high",
                        messages = listOf(
                            ConversationItem("tool-1", "tool", text = "读取配置", kind = "read", input = "cat config.toml", detail = "ok", outcome = "succeeded"),
                            ConversationItem("ans-1", "assistant", text = "甲流式正文"),
                        ),
                    )
                }
            }
            override fun closeSubAgent() {
                calls += "close"
                detailState = null
            }
            override fun stop() {
                calls += "stop"
            }
        }
        rule.setContent {
            LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    CockpitScreen(
                        state = CockpitState(
                            sessions = listOf(sessionA.summary, sessionB.summary),
                            selected = currentSession,
                            subAgentDetail = detailState,
                        ),
                        actions = actions,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, centerY - 120f), Offset(centerX, centerY + 180f), 400)
        }
        rule.waitForIdle()
        val trackedNode = rule.onAllNodesWithText("父会话 A 消息", substring = true).onFirst()
        val beforeParentMsgTop = trackedNode.fetchSemanticsNode().boundsInRoot.top

        val popupItemA = hasAnyAncestor(isPopup()) and hasText("甲子代理")
        rule.runOnIdle {
            currentSession = currentSession.copy(
                subAgents = listOf(currentSession.subAgents.single().copy(detail = "甲更新输出")),
            )
        }
        rule.waitForIdle()
        rule.onNode(popupItemA).assertDoesNotExist()
        assertEquals(beforeParentMsgTop, trackedNode.fetchSemanticsNode().boundsInRoot.top, 1f)

        rule.onNodeWithContentDescription("子代理 1 个").performClick()
        rule.waitForIdle()
        rule.onNode(popupItemA).performClick()
        rule.waitForIdle()
        assertEquals(listOf("open:A:shared"), calls)

        rule.onNodeWithContentDescription("返回父会话").assertIsDisplayed()
        rule.onNodeWithText("甲子代理").assertIsDisplayed()
        rule.onNodeWithText("GPT-5.4·high").assertIsDisplayed()
        rule.onNodeWithContentDescription("进行中").assertIsDisplayed()
        rule.onNodeWithText("展开任务").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("收起任务").assertIsDisplayed()
        rule.onNodeWithText("甲流式正文", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("消息草稿").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送").assertDoesNotExist()
        rule.onNodeWithContentDescription("停止任务").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多").assertDoesNotExist()

        rule.onNodeWithContentDescription("返回父会话").performClick()
        rule.waitForIdle()
        assertEquals(listOf("open:A:shared", "close"), calls)
        assertFalse("Closing detail must never stop the sub-agent", "stop" in calls)
        rule.onNodeWithContentDescription("消息草稿").assertTextEquals("父会话 A 的未发送草稿")
        rule.onNodeWithContentDescription("移除").assertExists()
        assertEquals(beforeParentMsgTop, trackedNode.fetchSemanticsNode().boundsInRoot.top, 1f)

        val popupItemB = hasAnyAncestor(isPopup()) and hasText("乙子代理")
        rule.runOnIdle { currentSession = sessionB }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("子代理 2 个").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNode(popupItemB).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("open:B:shared", calls.last())
        rule.onNodeWithText("乙短任务").assertIsDisplayed()
        rule.onNodeWithText("乙错误详情").assertIsDisplayed()
        rule.onNodeWithText("甲流式正文").assertDoesNotExist()
        rule.onNodeWithText("GPT-5.4·high").assertDoesNotExist()
        println("PASS full-screen read-only sub-agent detail, parent state preservation, and cross-session identity isolation")
    }

    @Test fun subAgentDetailSupportsScrollFollowRightReturnButtonSystemBackReversalAndTuiGate() {
        var tuiOpened = 0
        var subAgentClosed = 0
        val baseMessages = (1..24).map { ConversationItem("m-$it", "assistant", "子代理输出行 $it\n第二行内容\n第三行内容") }
        var detailMessages by mutableStateOf(baseMessages)
        val agent = SubAgentState("agent-1", "深度检索代理", "explorer", "turn-1", "spawn", "检索代码库", "running")
        val view = sampleView().copy(
            summary = sampleView().summary.copy(id = "parent-1", title = "主会话"),
            connected = false,
            canOpenTui = true,
            draft = "保留草稿",
            subAgents = listOf(agent),
        )
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null
        val actions = object : CockpitActions by previewActions {
            override fun openTui() { tuiOpened++ }
            override fun closeSubAgent() { subAgentClosed++ }
        }
        rule.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    CockpitScreen(
                        state = CockpitState(
                            selected = view,
                            subAgentDetail = SubAgentDetailView(
                                parentSessionId = "parent-1",
                                agent = agent,
                                messages = detailMessages,
                            ),
                        ),
                        actions = actions,
                    )
                }
            }
        }
        rule.waitForIdle()

        val statusBounds = rule.onNodeWithContentDescription("进行中").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("subagent-detail-list").performTouchInput {
            swipe(Offset(centerX, centerY - 120f), Offset(centerX, centerY + 180f), 400)
        }
        rule.waitForIdle()
        val returnBounds = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        assertEquals("Sub-agent detail return button must align with top-right status indicator", statusBounds.center.x, returnBounds.center.x, 0.5f)

        val anchorNode = rule.onAllNodesWithText("子代理输出行", substring = true).onFirst()
        val anchorTop = anchorNode.fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle {
            detailMessages = detailMessages + ConversationItem("m-25", "assistant", "新流式到达内容")
        }
        rule.waitForIdle()
        assertEquals("Streaming update in sub-agent detail must not steal upward scroll position", anchorTop, anchorNode.fetchSemanticsNode().boundsInRoot.top, 1f)

        rule.onNodeWithContentDescription("↓ 回到底部").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("↓ 回到底部").assertDoesNotExist()

        rule.runOnIdle { backDispatcher!!.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, subAgentClosed)
        rule.onNodeWithContentDescription("消息草稿").assertTextEquals("保留草稿")

        scale.scaleFactor = 0f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("子代理 1 个").performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNode(hasAnyAncestor(isPopup()) and hasText("深度检索代理")).performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithContentDescription("返回父会话").assertIsDisplayed().performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithContentDescription("返回父会话").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        scale.scaleFactor = 1f

        rule.onNodeWithContentDescription("会话 Dashboard").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("终端").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, tuiOpened) }
        println("PASS sub-agent detail scroll follow, right return button alignment, system Back, zero-scale motion, and canOpenTui gate")
    }
}
