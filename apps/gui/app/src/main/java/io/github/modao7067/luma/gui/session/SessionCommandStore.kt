package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class SessionCommandStore(file: File) {
    data class Entry(val requestId: String, val payload: String, val sessionId: String, val submissionId: String?,
                     var state: String = "reserved", var error: String? = null)
    private val file = AtomicFile(file)
    val entries = linkedMapOf<String, Entry>()
    var error: String? = null; private set
    init {
        if (file.exists()) try {
            val root = JSONObject(String(this.file.readFully(), Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val rows = root.getJSONArray("commands")
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val entry = Entry(row.getString("requestId"), row.getString("payload"), row.getString("sessionId"),
                    row.optString("submissionId").takeIf { !row.isNull("submissionId") }, row.getString("state"),
                    row.optString("error").takeIf { !row.isNull("error") })
                check(entries.put(entry.requestId, entry) == null)
            }
        } catch (_: Exception) { error = tr("命令账本无法读取，原文件已保留；create/send 已停止以免重复执行", "Command ledger unreadable (original file kept); create/send halted to prevent duplicate execution"); entries.clear() }
    }
    fun reserve(request: JSONObject, sessionId: String, submissionId: String? = null): Entry {
        check(error == null) { error!! }
        val key = request.getString("requestId")
        check(key !in entries) { tr("requestId 已存在", "requestId already exists") }
        val entry = Entry(key, SessionManagementProtocol.canonical(request), sessionId, submissionId)
        entries[key] = entry
        try { save() } catch (e: Exception) { entries.remove(key); throw e }
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
        try { save() } catch (e: Exception) { entry.state = previous; entry.error = previousError; throw e }
    }
    private fun save() {
        val root = JSONObject().put("version", 1).put("commands", JSONArray(entries.values.map { entry ->
            JSONObject().put("requestId", entry.requestId).put("payload", entry.payload).put("sessionId", entry.sessionId)
                .put("submissionId", entry.submissionId ?: JSONObject.NULL).put("state", entry.state).put("error", entry.error ?: JSONObject.NULL)
        }))
        val out = file.startWrite()
        try { out.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
}
