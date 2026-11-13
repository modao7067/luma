package io.github.modao7067.luma.gui

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import io.github.modao7067.luma.gui.session.SessionController
import io.github.modao7067.luma.gui.session.SessionManagementBinding
import io.github.modao7067.luma.gui.session.SessionManagementServer

class LumaApplication : Application() {
    val browser by lazy { io.github.modao7067.luma.gui.browser.AgentBrowser(this) }
    val sessions: SessionController by lazy { SessionController(this) }
    private val managementScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val management by lazy {
        SessionManagementBinding(this) { token -> SessionManagementServer(token, { sessions.manage(it) }, managementScope) }
    }
    override fun onCreate() {
        super.onCreate()
        io.github.modao7067.luma.gui.ui.LumaSelectionConfiguration.initialize()
        if (getProcessName() == packageName) {
            management
            if (io.github.modao7067.luma.gui.runtime.DebianRootMode.isEnabled(this)) {
                Thread({ io.github.modao7067.luma.gui.runtime.DebianRootMode.refreshInstalled(this) }, "LumaRootRefresh").start()
            }
        }
    }
}
