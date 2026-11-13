package io.github.modao7067.luma.gui.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class MarkdownParseTarget(val source: String, val complete: Boolean, val receivedNanos: Long = System.nanoTime(), val revision: Long = revisions.incrementAndGet()) {
    companion object { private val revisions = AtomicLong() }
}
internal data class MarkdownQueueUsage(val submitted: Long, val coalesced: Long, val published: Long, val rejectedStale: Long,
    val pending: Int, val peakPending: Int, val active: Int, val peakActive: Int, val latencyNanos: Long, val maxLatencyNanos: Long)

internal class MarkdownParseCoordinator(
    val parser: MarkdownDocumentParser = MarkdownDocumentParser(),
    val cache: MarkdownDocumentCache = MarkdownDocumentCache(),
    private val capacity: Int = 24,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {
    private val lock = Any()
    private val pending = LinkedHashMap<MarkdownParseSession, MarkdownParseTarget>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var submitted = 0L; private var coalesced = 0L; private var published = 0L; private var rejected = 0L
    private var peakPending = 0; private var active = 0; private var peakActive = 0
    private var latency = 0L; private var maxLatency = 0L
    init {
        require(capacity > 0)
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val work = synchronized(lock) {
                        val entry = pending.entries.firstOrNull() ?: return@synchronized null
                        val value = entry.key to entry.value; pending.remove(entry.key)
                        active = 1; peakActive = maxOf(peakActive, active); value
                    } ?: break
                    val (session, target) = work
                    try {
                        val cached = cache.get(target.source)
                        val current = session.snapshot.value
                        val result = cached?.copy(complete = target.complete)
                            ?: current?.takeIf { it.source == target.source }?.copy(complete = target.complete)
                            ?: parser.parse(target.source, target.complete, current)
                        if (target.complete) cache.put(result)
                        synchronized(lock) {
                            val latest = session.latest
                            if (!session.closed && latest != null && markdownResultBelongsTo(latest.source, result.source)) {
                                session.failure.value = null
                                session.result.value = result
                                published++
                                val duration = System.nanoTime() - target.receivedNanos
                                latency += duration; maxLatency = maxOf(maxLatency, duration)
                            } else rejected++
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        synchronized(lock) { if (!session.closed && session.latest === target) session.failure.value = failure }
                    } finally { synchronized(lock) { active = 0 } }
                }
            }
        }
    }
    fun session(identity: Any, initialSource: String): MarkdownParseSession =
        MarkdownParseSession(identity, cache.peek(initialSource), this)
    internal fun offer(session: MarkdownParseSession, target: MarkdownParseTarget): Boolean = synchronized(lock) {
        if (session.closed || (session.latest?.revision ?: 0L) > target.revision) return@synchronized true
        if (session.latest !== target) { session.latest = target; submitted++ }
        if (pending.containsKey(session)) { pending[session] = target; coalesced++; wake.trySend(Unit); return@synchronized true }
        if (pending.size >= capacity) return@synchronized false
        pending[session] = target; peakPending = maxOf(peakPending, pending.size); wake.trySend(Unit); true
    }
    internal fun release(session: MarkdownParseSession) = synchronized(lock) {
        session.closed = true; session.latest = null; pending.remove(session); Unit
    }
    fun usage(): MarkdownQueueUsage = synchronized(lock) {
        MarkdownQueueUsage(submitted, coalesced, published, rejected, pending.size, peakPending, active, peakActive, latency, maxLatency)
    }
    override fun close() { scope.cancel(); wake.close(); synchronized(lock) { pending.clear() } }
}
internal class MarkdownParseSession internal constructor(
    val identity: Any,
    initial: MarkdownDocument?,
    private val coordinator: MarkdownParseCoordinator,
) : AutoCloseable {
    internal val result = MutableStateFlow(initial)
    val snapshot: StateFlow<MarkdownDocument?> = result
    val failure = MutableStateFlow<Exception?>(null)
    internal var latest: MarkdownParseTarget? = null
    internal var closed = false
    suspend fun submit(target: MarkdownParseTarget) {
        while (!coordinator.offer(this, target)) { currentCoroutineContext().ensureActive(); delay(8) }
    }
    override fun close() = coordinator.release(this)
}
internal val markdownParseCoordinator by lazy { MarkdownParseCoordinator() }

internal fun markdownResultBelongsTo(latest: String, result: String): Boolean = latest.startsWith(result)
