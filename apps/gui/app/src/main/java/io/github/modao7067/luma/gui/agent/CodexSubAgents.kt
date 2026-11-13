package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.SubAgentState
import org.json.JSONObject

internal class CodexSubAgents(private val parentThreadId: () -> String?) {
    private val agents = linkedMapOf<String, SubAgentState>()
    private val verified = mutableSetOf<String>()
    private val rejected = mutableSetOf<String>()
    fun values() = agents.values.toList()
    fun clear() {
        agents.clear()
        verified.clear()
        rejected.clear()
    }
    fun contains(id: String) = id in agents
    fun isVerified(id: String) = id in agents && id in verified && id !in rejected
    fun acceptsRequestsFrom(threadId: String): Boolean = threadId.isNotBlank() && threadId in agents && threadId !in rejected

    fun item(item: JSONObject, turnId: String) {
        val parent = parentThreadId()
        if (item.optString("type") == "collabAgentToolCall") {
            if (parent.isNullOrBlank() || item.optString("senderThreadId") != parent) return
            val states = item.optJSONObject("agentsStates") ?: JSONObject()
            val ids = (item.optJSONArray("receiverThreadIds")?.strings().orEmpty() + states.keys().asSequence().toList()).distinct()
            ids.filter { it.isNotBlank() && it != parent && it !in rejected }.forEach { id ->
                verified.add(id)
                val old = agents[id] ?: SubAgentState(id)
                val state = states.optJSONObject(id)
                val native = state?.stringOrNull("status")
                agents[id] = old.copy(
                    turnId = old.turnId.ifEmpty { turnId }, itemId = old.itemId.ifEmpty { item.optString("id") },
                    task = old.task.ifEmpty { item.stringOrNull("prompt").orEmpty() },
                    status = native?.let(::status) ?: old.status,
                    nativeStatus = native ?: old.nativeStatus,
                    detail = state?.stringOrNull("message") ?: old.detail)
            }
        } else if (item.optString("type") == "subAgentActivity") {
            val id = item.stringOrNull("agentThreadId")?.takeIf { it.isNotBlank() && it != parent } ?: return
            if (id in rejected) return
            val owner = item.stringOrNull("senderThreadId") ?: item.stringOrNull("parentThreadId")
            if (owner != null) {
                if (parent.isNullOrBlank() || owner != parent) {
                    rejected.add(id)
                    verified.remove(id)
                    agents.remove(id)
                    return
                }
                verified.add(id)
            }
            val old = agents[id] ?: SubAgentState(id)
            val native = item.optString("kind")
            agents[id] = old.copy(turnId = old.turnId.ifEmpty { turnId }, itemId = old.itemId.ifEmpty { item.optString("id") },
                name = old.name.ifEmpty { item.optString("agentPath") },
                status = when (native) {
                    "started" -> if (old.status in terminal) old.status else "running"
                    "interrupted" -> "cancelled"
                    "completed" -> if (old.status in terminal) old.status else "unknown"
                    else -> old.status
                })
        }
    }

    fun metadata(thread: JSONObject): Boolean {
        val id = thread.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return false
        val parent = parentThreadId()
        val spawn = thread.optJSONObject("source")?.optJSONObject("subAgent")?.optJSONObject("thread_spawn")
        val parents = listOfNotNull(thread.stringOrNull("parentThreadId"), spawn?.stringOrNull("parent_thread_id"))
        if (parent.isNullOrBlank() || id == parent || parents.isEmpty() || parents.any { it != parent }) {
            if (id != parent && (id in agents || parents.any { it != parent })) {
                rejected.add(id)
            }
            verified.remove(id)
            agents.remove(id)
            return false
        }
        rejected.remove(id)
        verified.add(id)
        val old = agents[id] ?: SubAgentState(id)
        agents[id] = old.copy(name = thread.stringOrNull("agentNickname") ?: spawn?.stringOrNull("agent_nickname") ?: old.name,
            role = thread.stringOrNull("agentRole") ?: spawn?.stringOrNull("agent_role") ?: old.role)
        return true
    }

    fun event(message: JSONObject): Boolean {
        val p = message.optJSONObject("params") ?: return false
        val method = message.optString("method")
        if (method == "thread/started") p.optJSONObject("thread")?.let(::metadata)
        val id = p.stringOrNull("threadId") ?: return false
        val old = agents[id] ?: return false
        when (method) {
            "turn/started" -> agents[id] = old.copy(status = "running", nativeStatus = "inProgress", detail = "")
            "turn/completed" -> p.optJSONObject("turn")?.let { turn ->
                val native = turn.optString("status")
                agents[id] = old.copy(status = status(native), nativeStatus = native,
                    detail = turn.optJSONObject("error")?.stringOrNull("message") ?: old.detail)
            }
            "thread/status/changed" -> when (p.optJSONObject("status")?.optString("type")) {
                "active" -> agents[id] = old.copy(status = "running", nativeStatus = "active")
                "systemError" -> agents[id] = old.copy(status = "failed", nativeStatus = "systemError")
                "idle" -> if (old.status !in terminal) agents[id] = old.copy(status = "unknown", nativeStatus = "idle")
            }
        }
        return true
    }

    companion object {
        private val terminal = setOf("succeeded", "failed", "cancelled", "closed")
        fun status(native: String) = when (native) {
            "pendingInit" -> "pending"; "running", "inProgress", "active" -> "running"
            "completed" -> "succeeded"; "errored", "failed", "systemError" -> "failed"
            "interrupted" -> "cancelled"; "shutdown" -> "closed"; else -> "unknown"
        }
    }
}
