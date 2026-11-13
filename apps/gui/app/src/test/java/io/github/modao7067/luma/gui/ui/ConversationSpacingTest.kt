package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSpacingTest {
    @Test fun keyboardReadingGapTracksThreeMeasuredLines() {
        listOf(22f, 28f, 35f).forEach { measuredLine ->
            assertEquals((measuredLine * 3).dp, conversationReadingGap(true, measuredLine.dp))
            assertEquals(12.dp, conversationReadingGap(false, measuredLine.dp))
        }
    }
}
