package io.github.modao7067.luma.gui.session

import org.junit.Assert.*
import org.junit.Test

class SessionNotificationsTest {
    @Test fun titleAndKeepAliveSummaryFormatting() {
        assertEquals("未命名会话 · Codex", SessionNotifications.formatTitle("   ", "Codex"))
        assertEquals("重构通知系统 · Claude Code", SessionNotifications.formatTitle("重构通知系统", "Claude Code"))
        assertEquals("修复测试 · Pi", SessionNotifications.formatTitle("修复测试", "pi"))

        assertEquals("后台环境运行中", SessionNotifications.keepAliveSummaryText(0, 0))
        assertEquals("2 个会话执行中 · 1 个待处理", SessionNotifications.keepAliveSummaryText(2, 1))
        assertEquals("1 个会话执行中", SessionNotifications.keepAliveSummaryText(1, 0))
        assertEquals("3 个会话待处理", SessionNotifications.keepAliveSummaryText(0, 3))
    }

    @Test fun coldStartHydrationDoesNotAlertHistoricalTurnsOrExistingRequests() {
        val tracker = SessionNotificationTracker()
        val existingApproval = UserRequest("perm-old", "授权", "rm -rf build")
        val decision = tracker.reconcile(
            connected = true,
            loading = false,
            hydrating = false,
            turnActive = false,
            turnCompleted = false,
            consumeUserStop = { false },
            requests = listOf(existingApproval),
            viewingInForeground = false,
        )
        assertEquals(SessionNotificationDecision.None, decision)
        assertNull(tracker.activePostedKind)
    }

    @Test fun distinguishesApprovalQuestionAndTurnCompletionWithMinimalistCopy() {
        val record = SessionRecord("s1", "通知测试", "noroot", "/workspace", threadId = "t1", cli = "Codex")
        val activity = SessionActivityState(record)
        val clock = SessionActivityClock(nowMs = { 1000L })
        val tracker = SessionNotificationTracker()

        fun step(activeTurnId: String?, requests: List<UserRequest>, foreground: Boolean = false): SessionNotificationDecision {
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = activeTurnId, messages = emptyList(), requests = requests,
                viewingInForeground = foreground, clock = clock,
            )
            return tracker.reconcile(
                connected = true,
                loading = false,
                hydrating = false,
                turnActive = activeTurnId != null,
                turnCompleted = activity.lastTurnCompleted,
                consumeUserStop = { false },
                requests = requests,
                viewingInForeground = foreground,
            )
        }

        assertEquals(SessionNotificationDecision.None, step("turn-1", emptyList()))

        assertEquals(SessionNotificationDecision.None, step("turn-1", emptyList()))

        val approval = UserRequest("perm-1", "命令授权", "git status")
        assertEquals(SessionNotificationDecision.Post(SessionNotificationKind.APPROVAL), step("turn-1", listOf(approval)))
        assertEquals("等待你的审批", SessionNotificationKind.APPROVAL.bodyText)
        assertEquals(SessionNotifications.ACTION_CHANNEL_ID, SessionNotificationKind.APPROVAL.channelId)

        val question = UserRequest("q-1", "Answer", "请选择方案", questions = listOf(RequestQuestion("q", "方案", "请选择", emptyList())))
        assertEquals(SessionNotificationDecision.Post(SessionNotificationKind.QUESTION), step("turn-1", listOf(approval, question)))
        assertEquals("等待你的回答", SessionNotificationKind.QUESTION.bodyText)

        val ocForm = UserRequest("form:42", "外部表单", "")
        assertTrue(SessionNotifications.isQuestionRequest(ocForm))

        assertEquals(SessionNotificationDecision.Cancel, step("turn-1", emptyList()))

        assertEquals(SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED), step(null, emptyList()))
        assertEquals("本轮回复已完成", SessionNotificationKind.COMPLETED.bodyText)
        assertEquals(SessionNotifications.COMPLETE_CHANNEL_ID, SessionNotificationKind.COMPLETED.channelId)
    }

    @Test fun manualUserStopSuppressesCompletionOnceAndResetsForNextTurn() {
        val record = SessionRecord("s2", "手动停止", "root", "/workspace", threadId = "t2", cli = "OpenCode")
        val activity = SessionActivityState(record)
        val clock = SessionActivityClock(nowMs = { 2000L })
        val tracker = SessionNotificationTracker()
        var userStopped = false

        fun step(activeTurnId: String?, foreground: Boolean = false): SessionNotificationDecision {
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = activeTurnId, messages = emptyList(), requests = emptyList(),
                viewingInForeground = foreground, clock = clock,
            )
            return tracker.reconcile(
                connected = true,
                loading = false,
                hydrating = false,
                turnActive = activeTurnId != null,
                turnCompleted = activity.lastTurnCompleted,
                consumeUserStop = { val s = userStopped; userStopped = false; s },
                requests = emptyList(),
                viewingInForeground = foreground,
            )
        }

        assertEquals(SessionNotificationDecision.None, step("turn-1", foreground = true))
        userStopped = true
        assertEquals(SessionNotificationDecision.None, step(null, foreground = false))
        assertFalse(userStopped)

        assertEquals(SessionNotificationDecision.None, step("turn-2", foreground = false))
        assertEquals(SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED), step(null, foreground = false))
    }

    @Test fun foregroundViewingAutoCancelsAndNewTurnClearsCompletedNotification() {
        val tracker = SessionNotificationTracker()
        tracker.reconcile(
            connected = true, loading = false, hydrating = false,
            turnActive = true, turnCompleted = false, consumeUserStop = { false },
            requests = emptyList(), viewingInForeground = false,
        )
        assertEquals(
            SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED),
            tracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = false, turnCompleted = true, consumeUserStop = { false },
                requests = emptyList(), viewingInForeground = false,
            ),
        )
        assertEquals(
            SessionNotificationDecision.Cancel,
            tracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = true, turnCompleted = false, consumeUserStop = { false },
                requests = emptyList(), viewingInForeground = false,
            ),
        )
        val req = UserRequest("r1", "授权", "exec")
        assertEquals(
            SessionNotificationDecision.Post(SessionNotificationKind.APPROVAL),
            tracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = true, turnCompleted = false, consumeUserStop = { false },
                requests = listOf(req), viewingInForeground = false,
            ),
        )
        assertEquals(
            SessionNotificationDecision.Cancel,
            tracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = true, turnCompleted = false, consumeUserStop = { false },
                requests = listOf(req), viewingInForeground = true,
            ),
        )
    }

    @Test fun externalWakeDeduplicatesWithColdStartHydrationAndCancelsIfAlreadyResolved() {
        val tracker = SessionNotificationTracker()
        assertEquals(
            SessionNotificationDecision.Post(SessionNotificationKind.APPROVAL),
            tracker.onExternalWake(SessionNotificationKind.APPROVAL, viewingInForeground = false),
        )
        assertEquals(
            SessionNotificationDecision.None,
            tracker.onExternalWake(SessionNotificationKind.APPROVAL, viewingInForeground = false),
        )
        assertEquals(
            SessionNotificationDecision.None,
            tracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = true, turnCompleted = false, consumeUserStop = { false },
                requests = listOf(UserRequest("r1", "授权", "exec")), viewingInForeground = false,
            ),
        )

        val resolvedTracker = SessionNotificationTracker()
        assertEquals(
            SessionNotificationDecision.Post(SessionNotificationKind.QUESTION),
            resolvedTracker.onExternalWake(SessionNotificationKind.QUESTION, viewingInForeground = false),
        )
        assertEquals(
            SessionNotificationDecision.Cancel,
            resolvedTracker.reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = true, turnCompleted = false, consumeUserStop = { false },
                requests = emptyList(), viewingInForeground = false,
            ),
        )
    }

    @Test fun stressStreamingFiftyThousandDeltasAcrossFiftySessionsProducesZeroSpuriousDecisions() {
        val sessionCount = 50
        val trackers = Array(sessionCount) { SessionNotificationTracker() }
        for (t in trackers) {
            assertEquals(
                SessionNotificationDecision.None,
                t.reconcile(
                    connected = true, loading = false, hydrating = false,
                    turnActive = true, turnCompleted = false, consumeUserStop = { false },
                    requests = emptyList(), viewingInForeground = false,
                ),
            )
        }
        repeat(1000) {
            for (i in 0 until sessionCount) {
                val decision = trackers[i].reconcile(
                    connected = true, loading = false, hydrating = false,
                    turnActive = true, turnCompleted = false, consumeUserStop = { false },
                    requests = emptyList(), viewingInForeground = false,
                )
                assertEquals(SessionNotificationDecision.None, decision)
            }
        }
        for (i in 0 until sessionCount) {
            val stopped = (i % 5 == 0)
            val decision = trackers[i].reconcile(
                connected = true, loading = false, hydrating = false,
                turnActive = false, turnCompleted = true, consumeUserStop = { stopped },
                requests = emptyList(), viewingInForeground = false,
            )
            if (stopped) {
                assertEquals(SessionNotificationDecision.None, decision)
                assertNull(trackers[i].activePostedKind)
            } else {
                assertEquals(SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED), decision)
                assertEquals(SessionNotificationKind.COMPLETED, trackers[i].activePostedKind)
            }
        }
    }

    @Test fun fuzzTwoThousandRandomTransitionsAcrossThirtyTwoSessionsMaintainsAllInvariants() {
        val rng = java.util.Random(20261109L)
        val n = 32
        val records = Array(n) { SessionRecord("s-$it", "Session $it", "root", "/workspace", threadId = "t-$it") }
        val activities = Array(n) { SessionActivityState(records[it]) }
        val trackers = Array(n) { SessionNotificationTracker() }
        val sinkPosted = HashMap<String, SessionNotificationKind>()
        var clockMs = 1_000L
        val clock = SessionActivityClock(nowMs = { clockMs++ })

        val connected = BooleanArray(n) { true }
        val loading = BooleanArray(n)
        val hydrating = BooleanArray(n)
        val turnActive = BooleanArray(n)
        val userStopped = BooleanArray(n)
        val foreground = BooleanArray(n)
        val requests = Array<List<UserRequest>>(n) { emptyList() }
        var reqSeq = 0

        repeat(2_000) { step ->
            val i = rng.nextInt(n)
            when (rng.nextInt(10)) {
                0 -> connected[i] = !connected[i]
                1 -> loading[i] = !loading[i]
                2 -> hydrating[i] = !hydrating[i]
                3 -> {
                    turnActive[i] = true
                    userStopped[i] = false
                }
                4 -> {
                    turnActive[i] = false
                    requests[i] = emptyList()
                }
                5 -> {
                    if (turnActive[i]) {
                        userStopped[i] = true
                        turnActive[i] = false
                        requests[i] = emptyList()
                    }
                }
                6 -> {
                    reqSeq++
                    val isQuestion = rng.nextBoolean()
                    val req = if (isQuestion) {
                        if (rng.nextBoolean()) {
                            UserRequest("form:$reqSeq", "Form", "")
                        } else {
                            UserRequest("q:$reqSeq", "Answer", "Q?", questions = listOf(RequestQuestion("id", "H", "Q", emptyList())))
                        }
                    } else {
                        UserRequest("perm:$reqSeq", "Approval", "cmd")
                    }
                    requests[i] = (requests[i] + req).takeLast(3)
                }
                7 -> requests[i] = emptyList()
                8 -> foreground[i] = !foreground[i]
                9 -> {
                    val wakeKind = SessionNotificationKind.entries[rng.nextInt(3)]
                    val wakeDecision = trackers[i].onExternalWake(wakeKind, foreground[i])
                    when (wakeDecision) {
                        is SessionNotificationDecision.Post -> sinkPosted[records[i].id] = wakeDecision.kind
                        SessionNotificationDecision.Cancel -> sinkPosted.remove(records[i].id)
                        SessionNotificationDecision.None -> {}
                    }
                }
            }

            val wasStoppedBefore = userStopped[i]
            activities[i].reconcile(
                records[i],
                connected = connected[i],
                loading = loading[i],
                hydrating = hydrating[i],
                activeTurnId = if (turnActive[i]) "turn-$step" else null,
                messages = emptyList(),
                requests = requests[i],
                viewingInForeground = foreground[i],
                clock = clock,
            )
            val turnCompleted = activities[i].lastTurnCompleted
            val decision = trackers[i].reconcile(
                connected = connected[i],
                loading = loading[i],
                hydrating = hydrating[i],
                turnActive = turnActive[i],
                turnCompleted = turnCompleted,
                consumeUserStop = { val s = userStopped[i]; userStopped[i] = false; s },
                requests = requests[i],
                viewingInForeground = foreground[i],
            )
            when (decision) {
                is SessionNotificationDecision.Post -> sinkPosted[records[i].id] = decision.kind
                SessionNotificationDecision.Cancel -> sinkPosted.remove(records[i].id)
                SessionNotificationDecision.None -> {}
            }

            if (foreground[i]) {
                assertNull("Step $step session $i leaked notification in foreground", sinkPosted[records[i].id])
                assertNull(trackers[i].activePostedKind)
            }
            assertEquals("Step $step session $i sink mismatch", trackers[i].activePostedKind, sinkPosted[records[i].id])
            if (wasStoppedBefore && turnCompleted && requests[i].isEmpty()) {
                assertNotEquals(
                    "Step $step session $i posted COMPLETED despite user stop",
                    SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED),
                    decision,
                )
            }
        }
    }
}
