package io.github.modao7067.luma.gui.session

object CliCatalog {
    val ids = listOf("Codex", "OpenCode", "pi", "DeepSeek Harness", "Kimi Code", "Claude Code", "Antigravity", "Qoder CLI", "Grok Build", "CodeBuddy Code")
    val sortedIds: List<String> = ids.sortedWith(compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it })
    val temporarilyDisabled: Set<String> = emptySet()
    val visibleIds: List<String> = ids.filterNot { it in temporarilyDisabled }
    val visibleSortedIds: List<String> = sortedIds.filterNot { it in temporarilyDisabled }
    val bridged = ids.filterNot { it == "Codex" || it == "OpenCode" }.toSet()
    val nativeProtocols = mapOf("CodeBuddy Code" to listOf("codebuddy-http-acp"), "Antigravity" to listOf("antigravity-agy-acp", "antigravity-cli-stream-json", "antigravity-acp"),
        "Kimi Code" to listOf("kimi-web-v1", "kimi-acp"),
        "pi" to listOf("pi-rpc", "pi-native-sdk-v1"),
        "Grok Build" to listOf("grok-acp", "grok-private-leader-v1"))
    fun displayName(cli: String): String = when (cli) {
        "pi" -> "Pi"
        else -> cli
    }
}
data class CliCapabilities(
    val queue: Boolean = true, val steer: Boolean = true, val stop: Boolean = true,
    val images: Boolean = true, val skills: Boolean = true, val maxSkills: Int = Int.MAX_VALUE,
    val rename: Boolean = true, val compact: Boolean = true, val diff: Boolean = true,
)
