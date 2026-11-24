package io.github.modao7067.luma.gui.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.modao7067.luma.gui.GuiProduct
import io.github.modao7067.luma.gui.LumaApplication
import io.github.modao7067.luma.gui.runtime.RootBackgroundNotifications
import org.json.JSONArray

class SessionWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if ((!GuiProduct.usesRootExecution(context) && !RootBackgroundNotifications.forceEnableForTesting) ||
            intent?.action != RootBackgroundNotifications.ACTION_SESSION_WAKE
        ) return
        val token = intent.getStringExtra("token").orEmpty()
        if (!RootBackgroundNotifications.verifyToken(context, token)) return
        val watchGen = intent.getStringExtra("watchGen")?.toLongOrNull() ?: 0L
        if (!RootBackgroundNotifications.verifyWakeGeneration(context, watchGen)) return

        val parsed = parseWakeEvents(intent)
        if (parsed.isEmpty()) return

        val app = context.applicationContext as? LumaApplication ?: return
        val pending = goAsync()
        app.sessions.onBackgroundWakeBatch(parsed) {
            runCatching { pending?.finish() }
        }
    }

    companion object {
        internal fun parseWakeEvents(intent: Intent): List<Pair<String, SessionNotificationKind>> {
            val out = LinkedHashMap<String, SessionNotificationKind>()
            val rawBatch = intent.getStringExtra("events").orEmpty()
            if (rawBatch.isNotBlank() && rawBatch.length <= 65_536) {
                runCatching {
                    val arr = JSONArray(rawBatch)
                    val scanLimit = arr.length().coerceAtMost(128)
                    for (i in 0 until scanLimit) {
                        val row = arr.optJSONObject(i) ?: continue
                        val sid = row.optString("sessionId")
                        val kind = parseKind(row.optString("kind")) ?: continue
                        if (sid.isNotBlank() && (sid in out || out.size < 32)) {
                            out[sid] = kind
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                val sid = intent.getStringExtra("sessionId").orEmpty()
                val kind = parseKind(intent.getStringExtra("kind").orEmpty())
                if (sid.isNotBlank() && kind != null) out[sid] = kind
            }
            return out.entries.map { it.key to it.value }
        }

        private fun parseKind(raw: String): SessionNotificationKind? = when (raw) {
            "approval" -> SessionNotificationKind.APPROVAL
            "question" -> SessionNotificationKind.QUESTION
            "completed" -> SessionNotificationKind.COMPLETED
            else -> null
        }
    }
}
