package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

object ExternalCodexPolicy {
    fun validateSocket(socket: String) {
        require(socket.startsWith('/') && socket.toByteArray(Charsets.UTF_8).size <= 107 && socket.none { it.isISOControl() }) {
            tr("externalSocket 必须是 Ubuntu 内的绝对 Unix socket 路径（最多 107 字节）", "externalSocket must be an absolute Unix socket path inside Ubuntu (up to 107 bytes)")
        }
    }
    fun validateAttachment(cli: String, environment: String, threadId: String?, socket: String?) {
        if (socket == null) return
        require(cli == "Codex" && environment == "root" && !threadId.isNullOrBlank()) { tr("外部 socket 仅用于 Root Codex 已有会话", "External socket is only supported for existing Root Codex sessions") }
        validateSocket(socket)
    }
    fun remoteCommand(socket: String, threadId: String, cwd: String): String {
        validateSocket(socket)
        require(threadId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { tr("原生会话标识无效", "Invalid native session ID") }
        require(cwd.startsWith('/') && '\u0000' !in cwd) { tr("请输入绝对路径", "Enter an absolute path") }
        return "cd ${quote(cwd)} && exec codex resume ${quote(threadId)} --remote ${quote("unix://$socket")}"
    }
    fun proxyCommand(socket: String): List<String> {
        validateSocket(socket)
        return listOf("/system/bin/su", "-c", "exec " +
            listOf("/data/local/linux/bin/ubuntu", "codex", "app-server", "proxy", "--sock", socket).joinToString(" ", transform = ::quote))
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
