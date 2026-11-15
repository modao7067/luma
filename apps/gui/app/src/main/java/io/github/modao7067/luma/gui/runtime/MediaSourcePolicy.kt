package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale

internal object MediaSourcePolicy {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val CACHE_BYTES = 192L * 1024 * 1024
    private val forbiddenPseudoRoots = setOf("/proc", "/sys", "/dev")
    internal fun isForbiddenGuestPath(normalizedPath: String): Boolean =
        forbiddenPseudoRoots.any { root -> normalizedPath == root || normalizedPath.startsWith("$root/") }

    fun guestPath(source: String, cwd: String): String {
        require(source.isNotBlank() && '\u0000' !in source && '\u0000' !in cwd) { tr("无效文件引用", "Invalid file reference") }
        val path = when {
            source.startsWith("sandbox:") -> URI(source.replace(" ", "%20")).path ?: throw IOException(tr("无效 sandbox 文件地址", "Invalid sandbox file URI"))
            source.startsWith("file:") -> {
                val uri = URI(source.replace(" ", "%20"))
                require(uri.authority.isNullOrEmpty() || uri.authority == "localhost") { tr("不支持远程 file 地址", "Remote file URIs are not supported") }
                uri.path ?: throw IOException(tr("无效文件地址", "Invalid file URI"))
            }
            else -> source
        }
        require(!path.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"))) { tr("此资源协议无法读取", "Unsupported resource URI scheme") }
        require(cwd.startsWith('/')) { tr("资源缺少所属工作目录", "Resource is missing working directory") }
        val normalized = File(if (path.startsWith('/')) path else cwd.trimEnd('/') + "/" + path).toPath().normalize().toString()
        if (isForbiddenGuestPath(normalized)) throw IOException(tr("不允许读取系统伪文件系统路径", "Reading system pseudo-filesystem paths is not allowed"))
        return normalized
    }
    fun documentDirectory(source: String, cwd: String): String {
        val actual = relativeHttp(source, cwd)
        return when {
            actual.startsWith("http:") || actual.startsWith("https:") -> URI(actual.replace(" ", "%20")).resolve(".").toASCIIString()
            actual.startsWith("data:") || actual.startsWith("content:") || actual.startsWith("codex-") -> cwd
            else -> File(guestPath(actual, cwd)).parent ?: "/"
        }
    }
    fun relativeHttp(source: String, cwd: String?): String = if (cwd != null && (cwd.startsWith("http:") || cwd.startsWith("https:")) &&
        !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(source)) URI(cwd).resolve(source.replace(" ", "%20")).toASCIIString() else source
    fun name(source: String): String = if (source.startsWith("data:")) tr("附件", "attachment") else runCatching {
        val path = if (source.contains("://") || source.startsWith("file:") || source.startsWith("sandbox:")) URI(source.replace(" ", "%20")).path else source
        path.substringAfterLast('/').ifBlank { tr("附件", "attachment") }
    }.getOrDefault(tr("附件", "attachment"))
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "bmp" -> "image/bmp"; "avif" -> "image/avif"; "heic", "heif" -> "image/heif"; "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"; "txt", "log", "csv" -> "text/plain"; "md", "markdown" -> "text/markdown"
        "json" -> "application/json"; "xml" -> "application/xml"; "html", "htm" -> "text/html"
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "rs", "go", "sh", "yaml", "yml", "toml", "ini", "conf", "css", "sql", "patch", "diff" -> "text/plain"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "zip" -> "application/zip"; "gz" -> "application/gzip"
        "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> if (isTextName(name)) "text/plain" else "application/octet-stream"
    }
    private val textExtensions = setOf(
        "txt", "text", "log", "out", "csv", "tsv", "md", "markdown", "mdx", "rst", "adoc", "asciidoc", "org", "tex", "bib",
        "kt", "kts", "java", "groovy", "gradle", "scala", "sc", "clj", "cljs", "edn",
        "py", "pyi", "pyw", "rb", "pl", "pm", "php", "lua", "r", "jl", "dart", "swift", "m", "mm",
        "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx", "vue", "svelte", "astro", "coffee",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "fs", "fsx", "go", "rs", "zig", "nim", "v", "d", "hs", "ml", "mli", "ex", "exs", "erl", "elm",
        "sh", "bash", "zsh", "fish", "ksh", "ps1", "psm1", "bat", "cmd", "awk", "sed",
        "json", "jsonc", "json5", "jsonl", "ndjson", "xml", "xsd", "xsl", "plist", "html", "htm", "xhtml", "css", "scss", "sass", "less",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "prop", "env", "lock", "pro", "cmake", "mk", "make", "ninja", "bazel", "bzl",
        "sql", "graphql", "gql", "proto", "thrift", "avsc", "patch", "diff", "srt", "vtt", "service", "desktop", "rules",
        "gitignore", "gitattributes", "gitmodules", "editorconfig", "dockerignore", "npmrc", "nvmrc", "bashrc", "zshrc", "profile", "vimrc",
    )
    private val textFileNames = setOf(
        "makefile", "gnumakefile", "dockerfile", "containerfile", "jenkinsfile", "vagrantfile", "gemfile", "rakefile", "podfile", "procfile",
        "brewfile", "justfile", "cmakelists.txt", "license", "licence", "copying", "notice", "authors", "contributors", "readme", "changelog",
        "todo", "gradlew", "codeowners", "build", "workspace", "manifest",
    )
    fun isTextName(name: String): Boolean {
        val file = name.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase(Locale.ROOT)
        if (file.isEmpty()) return false
        if (file in textFileNames) return true
        return file.contains('.') && file.substringAfterLast('.') in textExtensions
    }
    fun isText(mime: String): Boolean {
        val m = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (m.startsWith("image/")) return false
        return m.startsWith("text/") || m.endsWith("+json") || m.endsWith("+xml") || m in setOf(
            "application/json", "application/xml", "application/javascript", "application/ecmascript", "application/x-javascript",
            "application/x-yaml", "application/yaml", "application/toml", "application/sql", "application/x-sh", "application/x-shellscript",
            "application/x-csh", "application/x-perl", "application/x-python", "application/x-ruby", "application/x-httpd-php",
            "application/x-tex", "application/x-latex", "application/x-ndjson", "application/graphql", "application/x-subrip",
        )
    }
    fun sniff(bytes: ByteArray, fallback: String): String {
        fun begins(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        fun matchesAt(offset: Int, ascii: String): Boolean =
            offset >= 0 && bytes.size >= offset + ascii.length &&
                ascii.indices.all { (bytes[offset + it].toInt() and 255) == ascii[it].code }
        fun brandAt(offset: Int): String = String(bytes, offset, 4, Charsets.ISO_8859_1)

        if (begins(137, 80, 78, 71, 13, 10, 26, 10)) return "image/png"
        if (begins(255, 216, 255)) return "image/jpeg"
        if (matchesAt(0, "GIF87a") || matchesAt(0, "GIF89a")) return "image/gif"
        if (bytes.size >= 12 && matchesAt(0, "RIFF") && matchesAt(8, "WEBP")) return "image/webp"
        if (bytes.size >= 12 && matchesAt(0, "RIFF") && matchesAt(8, "WAVE")) return "audio/wav"
        if (matchesAt(0, "ID3")) return "audio/mpeg"
        if (matchesAt(0, "fLaC")) return "audio/flac"
        if (matchesAt(0, "OggS")) return "audio/ogg"
        if (matchesAt(0, "BM")) return "image/bmp"
        if (bytes.size >= 12 && matchesAt(4, "ftyp")) {
            val boxSize = (((bytes[0].toInt() and 255) shl 24) or
                ((bytes[1].toInt() and 255) shl 16) or
                ((bytes[2].toInt() and 255) shl 8) or
                (bytes[3].toInt() and 255)).let { if (it in 12..bytes.size) it else minOf(bytes.size, 128) }
            val brands = buildList {
                add(brandAt(8))
                var offset = 16
                while (offset + 4 <= boxSize) {
                    add(brandAt(offset))
                    offset += 4
                }
            }
            if (brands.any { it == "avif" || it == "avis" }) return "image/avif"
            if (brands.any { it in setOf("heic", "heix", "hevc", "hevx", "mif1") }) return "image/heif"
        }
        if (matchesAt(0, "%PDF-")) return "application/pdf"
        val head = bytes.take(4096).toByteArray().toString(Charsets.UTF_8)
        if (Regex("(?is)^\\s*(?:<\\?xml[^>]*>\\s*)?(?:<!--.*?-->\\s*)*<svg(?:\\s|>)").containsMatchIn(head)) return "image/svg+xml"
        return fallback.takeUnless { it.isBlank() || it == "image/*" } ?: "application/octet-stream"
    }
}
