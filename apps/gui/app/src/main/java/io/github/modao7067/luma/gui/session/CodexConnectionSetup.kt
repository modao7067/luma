package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.agent.stringOrNull
import io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy
import org.json.JSONArray
import org.json.JSONObject

internal object CodexConnectionSetup {
    suspend fun prepare(
        record: SessionRecord,
        skillsPath: String,
        browserConfig: () -> String,
        request: suspend (String, JSONObject) -> JSONObject,
        persist: () -> Unit,
        recoverExternal: Boolean = false,
    ): JSONObject? {
        ExternalCodexPolicy.validateAttachment(record.cli, record.environment, record.threadId, record.externalSocket)
        if ((record.requireLoaded || record.externalSocket != null) && !(recoverExternal && record.externalSocket != null)) {
            ManagedSessionPolicy.acceptLoaded(record, loadedThreads(request))
            persist()
        }
        if (record.externalSocket != null) return null
        request("skills/extraRoots/set", obj("extraRoots" to JSONArray(listOf(skillsPath))))
        val effective = request("config/read", obj("cwd" to record.cwd, "includeLayers" to false)).optJSONObject("config")
        val vars = JSONObject(effective?.optJSONObject("shell_environment_policy")?.optJSONObject("set")?.toString() ?: "{}")
        vars.put("LUMA_BROWSER_CONFIG", browserConfig())
        return obj("shell_environment_policy.set" to vars)
    }

    fun resumeParams(record: SessionRecord, managedConfig: JSONObject?): JSONObject =
        obj("threadId" to checkNotNull(record.threadId), "excludeTurns" to true).apply {
            if (record.externalSocket == null && managedConfig != null) put("config", managedConfig)
        }

    suspend fun loadedThreads(request: suspend (String, JSONObject) -> JSONObject): Set<String> {
        val loaded = mutableSetOf<String>(); var cursor: String? = null
        do {
            val page = request("thread/loaded/list", obj("limit" to 100, "cursor" to cursor))
            page.optJSONArray("data")?.let { data -> (0 until data.length()).forEach { loaded += data.getString(it) } }
            cursor = page.stringOrNull("nextCursor")
        } while (cursor != null)
        return loaded
    }
}
