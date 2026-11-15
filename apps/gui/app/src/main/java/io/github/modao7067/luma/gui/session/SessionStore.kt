package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.util.AtomicFile
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.agent.objects
import io.github.modao7067.luma.gui.agent.stringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class SessionRecord(
    val id: String, var title: String, val environment: String, val cwd: String,
    var model: String = "", var effort: String = "", var threadId: String? = null,
    var draft: String = "", var creationUnknown: Boolean = false,
    var submissionId: String? = null, var submissionText: String? = null,
    val images: MutableList<StoredImage> = mutableListOf(),
    var submissionImages: List<String> = emptyList(),
    var needsName: Boolean = threadId == null,
    var requireLoaded: Boolean = false,
    var queueTransfer: String? = null,
    val cli: String = "Codex",
    val skills: MutableList<DraftSkill> = mutableListOf(),
    var submissionSkills: List<String> = emptyList(),
    val useNativeTitle: Boolean = false,
    var draftRevision: Long = 0,
    var submissionDraftRevision: Long = 0,
    var submissionState: String = "unknown",
    var submissionDeadlineMs: Long = 0,
    var submissionRecovery: String = "",
    var submissionImageData: List<StoredImage> = emptyList(),
    var submissionSkillData: List<DraftSkill> = emptyList(),
    val deliveryResults: MutableMap<String, String> = linkedMapOf(),
    var manualTitle: String? = title.trim().takeIf { !useNativeTitle && it.isNotEmpty() && !SessionTitlePolicy.isUnnamed(it) },
    var nativeTitle: String? = title.takeIf { useNativeTitle && !SessionTitlePolicy.isUnnamed(it) }?.let { SessionTitlePolicy.validNativeTitle(cli, it) },
    var messageTitle: String? = null,
    val initialNativeTitle: String? = title.trim().takeIf { needsName && !useNativeTitle && it.isNotEmpty() },
    var submissionExternal: Boolean = false,
    var nativeProtocol: String? = null,
    val submissions: MutableList<StoredSubmission> = mutableListOf(),
    var externalSocket: String? = null,
    val queueDismissals: MutableList<StoredQueueDismissal> = mutableListOf(),
    var observationRequired: Boolean = false,
    val receiptRecoveryRequired: MutableSet<String> = linkedSetOf(),
    var lastActiveAt: Long = 0L,
    var unread: Boolean = false,
)
data class StoredSubmission(
    val id: String, val text: String, val images: List<StoredImage> = emptyList(), val skills: List<DraftSkill> = emptyList(),
    val external: Boolean = false, val draftRevision: Long = 0, var state: String = "pending",
    var nativeQueued: Boolean = false, val settings: Map<String, String> = emptyMap(),
    var queuePresentation: Boolean = false, var nativeConsumed: Boolean = false,
    var dismissed: Boolean = false, var nativeQueueId: String? = null,
    var takebackId: String? = null, var takebackState: String = "", var takebackDraftRevision: Long = 0,
    var restoredToComposer: Boolean = false,
    var livePendingObserved: Boolean = false,
    var baselineNativeUserIds: Set<String>? = null,
    var matchedNativeUserId: String? = null,
)
data class StoredQueueDismissal(val id: String, val clientUserMessageId: String? = null,
    var nativeQueueId: String? = null, var hostOwned: Boolean = false, var state: String = "waiting")
data class StoredImage(val id: String, val uri: String, val path: String, val name: String)
internal fun SessionRecord.snapshot(): SessionRecord = copy(
    images = images.toMutableList(),
    submissionImages = submissionImages.toList(),
    skills = skills.toMutableList(),
    submissionSkills = submissionSkills.toList(),
    submissionImageData = submissionImageData.toList(),
    submissionSkillData = submissionSkillData.toList(),
    deliveryResults = LinkedHashMap(deliveryResults),
    submissions = submissions.map {
        it.copy(
            images = it.images.toList(),
            skills = it.skills.toList(),
            settings = LinkedHashMap(it.settings),
            baselineNativeUserIds = it.baselineNativeUserIds?.toSet(),
        )
    }.toMutableList(),
    queueDismissals = queueDismissals.map { it.copy() }.toMutableList(),
    receiptRecoveryRequired = LinkedHashSet(receiptRecoveryRequired),
)
internal fun SessionNavigation.snapshot(): SessionNavigation = SessionNavigation(
    initialized = initialized,
    selectedId = selectedId,
    blank = BlankSessionDraft(
        key = blank.key,
        cli = blank.cli,
        cwd = blank.cwd,
        text = blank.text,
        images = blank.images.toMutableList(),
        skills = blank.skills.toMutableList(),
    ),
)
internal fun decodeSessionIndex(root: JSONObject): List<SessionRecord> {
    require(root.optInt("version") == 1) { tr("会话索引版本不兼容，原文件已保留", "Incompatible session index version; original file preserved") }
    return root.getJSONArray("sessions").objects().mapIndexed { index, value ->
        SessionRecord(value.getString("id"), value.getString("title"), value.getString("environment"), value.getString("cwd"),
            value.optString("model"), value.optString("effort"), value.stringOrNull("threadId"), value.optString("draft"),
            value.optBoolean("creationUnknown"), value.stringOrNull("submissionId"), value.stringOrNull("submissionText"),
            value.optJSONArray("images")?.objects()?.map { StoredImage(it.getString("id"), it.getString("uri"), it.getString("path"), it.getString("name")) }?.toMutableList() ?: mutableListOf(),
            value.optJSONArray("submissionImages")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            value.optBoolean("needsName", value.stringOrNull("threadId") == null), value.optBoolean("requireLoaded"), value.stringOrNull("queueTransfer"), value.optString("cli", "Codex"),
            value.optJSONArray("skills")?.objects()?.map { DraftSkill(it.getString("id"), it.getString("name"), it.getString("path")) }?.toMutableList() ?: mutableListOf(),
            value.optJSONArray("submissionSkills")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), value.optBoolean("useNativeTitle"),
            draftRevision = value.optLong("draftRevision"), submissionDraftRevision = value.optLong("submissionDraftRevision"),
            submissionState = value.optString("submissionState", "unknown"), submissionDeadlineMs = value.optLong("submissionDeadlineMs"),
            submissionRecovery = value.optString("submissionRecovery"),
            submissionImageData = value.optJSONArray("submissionImageData")?.objects()?.map { StoredImage(it.getString("id"), it.getString("uri"), it.getString("path"), it.getString("name")) }.orEmpty(),
            submissionSkillData = value.optJSONArray("submissionSkillData")?.objects()?.map { DraftSkill(it.getString("id"), it.getString("name"), it.getString("path")) }.orEmpty(),
            submissionExternal = value.optBoolean("submissionExternal"),
            nativeProtocol = value.stringOrNull("nativeProtocol"),
            externalSocket = value.stringOrNull("externalSocket"),
            receiptRecoveryRequired = value.optJSONArray("receiptRecoveryRequired")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toMutableSet()
            } ?: linkedSetOf(),
            lastActiveAt = value.optLong("lastActiveAt", 0L).let { if (it > 0L) it else (index + 1).toLong() },
            unread = value.optBoolean("unread", false),
            observationRequired = if (value.has("observationRequired")) value.optBoolean("observationRequired")
                else value.stringOrNull("threadId") != null,
            initialNativeTitle = value.stringOrNull("initialNativeTitle") ?: value.getString("title").trim().takeIf {
                !value.has("initialNativeTitle") && value.optBoolean("needsName", value.stringOrNull("threadId") == null) &&
                    !value.optBoolean("useNativeTitle") && it.isNotEmpty()
            }).also { record ->
                if (value.has("manualTitle")) record.manualTitle = value.stringOrNull("manualTitle")?.takeIf { it.isNotBlank() }
                if (value.has("nativeTitle")) record.nativeTitle = SessionTitlePolicy.validNativeTitle(record.cli, value.stringOrNull("nativeTitle"))
                record.messageTitle = value.stringOrNull("messageTitle")?.takeIf { it.isNotBlank() }
                value.optJSONArray("queueDismissals")?.objects()?.forEach { item ->
                    record.queueDismissals += StoredQueueDismissal(item.getString("id"), item.stringOrNull("clientUserMessageId"),
                        item.stringOrNull("nativeQueueId"), item.optBoolean("hostOwned"), item.optString("state", "waiting"))
                }
                value.optJSONArray("submissions")?.objects()?.forEach { item ->
                    val dismissed = item.optBoolean("dismissed")
                    val rawState = item.optString("state", "unknown")
                    val normalizedState = if (rawState == "pending" && !dismissed) "unknown" else rawState
                    record.submissions += StoredSubmission(item.getString("id"), item.getString("text"),
                        item.optJSONArray("images")?.objects()?.map { StoredImage(it.getString("id"), it.getString("uri"), it.getString("path"), it.getString("name")) }.orEmpty(),
                        item.optJSONArray("skills")?.objects()?.map { DraftSkill(it.getString("id"), it.getString("name"), it.getString("path")) }.orEmpty(),
                        item.optBoolean("external"), item.optLong("draftRevision"), normalizedState,
                        item.optBoolean("nativeQueued"), item.optJSONObject("settings")?.let { settings -> settings.keys().asSequence().associateWith { settings.getString(it) } }.orEmpty(),
                        item.optBoolean("queuePresentation"), item.optBoolean("nativeConsumed"), dismissed, item.stringOrNull("nativeQueueId"),
                        item.stringOrNull("takebackId"), item.optString("takebackState"), item.optLong("takebackDraftRevision"),
                        item.optBoolean("restoredToComposer"))
                }
                SubmissionLedger.migrate(record)
                SessionTitlePolicy.refresh(record)
            }
    }
}
internal fun encodeSessionIndex(records: Collection<SessionRecord>): JSONObject {
    return obj("version" to 1, "sessions" to JSONArray(records.map { r ->
        obj("id" to r.id, "title" to r.title, "environment" to r.environment, "cwd" to r.cwd,
            "model" to r.model, "effort" to r.effort, "threadId" to r.threadId, "draft" to r.draft,
            "creationUnknown" to r.creationUnknown, "submissionId" to r.submissionId, "submissionText" to r.submissionText,
            "submissionImages" to JSONArray(r.submissionImages), "needsName" to r.needsName, "requireLoaded" to r.requireLoaded, "queueTransfer" to r.queueTransfer, "cli" to r.cli,
            "skills" to JSONArray(r.skills.map { obj("id" to it.id, "name" to it.name, "path" to it.path) }),
            "submissionSkills" to JSONArray(r.submissionSkills), "useNativeTitle" to r.useNativeTitle,
            "manualTitle" to r.manualTitle, "nativeTitle" to r.nativeTitle, "messageTitle" to r.messageTitle,
            "initialNativeTitle" to r.initialNativeTitle,
            "draftRevision" to r.draftRevision, "submissionDraftRevision" to r.submissionDraftRevision,
            "submissionState" to r.submissionState, "submissionDeadlineMs" to r.submissionDeadlineMs, "submissionRecovery" to r.submissionRecovery,
            "submissionExternal" to r.submissionExternal,
            "nativeProtocol" to r.nativeProtocol, "externalSocket" to r.externalSocket, "observationRequired" to r.observationRequired,
            "lastActiveAt" to r.lastActiveAt, "unread" to r.unread,
            "receiptRecoveryRequired" to JSONArray(r.receiptRecoveryRequired.toList()),
            "queueDismissals" to JSONArray(r.queueDismissals.map { d -> obj("id" to d.id, "clientUserMessageId" to d.clientUserMessageId,
                "nativeQueueId" to d.nativeQueueId, "hostOwned" to d.hostOwned, "state" to d.state) }),
            "submissions" to JSONArray(r.submissions.map { m -> obj("id" to m.id, "text" to m.text, "external" to m.external, "draftRevision" to m.draftRevision, "state" to m.state, "nativeQueued" to m.nativeQueued, "settings" to JSONObject(m.settings),
                "queuePresentation" to m.queuePresentation, "nativeConsumed" to m.nativeConsumed, "dismissed" to m.dismissed, "nativeQueueId" to m.nativeQueueId,
                "takebackId" to m.takebackId, "takebackState" to m.takebackState, "takebackDraftRevision" to m.takebackDraftRevision,
                "restoredToComposer" to m.restoredToComposer,
                "images" to JSONArray(m.images.map { obj("id" to it.id, "uri" to it.uri, "path" to it.path, "name" to it.name) }),
                "skills" to JSONArray(m.skills.map { obj("id" to it.id, "name" to it.name, "path" to it.path) })) }),
            "submissionImageData" to JSONArray(r.submissionImageData.map { obj("id" to it.id, "uri" to it.uri, "path" to it.path, "name" to it.name) }),
            "submissionSkillData" to JSONArray(r.submissionSkillData.map { obj("id" to it.id, "name" to it.name, "path" to it.path) }),
            "images" to JSONArray(r.images.map { obj("id" to it.id, "uri" to it.uri, "path" to it.path, "name" to it.name) }))
    }))
}
class SessionStore internal constructor(
    private val baseFile: File,
    private val writer: (File, ByteArray) -> Unit = ::defaultWriteAtomic,
) {
    constructor(context: Context) : this(File(context.filesDir, "codex-sessions.json"))
    internal var navigation = SessionNavigation(); private set
    fun load(): List<SessionRecord> {
        drainWrites(baseFile.absolutePath, rethrowError = false)
        if (!baseFile.exists()) return emptyList()
        val bytes = AtomicFile(baseFile).readFully()
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val records = decodeSessionIndex(root)
        navigation = decodeNavigation(root.optJSONObject("navigation"))
        return records
    }
    private fun verifyWritableTarget() {
        val parent = baseFile.parentFile
        if (baseFile.isDirectory || (parent != null && (parent.isFile || (!parent.exists() && !parent.mkdirs()) || !parent.canWrite()))) {
            throw IOException(tr("会话存储路径不可写: ${baseFile.path}", "Session store path is not writable: ${baseFile.path}"))
        }
    }
    fun save(records: Collection<SessionRecord>) {
        verifyWritableTarget()
        enqueueWrite(baseFile, records.map { it.snapshot() }, navigation.snapshot(), writer)
    }
    fun saveDurable(records: Collection<SessionRecord>) {
        verifyWritableTarget()
        val seq = enqueueWrite(baseFile, records.map { it.snapshot() }, navigation.snapshot(), writer)
        awaitWrite(baseFile.absolutePath, seq)
    }
    fun flush() = drainWrites(baseFile.absolutePath, rethrowError = true)
    private data class PendingWrite(
        val seq: Long,
        val target: File,
        val records: List<SessionRecord>,
        val navigation: SessionNavigation,
        val writer: (File, ByteArray) -> Unit,
    )
    private companion object {
        private val writeLock = Object()
        private val pendingWrites = LinkedHashMap<String, PendingWrite>()
        private val activeByKey = HashMap<String, Int>()
        private val completedSeq = HashMap<String, Long>()
        private val batchErrors = HashMap<String, Pair<Long, Throwable>>()
        private var nextSeq = 0L
        private var activeWrites = 0
        private var workerStarted = false
        private fun defaultWriteAtomic(target: File, bytes: ByteArray) {
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
        }
        private fun asIoException(failure: Throwable): IOException =
            failure as? IOException ?: IOException(failure.message ?: tr("无法写入会话存储", "Failed to write session store"), failure)
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
                        activeWrites++
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
                            activeWrites--
                            val rem = (activeByKey[key] ?: 1) - 1
                            if (rem <= 0) activeByKey.remove(key) else activeByKey[key] = rem
                            val prevSeq = completedSeq[key] ?: 0L
                            if (next.seq >= prevSeq) {
                                completedSeq[key] = next.seq
                                if (writeFailure != null) {
                                    batchErrors[key] = next.seq to writeFailure
                                } else {
                                    batchErrors.remove(key)
                                }
                            }
                            writeLock.notifyAll()
                        }
                    }
                }
            }, "LumaSessionStore").apply {
                isDaemon = true
                start()
            }
        }
        private fun enqueueWrite(
            target: File,
            records: List<SessionRecord>,
            navigation: SessionNavigation,
            writer: (File, ByteArray) -> Unit,
        ): Long = synchronized(writeLock) {
            ensureWorkerLocked()
            val seq = ++nextSeq
            val key = target.absolutePath
            pendingWrites[key] = PendingWrite(seq, target, records, navigation, writer)
            writeLock.notifyAll()
            seq
        }
        private fun awaitWrite(key: String, minSeq: Long) {
            synchronized(writeLock) {
                while ((completedSeq[key] ?: 0L) < minSeq) {
                    writeLock.wait()
                }
                val err = batchErrors[key]
                if (err != null && err.first >= minSeq) {
                    batchErrors.remove(key)
                    throw asIoException(err.second)
                }
            }
        }
        private fun drainWrites(key: String, rethrowError: Boolean) {
            synchronized(writeLock) {
                while (pendingWrites.isNotEmpty() || activeWrites > 0) {
                    writeLock.wait()
                }
                val err = batchErrors.remove(key)
                if (rethrowError && err != null) {
                    throw asIoException(err.second)
                }
            }
        }
        private fun writeDisk(item: PendingWrite) {
            val root = encodeSessionIndex(item.records)
            if (item.navigation.initialized) root.put("navigation", encodeNavigation(item.navigation))
            item.writer(item.target, root.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
