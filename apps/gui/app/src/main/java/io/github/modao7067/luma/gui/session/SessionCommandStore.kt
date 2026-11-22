package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class SessionCommandStore internal constructor(
    private val baseFile: File,
    private val reader: (File) -> ByteArray? = ::defaultReadAtomic,
    private val writer: (File, ByteArray) -> Unit = ::defaultWriteAtomic,
) {
    constructor(file: File) : this(file, ::defaultReadAtomic, ::defaultWriteAtomic)
    data class Entry(val requestId: String, val payload: String, val sessionId: String, val submissionId: String?,
                     var state: String = "reserved", var error: String? = null)
    val entries = linkedMapOf<String, Entry>()
    var error: String? = null; private set
    init {
        drainWrites(baseFile.absolutePath, rethrowError = false)
        try {
            val bytes = reader(baseFile)
            if (bytes != null) {
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                require(root.getInt("version") == 1)
                val rows = root.getJSONArray("commands")
                repeat(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val entry = Entry(row.getString("requestId"), row.getString("payload"), row.getString("sessionId"),
                        row.optString("submissionId").takeIf { !row.isNull("submissionId") }, row.getString("state"),
                        row.optString("error").takeIf { !row.isNull("error") })
                    check(entries.put(entry.requestId, entry) == null)
                }
            }
        } catch (_: Exception) {
            error = tr("命令账本无法读取，原文件已保留；create/send 已停止以免重复执行", "Command ledger unreadable (original file kept); create/send halted to prevent duplicate execution")
            entries.clear()
        }
    }
    fun reserve(request: JSONObject, sessionId: String, submissionId: String? = null): Entry {
        check(error == null) { error!! }
        val key = request.getString("requestId")
        check(key !in entries) { tr("requestId 已存在", "requestId already exists") }
        val entry = Entry(key, SessionManagementProtocol.canonical(request), sessionId, submissionId)
        entries[key] = entry
        try { saveDurable() } catch (e: Exception) { entries.remove(key); throw e }
        return entry
    }
    suspend fun reserveDurable(
        request: JSONObject,
        sessionId: String,
        submissionId: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Entry {
        check(error == null) { error!! }
        val key = request.getString("requestId")
        check(key !in entries) { tr("requestId 已存在", "requestId already exists") }
        val entry = Entry(key, SessionManagementProtocol.canonical(request), sessionId, submissionId)
        entries[key] = entry
        val batch = try {
            enqueueSnapshot(trackForFlush = false)
        } catch (e: Exception) {
            entries.remove(key)
            throw e
        }
        try {
            withContext(dispatcher) { awaitBatch(batch) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entries.remove(key)
            throw e
        }
        return entry
    }
    fun existing(request: JSONObject): Entry? {
        check(error == null) { error!! }
        return entries[request.getString("requestId")]?.also {
            require(it.payload == SessionManagementProtocol.canonical(request)) { tr("requestId 已用于另一份请求；不会执行不同负载", "requestId already used for a different payload") }
        }
    }
    fun update(entry: Entry, state: String, error: String? = null) {
        if (entry.state == state && entry.error == error) return
        val previous = entry.state; val previousError = entry.error
        entry.state = state; entry.error = error
        try { saveDurable() } catch (e: Exception) { entry.state = previous; entry.error = previousError; throw e }
    }
    fun updateAsync(entry: Entry, state: String, error: String? = null) {
        if (entry.state == state && entry.error == error) return
        val previous = entry.state; val previousError = entry.error
        entry.state = state; entry.error = error
        try { enqueueSnapshot(trackForFlush = true) } catch (e: Exception) { entry.state = previous; entry.error = previousError; throw e }
    }
    suspend fun updateDurable(
        entry: Entry,
        state: String,
        error: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        if (entry.state == state && entry.error == error) return
        val previous = entry.state; val previousError = entry.error
        entry.state = state; entry.error = error
        val batch = try {
            enqueueSnapshot(trackForFlush = false)
        } catch (e: Exception) {
            entry.state = previous; entry.error = previousError
            throw e
        }
        try {
            withContext(dispatcher) { awaitBatch(batch) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entry.state = previous; entry.error = previousError
            throw e
        }
    }
    fun flush() = drainWrites(baseFile.absolutePath, rethrowError = true)
    private fun verifyWritableTarget() {
        val parent = baseFile.parentFile
        if (baseFile.isDirectory || (parent != null && (parent.isFile || (!parent.exists() && !parent.mkdirs()) || !parent.canWrite()))) {
            throw IOException(tr("命令账本路径不可写: ${baseFile.path}", "Command store path is not writable: ${baseFile.path}"))
        }
    }
    private fun enqueueSnapshot(trackForFlush: Boolean): BatchState {
        verifyWritableTarget()
        return enqueueWrite(baseFile, entries.values.map { it.copy() }, writer, trackForFlush)
    }
    private fun saveDurable() {
        val batch = enqueueSnapshot(trackForFlush = false)
        awaitBatch(batch)
    }
    private class BatchState {
        var done = false
        var failure: Throwable? = null
    }
    private data class PendingWrite(
        val target: File,
        val entries: List<Entry>,
        val writer: (File, ByteArray) -> Unit,
        val batch: BatchState,
    )
    private companion object {
        private val writeLock = Object()
        private val pendingWrites = LinkedHashMap<String, PendingWrite>()
        private val activeByKey = HashMap<String, Int>()
        private val lastAsyncBatch = HashMap<String, BatchState>()
        private var workerStarted = false
        private fun defaultReadAtomic(target: File): ByteArray? {
            if (!target.exists() && !File("${target.path}.bak").exists()) return null
            val atomic = AtomicFile(target)
            return atomic.readFully()
        }
        private fun defaultWriteAtomic(target: File, bytes: ByteArray) {
            val atomic = AtomicFile(target)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (failure: Throwable) {
                atomic.failWrite(out)
                throw failure
            }
        }
        private fun asIoException(failure: Throwable): IOException =
            failure as? IOException ?: IOException(failure.message ?: tr("无法写入命令账本", "Failed to write command store"), failure)
        private fun ensureWorkerLocked() {
            if (workerStarted) return
            workerStarted = true
            Thread({
                while (true) {
                    val (key, next) = synchronized(writeLock) {
                        while (pendingWrites.isEmpty()) {
                            writeLock.wait()
                        }
                        val firstKey = pendingWrites.keys.first()
                        activeByKey[firstKey] = (activeByKey[firstKey] ?: 0) + 1
                        firstKey to pendingWrites.remove(firstKey)!!
                    }
                    var writeFailure: Throwable? = null
                    try {
                        writeDisk(next)
                    } catch (failure: Throwable) {
                        writeFailure = failure
                    } finally {
                        synchronized(writeLock) {
                            next.batch.failure = writeFailure
                            next.batch.done = true
                            if (writeFailure == null) {
                                val tracked = lastAsyncBatch[key]
                                if (tracked != null && tracked.done) {
                                    lastAsyncBatch.remove(key)
                                }
                            }
                            val rem = (activeByKey[key] ?: 1) - 1
                            if (rem <= 0) activeByKey.remove(key) else activeByKey[key] = rem
                            writeLock.notifyAll()
                        }
                    }
                }
            }, "LumaCommandStore").apply {
                isDaemon = true
                start()
            }
        }
        private fun enqueueWrite(
            target: File,
            entries: List<Entry>,
            writer: (File, ByteArray) -> Unit,
            trackForFlush: Boolean,
        ): BatchState = synchronized(writeLock) {
            ensureWorkerLocked()
            val key = target.absolutePath
            val batch = pendingWrites[key]?.batch ?: BatchState()
            pendingWrites[key] = PendingWrite(target, entries, writer, batch)
            if (trackForFlush) {
                lastAsyncBatch[key] = batch
            }
            writeLock.notifyAll()
            batch
        }
        private fun awaitBatch(batch: BatchState) {
            synchronized(writeLock) {
                while (!batch.done) {
                    writeLock.wait()
                }
                batch.failure?.let { throw asIoException(it) }
            }
        }
        private fun drainWrites(key: String, rethrowError: Boolean) {
            synchronized(writeLock) {
                while (pendingWrites.containsKey(key) || (activeByKey[key] ?: 0) > 0) {
                    writeLock.wait()
                }
                val batch = lastAsyncBatch.remove(key)
                if (rethrowError && batch?.failure != null) {
                    throw asIoException(batch.failure!!)
                }
            }
        }
        private fun writeDisk(item: PendingWrite) {
            val root = JSONObject().put("version", 1).put("commands", JSONArray(item.entries.map { entry ->
                JSONObject().put("requestId", entry.requestId).put("payload", entry.payload).put("sessionId", entry.sessionId)
                    .put("submissionId", entry.submissionId ?: JSONObject.NULL).put("state", entry.state).put("error", entry.error ?: JSONObject.NULL)
            }))
            item.writer(item.target, root.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
