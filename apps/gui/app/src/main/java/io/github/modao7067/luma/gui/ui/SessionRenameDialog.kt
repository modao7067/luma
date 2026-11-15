package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.SessionSummary

@Composable
internal fun SessionRenameDialog(session: SessionSummary, onRename: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(session.id) { mutableStateOf(session.title) }
    LiquidModalDialog(
        title = tr("重命名会话", "Rename session"),
        onDismiss = onDismiss,
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                onRename(session.id, text.trim())
                onDismiss()
            }) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            shape = LumaCardShape(16.dp), modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("会话名称", "Session name")) },
        )
    }
}
