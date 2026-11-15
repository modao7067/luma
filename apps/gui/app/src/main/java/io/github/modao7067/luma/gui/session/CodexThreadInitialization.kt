package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONObject

internal object CodexThreadInitialization {
    suspend fun finish(
        record: SessionRecord,
        startedThread: JSONObject?,
        request: suspend (String, JSONObject) -> JSONObject,
        persist: () -> Unit,
    ) {
        if (!record.needsName) return
        val id = checkNotNull(record.threadId)
        if (record.useNativeTitle) {
            check(!record.requireLoaded) { tr("接入会话不能重新初始化", "Attached sessions cannot be re-initialized") }
            val thread = startedThread ?: request("thread/read", obj("threadId" to id, "includeTurns" to false)).getJSONObject("thread")
            check(thread.getString("id") == id) { tr("服务返回了不同会话，已停止初始化", "Service returned a different session; initialization aborted") }
            check(thread.has("section")) { tr("Codex 未提供原生会话状态，无法安全完成空会话初始化", "Codex did not return native session state; cannot safely initialize empty session") }
            if (thread.isNull("section")) {
                request("thread/section/move", obj("threadId" to id, "sectionId" to JSONObject.NULL))
            }
        } else {
            request("thread/name/set", obj("threadId" to id, "name" to checkNotNull(record.initialNativeTitle)))
        }
        record.needsName = false
        persist()
    }
}
