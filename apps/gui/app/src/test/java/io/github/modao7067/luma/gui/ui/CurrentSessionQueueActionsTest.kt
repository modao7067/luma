package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.CockpitActions
import org.junit.Assert.*
import org.junit.Test

class CurrentSessionQueueActionsTest {
    @Test fun maintenanceAndRestoreReachTheActualTargetOnlyWhileTheirSessionOwnsTheUi() {
        var selected = "A"; val calls = mutableListOf<String>()
        val target = object : CockpitActions by previewActions {
            override fun restartCliBridge() { calls += "restart:$selected" }
            override fun restoreQueuedToComposer(id: String) { calls += "restore:$selected:$id" }
        }
        val old = CurrentSessionActions(target) { selected == "A" }
        old.restartCliBridge(); old.restoreQueuedToComposer("submission-exact")
        assertEquals(listOf("restart:A", "restore:A:submission-exact"), calls)
        selected = "B"
        old.restartCliBridge(); old.restoreQueuedToComposer("submission-exact")
        assertEquals(2, calls.size)
        val current = CurrentSessionActions(target) { selected == "B" }
        current.restoreQueuedToComposer("submission-other")
        assertEquals("restore:B:submission-other", calls.last())
    }
}
