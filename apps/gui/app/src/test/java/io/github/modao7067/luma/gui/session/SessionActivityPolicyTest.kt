package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionActivityPolicyTest {
    @Test fun sortedIdsOrdersCaseInsensitivelyWithoutPushingLowercaseClisToEnd() {
        assertEquals(
            listOf(
                "Antigravity", "Claude Code", "CodeBuddy Code", "Codex", "DeepSeek Harness",
                "Grok Build", "Kimi Code", "OpenCode", "pi", "Qoder CLI",
            ),
            CliCatalog.sortedIds,
        )
        assertEquals(CliCatalog.ids.toSet(), CliCatalog.sortedIds.toSet())
    }

    @Test fun legacySessionIndexAssignsReverseCreationOrderAndDefaultsUnreadFalse() {
        val legacy = JSONObject(
            """
            {
              "version": 1,
              "sessions": [
                {"id": "old", "title": "Old", "environment": "noroot", "cwd": "/workspace", "cli": "Codex"},
                {"id": "mid", "title": "Mid", "environment": "noroot", "cwd": "/workspace", "cli": "Codex"},
                {"id": "new", "title": "New", "environment": "noroot", "cwd": "/workspace", "cli": "Codex"}
              ]
            }
            """.trimIndent(),
        )
        val decoded = decodeSessionIndex(legacy)
        assertTrue(decoded.all { !it.unread })
        assertTrue(decoded[2].lastActiveAt > decoded[1].lastActiveAt)
        assertTrue(decoded[1].lastActiveAt > decoded[0].lastActiveAt)

        val summaries = decoded.map { SessionSummary(it.id, it.title, it.environment, it.cwd, cli = it.cli, lastActiveAt = it.lastActiveAt, hasUnreadDot = it.unread) }
        assertEquals(listOf("new", "mid", "old"), sortSessionsByActivity(summaries).map { it.id })

        decoded[1].unread = true
        decoded[1].lastActiveAt = 9_999L
        val roundTrip = decodeSessionIndex(JSONObject(encodeSessionIndex(decoded).toString()))
        assertEquals(9_999L, roundTrip[1].lastActiveAt)
        assertTrue(roundTrip[1].unread)
        assertFalse(roundTrip[0].unread)
    }

    @Test fun backgroundCompletionSetsUnreadAndForegroundViewingClearsUnreadWithoutReordering() {
        var now = 1_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("s1", "Task", "noroot", "/workspace", threadId = "t1", lastActiveAt = 100L)
        val activity = SessionActivityState(record)

        val initChanged = activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = listOf(ConversationItem("m1", "assistant", "old")),
            requests = emptyList(), viewingInForeground = false, clock = clock,
        )
        assertFalse(initChanged)
        assertEquals(100L, record.lastActiveAt)
        assertFalse(record.unread)

        now = 2_000L
        record.submissions += StoredSubmission("sub1", "hello")
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = "turn-1", messages = emptyList(), requests = emptyList(),
                viewingInForeground = true, clock = clock,
            ),
        )
        assertEquals(2_000L, record.lastActiveAt)
        assertFalse(record.unread)

        now = 3_000L
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = null, messages = listOf(ConversationItem("m2", "assistant", "done")),
                requests = emptyList(), viewingInForeground = false, clock = clock,
            ),
        )
        assertEquals(3_000L, record.lastActiveAt)
        assertTrue(record.unread)

        now = 5_000L
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = null, messages = listOf(ConversationItem("m2", "assistant", "done")),
                requests = emptyList(), viewingInForeground = true, clock = clock,
            ),
        )
        assertFalse(record.unread)
        assertEquals(3_000L, record.lastActiveAt)
    }

    @Test fun foregroundCompletionAndPendingRequestStillAdvanceSortTimestamp() {
        var now = 10_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("s2", "Foreground", "noroot", "/workspace", threadId = "t2", lastActiveAt = 500L)
        val activity = SessionActivityState(record)

        activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = "turn-a", messages = emptyList(), requests = emptyList(),
            viewingInForeground = true, clock = clock,
        )
        assertEquals(500L, record.lastActiveAt)

        now = 11_000L
        val req = UserRequest("req-1", "授权", "允许执行命令？")
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = "turn-a", messages = emptyList(), requests = listOf(req),
                viewingInForeground = true, clock = clock,
            ),
        )
        assertEquals(11_000L, record.lastActiveAt)
        assertFalse(record.unread)

        now = 12_000L
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = null, messages = listOf(ConversationItem("a1", "assistant", "reply")),
                requests = emptyList(), viewingInForeground = true, clock = clock,
            ),
        )
        assertEquals(12_000L, record.lastActiveAt)
        assertFalse(record.unread)
    }

    @Test fun turnFinishingAcrossDisconnectStillMarksUnreadOnReconnect() {
        var now = 20_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("s3", "BgReconnect", "noroot", "/workspace", threadId = "t3", lastActiveAt = 1_000L)
        val activity = SessionActivityState(record)

        activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = "turn-bg", messages = emptyList(), requests = emptyList(),
            viewingInForeground = false, clock = clock,
        )
        assertFalse(
            activity.reconcile(
                record, connected = false, loading = false, hydrating = false,
                activeTurnId = null, messages = emptyList(), requests = emptyList(),
                viewingInForeground = false, clock = clock,
            ),
        )
        assertFalse(record.unread)

        now = 25_000L
        assertTrue(
            activity.reconcile(
                record, connected = true, loading = false, hydrating = false,
                activeTurnId = null, messages = listOf(ConversationItem("a2", "assistant", "finished")),
                requests = emptyList(), viewingInForeground = false, clock = clock,
            ),
        )
        assertTrue(record.unread)
        assertEquals(25_000L, record.lastActiveAt)
    }

    @Test fun evictedHistoryRebasesRegeneratedMessageAndRequestIdsWithoutActivity() {
        val clock = SessionActivityClock(nowMs = { 10_000L })
        val record = SessionRecord("old", "History", "noroot", "/workspace", lastActiveAt = 100L)
        val activity = SessionActivityState(record)
        activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = listOf(ConversationItem("original", "assistant", "Old reply")),
            requests = emptyList(), viewingInForeground = false, clock = clock,
        )
        record.submissions += StoredSubmission("already-finished", "Earlier input", state = "accepted")
        activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = emptyList(), requests = emptyList(),
            viewingInForeground = false, clock = clock,
        )
        val beforeReload = record.lastActiveAt
        activity.forgetHistory()
        assertFalse(activity.reconcile(
            record, connected = false, loading = true, hydrating = true,
            activeTurnId = null, messages = emptyList(), requests = emptyList(),
            viewingInForeground = false, clock = clock,
        ))
        val replayed = listOf(ConversationItem("regenerated", "assistant", "Old reply"))
        val requests = listOf(UserRequest("restored-request", "Approval", "Existing request"))
        assertFalse(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = replayed, requests = requests,
            viewingInForeground = false, clock = clock,
        ))
        assertEquals(beforeReload, record.lastActiveAt)
        assertFalse(record.unread)
        assertFalse(activity.lastTurnCompleted)
        assertTrue(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = replayed,
            requests = requests + UserRequest("new-request", "Approval", "New request"),
            viewingInForeground = false, clock = clock,
        ))
        assertTrue(record.lastActiveAt > beforeReload)
    }

    @Test fun newSubmissionDuringHistoryReloadStillAdvancesActivityAndCompletion() {
        var now = 10_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("old", "History", "noroot", "/workspace", lastActiveAt = 100L)
        val activity = SessionActivityState(record)
        activity.forgetHistory()
        record.submissions += StoredSubmission("fresh-input", "New input", state = "local")
        assertTrue(activity.reconcile(
            record, connected = false, loading = true, hydrating = true,
            activeTurnId = null, messages = emptyList(), requests = emptyList(),
            viewingInForeground = true, clock = clock,
        ))
        assertEquals(10_000L, record.lastActiveAt)
        now = 11_000L
        record.submissions.single().state = "accepted"
        assertTrue(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = listOf(ConversationItem("fresh-reply", "assistant", "New reply")),
            requests = emptyList(), viewingInForeground = false, clock = clock,
        ))
        assertEquals(11_000L, record.lastActiveAt)
        assertTrue(record.unread)
        assertTrue(activity.lastTurnCompleted)
    }

    @Test fun dormantSessionWithHistoricalReplyAndUndispatchedLocalSubmissionDoesNotFalseComplete() {
        var now = 20_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("dormant", "History", "noroot", "/workspace", lastActiveAt = 100L)
        val activity = SessionActivityState(record)
        val historical = listOf(ConversationItem("old-reply", "assistant", "Earlier answer"))

        assertFalse(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = historical, requests = emptyList(),
            viewingInForeground = false, clock = clock,
        ))
        activity.forgetHistory()

        now = 21_000L
        record.submissions += StoredSubmission("queued-local", "Next question", state = "local")
        assertTrue(activity.reconcile(
            record, connected = false, loading = true, hydrating = true,
            activeTurnId = null, messages = emptyList(), requests = emptyList(),
            viewingInForeground = false, clock = clock,
        ))
        assertEquals(21_000L, record.lastActiveAt)
        assertFalse(activity.lastTurnCompleted)
        assertFalse(record.unread)

        now = 22_000L
        assertFalse(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = historical, requests = emptyList(),
            viewingInForeground = false, clock = clock,
        ))
        assertEquals(21_000L, record.lastActiveAt)
        assertFalse(activity.lastTurnCompleted)
        assertFalse(record.unread)

        now = 23_000L
        record.submissions.single().state = "accepted"
        assertTrue(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null,
            messages = historical + listOf(
                ConversationItem("queued-local", "user", "Next question"),
                ConversationItem("new-reply", "assistant", "Fresh answer"),
            ),
            requests = emptyList(), viewingInForeground = false, clock = clock,
        ))
        assertEquals(23_000L, record.lastActiveAt)
        assertTrue(activity.lastTurnCompleted)
        assertTrue(record.unread)
    }

    @Test fun acceptedSubmissionWithoutNewAssistantReplyAfterUserMessageDoesNotFalseComplete() {
        var now = 30_000L
        val clock = SessionActivityClock(nowMs = { now })
        val record = SessionRecord("dormant2", "History", "noroot", "/workspace", lastActiveAt = 100L)
        val activity = SessionActivityState(record)
        val historical = listOf(ConversationItem("old-reply", "assistant", "Earlier answer"))
        activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null, messages = historical, requests = emptyList(),
            viewingInForeground = false, clock = clock,
        )
        activity.forgetHistory()

        now = 31_000L
        record.submissions += StoredSubmission("accepted-sub", "Question", state = "accepted")
        assertTrue(activity.reconcile(
            record, connected = false, loading = true, hydrating = true,
            activeTurnId = null, messages = emptyList(), requests = emptyList(),
            viewingInForeground = false, clock = clock,
        ))

        now = 32_000L
        assertFalse(activity.reconcile(
            record, connected = true, loading = false, hydrating = false,
            activeTurnId = null,
            messages = historical + ConversationItem("accepted-sub", "user", "Question"),
            requests = emptyList(), viewingInForeground = false, clock = clock,
        ))
        assertFalse(activity.lastTurnCompleted)
        assertFalse(record.unread)
    }

    @Test fun formatSessionActivityTimeUses24HourYesterdayWeekdayAndCalendarDates() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 11, 10, 19, 27, 0, 0, zone).toInstant().toEpochMilli()
        fun ts(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 44) =
            ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("", formatSessionActivityTime(0L, now, zone))
        assertEquals("", formatSessionActivityTime(27L, now, zone))
        assertEquals("09:05", formatSessionActivityTime(ts(2026, 11, 10, 9, 5), now, zone))
        assertEquals("19:21", formatSessionActivityTime(ts(2026, 11, 10, 19, 21), now, zone))
        assertEquals("昨天", formatSessionActivityTime(ts(2026, 11, 9, 23, 50), now, zone))
        assertEquals("星期日", formatSessionActivityTime(ts(2026, 11, 8, 12, 0), now, zone))
        assertEquals("星期六", formatSessionActivityTime(ts(2026, 11, 7, 12, 0), now, zone))
        assertEquals("星期四", formatSessionActivityTime(ts(2026, 11, 5, 12, 0), now, zone))
        assertEquals("星期三", formatSessionActivityTime(ts(2026, 11, 4, 12, 0), now, zone))
        assertEquals("11月3日", formatSessionActivityTime(ts(2026, 11, 3, 23, 59), now, zone))
        assertEquals("10月28日", formatSessionActivityTime(ts(2026, 10, 28, 8, 0), now, zone))
        assertEquals("2025年10月28日", formatSessionActivityTime(ts(2025, 10, 28, 8, 0), now, zone))
    }
}
