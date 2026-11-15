package io.github.modao7067.luma.gui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.modao7067.luma.gui.ui.CockpitScreen
import io.github.modao7067.luma.gui.ui.LocalAgentBrowser

class MainActivity : ComponentActivity() {
    private val sessions get() = (application as LumaApplication).sessions
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContent {
            val state by sessions.state.collectAsState()
            val app = application as LumaApplication
            CompositionLocalProvider(LocalAgentBrowser provides app.browser) {
                LumaTheme {
                    CockpitScreen(
                        state,
                        sessions,
                        currentSessionId = { sessions.state.value.selected?.summary?.id },
                        currentBlankKey = { sessions.state.value.blank.key },
                    )
                }
            }
        }
        if (savedInstanceState == null) {
            intent.getStringExtra("sessionId")?.let(sessions::openSession)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }
    override fun onStart() { super.onStart(); sessions.onActivityStarted(this) }
    override fun onStop() { sessions.onActivityStopped(this); sessions.saveDrafts(); super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("sessionId")?.let(sessions::openSession)
    }
}
