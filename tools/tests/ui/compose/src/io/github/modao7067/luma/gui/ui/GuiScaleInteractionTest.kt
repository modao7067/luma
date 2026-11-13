package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuiScaleInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun popupAndDialogInheritExactlyOneLayoutScale() {
        var modal by mutableStateOf(false)
        var pageDensity = 0f
        var popupDensity = 0f
        var dialogDensity = 0f
        rule.setContent { LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
            pageDensity = LocalDensity.current.density
            Box {
                Text("锚点")
                if (!modal) OriginMenu(expanded = true, onDismiss = {}) {
                    popupDensity = LocalDensity.current.density
                    Box(Modifier.size(100.dp).testTag("popup-measure"))
                }
                if (modal) LiquidModalDialog(title = "设置", onDismiss = {}, confirmButton = {}) {
                    dialogDensity = LocalDensity.current.density
                    Box(Modifier.size(100.dp).testTag("dialog-measure"))
                }
            }
        } }
        assertEquals(pageDensity, popupDensity, 0.0001f)
        assertEquals(100 * pageDensity, rule.onNodeWithTag("popup-measure").fetchSemanticsNode().boundsInRoot.width, 1f)
        rule.runOnIdle { modal = true }
        rule.waitForIdle()
        assertEquals(pageDensity, dialogDensity, 0.0001f)
        assertEquals(100 * pageDensity, rule.onNodeWithTag("dialog-measure").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun densityPreservesNonLinearSystemFontConversionAndRoundTrip() {
        val system = object : Density {
            override val density = 3f
            override val fontScale = 1.5f
            override fun TextUnit.toDp() = (value * if (value >= 20) 1.3f else 1.5f).dp
            override fun Dp.toSp() = (value / if (value >= 26) 1.3f else 1.5f).sp
        }
        val scaled = GuiDensity(system)
        assertEquals(system.fontScale, scaled.fontScale)
        for (size in listOf(12.sp, 24.sp, 36.sp)) {
            val before = with(system) { size.toPx() }
            val after = with(scaled) { size.toPx() }
            assertEquals(before * GUI_LAYOUT_SCALE, after, 0.001f)
            assertEquals(size.value, with(scaled) { after.toSp().value }, 0.001f)
        }
        assertEquals(48f * 3 * GUI_LAYOUT_SCALE, with(scaled) { 48.dp.toPx() }, 0.001f)
    }

    @Test fun phoneBodyFitsTwentyHanColumnsInsteadOfTwentyTwo() {
        val system = Density(3.5f, 1f)
        val density = GuiDensity(system).density
        assertEquals(1f, GUI_LAYOUT_SCALE, 0.0001f)
        assertEquals(3.5f, density, 0.0001f)
        var bodyStyle: androidx.compose.ui.text.TextStyle? = null
        rule.setContent {
            SessionTypography {
                ConversationTypography {
                    bodyStyle = MaterialTheme.typography.bodyLarge
                }
            }
        }
        val style = requireNotNull(bodyStyle)
        assertEquals(androidx.compose.ui.text.font.FontFamily.SansSerif, style.fontFamily)
        assertEquals(16f, style.fontSize.value, 0.0001f)
        assertEquals(26f, style.lineHeight.value, 0.0001f)
        assertEquals(0.2f, style.letterSpacing.value, 0.0001f)
        val charAdvanceSp = style.fontSize.value + style.letterSpacing.value
        val columns = ((1272f - 36f * density) / (charAdvanceSp * density)).toInt()
        assertEquals(20, columns)
    }

    @Test fun realLayoutScalesButPhysicalMinimumTouchAndSystemFontScaleRemain() {
        val original = Density(2f, 1.3f)
        var actual: Density? = null
        var target = 0f
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides original) {
                LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
                    actual = LocalDensity.current
                    target = with(LocalDensity.current) { LocalViewConfiguration.current.minimumTouchTargetSize.width.toPx() }
                    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
                        Box(Modifier.size(100.dp).testTag("measured"))
                    }
                }
            }
        }
        assertEquals(200f * GUI_LAYOUT_SCALE, rule.onNodeWithTag("measured").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(1.3f, actual!!.fontScale)
        assertEquals(96f, target, 0.01f)
    }

    @Test fun scaledComposerRetainsEditorIdentityTextCaretSpaceAndOuterClick() {
        var draft by mutableStateOf("中文 English，标点。")
        var fraction by mutableStateOf(0f)
        var sends = 0
        var scaledDensity = 1f
        val actions = object : CockpitActions by previewActions {
            override fun draft(text: String) { draft = text }
            override fun send() { sends++ }
        }
        rule.setContent { LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
            scaledDensity = LocalDensity.current.density
            val view = sampleView().copy(draft = draft, activeTurnId = null, queue = emptyList())
            Box(Modifier.width(360.dp).testTag("capsule")) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewMotionFraction = fraction)
            }
        } }
        val editor = rule.onNodeWithContentDescription("消息草稿")
        val editorId = editor.fetchSemanticsNode().id
        editor.performTextReplacement("中文 abc\n第二行 🙂")
        editor.assertTextEquals("中文 abc\n第二行 🙂")
        rule.runOnIdle { fraction = 1f }
        assertEquals(editorId, editor.fetchSemanticsNode().id)
        editor.assertTextEquals("中文 abc\n第二行 🙂")
        val shell = rule.onNodeWithTag("capsule").fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        println("SCALED capsule=$shell send=$send density=$scaledDensity")
        assertEquals(24 * scaledDensity, shell.right - send.center.x, 2f)
        rule.onNodeWithTag("capsule").performTouchInput {
            click(Offset(send.center.x - shell.left, send.center.y - shell.top - 23f * rule.density.density))
        }
        assertEquals(1, sends)
        println("PASS scaled real bounds=$shell send=$send; same editor=$editorId; physical outer hit retained")
    }
}
