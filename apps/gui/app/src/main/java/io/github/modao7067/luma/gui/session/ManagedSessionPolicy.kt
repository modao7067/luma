package io.github.modao7067.luma.gui.session

internal object ManagedSessionPolicy {
    class ProtocolUpdateRequired(message: String) : IllegalStateException(message)

    fun acceptNativeProtocol(record: SessionRecord, health: org.json.JSONObject) {
        val protocol = record.nativeProtocol ?: return
        val supported = health.optJSONObject("nativeProtocols")?.optJSONArray(record.cli)
        if (supported == null || (0 until supported.length()).none { supported.optString(it) == protocol }) {
            throw ProtocolUpdateRequired("当前托管服务未声明 $protocol，已停止接入以免混用协议；请在现有任务结束后更新 CLI 桥服务再接入")
        }
    }
    fun newRecord(id: String, cli: String, title: String, environment: String, cwd: String, model: String, threadId: String?, nativeProtocol: String? = null, externalSocket: String? = null): SessionRecord {
        io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy.validateAttachment(cli, environment, threadId, externalSocket)
        val nativeTitle = title.isBlank()
        if (nativeProtocol != null) require(nativeProtocol in CliCatalog.nativeProtocols[cli].orEmpty()) { "此 CLI 未提供该原生协议" }
        val protocol = nativeProtocol ?: "antigravity-agy-acp".takeIf { cli == "Antigravity" && threadId == null }
        return SessionRecord(id, title.trim().ifEmpty { SessionTitlePolicy.unnamed }, environment, cwd.trim(), model,
            threadId = threadId, needsName = externalSocket == null && (threadId == null || !nativeTitle),
            requireLoaded = threadId != null, cli = cli, useNativeTitle = nativeTitle,
            manualTitle = title.trim().takeIf { it.isNotEmpty() }, nativeProtocol = protocol, externalSocket = externalSocket)
    }

    fun acceptLoaded(record: SessionRecord, loaded: Set<String>) {
        check(record.threadId != null && record.threadId in loaded) {
            if (record.externalSocket != null) "该会话不在指定外部 AppServer 的已加载列表中；不会离线恢复或另起执行进程，请由原客户端加载后重连。"
            else "该 ID 不在本运行环境的 Luma 托管服务中。其它 AppServer 请显式指定 externalSocket；不会接管普通 CLI 或离线恢复会话。"
        }
        record.requireLoaded = false
    }
    fun validateExternalRepair(record: SessionRecord, cli: String, nativeId: String, socket: String, connected: Boolean, loading: Boolean) {
        io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy.validateAttachment(cli, record.environment, nativeId, socket)
        require(record.cli == cli && record.threadId == nativeId) { "attach.id 的 CLI 或原生 ID 与目标不一致" }
        if (record.externalSocket == socket) return
        check(record.externalSocket == null && record.requireLoaded && !connected && !loading &&
            record.submissions.isEmpty() && record.submissionId == null && record.queueTransfer == null && !record.creationUnknown) {
            "仅能显式修复从未成功绑定、未连接且没有待发消息的失败引用；已绑定会话不能改服务"
        }
    }

    fun nativeTitle(record: SessionRecord, name: String?) {
        SessionTitlePolicy.nativeTitle(record, name)
    }
}
