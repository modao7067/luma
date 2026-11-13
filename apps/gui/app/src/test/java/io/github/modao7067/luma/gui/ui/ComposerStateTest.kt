package io.github.modao7067.luma.gui.ui

import org.junit.Assert.*
import org.junit.Test

class ComposerStateTest {
    @Test fun pendingDeliveryOwnsThePrimarySlotEvenWhenTheTurnIsAlreadyActive() {
        assertEquals(ComposerPrimaryAction.SENDING, composerPrimaryAction(true, false, true))
        assertEquals(ComposerPrimaryAction.SENDING, composerPrimaryAction(true, true, true))
        assertEquals(ComposerPrimaryAction.SENDING, composerPrimaryAction(false, false, true))
        assertEquals(ComposerPrimaryAction.STOP, composerPrimaryAction(true, false, false))
    }

    @Test fun idleWithDraftKeepsTextAboveControls() {
        val p = ComposerFocusState().presentation("保留\n这段草稿", false)
        assertTrue(p.expanded)
        assertEquals("保留\n这段草稿", p.draft)
    }
    @Test fun idleEmptyIsCompact() { assertFalse(ComposerFocusState().presentation("", false).expanded) }
    @Test fun keyboardDismissalPreservesFocusButEmptyPostureCollapses() {
        val state = ComposerFocusState(true, true).onIme(false)
        assertTrue(state.focused)
        assertFalse(state.presentation("", false).expanded)
        assertFalse(state.capturesBack)
        assertTrue(state.onIme(true).presentation("", false).expanded)
    }
    @Test fun popupFocusRoundTripDoesNotUndoKeyboardDismissal() {
        val state = ComposerFocusState(true, true).onIme(false).onFocus(false).onFocus(true)
        assertTrue(state.focused)
        assertFalse(state.presentation("", false).expanded)
        assertTrue(state.onIme(true).presentation("", false).expanded)
    }
    @Test fun focusExpandsWithoutWaitingForIme() {
        val s = ComposerFocusState().onFocus(true)
        assertTrue(s.presentation("", false).expanded)
        assertTrue(s.onIme(false).focused)
    }
    @Test fun imeOpenKeepsExpandedDuringPopupFocusTransfer() {
        val s = ComposerFocusState().onFocus(true).onIme(true).onFocus(false)
        assertTrue(s.presentation("draft", false).expanded)
    }
    @Test fun imeHideCollapsesWithoutClearingSessionDraft() {
        val draft = "中文草稿\n@\"/workspace/file.md\""
        val s = ComposerFocusState().onFocus(true).onIme(true).onIme(false)
        assertTrue(s.focused)
        assertTrue(s.presentation(draft, false).expanded)
        assertEquals(draft, s.presentation(draft, false).draft)
    }
    @Test fun explicitCollapseKeepsDraftAndDoesNotSend() {
        val s = ComposerFocusState(true, true).collapse()
        assertTrue(s.focused)
        assertTrue(s.imeDismissed)
        assertFalse(s.capturesBack)
        assertEquals("待发送", s.presentation("待发送", false).draft)
    }
    @Test fun actualAttachmentsExpandButMenuStateIsNotEditingPosture() {
        val s = ComposerFocusState()
        assertTrue(s.presentation("", true).expanded)
        assertFalse(s.presentation("", false).expanded)
    }
    @Test fun attachmentsDoNotTrapBackAfterEditingEnds() {
        val s = ComposerFocusState(true, true).onIme(false)
        assertTrue(s.presentation("draft", true).expanded)
        assertFalse(s.capturesBack)
    }
    @Test fun busyEmptyPrimaryStopsButInputPrimarySends() {
        assertEquals(ComposerPrimaryAction.STOP, composerPrimaryAction(true, false))
        assertEquals(ComposerPrimaryAction.SEND, composerPrimaryAction(true, true))
        assertEquals(ComposerPrimaryAction.SEND, composerPrimaryAction(false, false))
    }
    @Test fun unknownSubmissionNeverEnablesRepeatSend() {
        assertFalse(composerCanSend(true, false, false, true, true, false, true, false))
        assertTrue(composerCanSend(true, false, false, false, true, false, true, false))
    }
    @Test fun sendingRequiresConnectionInputAndReadySupportedAttachments() {
        assertFalse(composerCanSend(false, false, false, false, true, false, true, false))
        assertFalse(composerCanSend(true, true, false, false, true, false, true, false))
        assertFalse(composerCanSend(true, false, true, false, true, false, true, false))
        assertFalse(composerCanSend(true, false, false, false, false, false, true, false))
        assertFalse(composerCanSend(true, false, false, false, true, true, true, true))
        assertFalse(composerCanSend(true, false, false, false, true, false, false, true))
    }
}
