package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.CodexCapabilities
import io.github.modao7067.luma.gui.agent.obj
import java.io.File

internal class DeliveryDiagnostics(private val file: File) {
    private val errors = mutableMapOf<String, String?>()
    private val states = mutableMapOf<String, String>()
    fun observe(sessionId: String, error: String?, messages: List<StoredSubmission>, dismissals: List<StoredQueueDismissal> = emptyList()) {
        if (errors[sessionId] != error) {
            errors[sessionId] = error
            append(obj("session" to sessionId, "event" to if (error == null) "recovered" else "error",
                "error" to error?.let { CodexCapabilities.redact(it).take(2048) }).toString())
        }
        messages.forEach { message ->
            val key = "$sessionId:${message.id}"
            if (states[key] != message.state) {
                states[key] = message.state
                append(obj("session" to sessionId, "submissionId" to message.id, "state" to message.state).toString())
            }
        }
        dismissals.forEach { intent ->
            val key = "$sessionId:dismiss:${intent.id}"
            if (states[key] != intent.state) {
                states[key] = intent.state
                append(obj("session" to sessionId, "event" to "queue-dismissal", "queueId" to intent.id, "state" to intent.state).toString())
            }
        }
    }
    private fun append(line: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.length() > 512 * 1024) {
                val previous = File(file.path + ".1")
                previous.delete(); file.renameTo(previous)
            }
            file.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Exception) {  }
    }
}
