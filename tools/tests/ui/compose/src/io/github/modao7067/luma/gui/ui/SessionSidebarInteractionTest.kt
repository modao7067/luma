package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.modao7067.luma.gui.session.SessionSummary
import io.github.modao7067.luma.gui.session.CliCatalog
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
class SessionSidebarInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val sessions = listOf(SessionSummary("codex", "Codex child title", "noroot", "/workspace"),
        SessionSummary("opencode", "Google VAI Argon child title", "noroot", "/workspace", cli = "OpenCode"))
    private var selected by mutableStateOf("opencode")
    private var visible by mutableStateOf(true)
    private val creations = mutableListOf<String>()
    private val preferences get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("cockpit-navigation", Context.MODE_PRIVATE)
    @Before fun resetOwnedPreferences() { preferences.edit().clear().commit() }
    private fun fixture() {
        rule.setContent { MaterialTheme {
            if (visible) SessionSidebar(sessions, selected, { selected = it }, { creations += it }, enabledClis = setOf("Codex", "OpenCode"))
        } }
    }

    @Test fun defaultCatalogIncludesNewAcpGroupsAndEachPlusTargetsItsOwnCli() {
        rule.setContent { MaterialTheme { SessionSidebar(emptyList(), null, {}, { creations += it }) } }
        CliCatalog.visibleIds.forEach { cli ->
            rule.onNodeWithTag("session-sidebar-list").performScrollToNode(hasContentDescription("新建 $cli 会话"))
            rule.onNodeWithContentDescription("新建 $cli 会话").assertExists()
        }
        CliCatalog.temporarilyDisabled.forEach { cli ->
            rule.onNodeWithContentDescription("新建 $cli 会话").assertDoesNotExist()
        }
        listOf("Qoder CLI", "Grok Build").forEach { cli ->
            rule.onNodeWithTag("session-sidebar-list").performScrollToNode(hasContentDescription("新建 $cli 会话"))
            rule.onNodeWithContentDescription("新建 $cli 会话").performClick()
        }
        rule.runOnIdle { assertEquals(listOf("Qoder CLI", "Grok Build"), creations) }
    }

    @Test fun categoriesStartCollapsedAndSelectionDoesNotRevealOrRepeatAChildTitle() {
        fixture()
        rule.onNodeWithText("Codex child title").assertDoesNotExist()
        rule.onNodeWithText("Google VAI Argon child title").assertDoesNotExist()
        rule.onNodeWithContentDescription("新建 Codex 会话").assertExists()
        rule.onNodeWithContentDescription("新建 OpenCode 会话").assertExists()
        rule.onAllNodesWithText("1", substring = false).assertCountEquals(2)
        rule.runOnIdle { selected = "codex" }
        rule.onNodeWithText("Codex child title").assertDoesNotExist()
        rule.onNodeWithText("OpenCode", substring = false).performClick()
        rule.onAllNodesWithText("Google VAI Argon child title").assertCountEquals(1)
        rule.onNodeWithText("OpenCode", substring = false).performClick()
        rule.onNodeWithText("Google VAI Argon child title").assertDoesNotExist()
    }

    @Test fun eachCategoryRemembersItsOwnChoiceAcrossDrawerRecreationAndSessionChanges() {
        fixture()
        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.onNodeWithText("Codex child title").assertExists()
        rule.runOnIdle {
            assertTrue(preferences.getBoolean("sidebar-expanded:Codex", false))
            assertFalse(preferences.getBoolean("sidebar-expanded:OpenCode", false))
            visible = false
        }
        rule.runOnIdle { selected = "codex"; visible = true }
        rule.onNodeWithText("Codex child title").assertExists()
        rule.onNodeWithText("Google VAI Argon child title").assertDoesNotExist()
        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.runOnIdle { visible = false }
        rule.runOnIdle { selected = "opencode"; visible = true }
        rule.onNodeWithText("Codex child title").assertDoesNotExist()
        rule.onNodeWithText("Google VAI Argon child title").assertDoesNotExist()
        rule.onNodeWithContentDescription("新建 OpenCode 会话").performClick()
        rule.onNodeWithText("Google VAI Argon child title").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("OpenCode"), creations); assertFalse(preferences.getBoolean("sidebar-expanded:Codex", true)) }
    }

    @Test fun cliCategoriesFollowAlphabeticalOrderAndSessionsSortNewestFirstWithUnreadDot() {
        var currentSessions by mutableStateOf(
            listOf(
                SessionSummary("c-old", "较早会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 100L, hasUnreadDot = false),
                SessionSummary("c-new", "最新会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 300L, hasUnreadDot = true),
                SessionSummary("c-mid", "中间会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 200L, hasUnreadDot = false),
            ),
        )
        var freeze by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                SessionSidebar(
                    currentSessions,
                    selected = "c-mid",
                    onOpen = {},
                    onNew = {},
                    enabledClis = setOf("Qoder CLI", "pi", "Antigravity", "Codex"),
                    initiallyExpandedClis = setOf("Codex"),
                    freezeOrder = freeze,
                )
            }
        }
        val antigravityY = rule.onNodeWithText("Antigravity", substring = false).fetchSemanticsNode().boundsInRoot.top
        val codexY = rule.onNodeWithText("Codex", substring = false).fetchSemanticsNode().boundsInRoot.top
        val piY = rule.onNodeWithText("Pi", substring = false).fetchSemanticsNode().boundsInRoot.top
        val qoderY = rule.onNodeWithText("Qoder", substring = false).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected A -> C -> P -> Q order: $antigravityY, $codexY, $piY, $qoderY", antigravityY < codexY && codexY < piY && piY < qoderY)

        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.waitForIdle()

        val newestY = rule.onNodeWithText("最新会话").fetchSemanticsNode().boundsInRoot.top
        val middleY = rule.onNodeWithText("中间会话").fetchSemanticsNode().boundsInRoot.top
        val oldestY = rule.onNodeWithText("较早会话").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected newest -> middle -> oldest: $newestY, $middleY, $oldestY", newestY < middleY && middleY < oldestY)

        rule.onNodeWithTag("session-unread-dot:c-new", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("session-unread-dot:c-old", useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { freeze = true }
        rule.waitForIdle()
        rule.runOnIdle {
            currentSessions = listOf(
                SessionSummary("c-old", "较早会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 999L, hasUnreadDot = true),
                SessionSummary("c-new", "最新会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 300L, hasUnreadDot = true),
                SessionSummary("c-mid", "中间会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = 200L, hasUnreadDot = false),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("session-unread-dot:c-old", useUnmergedTree = true).assertExists()
        val frozenNewestY = rule.onNodeWithText("最新会话").fetchSemanticsNode().boundsInRoot.top
        val frozenOldestY = rule.onNodeWithText("较早会话").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Row order stays frozen while drawer is open", frozenNewestY < frozenOldestY)

        rule.runOnIdle { freeze = false }
        rule.waitForIdle()
        val resortedOldestY = rule.onNodeWithText("较早会话").fetchSemanticsNode().boundsInRoot.top
        val resortedNewestY = rule.onNodeWithText("最新会话").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Row order updates after drawer unfreezes", resortedOldestY < resortedNewestY)

        rule.runOnIdle { freeze = false }
        rule.waitForIdle()
        val unfrozenOldestY = rule.onNodeWithText("较早会话").fetchSemanticsNode().boundsInRoot.top
        val unfrozenNewestY = rule.onNodeWithText("最新会话").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Row order updates after drawer closes", unfrozenOldestY < unfrozenNewestY)
    }

    @Test fun sessionRowsShowFormattedActivityTimeInsteadOfModelOrWorkspacePath() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.of(2026, 11, 10, 19, 27, 0, 0, zone).toInstant().toEpochMilli()
        val todayTs = java.time.ZonedDateTime.of(2026, 11, 10, 10, 44, 0, 0, zone).toInstant().toEpochMilli()
        val yesterdayTs = java.time.ZonedDateTime.of(2026, 11, 9, 21, 15, 0, 0, zone).toInstant().toEpochMilli()
        val weekTs = java.time.ZonedDateTime.of(2026, 11, 8, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val dateTs = java.time.ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val rows = listOf(
            SessionSummary("s-today", "今天会话", "noroot", "/workspace/project", model = "gpt-6", modelLabel = "GPT-6-Astra", cli = "Codex", lastActiveAt = todayTs),
            SessionSummary("s-yest", "昨天会话", "noroot", "/workspace/other", model = "claude", modelLabel = "Claude Sonnet", cli = "Codex", lastActiveAt = yesterdayTs),
            SessionSummary("s-week", "周日会话", "noroot", "/workspace/sub", modelLabel = "Gemini 4 Carbon", cli = "Codex", lastActiveAt = weekTs),
            SessionSummary("s-date", "上周会话", "noroot", "/workspace/old", modelLabel = "DeepSeek", cli = "Codex", lastActiveAt = dateTs),
            SessionSummary("s-legacy", "旧序号会话", "noroot", "/workspace/legacy", modelLabel = "LegacyModel", cli = "Codex", lastActiveAt = 12L),
        )
        rule.setContent {
            MaterialTheme {
                SessionSidebar(
                    rows,
                    selected = "s-today",
                    onOpen = {},
                    onNew = {},
                    enabledClis = setOf("Codex"),
                    initiallyExpandedClis = setOf("Codex"),
                    nowMs = now,
                )
            }
        }
        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("10:44").assertIsDisplayed()
        rule.onNodeWithText("昨天").assertIsDisplayed()
        rule.onNodeWithText("星期日").assertIsDisplayed()
        rule.onNodeWithText("11月3日").assertIsDisplayed()
        rule.onNodeWithText("GPT-6-Astra").assertDoesNotExist()
        rule.onNodeWithText("Claude Sonnet").assertDoesNotExist()
        rule.onNodeWithText("Gemini 4 Carbon").assertDoesNotExist()
        rule.onNodeWithText("LegacyModel").assertDoesNotExist()
        rule.onNodeWithText("/workspace/project").assertDoesNotExist()
        rule.onNodeWithText("1970", substring = true).assertDoesNotExist()
    }

    @Test fun norootHeaderReadsInstalledDebianOsReleaseFromLumaRootfs() {
        val deb13 = """
            PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            NAME="Debian GNU/Linux"
            VERSION_ID="13"
            VERSION="13 (trixie)"
            VERSION_CODENAME=trixie
            DEBIAN_VERSION_FULL=13.7
            ID=debian
        """.trimIndent()
        val parsed13 = requireNotNull(parseOsReleaseContent(deb13, "noroot"))
        assertEquals("Debian", parsed13.osName)
        assertEquals("PROOT", parsed13.modeBadge)
        assertTrue(parsed13.versionArch.startsWith("13-Trixie · "))

        val ubuntu = """
            PRETTY_NAME="Ubuntu 26.04.1 LTS"
            NAME="Ubuntu"
            VERSION_ID="26.04"
            VERSION="26.04.1 LTS (Resolute Raccoon)"
            VERSION_CODENAME=resolute
            ID=ubuntu
        """.trimIndent()
        val parsedUbuntu = requireNotNull(parseOsReleaseContent(ubuntu, "root"))
        assertTrue(parsedUbuntu.versionArch.startsWith("26.04.01-LTS · "))

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val osRelease = java.io.File(ctx.filesDir, "linux/debian/usr/lib/os-release")
        osRelease.parentFile?.mkdirs()
        try {
            osRelease.writeText(
                """
                PRETTY_NAME="Debian GNU/Linux 14 (forky)"
                NAME="Debian GNU/Linux"
                VERSION_ID="14"
                VERSION="14 (forky)"
                VERSION_CODENAME=forky
                ID=debian
                """.trimIndent(),
            )
            rule.setContent {
                MaterialTheme {
                    SessionSidebar(emptyList(), null, {}, {}, enabledClis = setOf("Codex"), environment = "noroot")
                }
            }
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText("14-Forky · ", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("14-Forky · ", substring = true).assertIsDisplayed()
            rule.onNodeWithText("12-Bookworm", substring = true).assertDoesNotExist()
        } finally {
            java.io.File(ctx.filesDir, "linux").deleteRecursively()
        }
    }

    @Test fun cliDrawerCollapseDeceleratesSmoothlyAndReversesCleanlyMidFlight() {
        val multiSessions = listOf(
            SessionSummary("c1", "Codex session 1", "noroot", "/workspace", cli = "Codex", lastActiveAt = 400L),
            SessionSummary("c2", "Codex session 2", "noroot", "/workspace", cli = "Codex", lastActiveAt = 300L),
            SessionSummary("c3", "Codex session 3", "noroot", "/workspace", cli = "Codex", lastActiveAt = 200L),
            SessionSummary("c4", "Codex session 4", "noroot", "/workspace", cli = "Codex", lastActiveAt = 100L),
            SessionSummary("o1", "OpenCode session 1", "noroot", "/workspace", cli = "OpenCode", lastActiveAt = 50L),
        )
        rule.setContent {
            MaterialTheme {
                SessionSidebar(
                    multiSessions,
                    selected = "c1",
                    onOpen = {},
                    onNew = {},
                    enabledClis = setOf("Codex", "OpenCode"),
                )
            }
        }
        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.waitForIdle()
        val expandedOpenCodeY = rule.onNodeWithText("OpenCode", substring = false).fetchSemanticsNode().boundsInRoot.top

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Codex", substring = false).performClick()
        val ys = mutableListOf(expandedOpenCodeY)
        repeat(20) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            ys += rule.onNodeWithText("OpenCode", substring = false).fetchSemanticsNode().boundsInRoot.top
        }
        val deltas = ys.zipWithNext { prev, next -> prev - next }
        val peakDelta = deltas.maxOrNull() ?: 0f
        val peakIdx = deltas.indexOf(peakDelta)
        assertTrue("Expected lower CLI header to move upward smoothly (peakDelta=$peakDelta)", peakDelta > 5f)
        assertTrue("Expected collapse velocity to peak early, not slam at the end (peakIdx=$peakIdx, deltas=$deltas)", peakIdx in 0..6)
        val lateDelta = deltas.drop(peakIdx + 4).firstOrNull { it > 0.1f } ?: 0f
        assertTrue("Expected smooth deceleration toward rest (late=$lateDelta, peak=$peakDelta, deltas=$deltas)", lateDelta < peakDelta * 0.55f)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Codex session 1").assertDoesNotExist()
        val collapsedOpenCodeY = rule.onNodeWithText("OpenCode", substring = false).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Collapsed OpenCode header must sit well above expanded position", collapsedOpenCodeY < expandedOpenCodeY - 100f)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Codex", substring = false).performClick()
        repeat(4) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
        }
        rule.onNodeWithText("Codex", substring = false).performClick()
        repeat(4) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
        }
        rule.onNodeWithText("Codex", substring = false).performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Codex session 1").assertIsDisplayed()
        rule.onNodeWithText("Codex session 4").assertIsDisplayed()
    }
}
