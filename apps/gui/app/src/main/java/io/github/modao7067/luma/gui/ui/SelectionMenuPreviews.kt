package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.CockpitState

@Composable private fun RevisedComposerMenu(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    val view = sampleView().copy(draft = "保留这段草稿。", activeTurnId = null, queue = emptyList())
    LiquidScaffold(wrapTop = false, wrapBottom = false,
        topBar = { ConversationHeader(view, previewActions, {}, {}) },
        bottomBar = { ComposerGlass { CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewMenu = true, onTakePhoto = {}) } },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) { NativeMarkdown("## 项目记录\n\n图片和文件随消息保留。") } }
}
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SelectionComposerMenuLightPreview() = RevisedComposerMenu(false)
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SelectionComposerMenuDarkPreview() = RevisedComposerMenu(true)
