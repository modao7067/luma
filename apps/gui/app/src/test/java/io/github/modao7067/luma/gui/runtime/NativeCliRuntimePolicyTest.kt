package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.LumaI18n
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

class NativeCliRuntimePolicyTest {
    private lateinit var savedLocale: Locale
    private var savedLangProp: String? = null

    @Before fun setUp() {
        savedLocale = Locale.getDefault()
        savedLangProp = System.getProperty("luma.lang")
        System.clearProperty("luma.lang")
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        LumaI18n.resetForTest()
    }

    @After fun tearDown() {
        Locale.setDefault(savedLocale)
        if (savedLangProp == null) System.clearProperty("luma.lang")
        else System.setProperty("luma.lang", savedLangProp!!)
        LumaI18n.resetForTest()
    }

    @Test fun newAcpPeersHaveAnIndependentStableTaskWithoutMovingExistingCliTasks() {
        assertEquals("cli-bridge-v71", NativeCliRuntimePolicy.revision)
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v38"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v55"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v56"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v57"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v58"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v59"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v60"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v61"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v62"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v63"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v64"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v65"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v66"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v68"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v69"}""")))
        assertTrue(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v70"}""")))
        assertFalse(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject("""{"carrierRevision":"cli-bridge-v71"}""")))
        assertFalse(NativeCliRuntimePolicy.hasCarrierUpdate(JSONObject()))
        assertTrue(NativeCliRuntimePolicy.isolatedAcpClis.contains("Grok Build"))
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { assertEquals("luma-acp-cli-gui", NativeCliRuntimePolicy.task(it)) }
        listOf("pi", "DeepSeek Harness").forEach { assertEquals("luma-native-cli-gui", NativeCliRuntimePolicy.task(it)) }
        listOf("Kimi Code", "Claude Code").forEach { assertEquals("luma-native-cli-gui-v3", NativeCliRuntimePolicy.task(it)) }
        assertEquals("luma-antigravity-gui", NativeCliRuntimePolicy.task("Antigravity"))
    }
    @Test fun healthRequiresProtocolAndExactAdvertisedNameBeforeConnect() {
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { cli ->
            NativeCliRuntimePolicy.acceptHealth(cli, JSONObject().put("protocol", 1).put("clis", org.json.JSONArray().put(cli)).put("carrierRevision", NativeCliRuntimePolicy.revision))
            listOf(JSONObject(), JSONObject("""{"protocol":1,"clis":["pi","Copilot","Qwen"]}"""),
                JSONObject().put("protocol", 2).put("clis", org.json.JSONArray().put(cli))).forEach { health ->
                assertThrows(IllegalStateException::class.java) { NativeCliRuntimePolicy.acceptHealth(cli, health) }
            }
        }
    }
    @Test fun onlyGrokRequiresTheUpdatedCarrierAndNeverSilentlyUsesAnOldRunningService() {
        fun health(cli: String) = JSONObject().put("protocol", 1).put("clis", org.json.JSONArray().put(cli))
        NativeCliRuntimePolicy.isolatedAcpClis.filter { it != "Grok Build" }.forEach {
            NativeCliRuntimePolicy.acceptHealth(it, health(it))
        }
        listOf("", "cli-bridge-v17", "cli-bridge-v18-staging", "18", "cli-bridge-v999999999999999999").forEach { old ->
            val failure = assertThrows(NativeCliRuntimePolicy.CarrierUpdateRequired::class.java) {
                NativeCliRuntimePolicy.acceptHealth("Grok Build", health("Grok Build").put("carrierRevision", old))
            }
            assertEquals("需更新CLI服务；当前任务结束后重启", failure.message)
        }
        assertThrows(NativeCliRuntimePolicy.CarrierUpdateRequired::class.java) {
            NativeCliRuntimePolicy.acceptHealth("Grok Build", health("pi"))
        }
        listOf("cli-bridge-v18", NativeCliRuntimePolicy.revision).forEach { compatible ->
            NativeCliRuntimePolicy.acceptHealth("Grok Build", health("Grok Build").put("carrierRevision", compatible))
        }
        assertEquals("luma-acp-cli-gui", NativeCliRuntimePolicy.task("Grok Build"))
    }

    @Test fun codeBuddyIsIndependentAndDoesNotChangeTheSharedGrokMaintenanceScope() {
        assertEquals("luma-codebuddy-gui-v1", NativeCliRuntimePolicy.task("CodeBuddy Code"))
        assertFalse(NativeCliRuntimePolicy.isolatedAcpClis.contains("CodeBuddy Code"))
        assertTrue(NativeCliRuntimePolicy.requiresHealthValidation("CodeBuddy Code"))
        NativeCliRuntimePolicy.acceptHealth("CodeBuddy Code", JSONObject("""{"protocol":1,"clis":["CodeBuddy Code"],"carrierRevision":"cli-bridge-v19"}"""))
        assertThrows(IllegalStateException::class.java) {
            NativeCliRuntimePolicy.acceptHealth("CodeBuddy Code", JSONObject("""{"protocol":1,"clis":["CodeBuddy"]}"""))
        }
        NativeCliRuntimePolicy.requireRestartConsent("CodeBuddy Code", true)
    }

    @Test fun maintenanceRequiresSeparateExplicitConsentAndDoesNotChangeNormalTaskIdentity() {
        assertThrows(IllegalArgumentException::class.java) { NativeCliRuntimePolicy.requireRestartConsent("Grok Build", false) }
        io.github.modao7067.luma.gui.session.CliCatalog.bridged.forEach { cli ->
            NativeCliRuntimePolicy.requireRestartConsent(cli, true)
            assertThrows(IllegalArgumentException::class.java) { NativeCliRuntimePolicy.requireRestartConsent(cli, false) }
        }
        listOf("Codex", "OpenCode", "ShellService", "unknown").forEach { cli ->
            assertThrows(IllegalArgumentException::class.java) { NativeCliRuntimePolicy.requireRestartConsent(cli, true) }
        }
        NativeCliRuntimePolicy.requireRestartConsent("Grok Build", true)
        assertTrue(NativeCliRuntimePolicy.restartWarning.contains("所有正在进行的任务"))
        assertTrue(NativeCliRuntimePolicy.restartWarning.contains("无法确认"))
        assertTrue(NativeCliRuntimePolicy.restartWarning.contains("记录和草稿保留"))
        assertEquals("luma-acp-cli-gui", NativeCliRuntimePolicy.task("Grok Build"))

        LumaI18n.overrideLanguage = "en"
        val enFailure = assertThrows(NativeCliRuntimePolicy.CarrierUpdateRequired::class.java) {
            NativeCliRuntimePolicy.acceptHealth(
                "Grok Build",
                JSONObject().put("protocol", 1).put("clis", org.json.JSONArray().put("Grok Build")).put("carrierRevision", "cli-bridge-v17"),
            )
        }
        assertEquals("CLI service update required; restart after the current task finishes", enFailure.message)
        assertTrue(NativeCliRuntimePolicy.restartWarning.contains("active tasks"))
        assertTrue(NativeCliRuntimePolicy.restartWarning.contains("Session history and drafts are preserved"))
    }

}
