package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme

@Preview(name = "Material action menu", widthDp = 393, heightDp = 852)
@Composable
internal fun MaterialActionMenuPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            OriginMenu(true, {}) {
                ActionMenuItem("终端", MenuSymbol.TERMINAL) {}
                ActionMenuItem("浏览器", MenuSymbol.BROWSER) {}
                ActionMenuItem("新建会话", MenuSymbol.NEW_CHAT) {}
            }
        }
    }
}
