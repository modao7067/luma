package io.github.modao7067.luma.gui.session

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.modao7067.luma.gui.GuiProduct
import io.github.modao7067.luma.gui.runtime.RootBackgroundNotifications
import io.github.modao7067.luma.gui.runtime.WatchedRootSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionNotificationSinkTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application

    @After fun tearDown() {
        RootBackgroundNotifications.forceEnableForTesting = false
    }

    @Test fun createsHighImportanceChannelsRemovesLegacyAndManagesGroupSummaryAndRouting() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("luma-agent-connection", "Legacy", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("luma-agent-approval", "Legacy2", NotificationManager.IMPORTANCE_HIGH))

        val sink = AndroidSessionNotificationSink(app)
        assertNull(nm.getNotificationChannel("luma-agent-connection"))
        assertNull(nm.getNotificationChannel("luma-agent-approval"))

        val actionChannel = nm.getNotificationChannel(SessionNotifications.ACTION_CHANNEL_ID)
        val completeChannel = nm.getNotificationChannel(SessionNotifications.COMPLETE_CHANNEL_ID)
        assertNotNull(actionChannel)
        assertNotNull(completeChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, actionChannel!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, completeChannel!!.importance)

        sink.post("sess-1", "修复构建", "Codex", SessionNotificationKind.APPROVAL)
        val shadowNm = shadowOf(nm)
        val n1 = shadowNm.getNotification("sess-1", SessionNotifications.SESSION_NOTIFICATION_ID)
        assertNotNull(n1)
        assertEquals("修复构建 · Codex", n1.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("等待你的审批", n1.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(n1.actions.isNullOrEmpty())
        assertNull(shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

        val intent = shadowOf(n1.contentIntent).savedIntent
        assertEquals("io.github.modao7067.luma.gui.MainActivity", intent.component?.className)
        assertEquals("sess-1", intent.getStringExtra("sessionId"))

        sink.post("sess-2", "", "Claude Code", SessionNotificationKind.COMPLETED)
        val n2 = shadowNm.getNotification("sess-2", SessionNotifications.SESSION_NOTIFICATION_ID)
        assertNotNull(n2)
        assertEquals("未命名会话 · Claude Code", n2.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("本轮回复已完成", n2.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

        sink.cancel("sess-1")
        assertNull(shadowNm.getNotification("sess-1", SessionNotifications.SESSION_NOTIFICATION_ID))
        assertNotNull(shadowNm.getNotification("sess-2", SessionNotifications.SESSION_NOTIFICATION_ID))
        assertNull(shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

        sink.cancelAll()
        assertNull(shadowNm.getNotification("sess-2", SessionNotifications.SESSION_NOTIFICATION_ID))
    }

    @Test fun hashCollidingSessionIdsNeverOverwriteSlotsOrPendingIntents() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        val sink = AndroidSessionNotificationSink(app)

        sink.post("Aa", "会话甲", "Codex", SessionNotificationKind.APPROVAL)
        sink.post("BB", "会话乙", "OpenCode", SessionNotificationKind.QUESTION)

        val nA = shadowNm.getNotification("Aa", SessionNotifications.SESSION_NOTIFICATION_ID)
        val nB = shadowNm.getNotification("BB", SessionNotifications.SESSION_NOTIFICATION_ID)
        assertNotNull(nA)
        assertNotNull(nB)
        assertEquals("会话甲 · Codex", nA.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("等待你的审批", nA.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("会话乙 · OpenCode", nB.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("等待你的回答", nB.extras.getString(Notification.EXTRA_TEXT))

        assertEquals("Aa", shadowOf(nA.contentIntent).savedIntent.getStringExtra("sessionId"))
        assertEquals("BB", shadowOf(nB.contentIntent).savedIntent.getStringExtra("sessionId"))

        sink.cancel("Aa")
        assertNull(shadowNm.getNotification("Aa", SessionNotifications.SESSION_NOTIFICATION_ID))
        assertNotNull(shadowNm.getNotification("BB", SessionNotifications.SESSION_NOTIFICATION_ID))
        sink.cancelAll()
    }

    @Test fun stressThirtyFiveConcurrentBurstPacesRateLimitAndPreservesUrgentApprovalsOverCompleted() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        var virtualNow = 10_000L
        val sink = AndroidSessionNotificationSink(app, nowElapsedMs = { virtualNow })

        for (i in 1..25) {
            sink.post("done-$i", "后台回合 $i", "Codex", SessionNotificationKind.COMPLETED)
        }
        for (i in 1..10) {
            val kind = if (i % 2 == 0) SessionNotificationKind.APPROVAL else SessionNotificationKind.QUESTION
            sink.post("urgent-$i", "紧急审批 $i", "Claude Code", kind)
        }

        val initialSessionNotifs = nm.activeNotifications.count { it.id == SessionNotifications.SESSION_NOTIFICATION_ID }
        assertEquals(AndroidSessionNotificationSink.BURST_CAPACITY, initialSessionNotifs)
        assertNotNull(shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

        repeat(40) {
            virtualNow += AndroidSessionNotificationSink.REFILL_INTERVAL_MS
            shadowOf(Looper.getMainLooper()).idleFor(AndroidSessionNotificationSink.REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        val activeSessionTags = nm.activeNotifications
            .filter { it.id == SessionNotifications.SESSION_NOTIFICATION_ID }
            .mapNotNull { it.tag }
            .toSet()

        assertEquals(SessionNotifications.MAX_ACTIVE_SESSION_NOTIFICATIONS, activeSessionTags.size)

        for (i in 1..10) {
            assertTrue("Missing urgent-$i", "urgent-$i" in activeSessionTags)
        }
        for (i in 1..15) {
            assertFalse("Expected old done-$i to be evicted first", "done-$i" in activeSessionTags)
        }
        for (i in 16..25) {
            assertTrue("Expected newer done-$i to remain", "done-$i" in activeSessionTags)
        }

        sink.cancelAll()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun stressRapidFlapCoalescesQueuedPostsAndCancelsLeaveZeroLeaks() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        var virtualNow = 50_000L
        val sink = AndroidSessionNotificationSink(app, nowElapsedMs = { virtualNow })

        for (i in 1..4) sink.post("base-$i", "Base $i", "Codex", SessionNotificationKind.COMPLETED)

        for (i in 1..50) {
            val sid = "flap-$i"
            sink.post(sid, "Flap $i", "pi", SessionNotificationKind.APPROVAL)
            sink.post(sid, "Flap $i", "pi", SessionNotificationKind.QUESTION)
            if (i <= 45) {
                sink.cancel(sid)
            } else {
                sink.post(sid, "Flap $i", "pi", SessionNotificationKind.COMPLETED)
            }
        }

        repeat(10) {
            virtualNow += AndroidSessionNotificationSink.REFILL_INTERVAL_MS
            shadowOf(Looper.getMainLooper()).idleFor(AndroidSessionNotificationSink.REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        for (i in 1..45) {
            assertNull(shadowNm.getNotification("flap-$i", SessionNotifications.SESSION_NOTIFICATION_ID))
        }
        for (i in 46..50) {
            val notif = shadowNm.getNotification("flap-$i", SessionNotifications.SESSION_NOTIFICATION_ID)
            assertNotNull(notif)
            assertEquals("本轮回复已完成", notif.extras.getString(Notification.EXTRA_TEXT))
        }

        sink.cancelAll()
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun stressRootArmDisarmFlappingNeverRacesOrLeavesActiveSpecAfterDisarm() {
        RootBackgroundNotifications.forceEnableForTesting = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = File(app.filesDir, GuiProduct.agentRuntimePath())
        try {
            repeat(100) { iter ->
                val watched = listOf(
                    WatchedRootSession(
                        id = "s-$iter",
                        epoch = iter,
                        protocol = "codex",
                        threadId = "t-$iter",
                        running = true,
                        userStopped = false,
                        requestIds = emptyList(),
                    ),
                )
                RootBackgroundNotifications.armInBackground(app, scope, watched, spawnLauncher = false)
                if (iter % 2 == 1) {
                    RootBackgroundNotifications.disarmLocal(app)
                }
            }
            RootBackgroundNotifications.disarmLocal(app)
            Thread.sleep(300L)

            val specFile = File(dir, "bg-watch-spec.json")
            assertTrue(specFile.isFile)
            val spec = JSONObject(specFile.readText())
            assertFalse("Disarmed spec must never be overwritten by stale IO arm job", spec.getBoolean("active"))
            val leftoverTemps = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
            assertTrue("Leftover temp files: $leftoverTemps", leftoverTemps.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun wakeReceiverRejectsInvalidTokenDeduplicatesAndCapsBatchAtThirtyTwo() {
        RootBackgroundNotifications.forceEnableForTesting = true
        val validToken = RootBackgroundNotifications.ensureTokenForTesting(app)
        assertTrue(RootBackgroundNotifications.verifyToken(app, validToken))
        assertFalse(RootBackgroundNotifications.verifyToken(app, "0".repeat(64)))
        assertFalse(RootBackgroundNotifications.verifyToken(app, "short"))

        val batch = JSONArray()
        batch.put(JSONObject().put("sessionId", "s-0").put("kind", "approval"))
        batch.put(JSONObject().put("sessionId", "s-0").put("kind", "question"))
        for (i in 1..39) {
            batch.put(JSONObject().put("sessionId", "s-$i").put("kind", "completed"))
        }
        val intent = Intent(RootBackgroundNotifications.ACTION_SESSION_WAKE)
            .putExtra("token", validToken)
            .putExtra("events", batch.toString())

        val parsed = SessionWakeReceiver.parseWakeEvents(intent)
        assertEquals(32, parsed.size)
        assertEquals("s-0" to SessionNotificationKind.QUESTION, parsed.first())
    }

    @Test fun stressControllerWakeBatchSuppressesForegroundAutoCancelsOnOpenAndAbortsOnDisconnectAll() {
        val store = SessionStore(app)
        val records = (1..25).map { i ->
            SessionRecord(
                id = "w-$i",
                title = "压测会话 $i",
                environment = GuiProduct.environment,
                cwd = "/workspace",
                threadId = "thread-$i",
                cli = if (i % 2 == 0) "Codex" else "Claude Code",
            )
        }
        store.save(records)

        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        var virtualNow = 100_000L
        val sink = AndroidSessionNotificationSink(app, nowElapsedMs = { virtualNow })
        val controller = SessionController(app)
        controller.notificationSink = sink

        try {
            controller.openSession("w-1")
            controller.setForeground(true)

            var batchCompleted = false
            val events = (1..25).map { i ->
                "w-$i" to if (i <= 6) SessionNotificationKind.APPROVAL else SessionNotificationKind.COMPLETED
            }
            controller.onBackgroundWakeBatch(events) { batchCompleted = true }

            repeat(30) {
                virtualNow += 260L
                shadowOf(Looper.getMainLooper()).idleFor(260L, TimeUnit.MILLISECONDS)
            }
            assertTrue("goAsync completion callback must fire", batchCompleted)

            assertNull(shadowNm.getNotification("w-1", SessionNotifications.SESSION_NOTIFICATION_ID))
            for (i in 2..6) {
                assertNotNull("Expected w-$i approval notification", shadowNm.getNotification("w-$i", SessionNotifications.SESSION_NOTIFICATION_ID))
            }
            val activeCount = nm.activeNotifications.count { it.id == SessionNotifications.SESSION_NOTIFICATION_ID }
            assertEquals(SessionNotifications.MAX_ACTIVE_SESSION_NOTIFICATIONS, activeCount)

            controller.openSession("w-2")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Opening w-2 in foreground must auto-cancel its notification", shadowNm.getNotification("w-2", SessionNotifications.SESSION_NOTIFICATION_ID))

            controller.setForeground(false)
            var raceCompleted = false
            controller.onBackgroundWakeBatch(listOf("w-3" to SessionNotificationKind.QUESTION)) { raceCompleted = true }
            controller.disconnectAll()
            assertEquals(0, nm.activeNotifications.size)

            repeat(8) {
                virtualNow += 260L
                shadowOf(Looper.getMainLooper()).idleFor(260L, TimeUnit.MILLISECONDS)
            }
            assertTrue("goAsync completion callback must still fire on disconnectAll abort", raceCompleted)
            assertEquals("Stale wake batch must not post after disconnectAll()", 0, nm.activeNotifications.size)
        } finally {
            controller.disconnectAll()
        }
    }

    @Test fun openingSessionInFreshProcessCancelsPersistedOsNotificationAndOrphanedSummary() {
        val store = SessionStore(app)
        store.save(
            listOf(
                SessionRecord("p-1", "已看会话1", GuiProduct.environment, "/workspace", threadId = "tp-1", cli = "Claude Code"),
                SessionRecord("p-2", "已看会话2", GuiProduct.environment, "/workspace", threadId = "tp-2", cli = "Codex"),
                SessionRecord("p-idle", "其他会话", GuiProduct.environment, "/workspace", threadId = "tp-3", cli = "Codex"),
            ),
        )
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)

        val priorSink = AndroidSessionNotificationSink(app)
        priorSink.post("p-1", "已看会话1", "Claude Code", SessionNotificationKind.COMPLETED)
        priorSink.post("p-2", "已看会话2", "Codex", SessionNotificationKind.COMPLETED)
        assertNotNull(shadowNm.getNotification("p-1", SessionNotifications.SESSION_NOTIFICATION_ID))
        assertNotNull(shadowNm.getNotification("p-2", SessionNotifications.SESSION_NOTIFICATION_ID))
        assertNotNull(shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

        val restartedSink = AndroidSessionNotificationSink(app)
        val restartedController = SessionController(app)
        restartedController.notificationSink = restartedSink
        try {
            restartedController.setForeground(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(shadowNm.getNotification("p-1", SessionNotifications.SESSION_NOTIFICATION_ID))
            assertNotNull(shadowNm.getNotification("p-2", SessionNotifications.SESSION_NOTIFICATION_ID))

            restartedController.openSession("p-1")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Opening p-1 after restart must cancel lingering OS notification", shadowNm.getNotification("p-1", SessionNotifications.SESSION_NOTIFICATION_ID))
            assertNotNull(shadowNm.getNotification("p-2", SessionNotifications.SESSION_NOTIFICATION_ID))
            assertNull("Summary must clear once only one child remains even after restart", shadowNm.getNotification(SessionNotifications.SUMMARY_NOTIFICATION_ID))

            restartedController.openSession("p-2")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Opening p-2 after restart must cancel lingering OS notification", shadowNm.getNotification("p-2", SessionNotifications.SESSION_NOTIFICATION_ID))
            assertEquals(0, nm.activeNotifications.size)
        } finally {
            restartedController.disconnectAll()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun staleWatchGenerationAndForegroundedSessionSuppressLateWakeNotification() {
        RootBackgroundNotifications.forceEnableForTesting = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = File(app.filesDir, GuiProduct.agentRuntimePath())
        try {
            RootBackgroundNotifications.armInBackground(
                app,
                scope,
                listOf(WatchedRootSession("s-gen", 1, "bridged", task = "luma-native-cli-gui-v3", running = true, userStopped = false, requestIds = emptyList())),
                spawnLauncher = false,
            )
            Thread.sleep(150L)
            val armedSpec = JSONObject(File(dir, "bg-watch-spec.json").readText())
            val armedGen = armedSpec.getLong("watchGen")
            assertTrue(armedGen > 0L)
            assertTrue(RootBackgroundNotifications.verifyWakeGeneration(app, armedGen))

            RootBackgroundNotifications.disarmLocal(app)
            assertFalse("Disarmed spec must reject stale watchGen broadcast", RootBackgroundNotifications.verifyWakeGeneration(app, armedGen))
        } finally {
            scope.cancel()
        }

        val store = SessionStore(app)
        store.save(
            listOf(
                SessionRecord("late-1", "刚看过", GuiProduct.environment, "/workspace", threadId = "tl-1", cli = "Claude Code"),
                SessionRecord("late-2", "切去别的", GuiProduct.environment, "/workspace", threadId = "tl-2", cli = "Codex"),
            ),
        )
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        val sink = AndroidSessionNotificationSink(app)
        val controller = SessionController(app)
        controller.notificationSink = sink
        try {
            controller.setForeground(false)
            var done = false
            controller.onBackgroundWakeBatch(listOf("late-1" to SessionNotificationKind.COMPLETED)) { done = true }
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)

            controller.openSession("late-1")
            controller.setForeground(true)
            shadowOf(Looper.getMainLooper()).idle()
            controller.openSession("late-2")
            controller.setForeground(false)

            repeat(15) {
                shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            }
            assertTrue(done)
            assertNull("Session viewed during in-flight wake must never re-post notification", shadowNm.getNotification("late-1", SessionNotifications.SESSION_NOTIFICATION_ID))
        } finally {
            controller.disconnectAll()
        }
    }

    @Test fun coldStartWakeMarksControllerBackgroundedAndApprovalWaitSkipsRootWatch() {
        RootBackgroundNotifications.forceEnableForTesting = true
        val store = SessionStore(app)
        store.save(
            listOf(
                SessionRecord("cold-1", "冷启动会话", GuiProduct.environment, "/workspace", threadId = "tc-1", cli = "Claude Code"),
            ),
        )
        val controller = SessionController(app)
        try {
            val bgField = SessionController::class.java.getDeclaredField("backgrounded").apply { isAccessible = true }
            assertFalse(bgField.getBoolean(controller))

            var done = false
            controller.onBackgroundWakeBatch(listOf("cold-1" to SessionNotificationKind.COMPLETED)) { done = true }
            assertTrue("Cold-start wake batch must mark controller backgrounded", bgField.getBoolean(controller))
            repeat(15) {
                shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            }
            assertTrue(done)

            val sessionsMap = SessionController::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(controller) as Map<*, *>
            val live = sessionsMap["cold-1"]!!
            live.javaClass.getDeclaredField("connectJob").apply { isAccessible = true }.let { f ->
                (f.get(live) as? kotlinx.coroutines.Job)?.cancel()
                f.set(live, null)
            }
            val viewField = live.javaClass.getDeclaredField("view").apply { isAccessible = true }
            val currentView = viewField.get(live) as SessionView
            val buildWatched = SessionController::class.java.getDeclaredMethod("buildWatchedRootSessions").apply { isAccessible = true }
            viewField.set(
                live,
                currentView.copy(
                    connected = true,
                    loading = false,
                    activeTurnId = "tc-1",
                    requests = emptyList(),
                ),
            )
            val watchedRunning = buildWatched.invoke(controller) as List<*>
            assertEquals("Running session without pending approval must arm root watcher", 1, watchedRunning.size)
            viewField.set(
                live,
                currentView.copy(
                    connected = true,
                    loading = false,
                    activeTurnId = "tc-1",
                    requests = listOf(UserRequest("req-1", "Bash", "echo hi", listOf(RequestChoice("allow", "Allow")))),
                ),
            )
            val watched = buildWatched.invoke(controller) as List<*>
            assertTrue("Approval-blocked session with no active subagents must not arm root watcher", watched.isEmpty())
        } finally {
            controller.disconnectAll()
        }
    }
}
