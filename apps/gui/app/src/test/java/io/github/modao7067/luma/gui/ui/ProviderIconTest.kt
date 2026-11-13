package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.R
import io.github.modao7067.luma.gui.session.CliCatalog
import org.junit.Assert.*
import org.junit.Test

class ProviderIconTest {
    @Test fun everyRegisteredCliHasItsOwnProviderMark() {
        val icons = CliCatalog.ids.map(::providerIconResource)
        assertEquals(CliCatalog.ids.size, icons.distinct().size)
        assertFalse(icons.contains(R.drawable.ic_menu_terminal))
        assertEquals(R.drawable.ic_provider_pi, providerIconResource("pi"))
        assertEquals(R.drawable.ic_provider_grok, providerIconResource("Grok Build"))
        assertEquals(R.drawable.ic_provider_codebuddy, providerIconResource("CodeBuddy Code"))
    }
    @Test fun unknownProviderKeepsAnHonestGenericFallback() {
        assertEquals(R.drawable.ic_menu_terminal, providerIconResource("future-cli"))
    }
    @Test fun failuresOnlyExposeTheRequestedShortLabel() {
        assertEquals("无法获取插件", pluginFetchErrorLabel("IOException: private error detail"))
        assertEquals("无法获取 MCP", pluginFetchErrorLabel("MCP 读取失败：private error detail"))
        assertEquals("无法获取 MCP", pluginFetchErrorLabel("MCP：此 CLI 协议未提供配置清单；保留原生配置。"))
        assertEquals("无法获取插件", inventoryFetchErrorLabel("Skill"))
        assertEquals("无法获取 MCP", inventoryFetchErrorLabel("MCP"))
    }
    @Test fun longCliDisplayLabelsAreShortenedInSidebarAndSettings() {
        assertEquals("Pi", cliDisplayLabel("pi"))
        assertEquals("Deepseek", cliDisplayLabel("DeepSeek Harness"))
        assertEquals("CodeBuddy", cliDisplayLabel("CodeBuddy Code"))
        assertEquals("Qoder", cliDisplayLabel("Qoder CLI"))
        assertEquals("Claude Code", cliDisplayLabel("Claude Code"))
    }
}
