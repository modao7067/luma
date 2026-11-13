package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionNavigationTest {
    private val records = listOf(SessionRecord("older", "旧会话", "noroot", "/work"), SessionRecord("last", "最后", "noroot", "/work"))
    @Test fun explicitBlankNeverFallsBackToLegacyOrLastSession() {
        val nav = SessionNavigation(true, null, BlankSessionDraft(cli = "Claude Code"))
        assertNull(restoredSelection(nav, records, "older", "noroot"))
        assertNull(restoredSelection(nav, records, "last", "noroot"))
        assertEquals("Claude Code", nav.blank.cli)
    }
    @Test fun blankDraftRoundtripDoesNotAllocateASessionOrLoseAttachments() {
        val blank = BlankSessionDraft(cli = "pi", cwd = "/original", text = "未发消息",
            images = mutableListOf(StoredImage("image", "private://image", "/image", "image")),
            skills = mutableListOf(DraftSkill("skill", "Skill", "/skill")))
        val restored = decodeNavigation(JSONObject(encodeNavigation(SessionNavigation(true, null, blank)).toString()))
        assertNull(restored.selectedId); assertEquals(blank, restored.blank)
        assertFalse(encodeNavigation(restored).has("sessions")); assertFalse(encodeNavigation(restored).has("threadId"))
    }
    @Test fun legacySelectionContractRemainsUntilExplicitNavigationWasSaved() {
        val legacy = decodeNavigation(null)
        assertEquals("older", restoredSelection(legacy, records, "older", "noroot"))
        assertEquals("last", restoredSelection(legacy, records, null, "noroot"))
    }
    @Test fun missingExplicitSelectionAndOtherEnvironmentNeverChooseAnotherSession() {
        assertNull(restoredSelection(SessionNavigation(true, "removed"), records, "older", "noroot"))
        assertNull(restoredSelection(SessionNavigation(true, "older"), records, "older", "root"))
    }
}
