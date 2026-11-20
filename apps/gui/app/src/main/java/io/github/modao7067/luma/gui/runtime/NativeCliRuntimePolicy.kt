package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr
import org.json.JSONObject

internal object NativeCliRuntimePolicy {
    const val revision = "cli-bridge-v85"
    private const val minimumGrokCarrier = 18
    val restartWarning: String
        get() = tr(
            "此操作会停止当前 Luma CLI 桥服务，可能中断该服务上所有正在进行的任务。会话记录和草稿保留。旧版服务无法确认全部任务是否已结束。",
            "This stops the current Luma CLI bridge service and may interrupt active tasks on it. Session history and drafts are preserved. Older services cannot verify whether all tasks have finished.",
        )
    fun requireRestartConsent(cli: String, confirmed: Boolean) {
        require(confirmed) { tr("必须明确确认后才能停止 CLI 桥服务", "Explicit confirmation is required before stopping the CLI bridge service") }
        require(cli in io.github.modao7067.luma.gui.session.CliCatalog.bridged) { tr("此更新入口仅用于受托管的 CLI 桥服务", "This update entry is only for managed CLI bridge services") }
    }
    class CarrierUpdateRequired : IllegalStateException(tr("需更新CLI服务；当前任务结束后重启", "CLI service update required; restart after the current task finishes"))
    val isolatedAcpClis = setOf("Qoder CLI", "Grok Build")
    fun task(cli: String): String = when (cli) {
        "CodeBuddy Code" -> "luma-codebuddy-gui-v1"
        in isolatedAcpClis -> "luma-acp-cli-gui"
        "Antigravity" -> "luma-antigravity-gui"
        "Kimi Code", "Claude Code" -> "luma-native-cli-gui-v3"
        else -> "luma-native-cli-gui"
    }
    fun requiresHealthValidation(cli: String): Boolean = cli in isolatedAcpClis || cli == "CodeBuddy Code"

    private fun carrierVersion(health: JSONObject): Int? =
        Regex("cli-bridge-v([0-9]+)").matchEntire(health.optString("carrierRevision"))
            ?.groupValues?.get(1)?.toIntOrNull()

    fun hasCarrierUpdate(health: JSONObject): Boolean {
        val running = carrierVersion(health) ?: return false
        val bundled = Regex("cli-bridge-v([0-9]+)").matchEntire(revision)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return running < bundled
    }

    fun acceptHealth(cli: String, health: JSONObject) {
        check(health.optInt("protocol") == 1) { tr("CLI 服务协议不兼容", "Incompatible CLI service protocol") }
        if (cli == "Grok Build" && (carrierVersion(health) ?: -1) < minimumGrokCarrier) throw CarrierUpdateRequired()
        val clis = health.optJSONArray("clis")
        check(clis != null && (0 until clis.length()).any { clis.optString(it) == cli }) {
            tr("托管 CLI 服务未提供 $cli；请在任务结束后升级服务", "Managed CLI service does not provide $cli; upgrade the service after the current task finishes")
        }
    }
}
