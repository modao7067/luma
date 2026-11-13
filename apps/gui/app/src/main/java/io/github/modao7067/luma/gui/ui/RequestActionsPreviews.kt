package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

@Preview(name="Short question actions narrow",widthDp=320,heightDp=680,fontScale=1.3f)
@Composable internal fun RequestActionsNarrowPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean)=LumaTheme(seedArgb=0xFF6750A4.toInt(),darkTheme=dark){
    RequestDialog(UserRequest("native-sync","Answer","",questions=listOf(RequestQuestion("q","验证路径","本次使用哪条验证路径？",listOf("先跑隔离验证","先检查协议差异"),defaultSelection=listOf("先跑隔离验证"))),skipChoice="skip"),previewActions){}
}
