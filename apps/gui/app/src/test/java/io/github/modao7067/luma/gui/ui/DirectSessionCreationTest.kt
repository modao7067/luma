package io.github.modao7067.luma.gui.ui

import java.lang.reflect.Proxy
import io.github.modao7067.luma.gui.session.CliCatalog
import io.github.modao7067.luma.gui.session.CockpitActions
import org.junit.Assert.*
import org.junit.Test

class DirectSessionCreationTest {
    private fun recording(calls: MutableList<List<Any?>>) = Proxy.newProxyInstance(
        CockpitActions::class.java.classLoader, arrayOf(CockpitActions::class.java),
    ) { _, method, args ->
        if (method.name == "getEnvironment") return@newProxyInstance io.github.modao7067.luma.gui.GuiProduct.environment
        check(method.name == "createCliSession") { "Unexpected action: ${method.name}" }
        calls += args!!.toList()
        null
    } as CockpitActions

    @Test fun initialEntryUsesCodexNativeDefaults() {
        val calls = mutableListOf<List<Any?>>()
        recording(calls).createDefaultCliSession()
        assertEquals(listOf(listOf("Codex", "", io.github.modao7067.luma.gui.GuiProduct.environment, "/workspace", "")), calls)
    }

    @Test fun explicitEntryUsesItsOwnCliAndOneCallPerPress() {
        val calls = mutableListOf<List<Any?>>()
        val actions = recording(calls)
        CliCatalog.ids.forEach(actions::createDefaultCliSession)
        actions.createDefaultCliSession("OpenCode")
        assertEquals(CliCatalog.ids + "OpenCode", calls.map { it[0] })
        assertEquals(CliCatalog.ids.size + 1, calls.size)
        calls.forEach { assertEquals(listOf("", io.github.modao7067.luma.gui.GuiProduct.environment, "/workspace", ""), it.drop(1)) }
    }
}
