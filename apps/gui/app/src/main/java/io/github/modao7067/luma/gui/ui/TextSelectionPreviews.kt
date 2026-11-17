@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.modao7067.luma.gui.ui

import androidx.collection.longObjectMapOf
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionRegistrarImpl
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import org.commonmark.parser.Parser

@Composable
private fun SelectedTextRange(start: Int, end: Int, content: @Composable () -> Unit) {
    val registrar = remember(start, end) {
        SelectionRegistrarImpl().apply {
            subselections = longObjectMapOf(1L, Selection(
                Selection.AnchorInfo(ResolvedTextDirection.Ltr, start, 1L),
                Selection.AnchorInfo(ResolvedTextDirection.Ltr, end, 1L)))
        }
    }
    CompositionLocalProvider(LocalSelectionRegistrar provides registrar, content = content)
}

@Composable
private fun SelectionLayers(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.width(390.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("文本选区 · ${if (dark) "深色" else "浅色"}", style = MaterialTheme.typography.titleLarge)
            Text("未选：保留代码底色", style = MaterialTheme.typography.labelLarge)
            RichText(remember { Parser.builder().build().parse("报告：`/workspace/project/verification/report.md` 然后继续。").firstChild }, MaterialTheme.typography.bodyLarge, null)
            Text("选中：project/verification", style = MaterialTheme.typography.labelLarge)
            SelectedTextRange(15, 35) {
                RichText(remember { Parser.builder().build().parse("报告：`/workspace/project/verification/report.md` 然后继续。").firstChild }, MaterialTheme.typography.bodyLarge, null)
            }
            Text("用户气泡底色上的原生选区", style = MaterialTheme.typography.labelLarge)
            val (bubbleBg, bubbleFg) = userBubbleColors(MaterialTheme.colorScheme)
            Surface(color = bubbleBg, shape = LumaCardShape(18.dp)) {
                SelectedTextRange(3, 11) {
                    Text("用户消息可以选择并复制", Modifier.padding(16.dp), color = bubbleFg)
                }
            }
            Text("代码块底色上的原生选区", style = MaterialTheme.typography.labelLarge)
            LiquidSurface(tonalTint = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .90f), cornerRadius = 16.dp) {
                SelectedTextRange(6, 20) {
                    Text(highlightedCode("print(\"hello world\")", "python", MaterialTheme.colorScheme), Modifier.padding(14.dp), fontFamily = terminalFont(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Preview(widthDp = 390, showBackground = true)
@Composable internal fun TextSelectionDarkPreview() = SelectionLayers(true)

@Preview(widthDp = 390, showBackground = true)
@Composable internal fun TextSelectionLightPreview() = SelectionLayers(false)
