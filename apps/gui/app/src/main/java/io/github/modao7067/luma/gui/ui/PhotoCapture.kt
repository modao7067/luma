package io.github.modao7067.luma.gui.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.modao7067.luma.gui.camera.PhotoCaptureStore

internal data class PhotoCaptureHandle(val available: Boolean, val busy: Boolean, val launch: () -> Unit)

internal class FullPhotoContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        clipData = ClipData.newRawUri("photo", input)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

@Composable
internal fun rememberPhotoCapture(
    ownerKey: () -> String,
    enabled: Boolean,
    onImage: (String) -> Unit,
    onError: (String) -> Unit,
): PhotoCaptureHandle {
    if (LocalInspectionMode.current) return PhotoCaptureHandle(true, false) {}
    val context = LocalContext.current
    val store = remember(context) { PhotoCaptureStore(context.applicationContext) }
    val currentOwner by rememberUpdatedState(ownerKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val imageCallback by rememberUpdatedState(onImage)
    val errorCallback by rememberUpdatedState(onError)
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val available = remember(context) {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    }
    fun revoke(name: String) {
        try {
            context.revokeUriPermission(store.uri(name), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { Log.w("PhotoCapture", "Capture URI grant cleanup failed") }
    }
    val launcher = rememberLauncherForActivityResult(FullPhotoContract()) { success ->
        val name = pendingName
        val owner = pendingOwner
        pendingName = null; pendingOwner = null
        if (name != null) {
            revoke(name)
            try {
                if (!success) { store.discard(name) }
                else if (owner != currentOwner() || !currentEnabled) {
                    store.discard(name)
                    errorCallback("会话已切换或当前不可添加，照片未添加")
                } else {
                    val uri = store.ready(name)
                    imageCallback(uri.toString())
                }
            } catch (_: Exception) {
                store.discard(name)
                errorCallback("照片添加失败，请重试")
            }
        }
    }
    return PhotoCaptureHandle(available && enabled, pendingName != null) {
        if (pendingName == null && currentEnabled) {
            if (!available) errorCallback("未找到相机应用")
            else {
                var created: String? = null
                try {
                    val owner = currentOwner()
                    check(owner.isNotBlank())
                    created = store.create()
                    pendingName = created; pendingOwner = owner
                    launcher.launch(store.uri(created))
                } catch (_: Exception) {
                    pendingName = null; pendingOwner = null
                    created?.let { revoke(it); store.discard(it) }
                    errorCallback("无法打开相机，请重试")
                }
            }
        }
    }
}
