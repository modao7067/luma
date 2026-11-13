package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.objects
import org.json.JSONArray

internal object QueueEdits {
    fun replaceText(input: JSONArray, text: String, cwd: String): JSONArray {
        val result = CodexProjection.inputs(text, emptyList(), cwd)
        val oldText = input.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        val parsedPaths = CodexProjection.inputs(oldText, emptyList(), cwd).objects().filter { it.optString("type") == "mention" }.map { it.optString("path") }.toSet()
        val newPaths = result.objects().filter { it.optString("type") == "mention" }.map { it.optString("path") }.toSet()
        input.objects().filter {
            it.optString("type") != "text" && (it.optString("type") != "mention" || it.optString("path") !in parsedPaths + newPaths)
        }.forEach { result.put(it) }
        return result
    }
}
