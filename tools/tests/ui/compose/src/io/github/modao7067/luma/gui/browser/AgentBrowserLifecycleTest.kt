package io.github.modao7067.luma.gui.browser

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentBrowserLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application

    @Test fun restoresSavedTabsLazilyWithoutInstantiatingWebViews() {
        val manifest = File(app.filesDir, "browser-tabs.json")
        manifest.writeText(
            JSONObject().put(
                "tabs",
                JSONArray()
                    .put(JSONObject().put("id", "t1").put("owner", "s1").put("url", "https://example.com/1").put("title", "Page 1"))
                    .put(JSONObject().put("id", "t2").put("owner", "s2").put("url", "https://example.com/2").put("title", "Page 2")),
            ).toString(),
        )

        val browser = AgentBrowser(app)
        assertEquals(2, browser.tabs.size)
        assertTrue(browser.tabs.none { it.hasWebView.value })
        assertEquals("Page 1", browser.tabs.first { it.id == "t1" }.title.value)
        assertEquals(listOf("t2"), browser.otherSessionsTabs("s1").map { it.id })

        browser.closeOtherSessionsTabs("s1")
        assertEquals(listOf("t1"), browser.tabs.map { it.id })
        assertTrue(browser.tabs.none { it.hasWebView.value })
    }

    @Test fun evictsLeastRecentlyUsedBackgroundWebViewBeyondCap() {
        val browser = AgentBrowser(app)
        val activity = Any()
        browser.onActivityStarted(activity)
        val created = (1..(AgentBrowser.MAX_LIVE_WEBVIEWS + 2)).map { idx ->
            browser.newTab(owner = "s-$idx", url = "https://example.com/$idx").also {
                it.lastAccessedAtMs = idx.toLong() * 100L
            }
        }
        val liveCount = browser.tabs.count { it.hasWebView.value }
        assertTrue("Expected at most ${AgentBrowser.MAX_LIVE_WEBVIEWS} live WebViews, got $liveCount", liveCount <= AgentBrowser.MAX_LIVE_WEBVIEWS)
        assertFalse("Oldest inactive tab should have been evicted to metadata", created.first().hasWebView.value)
        assertTrue("Newest tab should remain live", created.last().hasWebView.value)
        assertEquals("https://example.com/1", created.first().url.value)
    }

    @Test fun backgroundingPausesTimersUnlessAgentIsOperating() {
        val browser = AgentBrowser(app)
        val activity = Any()
        browser.onActivityStarted(activity)
        browser.setForegroundSession("s1")
        val tab = browser.newTab(owner = "s1", url = "https://example.com")
        assertFalse(browser.timersPaused)
        assertTrue(tab.resumed)

        browser.onActivityStopped(activity)
        assertTrue("Backgrounding with no active agent must pause WebView timers", browser.timersPaused)
        assertFalse(tab.resumed)

        browser.agentOperatingSessions["s1"] = true
        browser.selectActiveTab("s1", tab.id)
        assertFalse("Active agent operation must resume WebView timers even in background", browser.timersPaused)
        assertTrue(tab.resumed)

        browser.stopAgentOperating("s1")
        assertTrue("Stopping agent operation in background must re-pause timers", browser.timersPaused)
        assertFalse(tab.resumed)

        browser.onActivityStarted(activity)
        assertFalse("Returning to foreground must resume WebView timers", browser.timersPaused)
        assertTrue(tab.resumed)
    }

    @Test fun activeMultiTabAgentTaskIsNeverEvictedMidTaskAndReclaimsAfterCompletion() {
        val browser = AgentBrowser(app)
        browser.agentOperatingSessions["s-agent"] = true
        val opened = (1..(AgentBrowser.MAX_LIVE_WEBVIEWS + 3)).map { idx ->
            browser.newTab(owner = "s-agent", url = "https://example.com/task/$idx").also {
                it.lastAccessedAtMs = idx.toLong() * 100L
            }
        }
        assertEquals(
            "All tabs of an actively operating Agent session must stay live mid-task",
            AgentBrowser.MAX_LIVE_WEBVIEWS + 3,
            opened.count { it.hasWebView.value },
        )

        browser.stopAgentOperating("s-agent")
        val remainingLive = opened.count { it.hasWebView.value }
        assertTrue(
            "Once Agent finishes operating, excess idle tabs must reclaim down to ${AgentBrowser.MAX_LIVE_WEBVIEWS} (got $remainingLive)",
            remainingLive <= AgentBrowser.MAX_LIVE_WEBVIEWS,
        )
        assertEquals(AgentBrowser.MAX_LIVE_WEBVIEWS + 3, browser.tabs.size)
        assertEquals("https://example.com/task/1", opened.first().url.value)
    }
}
