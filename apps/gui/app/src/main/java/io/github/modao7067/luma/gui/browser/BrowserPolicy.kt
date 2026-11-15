package io.github.modao7067.luma.gui.browser

import io.github.modao7067.luma.gui.i18n.tr

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object BrowserPolicy {
    data class SnapshotDiff(
        val lines: List<String>,
        val added: Int,
        val removed: Int,
        val unchanged: Int,
    )

    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")
    private val blockedDownloadExtensions = setOf(
        "apk", "aab", "dex", "jar", "exe", "msi", "dmg", "pkg", "deb", "rpm",
        "bat", "cmd", "ps1", "vbs", "js", "mjs", "cjs", "sh", "bash", "zsh",
        "so", "dll", "dylib", "html", "htm", "xhtml", "svg", "svgz", "xml",
        "xsl", "wasm", "crx", "xpi", "scr", "com",
    )
    private val blockedDownloadMimes = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-dosexec",
        "application/x-msi",
        "application/x-sh",
        "application/x-shellscript",
        "application/wasm",
        "image/svg+xml",
        "text/html",
        "application/xhtml+xml",
        "text/javascript",
        "application/javascript",
    )

    fun addressUrl(input: String): String {
        val text = input.trim()
        require(text.isNotEmpty()) { tr("请输入网址", "Enter a URL") }
        val explicitScheme = text.contains("://")
        val candidate = if (explicitScheme) text else "https://${text.removePrefix("//")}"
        val parsed = requireNotNull(candidate.toHttpUrlOrNull()) { tr("网址格式不正确", "Invalid URL format") }
        val url = if (!explicitScheme && parsed.host in loopbackHosts) {
            "http://${text.removePrefix("//")}".toHttpUrl()
        } else parsed
        return url.toString().also {
            require(allowedUrl(it)) { tr("仅支持 HTTPS 或本机 HTTP 页面", "Only HTTPS or localhost HTTP pages are supported") }
        }
    }

    fun allowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.userInfo == null && !uri.host.isNullOrBlank() && (uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")))
    }.getOrDefault(false)
    fun requireOwner(owner: String, session: String) { require(owner == session) { tr("标签页不属于当前会话", "Tab does not belong to current session") } }

    fun sanitizeTelemetryUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed == null) {
            val scheme = trimmed.substringBefore(':', "").lowercase()
            return if (scheme in setOf("about", "blob", "data", "javascript")) "$scheme:..." else trimmed.substringBefore('?').substringBefore('#').take(200)
        }
        val builder = parsed.newBuilder().username("").password("").fragment(null)
        val names = parsed.queryParameterNames
        if (names.isNotEmpty()) {
            builder.query(null)
            for (name in names) {
                val values = parsed.queryParameterValues(name)
                if (values.all { it.isNullOrEmpty() }) builder.addQueryParameter(name, "")
                else builder.addQueryParameter(name, "REDACTED")
            }
        }
        return builder.build().toString().take(300)
    }

    fun isBlockedAgentDownload(fileName: String, mimeType: String?): Boolean {
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (normalizedMime in blockedDownloadMimes) return true
        val cleaned = fileName.trim().trimEnd('.', ' ').lowercase()
        val ext = cleaned.substringAfterLast('.', "")
        if (ext in blockedDownloadExtensions) return true
        val stem = cleaned.removeSuffix(".$ext")
        val secondExt = stem.substringAfterLast('.', "")
        return ext in setOf("gz", "xz", "bz2", "zst") && secondExt in blockedDownloadExtensions
    }

    fun computeSnapshotDiff(previous: List<String>, current: List<String>): SnapshotDiff {
        var prefix = 0
        val minLen = minOf(previous.size, current.size)
        while (prefix < minLen && previous[prefix] == current[prefix]) prefix++
        var prevEnd = previous.size
        var currEnd = current.size
        while (prevEnd > prefix && currEnd > prefix && previous[prevEnd - 1] == current[currEnd - 1]) {
            prevEnd--
            currEnd--
        }
        val unchangedOuter = prefix + (previous.size - prevEnd)
        val a = previous.subList(prefix, prevEnd)
        val b = current.subList(prefix, currEnd)
        if (a.isEmpty() && b.isEmpty()) {
            return SnapshotDiff(emptyList(), 0, 0, unchangedOuter)
        }
        if (a.size.toLong() * b.size.toLong() > 160_000L) {
            val out = ArrayList<String>(a.size + b.size)
            a.forEach { out.add("- $it") }
            b.forEach { out.add("+ $it") }
            return SnapshotDiff(out.take(400), b.size, a.size, unchangedOuter)
        }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val diffLines = ArrayList<String>()
        var added = 0
        var removed = 0
        var unchangedInner = 0
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                unchangedInner++
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                if (diffLines.size < 400) diffLines.add("- ${a[i]}")
                removed++
                i++
            } else {
                if (diffLines.size < 400) diffLines.add("+ ${b[j]}")
                added++
                j++
            }
        }
        while (i < a.size) {
            if (diffLines.size < 400) diffLines.add("- ${a[i]}")
            removed++
            i++
        }
        while (j < b.size) {
            if (diffLines.size < 400) diffLines.add("+ ${b[j]}")
            added++
            j++
        }
        return SnapshotDiff(diffLines, added, removed, unchangedOuter + unchangedInner)
    }
}
