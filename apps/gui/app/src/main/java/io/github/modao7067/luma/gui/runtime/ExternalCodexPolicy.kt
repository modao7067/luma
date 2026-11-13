package io.github.modao7067.luma.gui.runtime

object ExternalCodexPolicy {
    fun validateSocket(socket: String) {
        require(socket.startsWith('/') && socket.toByteArray(Charsets.UTF_8).size <= 107 && socket.none { it.isISOControl() }) {
            "externalSocket 必须是 Ubuntu 内的绝对 Unix socket 路径（最多 107 字节）"
        }
    }
    fun validateAttachment(cli: String, environment: String, threadId: String?, socket: String?) {
        if (socket == null) return
        require(cli == "Codex" && environment == "root" && !threadId.isNullOrBlank()) { "外部 socket 仅用于 Root Codex 已有会话" }
        validateSocket(socket)
    }
    fun remoteCommand(socket: String, threadId: String, cwd: String): String {
        validateSocket(socket)
        require(threadId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "原生会话标识无效" }
        require(cwd.startsWith('/') && '\u0000' !in cwd) { "请输入绝对路径" }
        return "cd ${quote(cwd)} && exec codex resume ${quote(threadId)} --remote ${quote("unix://$socket")}"
    }
    fun proxyCommand(socket: String): List<String> {
        validateSocket(socket)
        return listOf("/system/bin/su", "-c", "exec " +
            listOf("/data/local/linux/bin/ubuntu", "codex", "app-server", "proxy", "--sock", socket).joinToString(" ", transform = ::quote))
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
