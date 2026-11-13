package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal class OpenCodeSyncOutage(private val nanoTime: () -> Long = System::nanoTime) {
    private var started: Long? = null
    private var announced = false
    private var latest: Throwable? = null
    var notice: String? = null; private set
    var terminal = false; private set
    val active get() = started != null
    val remainingMillis: Long get() = started?.let {
        ((10_000_000_000L - (nanoTime() - it)).coerceAtLeast(0) + 999_999) / 1_000_000
    } ?: 0

    fun failed(failure: Throwable) {
        if (started == null) started = nanoTime()
        latest = failure
        if (!retryable(failure)) {
            terminal = true
            notice = "OpenCode 无法同步，请检查认证或配置：${failure.message ?: "未知错误"}"
            announced = true
        } else noticeIfDue()
    }

    fun noticeIfDue() {
        if (active && !announced && remainingMillis == 0L) {
            notice = "OpenCode 状态暂未同步，正在重试：${latest?.message ?: "连接中断"}"
            announced = true
        }
    }

    fun dismiss() { notice = null }
    fun recovered() { started = null; announced = false; latest = null; notice = null; terminal = false }

    companion object {
        fun retryable(failure: Throwable): Boolean = when (failure) {
            is OpenCodeRejected -> failure.status in listOf(408, 425, 429) || failure.status in 500..599
            is SSLHandshakeException, is SSLPeerUnverifiedException -> false
            is IOException -> true
            else -> false
        }
    }
}

internal class OpenCodeSync(
    private val scope: CoroutineScope,
    private val streamConnected: () -> Boolean,
    private val subscribe: suspend ((Throwable) -> Unit) -> Unit,
    private val refresh: suspend (all: Boolean, ids: List<String>, catalog: Boolean) -> Unit,
    private val changed: (State) -> Unit,
    private val outage: OpenCodeSyncOutage = OpenCodeSyncOutage(),
) {
    data class State(val connected: Boolean, val recovering: Boolean, val notice: String?)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val dirtyMessages = linkedSetOf<String>()
    private var full = true
    private var catalog = true
    private var closed = false
    private var streamEpoch = 0
    private var worker: Job? = null
    private var noticeTimer: Job? = null
    val recovering get() = !closed && outage.active && !outage.terminal

    fun invalidate(all: Boolean = false, ids: List<String> = emptyList(), models: Boolean = false) {
        if (closed || outage.terminal) return
        full = full || all; catalog = catalog || models; dirtyMessages.addAll(ids)
        signals.trySend(Unit)
    }

    fun start() {
        check(worker == null)
        invalidate()
        worker = scope.launch {
            while (isActive && !closed && !outage.terminal) {
                signals.receive()
                delay(100)
                try {
                    if (!streamConnected()) {
                        full = true
                        val epoch = ++streamEpoch
                        subscribe { failure -> scope.launch {
                            if (!closed && epoch == streamEpoch) {
                                streamEpoch++
                                failed(failure)
                                invalidate(all = true)
                            }
                        } }
                    }
                    val epoch = streamEpoch
                    val all = full || outage.active
                    val models = catalog
                    val ids = dirtyMessages.toList()
                    full = false; catalog = false; dirtyMessages.clear()
                    try { refresh(all, ids, models) }
                    catch (failure: Exception) { catalog = catalog || models; throw failure }
                    if (!streamConnected() || epoch != streamEpoch) throw IOException("OpenCode 事件连接已断开")
                    if (all) {
                        outage.recovered(); noticeTimer?.cancel(); noticeTimer = null
                        changed(State(connected = true, recovering = false, notice = null))
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    failed(failure)
                    if (!outage.terminal) {
                        full = true
                        delay(1_000)
                        invalidate(all = true)
                    }
                }
            }
        }
    }

    private fun failed(failure: Throwable) {
        if (closed || outage.terminal) return
        outage.failed(failure)
        publishFailure()
        if (!outage.terminal && noticeTimer == null) noticeTimer = scope.launch {
            while (outage.active && !outage.terminal && outage.remainingMillis > 0) delay(outage.remainingMillis)
            if (!closed && outage.active) { outage.noticeIfDue(); publishFailure() }
        }
    }

    private fun publishFailure() = changed(State(connected = false, recovering = recovering, notice = outage.notice))
    fun dismissNotice() { outage.dismiss() }
    fun close() { closed = true; streamEpoch++; worker?.cancel(); noticeTimer?.cancel(); signals.close() }
}
