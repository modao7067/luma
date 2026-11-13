package io.github.modao7067.luma.gui.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

internal object MediaByteReader {
    fun read(reference: String, cwd: String?, destination: File, fallbackMime: String,
             imported: () -> File?, guest: (String, File, Long) -> Unit, allowHttp: (String) -> Boolean = { true }, limit: Long = MediaSourcePolicy.MAX_BYTES,
             managedRootProtected: (() -> String?)? = null): String {
        val source = MediaSourcePolicy.relativeHttp(reference, cwd)
        var mime = fallbackMime
        when {
            source.startsWith("data:") -> {
                val comma = source.indexOf(',')
                if (comma < 0) throw IOException("内嵌资源格式不完整")
                val descriptor = source.substring(5, comma)
                mime = descriptor.substringBefore(';').ifBlank { mime }
                val base64 = descriptor.split(';').any { it.equals("base64", true) }
                val maximum = if (base64) limit * 4 / 3 + 4096 else limit * 3
                if (source.length - comma - 1 > maximum) throw IOException(limitMessage(limit))
                val raw = DataInput(source, comma + 1, base64)
                if (base64) {
                    try {
                        Base64.getDecoder().wrap(raw).use { copy(it, destination, limit) }
                        if (raw.read() != -1) throw IOException("内嵌资源编码损坏")
                    } catch (_: IllegalArgumentException) { throw IOException("内嵌资源编码损坏") }
                } else raw.use { copy(it, destination, limit) }
            }
            source.startsWith("https:") || source.startsWith("http:") -> {
                var url = URL(URI(source.replace(" ", "%20")).toASCIIString())
                var redirects = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (url.protocol !in setOf("http", "https") || url.userInfo != null) throw IOException("不支持此资源地址")
                    if (url.protocol == "http" && !allowHttp(url.host)) throw IOException("此 HTTP 明文地址被应用安全策略阻止，请使用 HTTPS 来源")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = false; useCaches = false
                    }
                    try {
                        val status = connection.responseCode
                        if (status in setOf(301,302,303,307,308)) {
                            if (++redirects > 5) throw IOException("资源重定向过多")
                            url = URL(url, connection.getHeaderField("Location") ?: throw IOException("资源重定向无效"))
                            continue
                        }
                        if (status !in 200..299) throw IOException("资源暂不可读（HTTP $status）")
                        if (connection.contentLengthLong > limit) throw IOException(limitMessage(limit))
                        connection.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { mime = it }
                        connection.inputStream.use { copy(it, destination, limit) }
                        if (connection.contentLengthLong >= 0 && destination.length() != connection.contentLengthLong) throw IOException("资源响应不完整")
                        break
                    } finally { connection.disconnect() }
                }
            }
            source.startsWith("codex-file:") -> throw IOException("此 Codex 附件仅提供文件 ID，原生接口未提供可读取地址")
            source.startsWith("luma-error:") -> throw IOException(source.removePrefix("luma-error:"))
            else -> {
                val local = imported()
                if (local != null) local.inputStream().use { copy(it, destination, limit) }
                else {
                    val protectedPath = managedRootProtected?.invoke()
                    if (protectedPath != null) guest(protectedPath, destination, limit)
                    else guest(MediaSourcePolicy.guestPath(source, cwd ?: throw IOException("资源缺少所属工作目录")), destination, limit)
                }
            }
        }
        return mime
    }
    fun copy(input: InputStream, file: File, limit: Long = MediaSourcePolicy.MAX_BYTES) {
        file.outputStream().use { output ->
            val buffer = ByteArray(65536); var bytes = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer); if (count < 0) break
                bytes += count; if (bytes > limit) throw IOException(limitMessage(limit))
                output.write(buffer, 0, count)
            }
        }
    }
    private fun limitMessage(limit: Long) = "附件超过 ${limit / (1024 * 1024)} MiB"
    private class DataInput(private val source: String, private var index: Int, private val base64: Boolean) : InputStream() {
        private var encoded = byteArrayOf(); private var next = 0
        override fun read(): Int {
            if (next < encoded.size) return encoded[next++].toInt() and 255
            if (base64) {
                while (index < source.length && source[index].isWhitespace()) index++
                if (index == source.length) return -1
                val char = source[index++]
                if (char.code > 127) throw IOException("内嵌资源编码损坏")
                return char.code
            }
            if (index == source.length) return -1
            if (source[index] == '%') {
                if (index + 2 >= source.length) throw IOException("内嵌资源编码损坏")
                val value = source.substring(index + 1, index + 3).toIntOrNull(16) ?: throw IOException("内嵌资源编码损坏")
                index += 3; return value
            }
            val size = Character.charCount(source.codePointAt(index))
            encoded = source.substring(index, index + size).toByteArray(Charsets.UTF_8)
            index += size; next = 1
            return encoded[0].toInt() and 255
        }
    }
}
