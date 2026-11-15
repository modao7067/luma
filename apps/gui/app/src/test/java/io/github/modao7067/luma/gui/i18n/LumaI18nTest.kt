package io.github.modao7067.luma.gui.i18n

import com.termux.terminal.LumaLang
import io.github.modao7067.luma.gui.session.Metric
import io.github.modao7067.luma.gui.session.SessionRecord
import io.github.modao7067.luma.gui.session.SessionTitlePolicy
import io.github.modao7067.luma.gui.ui.ProcessCategory
import io.github.modao7067.luma.gui.ui.dashboardMetric
import io.github.modao7067.luma.gui.ui.dashboardMetrics
import io.github.modao7067.luma.gui.ui.reasoningCaption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class LumaI18nTest {
    private lateinit var savedLocale: Locale
    private var savedProp: String? = null

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedProp = System.getProperty("luma.lang")
        System.clearProperty("luma.lang")
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        if (savedProp == null) {
            System.clearProperty("luma.lang")
        } else {
            System.setProperty("luma.lang", savedProp!!)
        }
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
    }

    @Test
    fun switchesBySystemLocaleBetweenChineseAndEnglish() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        LumaI18n.resetForTest()
        assertTrue(LumaI18n.isZh())
        assertEquals("zh", LumaI18n.langCode())
        assertEquals("设置", tr("设置", "Settings"))
        assertTrue(LumaLang.isZh())
        assertEquals("zh", LumaLang.code())

        Locale.setDefault(Locale.TRADITIONAL_CHINESE)
        LumaI18n.resetForTest()
        assertTrue(LumaI18n.isZh())
        assertEquals("zh", LumaI18n.langCode())

        Locale.setDefault(Locale.US)
        LumaI18n.resetForTest()
        LumaLang.setOverride("en")
        assertFalse(LumaI18n.isZh())
        assertEquals("en", LumaI18n.langCode())
        assertEquals("Settings", tr("设置", "Settings"))
        assertFalse(LumaLang.isZh())
        assertEquals("en", LumaLang.code())

        Locale.setDefault(Locale.JAPAN)
        LumaI18n.resetForTest()
        assertFalse(LumaI18n.isZh())
        assertEquals("en", LumaI18n.langCode())
    }

    @Test
    fun dashboardMetricsNormalizeBilingualLabels() {
        val raw = listOf(
            Metric("Context", "12500 / 200000 · 6.3%"),
            Metric("累计 · 输入缓存命中", "8000 / 10000 · 80%"),
            Metric("CLI recorded cost · USD", "$0.042"),
            Metric("Account balance · OpenAI", "$18.50"),
        )

        Locale.setDefault(Locale.US)
        LumaI18n.resetForTest()
        val enResult = dashboardMetrics(raw)
        assertEquals(
            listOf("Context", "Cache hit", "Cost", "Account balance · OpenAI"),
            enResult.map { it.label },
        )
        assertEquals("$0.042", dashboardMetric(Metric("CLI 累计估算 · USD", "$0.042")).value)

        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        LumaI18n.resetForTest()
        val zhResult = dashboardMetrics(raw)
        assertEquals(
            listOf("上下文", "缓存命中", "费用", "账户余额 · OpenAI"),
            zhResult.map { it.label },
        )
    }

    @Test
    fun sessionTitleAndProcessLabelsFollowActiveLocale() {
        Locale.setDefault(Locale.US)
        LumaI18n.resetForTest()
        assertEquals("Untitled session", SessionTitlePolicy.unnamed)
        assertTrue(SessionTitlePolicy.isUnnamed("未命名会话"))
        assertTrue(SessionTitlePolicy.isUnnamed("Untitled session"))
        assertEquals("Thinking", reasoningCaption("running"))
        assertEquals("Terminal", ProcessCategory.Terminal.caption)

        val record = SessionRecord(id = "s1", title = "未命名会话", environment = "noroot", cwd = "/tmp")
        SessionTitlePolicy.refresh(record)
        assertEquals("Untitled session", record.title)

        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        LumaI18n.resetForTest()
        SessionTitlePolicy.refresh(record)
        assertEquals("未命名会话", record.title)
        assertEquals("思考中", reasoningCaption("running"))
        assertEquals("终端", ProcessCategory.Terminal.caption)
    }

    @Test
    fun overrideLanguageAndSystemPropertyTakePrecedenceOverConfigurationAndDefault() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        LumaI18n.resetForTest()
        LumaI18n.onConfigurationChanged(null)
        assertTrue(LumaI18n.isZh())

        LumaI18n.updateConfiguredLanguage("en")
        assertFalse(LumaI18n.isZh())
        assertEquals("en", LumaI18n.langCode())

        System.setProperty("luma.lang", "zh-TW")
        assertTrue(LumaI18n.isZh())
        assertEquals("zh", LumaI18n.langCode())

        LumaI18n.overrideLanguage = "en-US"
        assertFalse(LumaI18n.isZh())
        assertEquals("en", LumaI18n.langCode())

        LumaI18n.overrideLanguage = "  "
        assertTrue(LumaI18n.isZh())

        System.clearProperty("luma.lang")
        assertFalse(LumaI18n.isZh())

        LumaI18n.updateConfiguredLanguage(null)
        assertTrue(LumaI18n.isZh())
    }
}
