package io.github.modao7067.luma.gui.i18n

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

object LumaI18n {
    private val lock = Any()
    private var epoch by mutableIntStateOf(0)
    @Volatile private var overrideBacking: String? = null
    @Volatile private var configLanguage: String? = null

    var overrideLanguage: String?
        get() = overrideBacking
        set(value) {
            synchronized(lock) {
                val normalized = value?.takeIf { it.isNotBlank() }
                if (normalized != overrideBacking) {
                    overrideBacking = normalized
                    epoch++
                }
            }
        }

    fun onConfigurationChanged(config: Configuration?) {
        val next = config?.locales?.takeIf { !it.isEmpty }?.get(0)?.language?.takeIf { it.isNotBlank() }
        updateConfiguredLanguage(next)
    }

    internal fun updateConfiguredLanguage(language: String?) {
        val next = language?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (next != configLanguage) {
                configLanguage = next
                epoch++
            }
        }
    }

    fun resetForTest() {
        synchronized(lock) {
            overrideBacking = null
            configLanguage = null
            epoch++
        }
    }

    fun isZh(): Boolean {
        epoch
        val ov = overrideBacking?.takeIf { it.isNotBlank() }
            ?: System.getProperty("luma.lang")?.takeIf { it.isNotBlank() }
        if (ov != null) return ov.lowercase(Locale.ROOT).startsWith("zh")
        val cfg = configLanguage
        if (cfg != null) return cfg.lowercase(Locale.ROOT).startsWith("zh")
        return Locale.getDefault().language.lowercase(Locale.ROOT).startsWith("zh")
    }

    fun langCode(): String = if (isZh()) "zh" else "en"
}

fun tr(zh: String, en: String): String = if (LumaI18n.isZh()) zh else en
