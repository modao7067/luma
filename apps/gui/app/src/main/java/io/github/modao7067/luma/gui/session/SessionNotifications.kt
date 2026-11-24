package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.modao7067.luma.gui.MainActivity
import io.github.modao7067.luma.root.R as RuntimeR

internal enum class SessionNotificationKind(val channelId: String, private val zh: String, private val en: String) {
    APPROVAL(SessionNotifications.ACTION_CHANNEL_ID, "等待你的审批", "Waiting for your approval"),
    QUESTION(SessionNotifications.ACTION_CHANNEL_ID, "等待你的回答", "Waiting for your reply"),
    COMPLETED(SessionNotifications.COMPLETE_CHANNEL_ID, "本轮回复已完成", "Turn completed"),
    ;
    val bodyText: String get() = tr(zh, en)
}

internal sealed interface SessionNotificationDecision {
    data object None : SessionNotificationDecision
    data object Cancel : SessionNotificationDecision
    data class Post(val kind: SessionNotificationKind) : SessionNotificationDecision
}

internal object SessionNotifications {
    const val ACTION_CHANNEL_ID = "luma-session-action"
    const val COMPLETE_CHANNEL_ID = "luma-session-complete"
    const val GROUP_KEY = "io.github.modao7067.luma.gui.SESSIONS"
    const val SUMMARY_NOTIFICATION_ID = 1999
    const val SESSION_NOTIFICATION_ID = 2000
    const val MAX_ACTIVE_SESSION_NOTIFICATIONS = 20

    private val LEGACY_CHANNELS = listOf("luma-agent-connection", "luma-agent-approval")

    fun formatTitle(sessionTitle: String, cli: String): String =
        "${sessionTitle.trim().let { if (it.isEmpty() || SessionTitlePolicy.isUnnamed(it)) SessionTitlePolicy.unnamed else it }} · ${CliCatalog.displayName(cli)}"

    fun isQuestionRequest(request: UserRequest): Boolean =
        request.questions.isNotEmpty() || request.id.startsWith("form:")

    fun keepAliveSummaryText(runningCount: Int, waitingCount: Int): String = when {
        runningCount > 0 && waitingCount > 0 -> tr("${runningCount} 个会话执行中 · ${waitingCount} 个待处理", "$runningCount running · $waitingCount waiting")
        runningCount > 0 -> tr("${runningCount} 个会话执行中", "$runningCount session(s) running")
        waitingCount > 0 -> tr("${waitingCount} 个会话待处理", "$waitingCount session(s) waiting")
        else -> tr("后台环境运行中", "Background runtime active")
    }

    fun ensureChannels(manager: NotificationManager) {
        LEGACY_CHANNELS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(ACTION_CHANNEL_ID, tr("待审批与提问", "Approvals & Questions"), NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(COMPLETE_CHANNEL_ID, tr("回合完成", "Turn Completed"), NotificationManager.IMPORTANCE_HIGH)
        )
    }
}

internal class SessionNotificationTracker {
    private var everSettled = false
    private var knownRequestIds: Set<String> = emptySet()
    private var lastRequestsRef: List<UserRequest>? = null
    var activePostedKind: SessionNotificationKind? = null
        private set
    private var activeRequestSignature: String = ""

    fun reconcile(
        connected: Boolean,
        loading: Boolean,
        hydrating: Boolean,
        turnActive: Boolean,
        turnCompleted: Boolean,
        consumeUserStop: () -> Boolean,
        requests: List<UserRequest>,
        viewingInForeground: Boolean,
    ): SessionNotificationDecision {
        val userStopped = if (turnCompleted) consumeUserStop() else false
        val settledNow = connected && !loading && !hydrating

        if (viewingInForeground) {
            if (settledNow) {
                everSettled = true
                knownRequestIds = snapshotIds(requests)
                lastRequestsRef = requests
            }
            return if (activePostedKind != null) {
                clear()
                SessionNotificationDecision.Cancel
            } else {
                SessionNotificationDecision.None
            }
        }

        if (!settledNow) return SessionNotificationDecision.None

        if (!everSettled) {
            everSettled = true
            knownRequestIds = snapshotIds(requests)
            lastRequestsRef = requests
            if (turnCompleted && !userStopped && requests.isEmpty()) {
                if (activePostedKind != SessionNotificationKind.COMPLETED) {
                    activePostedKind = SessionNotificationKind.COMPLETED
                    activeRequestSignature = ""
                    return SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED)
                }
                return SessionNotificationDecision.None
            }
            if (requests.isEmpty() && (activePostedKind == SessionNotificationKind.APPROVAL || activePostedKind == SessionNotificationKind.QUESTION)) {
                clear()
                return SessionNotificationDecision.Cancel
            }
            return SessionNotificationDecision.None
        }

        val sameRequestRef = requests === lastRequestsRef
        if (!sameRequestRef) {
            val currentIds = snapshotIds(requests)
            var latestNew: UserRequest? = null
            for (r in requests) {
                if (r.id !in knownRequestIds) latestNew = r
            }
            knownRequestIds = currentIds
            lastRequestsRef = requests

            if (latestNew != null) {
                val kind = if (SessionNotifications.isQuestionRequest(latestNew)) {
                    SessionNotificationKind.QUESTION
                } else {
                    SessionNotificationKind.APPROVAL
                }
                val signature = currentIds.sorted().joinToString(",")
                if (activePostedKind != kind || activeRequestSignature != signature) {
                    activePostedKind = kind
                    activeRequestSignature = signature
                    return SessionNotificationDecision.Post(kind)
                }
                return SessionNotificationDecision.None
            }
        }

        if (requests.isEmpty() && (activePostedKind == SessionNotificationKind.APPROVAL || activePostedKind == SessionNotificationKind.QUESTION)) {
            return if (turnCompleted && !userStopped) {
                activePostedKind = SessionNotificationKind.COMPLETED
                activeRequestSignature = ""
                SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED)
            } else {
                clear()
                SessionNotificationDecision.Cancel
            }
        }

        if (turnCompleted && !userStopped && requests.isEmpty()) {
            if (activePostedKind != SessionNotificationKind.COMPLETED) {
                activePostedKind = SessionNotificationKind.COMPLETED
                activeRequestSignature = ""
                return SessionNotificationDecision.Post(SessionNotificationKind.COMPLETED)
            }
            return SessionNotificationDecision.None
        }

        if (turnActive && activePostedKind == SessionNotificationKind.COMPLETED) {
            clear()
            return SessionNotificationDecision.Cancel
        }

        return SessionNotificationDecision.None
    }

    private fun snapshotIds(requests: List<UserRequest>): Set<String> {
        if (requests.isEmpty()) return emptySet()
        return requests.mapTo(HashSet(requests.size)) { it.id }
    }

    fun onExternalWake(kind: SessionNotificationKind, viewingInForeground: Boolean): SessionNotificationDecision {
        if (viewingInForeground) {
            return if (activePostedKind != null) {
                clear()
                SessionNotificationDecision.Cancel
            } else {
                SessionNotificationDecision.None
            }
        }
        if (activePostedKind == kind) return SessionNotificationDecision.None
        activePostedKind = kind
        activeRequestSignature = "wake:${kind.name}"
        return SessionNotificationDecision.Post(kind)
    }

    fun clear() {
        activePostedKind = null
        activeRequestSignature = ""
    }
}

internal interface SessionNotificationSink {
    fun post(sessionId: String, sessionTitle: String, cli: String, kind: SessionNotificationKind)
    fun cancel(sessionId: String)
    fun cancelAll()
}

internal class AndroidSessionNotificationSink(
    private val context: Context,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : SessionNotificationSink {
    private data class ActiveSlot(val formattedTitle: String, val kind: SessionNotificationKind)
    private data class PendingPost(val sessionId: String, val sessionTitle: String, val cli: String, val kind: SessionNotificationKind)

    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var channelsInitialized = false

    private val activeSlots = LinkedHashMap<String, ActiveSlot>()
    private val pendingPosts = LinkedHashMap<String, PendingPost>()
    private var summaryPosted = false
    private var drainScheduled = false

    private var tokens = BURST_CAPACITY.toDouble()
    private var lastRefillMs = nowElapsedMs()

    private val drainRunnable = Runnable {
        drainScheduled = false
        drainPending()
    }

    init {
        manager?.let {
            SessionNotifications.ensureChannels(it)
            channelsInitialized = true
        }
    }

    override fun post(sessionId: String, sessionTitle: String, cli: String, kind: SessionNotificationKind) {
        val nm = manager ?: return
        ensureChannelsOnce(nm)
        val formattedTitle = SessionNotifications.formatTitle(sessionTitle, cli)
        val existing = activeSlots[sessionId]
        if (existing != null && existing.formattedTitle == formattedTitle && existing.kind == kind && isPresentInOs(nm, sessionId)) {
            pendingPosts.remove(sessionId)
            return
        }
        pendingPosts[sessionId] = PendingPost(sessionId, sessionTitle, cli, kind)
        drainPending()
    }

    override fun cancel(sessionId: String) {
        pendingPosts.remove(sessionId)
        val nm = manager ?: return
        activeSlots.remove(sessionId)
        nm.cancel(sessionId, SessionNotifications.SESSION_NOTIFICATION_ID)
        reconcileGroupSummary(nm)
    }

    override fun cancelAll() {
        pendingPosts.clear()
        if (drainScheduled) {
            mainHandler.removeCallbacks(drainRunnable)
            drainScheduled = false
        }
        val nm = manager ?: return
        val ids = activeSlots.keys.toList()
        activeSlots.clear()
        ids.forEach { nm.cancel(it, SessionNotifications.SESSION_NOTIFICATION_ID) }
        summaryPosted = false
        nm.cancel(SessionNotifications.SUMMARY_NOTIFICATION_ID)
    }

    private fun drainPending() {
        val nm = manager ?: return
        refillTokens()
        while (pendingPosts.isNotEmpty() && tokens >= 1.0) {
            val iterator = pendingPosts.entries.iterator()
            val next = iterator.next().value
            iterator.remove()
            if (deliverNow(nm, next)) {
                tokens -= 1.0
            }
        }
        if (pendingPosts.isNotEmpty() && !drainScheduled) {
            drainScheduled = true
            mainHandler.postDelayed(drainRunnable, REFILL_INTERVAL_MS)
        }
    }

    private fun refillTokens() {
        val now = nowElapsedMs()
        val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
        if (elapsed > 0L) {
            val gained = elapsed.toDouble() / REFILL_INTERVAL_MS.toDouble()
            tokens = (tokens + gained).coerceAtMost(BURST_CAPACITY.toDouble())
            lastRefillMs = now
        }
    }

    private fun evictIfNeeded(nm: NotificationManager, incomingSessionId: String) {
        if (incomingSessionId in activeSlots) return
        pruneDismissedFromOs(nm)
        while (activeSlots.size >= SessionNotifications.MAX_ACTIVE_SESSION_NOTIFICATIONS) {
            val victim = activeSlots.entries.firstOrNull { it.value.kind == SessionNotificationKind.COMPLETED }?.key
                ?: activeSlots.keys.firstOrNull()
                ?: break
            activeSlots.remove(victim)
            nm.cancel(victim, SessionNotifications.SESSION_NOTIFICATION_ID)
        }
    }

    private fun pruneDismissedFromOs(nm: NotificationManager) {
        val osTags = runCatching {
            nm.activeNotifications.asSequence()
                .filter { it.id == SessionNotifications.SESSION_NOTIFICATION_ID && it.tag != null }
                .mapTo(HashSet()) { it.tag }
        }.getOrNull() ?: return
        activeSlots.keys.retainAll(osTags)
    }

    private fun isPresentInOs(nm: NotificationManager, sessionId: String): Boolean =
        runCatching {
            nm.activeNotifications.any { it.id == SessionNotifications.SESSION_NOTIFICATION_ID && it.tag == sessionId }
        }.getOrDefault(true)

    private fun isSummaryPresentInOs(nm: NotificationManager): Boolean =
        runCatching {
            nm.activeNotifications.any { it.id == SessionNotifications.SUMMARY_NOTIFICATION_ID && it.tag == null }
        }.getOrDefault(false)

    private fun deliverNow(nm: NotificationManager, item: PendingPost): Boolean {
        evictIfNeeded(nm, item.sessionId)
        val formattedTitle = SessionNotifications.formatTitle(item.sessionTitle, item.cli)
        val openIntent = Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("luma-session", item.sessionId, null))
            .putExtra("sessionId", item.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val category = if (item.kind == SessionNotificationKind.COMPLETED) {
            Notification.CATEGORY_STATUS
        } else {
            Notification.CATEGORY_MESSAGE
        }
        val notification = Notification.Builder(context, item.kind.channelId)
            .setSmallIcon(RuntimeR.drawable.ic_notification)
            .setContentTitle(formattedTitle)
            .setContentText(item.kind.bodyText)
            .setCategory(category)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(SessionNotifications.GROUP_KEY)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .build()
        return try {
            nm.notify(item.sessionId, SessionNotifications.SESSION_NOTIFICATION_ID, notification)
            activeSlots.remove(item.sessionId)
            activeSlots[item.sessionId] = ActiveSlot(formattedTitle, item.kind)
            reconcileGroupSummary(nm)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannelsOnce(nm: NotificationManager) {
        if (!channelsInitialized) {
            SessionNotifications.ensureChannels(nm)
            channelsInitialized = true
        }
    }

    private fun reconcileGroupSummary(nm: NotificationManager) {
        val osGroupedCount = runCatching {
            nm.activeNotifications.count {
                it.id != SessionNotifications.SUMMARY_NOTIFICATION_ID &&
                    it.notification.group == SessionNotifications.GROUP_KEY
            }
        }.getOrElse { activeSlots.size }

        if (osGroupedCount >= 2) {
            val summaryAlive = summaryPosted && runCatching {
                nm.activeNotifications.any { it.id == SessionNotifications.SUMMARY_NOTIFICATION_ID && it.tag == null }
            }.getOrDefault(true)
            if (summaryAlive) return
            val openApp = PendingIntent.getActivity(
                context,
                SessionNotifications.SUMMARY_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .setData(Uri.fromParts("luma-summary", "sessions", null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val summary = Notification.Builder(context, SessionNotifications.COMPLETE_CHANNEL_ID)
                .setSmallIcon(RuntimeR.drawable.ic_notification)
                .setContentTitle("Luma")
                .setContentText(tr("多条会话通知", "Multiple session notifications"))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(SessionNotifications.GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build()
            try {
                nm.notify(SessionNotifications.SUMMARY_NOTIFICATION_ID, summary)
                summaryPosted = true
            } catch (_: SecurityException) {
            }
        } else if (summaryPosted || isSummaryPresentInOs(nm)) {
            summaryPosted = false
            nm.cancel(SessionNotifications.SUMMARY_NOTIFICATION_ID)
        }
    }

    companion object {
        internal const val BURST_CAPACITY = 4
        internal const val REFILL_INTERVAL_MS = 260L
    }
}
