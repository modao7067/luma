package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CodeBlockLongOutputTest {
    @get:Rule val rule = createComposeRule()
    private var code by mutableStateOf("val x = \"text\"; return 123 // comment\n".repeat(10000) + "最后一行🙂 " + "wide-output ".repeat(30))
    private var copied: AnnotatedString? = null
    private val clipboard = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        override fun getText() = copied
    }
    @Test fun longOutputStaysCompleteCopyableAndFixedWhileStreamingAtAnInnerScrollPosition() {
        lateinit var vertical: ScrollState
        lateinit var horizontal: ScrollState
        rule.setContent { MaterialTheme {
            vertical = rememberScrollState(); horizontal = rememberScrollState()
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(360.dp).testTag("shell")) { CodeBlock(code, "output", vertical, horizontal) }
            }
        } }
        rule.waitForIdle()
        val height = rule.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(code, copied?.text)
        rule.onNodeWithTag("shell").performTouchInput {
            swipe(Offset(center.x, bottom - 24f), Offset(center.x, top + 100f), 180)
        }
        rule.runOnIdle { assertTrue("actual body drag scrolls", vertical.value > 0) }
        rule.onNodeWithTag("shell").performTouchInput {
            swipe(Offset(right - 24f, center.y), Offset(left + 24f, center.y), 180)
        }
        rule.runOnIdle { assertTrue("actual horizontal drag scrolls", horizontal.value > 0) }
        rule.runOnIdle { runBlocking { vertical.scrollTo(3000) } }
        val anchor = rule.runOnIdle { vertical.value }
        repeat(5) { step -> rule.runOnIdle { code += "\nstream $step val 42" }; rule.waitForIdle() }
        rule.runOnIdle { assertEquals(anchor, vertical.value); assertTrue(vertical.maxValue > 3000) }
        assertEquals(height, rule.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot.height)
        rule.onNodeWithContentDescription("复制").assertIsDisplayed().performClick()
        assertEquals(code, copied?.text)
        rule.runOnIdle { runBlocking { vertical.scrollTo(vertical.maxValue) } }
        rule.onNodeWithText(code).assertExists()
        println("PASS full ${code.length} chars, fixed shell, inner reading anchor, five streaming updates, exact copy including tail")
    }
    @Test fun backgroundHighlightNeverPublishesAnotherInputsText() {
        var rendered = AnnotatedString("")
        rule.setContent { MaterialTheme { rendered = rememberHighlightedCode(code, "output", MaterialTheme.colorScheme) } }
        repeat(8) { step ->
            rule.runOnIdle { code = "val current = $step\n" + "x ".repeat(100000) }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(code, rendered.text) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitUntil(10000) { rendered.spanStyles.isNotEmpty() }
        rule.runOnIdle { assertEquals(code, rendered.text) }
        println("PASS immediate full text and latest-only async coloring through eight replacements")
    }
}
