package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONArray
import org.json.JSONObject

internal object HostQueueTakeback {
    fun ready(message: StoredSubmission) = message.takebackState == "ready" && message.state == "cancelled" &&
        !message.dismissed && !message.nativeQueued && !message.nativeConsumed

    fun payload(message: StoredSubmission) = obj("text" to message.text,
        "images" to JSONArray(message.images.map { it.path }), "skills" to JSONArray(message.skills.map { it.id }))

    fun begin(record: SessionRecord, id: String, operationId: String, persist: () -> Unit): String {
        val message = SubmissionLedger.find(record, id) ?: error("原待发内容不在本地索引中，不能取回")
        check(message.state == "queued" && !message.nativeQueued && !message.nativeConsumed && !message.dismissed && id !in record.receiptRecoveryRequired) {
            "消息已开始投递或仍需核对，不能返回输入框"
        }
        check(record.draft.isEmpty() && record.images.isEmpty() && record.skills.isEmpty()) {
            "输入框已有内容，请先处理当前输入，再编辑待发消息；两边内容均保留"
        }
        val token = message.takebackId.takeIf { message.takebackState == "pending" } ?: operationId
        SubmissionLedger.checkpoint(record, id, persist) {
            message.takebackId = token; message.takebackState = "pending"; message.takebackDraftRevision = record.draftRevision
        }
        return token
    }

    fun reconcile(record: SessionRecord, snapshot: JSONObject): String? {
        var error: String? = null
        record.submissions.filter { it.takebackState in setOf("pending", "ready") }.forEach { message ->
            val proof = snapshot.optJSONObject("takebacks")?.optJSONObject(message.id)
            val receipt = snapshot.optJSONObject("receipts")?.optString(message.id).orEmpty()
            val delivery = snapshot.optJSONObject("deliveries")?.optJSONObject(message.id)?.optString("state")
            if (message.nativeQueued || message.nativeConsumed || receipt.isNotEmpty() || delivery in setOf("dispatching", "unknown", "accepted", "rejected")) {
                message.takebackState = "failed"; error = "消息已开始投递，未返回输入框；不会重发"
            } else if (proof != null) {
                val actual = proof.optJSONObject("payload")
                val expected = payload(message)
                val matches = actual != null && actual.optString("text") == message.text && listOf("images", "skills").all { key ->
                    val native = actual.optJSONArray(key) ?: JSONArray()
                    native.toString() == expected.getJSONArray(key).toString()
                }
                if (proof.optString("operationId") == message.takebackId && delivery == "cancelled" && matches) {
                    message.state = "cancelled"; message.takebackState = "ready"
                } else {
                    message.takebackState = "failed"; error = "取回证明与待发身份不一致，原内容保留待核对"
                }
            }
        }
        return error
    }
}
