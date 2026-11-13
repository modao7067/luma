package io.github.modao7067.luma.gui.browser

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.github.modao7067.luma.gui.LumaApplication
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.ui.AttachedBrowserPane
import io.github.modao7067.luma.gui.ui.LocalAgentBrowser
import io.github.modao7067.luma.gui.ui.SessionTypography

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = intent.getStringExtra("sessionId") ?: run { finish(); return }
        val app = application as LumaApplication
        if (app.sessions.state.value.sessions.none { it.id == owner }) { finish(); return }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContent {
            CompositionLocalProvider(LocalAgentBrowser provides app.browser) {
                LumaTheme {
                    SessionTypography {
                        PredictiveBackHandler { progress ->
                            progress.collect {}
                            finish()
                        }
                        AttachedBrowserPane(
                            sessionId = owner,
                            active = true,
                            onBack = ::finish,
                            onQuoteSelection = { quote ->
                                val existing = app.sessions.state.value.selected?.draft.orEmpty()
                                app.sessions.draft(if (existing.isBlank()) quote else existing.trimEnd() + "\n\n" + quote)
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }
}
