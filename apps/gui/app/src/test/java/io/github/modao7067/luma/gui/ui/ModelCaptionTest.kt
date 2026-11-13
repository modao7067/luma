package io.github.modao7067.luma.gui.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class ModelCaptionTest {
    @Test fun modelAndEffortShareOneCompactLine() { assertEquals("GPT-6-Astra·xhigh", modelEffortLabel("GPT-6-Astra", "xhigh")) }
    @Test fun absentEffortHasNoDanglingSeparator() { assertEquals("GPT-6-Astra", modelEffortLabel("GPT-6-Astra", "")) }
}
