package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.runtime.compositionLocalOf
import io.github.modao7067.luma.gui.session.*

internal val LocalSessionInteractive = compositionLocalOf { true }

internal class CurrentSessionActions(
    private val target: CockpitActions,
    private val isCurrent: () -> Boolean,
) : CockpitActions {
    override val environment get() = target.environment
    private inline fun dispatch(block: CockpitActions.() -> Unit) { if (isCurrent()) target.block() }
    override fun createSession(title: String, environment: String, cwd: String, model: String) = dispatch { createSession(title, environment, cwd, model) }
    override fun createCliSession(cli: String, title: String, environment: String, cwd: String, model: String) = dispatch { createCliSession(cli, title, environment, cwd, model) }
    override fun attachCliSession(cli: String, title: String, environment: String, cwd: String, threadId: String) = dispatch { attachCliSession(cli, title, environment, cwd, threadId) }
    override fun attachSession(title: String, environment: String, cwd: String, threadId: String) = dispatch { attachSession(title, environment, cwd, threadId) }
    override fun openSession(id: String) = dispatch { openSession(id) }
    override fun openSubAgent(parentSessionId: String, agentId: String) = dispatch { openSubAgent(parentSessionId, agentId) }
    override fun closeSubAgent() = dispatch { closeSubAgent() }
    override fun showSessions() = dispatch { showSessions() }
    override fun renameSession(id: String, title: String) = dispatch { renameSession(id, title) }
    override fun archiveSession(id: String) = dispatch { archiveSession(id) }
    override fun recoverSubmission(id: String) = dispatch { recoverSubmission(id) }
    override fun draft(text: String) = dispatch { draft(text) }
    override fun addImage(uri: String) = dispatch { addImage(uri) }
    override fun removeImage(id: String) = dispatch { removeImage(id) }
    override fun send() = dispatch { send() }
    override fun steer() = dispatch { steer() }
    override fun stop() = dispatch { stop() }
    override fun discardUncertainSubmission() = dispatch { discardUncertainSubmission() }
    override fun reconnect() = dispatch { reconnect() }
    override fun restartCliBridge() = dispatch { restartCliBridge() }
    override fun configure(model: String, effort: String) = dispatch { configure(model, effort) }
    override fun removeQueued(id: String) = dispatch { removeQueued(id) }
    override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) = dispatch { answer(requestId, choice, answers) }
    override fun openTui() = dispatch { openTui() }
    override fun openShell(environment: String, cwd: String) = dispatch { openShell(environment, cwd) }
    override fun browseFiles(path: String) = dispatch { browseFiles(path) }
    override fun pickFiles(path: String) = dispatch { pickFiles(path) }
    override fun closeFiles() = dispatch { closeFiles() }
    override fun referenceFile(path: String) = dispatch { referenceFile(path) }
    override fun referenceFiles(paths: List<String>) = dispatch { referenceFiles(paths) }
    override fun previewDiff() = dispatch { previewDiff() }
    override fun previewResource(resource: MediaResource) = dispatch { previewResource(resource) }
    override fun previewFile(path: String) = dispatch { previewFile(path) }
    override suspend fun inspectMedia(resource: MediaResource): MediaResource {
        check(isCurrent()) { tr("会话已切换", "Session switched") }; return target.inspectMedia(resource)
    }
    override suspend fun resolveMedia(resource: MediaResource): ResolvedMedia {
        check(isCurrent()) { tr("会话已切换", "Session switched") }
        return target.resolveMedia(resource)
    }
    override suspend fun resolveMedia(resource: MediaResource, forceRefresh: Boolean): ResolvedMedia {
        check(isCurrent()) { tr("会话已切换", "Session switched") }
        return target.resolveMedia(resource, forceRefresh)
    }
    override suspend fun saveMedia(resource: MediaResource): String {
        check(isCurrent()) { tr("会话已切换", "Session switched") }; return target.saveMedia(resource)
    }
    override suspend fun openMediaExternally(resource: MediaResource) {
        check(isCurrent()) { tr("会话已切换", "Session switched") }; target.openMediaExternally(resource)
    }
    override suspend fun openMediaWithChooser(resource: MediaResource) {
        check(isCurrent()) { tr("会话已切换", "Session switched") }; target.openMediaWithChooser(resource)
    }
    override fun closePreview() = dispatch { closePreview() }
    override fun refreshDashboard() = dispatch { refreshDashboard() }
    override fun configurePermission(id: String) = dispatch { configurePermission(id) }
    override fun configureSpeed(id: String) = dispatch { configureSpeed(id) }
    override fun showInventory(kind: String) = dispatch { showInventory(kind) }
    override fun loadPlugins() = dispatch { loadPlugins() }
    override fun toggleSkill(skill: DraftSkill) = dispatch { toggleSkill(skill) }
    override fun closeInventory() = dispatch { closeInventory() }
    override fun previewInventory(entry: ReadOnlyEntry) = dispatch { previewInventory(entry) }
    override fun editQueued(id: String, text: String) = dispatch { editQueued(id, text) }
    override fun restoreQueuedToComposer(id: String) = dispatch { restoreQueuedToComposer(id) }
    override fun steerQueued(id: String) = dispatch { steerQueued(id) }
    override fun resolveQueueRecovery(restore: Boolean) = dispatch { resolveQueueRecovery(restore) }
    override fun openBrowser() = dispatch { openBrowser() }
    override fun closeBrowser() = dispatch { closeBrowser() }
    override fun command(id: String) = dispatch { command(id) }
    override fun dismissError() = dispatch { dismissError() }
}
