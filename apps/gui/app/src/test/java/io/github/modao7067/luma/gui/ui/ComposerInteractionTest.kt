package io.github.modao7067.luma.gui.ui

import org.junit.Assert.*
import org.junit.Test

class ComposerInteractionTest {
    @Test fun sameAnchorTogglesClosedAndDifferentAnchorSwitches() {
        val plus = ComposerMenuState().toggle(ComposerMenuAnchor.Plus)
        assertNull(plus.toggle(ComposerMenuAnchor.Plus).active)
        assertEquals(ComposerMenuAnchor.Permission, plus.toggle(ComposerMenuAnchor.Permission).active)
        assertEquals(ComposerMenuAnchor.Model, plus.toggle(ComposerMenuAnchor.Model).active)
    }
    @Test fun outgoingPopupDismissCannotCloseItsReplacement() {
        val model = ComposerMenuState(ComposerMenuAnchor.Plus).toggle(ComposerMenuAnchor.Model)
        assertEquals(model, model.dismiss(ComposerMenuAnchor.Plus))
        assertNull(model.dismiss(ComposerMenuAnchor.Model).active)
    }
    @Test fun compactEditorFillsSpaceBetweenButtonsWithoutCoveringThem() {
        for (width in listOf(320, 365, 393)) {
            val bounds = composerEditorHorizontal(width, 48, 6, 18, 0f)
            assertEquals(48, bounds.left)
            assertEquals(width - 54, bounds.left + bounds.width)
            assertEquals(54, bounds.left + bounds.leadingInset)
            val expanded = composerEditorHorizontal(width, 48, 6, 18, 1f)
            assertEquals(18, expanded.left); assertEquals(width - 36, expanded.width)
            assertEquals(0, expanded.leadingInset)
        }
    }
    @Test fun editorWidthWaitsForControlsToClearBeforeExpandingItsHitArea() {
        for (step in 0..100) {
            val fraction = step / 100f
            val bounds = composerEditorHorizontal(393, 48, 6, 18, fraction)
            if (bounds.left < 48) assertEquals(1f, composerControlsFraction(fraction), 0f)
        }
    }
    @Test fun menusAndPrimaryActionsDoNotAlterEditingPosture() {
        val collapsed = ComposerFocusState(true, true).onIme(false)
        val editing = ComposerFocusState(true, true)
        for (anchor in ComposerMenuAnchor.entries) {
            ComposerMenuState().toggle(anchor).toggle(anchor)
            assertFalse(collapsed.presentation("", false).expanded)
            assertTrue(editing.presentation("", false).expanded)
            assertTrue(editing.imeVisible)
        }
        assertEquals(ComposerPrimaryAction.STOP, composerPrimaryAction(true, false))
        assertEquals(ComposerPrimaryAction.SEND, composerPrimaryAction(false, true))
        assertFalse(collapsed.presentation("", false).expanded)
    }
}
