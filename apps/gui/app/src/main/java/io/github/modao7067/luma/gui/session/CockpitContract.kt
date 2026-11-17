package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

data class SessionSummary(
    val id: String, val title: String, val environment: String, val cwd: String,
    val model: String = "", val status: String = tr("未连接", "Disconnected"), val threadId: String? = null, val cli: String = "Codex", val modelLabel: String = "",
    val lastActiveAt: Long = 0L, val hasUnreadDot: Boolean = false,
)
data class ModelOption(val id: String, val label: String, val efforts: List<String> = emptyList(), val supportsImages: Boolean = true, val speeds: List<RequestChoice> = emptyList(), val defaultEffort: String = "")
data class ConversationItem(
    val id: String, val role: String, val text: String = "", val detail: String = "",
    val status: String = "", val images: List<String> = emptyList(),
    val kind: String = "", val input: String = "", val outcome: String = "",
    val toolName: String = "", val toolKind: String = "",
    val turnId: String = "",
    val delivery: String = "",
    val userContent: UserMessageContent? = null,
    val nativeInputEvidence: Boolean = true,
    val asyncQuestions: List<RequestQuestion> = emptyList(),
    val media: List<MediaResource> = emptyList(),
    val canRecover: Boolean = false,
    val commandActions: List<CommandAction> = emptyList(),
    val commandCwd: String = "",
)
data class CommandAction(val type: String, val command: String, val path: String = "", val name: String = "")
data class UserMessageContent(val text: String = "", val attachments: List<String> = emptyList())
data class SubAgentState(
    val id: String, val name: String = "", val role: String = "", val turnId: String = "", val itemId: String = "",
    val task: String = "", val status: String = "unknown", val detail: String = "", val nativeStatus: String = "",
    val messages: List<ConversationItem> = emptyList(),
    val model: String = "",
)
data class SubAgentDetailView(
    val parentSessionId: String, val agent: SubAgentState,
    val messages: List<ConversationItem> = emptyList(), val modelLabel: String = "", val effort: String = "", val loading: Boolean = false,
    val cwd: String = "",
)
data class Attachment(val id: String, val uri: String, val name: String, val busy: Boolean = false)
data class QueueEntry(val id: String, val text: String, val status: String, val editableText: String = text, val canEdit: Boolean = true, val canSteer: Boolean = true, val canRemove: Boolean = true,
    val clientUserMessageId: String? = null, val hostOwned: Boolean = false, val localSubmission: Boolean = false,
    val canDismiss: Boolean = false,
    val localRecovery: Boolean = false)
data class RequestChoice(val id: String, val label: String, val description: String = "", val sandbox: String = "", val approval: String = "",
    val configId: String = "", val configName: String = "", val selected: Boolean? = null)
data class RequestQuestion(val id: String, val header: String, val prompt: String, val options: List<String> = emptyList(), val multiple: Boolean = false, val secret: Boolean = false, val allowOther: Boolean = true, val required: Boolean = true, val defaultSelection: List<String> = emptyList(), val optionValues: List<String> = emptyList())
data class UserRequest(
    val id: String, val title: String, val body: String, val choices: List<RequestChoice> = emptyList(),
    val questions: List<RequestQuestion> = emptyList(), val answering: Boolean = false,
    val externalUrl: String? = null,
    val submitChoice: String? = "submit", val skipChoice: String? = null,
)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long? = null, val modified: Long? = null)
data class FileBrowser(val path: String, val entries: List<FileEntry> = emptyList(), val loading: Boolean = false, val error: String? = null, val picking: Boolean = false)
data class MediaResource(val source: String, val name: String = "", val mimeType: String = "", val sessionId: String? = null, val cwd: String? = null, val sizeBytes: Long? = null)
data class ResolvedMedia(val uri: String, val mimeType: String, val name: String = "", val sizeBytes: Long = 0)
data class FilePreview(val path: String, val text: String, val loading: Boolean = false, val media: MediaResource? = null, val sessionId: String? = null, val cwd: String? = null, val sourceResource: MediaResource? = null)
data class Metric(val label: String, val value: String)
data class ReadOnlyEntry(val name: String, val detail: String, val path: String? = null)
data class DraftSkill(val id: String, val name: String, val path: String)
data class PluginCatalog(val skills: List<DraftSkill> = emptyList(), val mcp: List<ReadOnlyEntry> = emptyList(), val loading: Boolean = false, val error: String? = null)
data class Inventory(val title: String, val entries: List<ReadOnlyEntry> = emptyList(), val loading: Boolean = false, val error: String? = null)
data class QueueRecovery(val text: String, val phase: String)
data class SessionView(
    val summary: SessionSummary,
    val connection: String = tr("未连接", "Disconnected"), val connected: Boolean = false, val loading: Boolean = false,
    val activeTurnId: String? = null, val stopping: Boolean = false,
    val messages: List<ConversationItem> = emptyList(), val subAgents: List<SubAgentState> = emptyList(), val queue: List<QueueEntry> = emptyList(),
    val requests: List<UserRequest> = emptyList(), val draft: String = "", val attachments: List<Attachment> = emptyList(),
    val effort: String = "", val error: String? = null, val sending: Boolean = false,
    val uncertainSubmission: Boolean = false, val canOpenTui: Boolean = false,
    val permissions: List<RequestChoice> = emptyList(), val permission: String = "", val speed: String = "",
    val planModeSupported: Boolean = false,
    val planMode: Boolean = false,
    val skipPermissionsActive: Boolean = false,
    val configuring: Boolean = false,
    val queueRecovery: QueueRecovery? = null, val queueBusy: Boolean = false,
    val skills: List<DraftSkill> = emptyList(),
    val metrics: List<Metric> = emptyList(), val capabilityNotice: String? = null,
    val capabilities: CliCapabilities = CliCapabilities(),
    val submissionRecovery: String = "",
    val preparingFirstSend: Boolean = false,
    val cliServiceUpdateRequired: Boolean = false,
    val cliServiceUpdateImpact: String = "",
    val cliServiceUpdateAvailable: Boolean = false,
    val awaitingHandshake: Boolean = false,
)
data class BlankSessionView(
    val key: String = "blank", val cli: String = "Codex", val cwd: String = "/workspace", val draft: String = "",
    val attachments: List<Attachment> = emptyList(), val skills: List<DraftSkill> = emptyList(),
)
data class CockpitState(
    val sessions: List<SessionSummary> = emptyList(), val selected: SessionView? = null,
    val subAgentDetail: SubAgentDetailView? = null,
    val models: List<ModelOption> = emptyList(), val creating: Boolean = false,
    val plugins: PluginCatalog = PluginCatalog(),
    val inventory: Inventory? = null, val error: String? = null, val fileBrowser: FileBrowser? = null, val filePreview: FilePreview? = null,
    val browserOpen: Boolean = false,
    val blank: BlankSessionView = BlankSessionView(),
)
interface CockpitActions {
    val environment: String get() = io.github.modao7067.luma.gui.GuiProduct.environment
    fun createSession(title: String, environment: String, cwd: String, model: String)
    fun createCliSession(cli: String, title: String, environment: String, cwd: String, model: String) = createSession(title, environment, cwd, model)
    fun attachCliSession(cli: String, title: String, environment: String, cwd: String, threadId: String) = attachSession(title, environment, cwd, threadId)
    fun attachSession(title: String, environment: String, cwd: String, threadId: String)
    fun openSession(id: String)
    fun openSubAgent(parentSessionId: String, agentId: String) {}
    fun closeSubAgent() {}
    fun showSessions()
    fun renameSession(id: String, title: String)
    fun archiveSession(id: String) {}
    fun draft(text: String)
    fun recoverSubmission(id: String) {}
    fun addImage(uri: String)
    fun removeImage(id: String)
    fun send()
    fun steer()
    fun stop()
    fun discardUncertainSubmission()
    fun reconnect()
    fun restartCliBridge() {}
    fun configure(model: String, effort: String)
    fun removeQueued(id: String)
    fun answer(requestId: String, choice: String, answers: Map<String, List<String>> = emptyMap())
    fun openTui()
    fun openShell(environment: String, cwd: String)
    fun browseFiles(path: String)
    fun pickFiles(path: String) = browseFiles(path)
    fun closeFiles()
    fun referenceFile(path: String)
    fun referenceFiles(paths: List<String>) = paths.forEach(::referenceFile)
    fun previewDiff()
    fun previewResource(resource: MediaResource) = previewFile(resource.source)
    fun previewFile(path: String)
    suspend fun inspectMedia(resource: MediaResource): MediaResource = resource
    suspend fun resolveMedia(resource: MediaResource): ResolvedMedia = throw java.io.IOException(tr("无法读取", "Unable to read"))
    suspend fun resolveMedia(resource: MediaResource, forceRefresh: Boolean): ResolvedMedia = resolveMedia(resource)
    suspend fun openMediaExternally(resource: MediaResource): Unit = throw java.io.IOException(tr("无法打开", "Unable to open"))
    suspend fun openMediaWithChooser(resource: MediaResource): Unit = openMediaExternally(resource)
    suspend fun saveMedia(resource: MediaResource): String = throw java.io.IOException(tr("无法保存", "Failed to save"))
    fun closePreview()
    fun refreshDashboard() {}
    fun configurePermission(id: String) {}
    fun configurePermissionGroup(configId: String, id: String) {}
    fun configurePlanMode(enabled: Boolean) {}
    fun configureSpeed(id: String) {}
    fun showInventory(kind: String) {}
    fun loadPlugins() {}
    fun toggleSkill(skill: DraftSkill) {}
    fun closeInventory() {}
    fun previewInventory(entry: ReadOnlyEntry) {}
    fun editQueued(id: String, text: String) {}
    fun restoreQueuedToComposer(id: String) {}
    fun steerQueued(id: String) {}
    fun resolveQueueRecovery(restore: Boolean) {}
    fun openBrowser() {}
    fun closeBrowser() {}
    fun command(id: String) {}
    fun dismissError()
}
