package io.github.modao7067.luma.gui.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URLDecoder

internal fun googleSignInIntent(url: String): Intent? {
    val valid = runCatching {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawAuthority == "accounts.google.com")
        require(uri.rawPath in setOf("/o/oauth2/auth", "/o/oauth2/v2/auth") && uri.rawFragment == null)
        val redirects = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            .filter { URLDecoder.decode(it[0], "UTF-8") == "redirect_uri" }
        require(redirects.size == 1 && redirects[0].size == 2)
        val callback = URI(URLDecoder.decode(redirects[0][1], "UTF-8"))
        require(callback.scheme == "http" && callback.host == "127.0.0.1")
        require(callback.rawUserInfo == null && callback.port in 1..65535 && callback.rawPath == "/")
        require(callback.rawQuery == null && callback.rawFragment == null)
    }.isSuccess
    if (!valid) return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun openGoogleSignIn(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
