package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.MediaResource
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object MediaProjection {
    fun blocks(value: Any?): List<MediaResource> = when (value) {
        is JSONArray -> value.objects().flatMap(::block).distinctBy { it.source }
        is JSONObject -> block(value)
        else -> emptyList()
    }
    private val rejectedProjectedSchemes = setOf("content:", "android.resource:")
    internal fun isAllowedProjectedSource(source: String): Boolean {
        val lower = source.trim().lowercase(java.util.Locale.ROOT)
        return rejectedProjectedSchemes.none { lower.startsWith(it) }
    }
    private fun block(b: JSONObject): List<MediaResource> {
        val type = b.optString("type")
        val mime = b.stringOrNull("mimeType") ?: b.stringOrNull("mime_type") ?: b.stringOrNull("mime").orEmpty()
        val name = b.stringOrNull("name") ?: b.stringOrNull("filename").orEmpty()
        fun resource(source: String?, defaultMime: String = mime) = source?.takeIf { it.isNotBlank() && isAllowedProjectedSource(it) }?.let {
            listOf(MediaResource(it, name, defaultMime, sizeBytes = if (b.has("sizeBytes") && !b.isNull("sizeBytes")) b.optLong("sizeBytes").takeIf { size -> size >= 0 } else null))
        }.orEmpty()
        fun inline(data: String?, contentMime: String) = resource(data?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("data:")) it else "data:${contentMime.ifBlank { "application/octet-stream" }};base64,$it" }, contentMime)
        return when (type) {
            "content" -> blocks(b.opt("content"))
            "image", "audio", "document" -> {
                val source = b.optJSONObject("source")
                val contentMime = source?.stringOrNull("media_type") ?: mime.ifBlank { if (type == "image") "image/*" else if (type == "audio") "audio/*" else "application/octet-stream" }
                when {
                    source?.optString("type") == "base64" -> inline(source.stringOrNull("data"), contentMime)
                    source?.optString("type") in setOf("url", "uri") -> resource(source?.stringOrNull("url") ?: source?.stringOrNull("uri"), contentMime)
                    b.has("data") -> inline(b.stringOrNull("data"), contentMime)
                    else -> resource(b.stringOrNull("url") ?: b.stringOrNull("uri") ?: b.stringOrNull("path") ?: b.stringOrNull("fileId")?.let { "codex-file:$it" }, contentMime)
                }
            }
            "localImage", "imageView" -> resource(b.stringOrNull("path"), "image/*")
            "inputImage" -> resource(b.stringOrNull("imageUrl"), "image/*")
            "inputAudio" -> resource(b.stringOrNull("audioUrl"), mime.ifBlank { "audio/*" })
            "image_url" -> resource(b.optJSONObject("image_url")?.stringOrNull("url") ?: b.stringOrNull("image_url"), "image/*")
            "resource" -> b.optJSONObject("resource")?.let { embedded ->
                val contentMime = embedded.stringOrNull("mimeType") ?: mime
                val contentName = embedded.stringOrNull("uri")?.substringAfterLast('/').orEmpty()
                val source = when {
                    embedded.has("blob") -> "data:${contentMime.ifBlank { "application/octet-stream" }};base64," + embedded.optString("blob")
                    embedded.has("text") -> "data:${contentMime.ifBlank { "text/plain" }};base64," + Base64.getEncoder().encodeToString(embedded.optString("text").toByteArray())
                    else -> embedded.stringOrNull("uri")?.takeIf { isAllowedProjectedSource(it) }
                }
                source?.let { listOf(MediaResource(it, name.ifBlank { contentName }, contentMime)) }.orEmpty()
            }.orEmpty()
            "resource_link", "file", "attachment" -> resource(b.stringOrNull("uri") ?: b.optJSONObject("source")?.stringOrNull("uri") ?: b.stringOrNull("url") ?: b.stringOrNull("path"))
            else -> emptyList()
        }
    }
    fun codex(item: JSONObject): List<MediaResource> = when (item.optString("type")) {
        "userMessage" -> blocks(item.opt("content"))
        "imageView" -> blocks(item)
        "dynamicToolCall" -> blocks(item.opt("contentItems"))
        "mcpToolCall" -> item.optJSONObject("result")?.optJSONArray("content")?.objects().orEmpty().flatMap { block ->
            val resources = blocks(block)
            val linked = block.optString("type") == "resource_link" || block.optString("type") == "resource" && block.optJSONObject("resource")?.let { !it.has("blob") && !it.has("text") } == true
            if (!linked || item.optString("server").isBlank()) resources else resources.map { resource ->
                resource.copy(name = resource.name.ifBlank { runCatching { java.net.URLDecoder.decode(resource.source.substringBefore('?').substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrDefault("附件") }, source = mcpReference(obj("server" to item.optString("server"), "originCallId" to item.optString("id"), "uri" to resource.source)))
            }
        }
        "imageGeneration" -> {
            val path = item.stringOrNull("savedPath")?.takeIf { it.isNotBlank() }
            val result = item.stringOrNull("result")?.takeIf { it.isNotBlank() }
            val id = item.stringOrNull("fileId")?.takeIf { it.isNotBlank() }
            val source = (path?.takeIf { isAllowedProjectedSource(it) }) ?: result?.let { if (it.startsWith("data:")) it else "data:image/png;base64,$it" } ?: id?.let { "codex-file:$it" }
            source?.let { listOf(MediaResource(it, mimeType = "image/*")) }.orEmpty()
        }
        else -> emptyList()
    }
    private fun mcpReference(params: JSONObject) = "codex-mcp:" + Base64.getUrlEncoder().withoutPadding().encodeToString(params.toString().toByteArray())
    fun mcpParams(source: String): JSONObject? = if (!source.startsWith("codex-mcp:")) null else try {
        JSONObject(String(Base64.getUrlDecoder().decode(source.removePrefix("codex-mcp:")), Charsets.UTF_8))
    } catch (_: Exception) { throw java.io.IOException("MCP 资源引用损坏") }
    fun mcpThread(resource: MediaResource, threadId: String?): MediaResource {
        if (threadId == null) return resource
        val params = mcpParams(resource.source) ?: return resource
        if (!params.has("threadId")) params.put("threadId", threadId)
        return resource.copy(source = mcpReference(params))
    }
    fun display(value: Any?): String {
        fun clean(v: Any?): Any? = when (v) {
            is JSONObject -> JSONObject().apply { v.keys().forEach { key ->
                val x = v.opt(key)
                put(key, if (x is String && (key in setOf("data", "blob", "result", "imageUrl", "audioUrl") && x.length > 256 || x.startsWith("data:") && x.length > 256)) "[附件数据]" else clean(x))
            } }
            is JSONArray -> JSONArray().apply { (0 until v.length()).forEach { put(clean(v.opt(it))) } }
            else -> v
        }
        return clean(value)?.let { if (it is JSONObject) it.toString(2) else it.toString() }.orEmpty()
    }
}

fun ConversationItem.withMediaContext(sessionId: String, cwd: String, nativeThreadId: String? = null): ConversationItem {
    if (media.isEmpty() && images.isEmpty()) return this
    val combined = if (images.isEmpty()) {
        media
    } else {
        val extra = images.filter { MediaProjection.isAllowedProjectedSource(it) }.filterNot { image -> media.any { it.source == image } }.map { MediaResource(it, mimeType = "image/*") }
        if (extra.isEmpty()) media else media + extra
    }
    val mapped = combined.map {
        val scoped = if (it.sessionId != null && it.cwd != null) it else it.copy(sessionId = it.sessionId ?: sessionId, cwd = it.cwd ?: cwd)
        MediaProjection.mcpThread(scoped, nativeThreadId)
    }.distinctBy { it.source }
    return if (mapped == media) this else copy(media = mapped)
}
