package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.CockpitState
import java.util.Locale

internal data class ComposerCursorCase(val label: String, val text: String)
internal class ComposerCursorProvider : PreviewParameterProvider<ComposerCursorCase> {
    override val values = sequenceOf(
        ComposerCursorCase("CJK 2", "光标"),
        ComposerCursorCase("CJK 8", "光标检查继续输入"),
        ComposerCursorCase("CJK 20", "光标检查继续输入并且确认末尾位置正确无误"),
        ComposerCursorCase("Latin", "iWiWiWiWiWiWiWiWiWiWiWiW"),
        ComposerCursorCase("Mixed", "中文 abcXYZ 1234 混排到行末"),
        ComposerCursorCase("Wrapped", "中文 abcXYZ 1234 混排到行末。".repeat(4)),
        ComposerCursorCase("CJK 26 near right edge", "继续继续继续光标检查继续输入并且确认末尾位置正确无误"),
    )
}

@Preview(name = "Native editor cursor geometry", widthDp = 393, heightDp = 390)
@Composable
internal fun ComposerCursorProbePreview(@PreviewParameter(ComposerCursorProvider::class) sample: ComposerCursorCase) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        val focus = remember { FocusRequester() }
        var result by remember { mutableStateOf<TextLayoutResult?>(null) }
        var focused by remember { mutableStateOf(false) }
        val value = remember(sample.text) { TextFieldValue(sample.text, TextRange(sample.text.length)) }
        LaunchedEffect(focus) { focus.requestFocus() }
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false, LocalWindowInfo provides remember { object : WindowInfo { override val isWindowFocused = true } }) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(sample.label, style = MaterialTheme.typography.labelLarge)
                BasicTextField(
                    value = value, onValueChange = {},
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(Color.Magenta),
                    singleLine = false, minLines = 1, maxLines = 7,
                    onTextLayout = { result = it },
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 12.dp)) {
                            Box(Modifier.drawWithContent {
                                drawContent()
                                result?.let { layout ->
                                    val box = layout.getBoundingBox(sample.text.lastIndex)
                                    drawLine(Color(0xFF008577), Offset(box.right, box.top), Offset(box.right, box.bottom), 1.dp.toPx())
                                }
                            }) { inner() }
                        }
                    },
                )
                result?.let { layout ->
                    val cursor = layout.getCursorRect(value.selection.end)
                    val last = layout.getBoundingBox(sample.text.lastIndex)
                    val line = layout.getLineForOffset(value.selection.end)
                    fun f(n: Float) = String.format(Locale.ROOT, "%.2f", n)
                    Text("len=${sample.text.length}  line=$line  focus=$focused\ncursor.x=${f(cursor.left)} px\nlast.right=${f(last.right)} px\ndelta=${f(cursor.left - last.right)} px\nline.right=${f(layout.getLineRight(line))} px", style = MaterialTheme.typography.bodyMedium)
                    Text("洋红：原生光标；绿色：末字 advance 边界。\n输入和光标共用 SysSans / 14 sp / 0 字距。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        }
    } } }

internal data class ComposerCursorViewportCase(val label: String, val text: String, val fixed: Boolean)
internal class ComposerCursorViewportProvider : PreviewParameterProvider<ComposerCursorViewportCase> {
    override val values = sequenceOf("gg", "g".repeat(36), "中文 abcXYZ 1234 混排到行末").flatMap { text ->
        sequenceOf(false, true).map { fixed ->
            ComposerCursorViewportCase("${if (fixed) "full viewport" else "old wrap viewport"} · ${text.length} chars", text, fixed)
        }
    }
}

@Preview(name = "Native cursor viewport regression", device = "spec:width=1272px,height=1200px,dpi=560")
@Composable
internal fun ComposerCursorViewportPreview(@PreviewParameter(ComposerCursorViewportProvider::class) sample: ComposerCursorViewportCase) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
        val focus = remember { FocusRequester() }
        var result by remember { mutableStateOf<TextLayoutResult?>(null) }
        val value = remember(sample.text) { TextFieldValue(sample.text, TextRange(sample.text.length)) }
        LaunchedEffect(focus) { focus.requestFocus() }
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false, LocalWindowInfo provides remember { object : WindowInfo { override val isWindowFocused = true } }) {
            Surface {
                Column(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(sample.label, style = MaterialTheme.typography.labelMedium)
                    BasicTextField(
                        value = value, onValueChange = {},
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Color.Magenta), singleLine = false, minLines = 1, maxLines = 7,
                        onTextLayout = { result = it },
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), propagateMinConstraints = sample.fixed) { inner() }
                        },
                    )
                    result?.let { layout ->
                        Text("native layout width = ${layout.size.width} px\nadvance = ${layout.getCursorRect(value.selection.end).left} px", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    } } }

@Preview(name = "Production editor long g viewport", device = "spec:width=1272px,height=900px,dpi=560")
@Composable
internal fun ComposerCursorProductionPreview() =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
        val view = sampleView().copy(draft = "g".repeat(36), activeTurnId = null, queue = emptyList())
        Surface { Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 20.dp)) {
            ComposerGlass(Modifier.fillMaxWidth()) {
                CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true, previewMotionFraction = 1f)
            }
        } }
    } } }
