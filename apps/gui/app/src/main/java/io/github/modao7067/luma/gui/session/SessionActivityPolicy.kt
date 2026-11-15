package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr
import java.time.format.DateTimeFormatter

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

internal const val MIN_SESSION_EPOCH_MS = 1_000_000_000_000L

internal class SessionActivityClock(
    initialFloor: Long = 0L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastIssued = initialFloor.coerceAtLeast(0L)

    fun seed(existing: Long) {
        if (existing > lastIssued) lastIssued = existing
    }

    fun next(): Long {
        val candidate = nowMs()
        lastIssued = if (candidate > lastIssued) candidate else lastIssued + 1L
        return lastIssued
    }
}

internal class SessionActivityState(record: SessionRecord) {
    private val knownSubmissionIds = record.submissions.mapTo(HashSet()) { it.id }
    private var everSettled = false
    private var wasTurnActive = false
    private var knownRequestIds = emptySet<String>()
    private var knownAssistantIds = emptySet<String>()
    private var lastRequestsRef: List<UserRequest>? = null
    private var lastMessagesRef: List<ConversationItem>? = null
    private var pendingPostSubmitCompletion = false
    var lastTurnCompleted: Boolean = false
        private set

    fun reconcile(
        record: SessionRecord,
        connected: Boolean,
        loading: Boolean,
        hydrating: Boolean,
        activeTurnId: String?,
        messages: List<ConversationItem>,
        requests: List<UserRequest>,
        viewingInForeground: Boolean,
        clock: SessionActivityClock,
    ): Boolean {
        var changed = false
        lastTurnCompleted = false

        if (viewingInForeground && record.unread) {
            record.unread = false
            changed = true
        }

        var hasNewSubmission = false
        for (submission in record.submissions) {
            if (knownSubmissionIds.add(submission.id)) hasNewSubmission = true
        }
        if (hasNewSubmission) {
            record.lastActiveAt = clock.next()
            pendingPostSubmitCompletion = true
            changed = true
        }

        if (!connected || loading || hydrating) return changed

        val currentRequestIds = if (everSettled && requests === lastRequestsRef) {
            knownRequestIds
        } else {
            requests.mapTo(HashSet()) { it.id }
        }
        val currentAssistantIds = if (everSettled && messages === lastMessagesRef) {
            knownAssistantIds
        } else {
            messages.asSequence().filter { it.role == "assistant" }.mapTo(HashSet()) { it.id }
        }
        val turnActive = activeTurnId != null

        if (!everSettled) {
            everSettled = true
            wasTurnActive = turnActive
            knownRequestIds = currentRequestIds
            knownAssistantIds = currentAssistantIds
            lastRequestsRef = requests
            lastMessagesRef = messages
            if (pendingPostSubmitCompletion && !turnActive && currentAssistantIds.isNotEmpty()) {
                pendingPostSubmitCompletion = false
                lastTurnCompleted = true
                record.lastActiveAt = clock.next()
                if (!viewingInForeground && !record.unread) record.unread = true
                changed = true
            } else if (turnActive) {
                pendingPostSubmitCompletion = false
            }
            return changed
        }

        if (turnActive) pendingPostSubmitCompletion = false

        val hasNewRequest = currentRequestIds !== knownRequestIds && currentRequestIds.any { it !in knownRequestIds }
        val fastSyncCompleted = !wasTurnActive && !turnActive && currentAssistantIds !== knownAssistantIds && currentAssistantIds.any { it !in knownAssistantIds }
        val turnCompleted = (wasTurnActive && !turnActive) || fastSyncCompleted

        if (hasNewRequest || turnCompleted) {
            record.lastActiveAt = clock.next()
            changed = true
        }
        if (turnCompleted) {
            pendingPostSubmitCompletion = false
            lastTurnCompleted = true
            if (!viewingInForeground && !record.unread) {
                record.unread = true
                changed = true
            }
        }

        wasTurnActive = turnActive
        knownRequestIds = currentRequestIds
        knownAssistantIds = currentAssistantIds
        lastRequestsRef = requests
        lastMessagesRef = messages
        return changed
    }
}

internal fun sortSessionsByActivity(sessions: List<SessionSummary>): List<SessionSummary> =
    sessions.sortedByDescending { it.lastActiveAt }

internal fun formatSessionActivityTime(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestampMs < MIN_SESSION_EPOCH_MS) return ""
    val targetZdt = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
    val nowZdt = Instant.ofEpochMilli(nowMs.coerceAtLeast(MIN_SESSION_EPOCH_MS)).atZone(zoneId)
    val targetDate = targetZdt.toLocalDate()
    val today = nowZdt.toLocalDate()
    val daysDiff = ChronoUnit.DAYS.between(targetDate, today)
    return when {
        daysDiff == 0L -> String.format(Locale.ROOT, "%02d:%02d", targetZdt.hour, targetZdt.minute)
        daysDiff == 1L -> tr("昨天", "Yesterday")
        daysDiff in 2L..6L -> when (targetDate.dayOfWeek) {
            DayOfWeek.MONDAY -> tr("星期一", "Mon")
            DayOfWeek.TUESDAY -> tr("星期二", "Tue")
            DayOfWeek.WEDNESDAY -> tr("星期三", "Wed")
            DayOfWeek.THURSDAY -> tr("星期四", "Thu")
            DayOfWeek.FRIDAY -> tr("星期五", "Fri")
            DayOfWeek.SATURDAY -> tr("星期六", "Sat")
            DayOfWeek.SUNDAY -> tr("星期日", "Sun")
        }
        targetDate.year == today.year -> if (LumaI18n.isZh()) "${targetDate.monthValue}月${targetDate.dayOfMonth}日" else targetDate.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        else -> if (LumaI18n.isZh()) "${targetDate.year}年${targetDate.monthValue}月${targetDate.dayOfMonth}日" else targetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    }
}
