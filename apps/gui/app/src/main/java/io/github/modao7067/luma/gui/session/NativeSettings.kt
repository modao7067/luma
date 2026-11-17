package io.github.modao7067.luma.gui.session

import org.json.JSONObject

internal object NativeSettings {
    fun matches(expected: JSONObject?, actual: JSONObject): Boolean {
        if (expected == null || expected.keys().asSequence().none { it != "threadId" }) return false
        return expected.keys().asSequence().all { key ->
            when (key) {
                "threadId" -> true
                "permissions" -> actual.optJSONObject("activePermissionProfile")?.optString("id") == expected.getString(key)
                "serviceTier" -> actual.optString(key) == if (expected.isNull(key)) "default" else expected.getString(key)
                "collaborationMode" -> {
                    val expectedMode = expected.optJSONObject("collaborationMode")?.optString("mode").orEmpty()
                    expectedMode.isNotEmpty() && actual.optJSONObject("collaborationMode")?.optString("mode") == expectedMode
                }
                else -> actual.opt(key) == expected.opt(key)
            }
        }
    }
}
