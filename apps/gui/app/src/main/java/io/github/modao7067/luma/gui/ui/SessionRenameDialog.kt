package io.github.modao7067.luma.gui.ui

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
        title = "重命名会话",
        onDismiss = onDismiss,
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                onRename(session.id, text.trim())
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            shape = LumaCardShape(16.dp), modifier = Modifier.fillMaxWidth(),
            label = { Text("会话名称") },
        )
    }
}
