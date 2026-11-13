package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.CliCatalog
import io.github.modao7067.luma.gui.session.RequestChoice
import org.junit.Assert.*
import org.junit.Test

class PermissionPresentationTest {
    @Test fun everyVerifiedNativeCatalogUsesTheSameSemanticSymbols() {
        val matrix = mapOf(
            "Codex" to mapOf(":read-only" to MenuSymbol.HAND, ":workspace" to MenuSymbol.EDIT, ":danger-full-access" to MenuSymbol.WARNING),
            "OpenCode" to mapOf("opencode-native" to MenuSymbol.AUTO_ALLOW),
            "pi" to mapOf("" to MenuSymbol.UNKNOWN),
            "DeepSeek Harness" to mapOf("read-only" to MenuSymbol.HAND, "workspace-write" to MenuSymbol.EDIT, "danger-full-access" to MenuSymbol.WARNING, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN),
            "Kimi Code" to mapOf("default" to MenuSymbol.HAND, "plan" to MenuSymbol.PLAN, "auto" to MenuSymbol.AUTO_ALLOW, "yolo" to MenuSymbol.WARNING),
            "Claude Code" to mapOf("default" to MenuSymbol.HAND, "manual" to MenuSymbol.HAND, "acceptEdits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "dontAsk" to MenuSymbol.BLOCK, "bypassPermissions" to MenuSymbol.WARNING),
            "CodeBuddy Code" to mapOf("default" to MenuSymbol.HAND, "acceptEdits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "dontAsk" to MenuSymbol.BLOCK, "bypassPermissions" to MenuSymbol.WARNING, "fullAccess" to MenuSymbol.WARNING),
            "Antigravity" to mapOf("default" to MenuSymbol.HAND, "accept-edits" to MenuSymbol.EDIT, "plan" to MenuSymbol.PLAN, "always-proceed" to MenuSymbol.WARNING),
            "Qoder CLI" to mapOf("default" to MenuSymbol.HAND, "acceptEdits" to MenuSymbol.EDIT, "accept_edits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "dontAsk" to MenuSymbol.BLOCK, "dont_ask" to MenuSymbol.BLOCK, "yolo" to MenuSymbol.WARNING, "bypassPermissions" to MenuSymbol.WARNING, "bypass_permissions" to MenuSymbol.WARNING),
            "Grok Build" to mapOf("default" to MenuSymbol.HAND, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "always-approve" to MenuSymbol.WARNING, "bypassPermissions" to MenuSymbol.WARNING),
        )
        assertEquals(CliCatalog.ids.toSet(), matrix.keys)
        matrix.forEach { (cli, modes) -> modes.forEach { (id, symbol) ->
            val option = RequestChoice(id, "Native $id", sandbox = if (cli == "DeepSeek Harness") id else "", approval = "ask")
            val shown = permissionPresentation(id, cli = cli, option = option)
            assertEquals("$cli / $id", symbol, shown.icon)
            assertEquals("$cli / $id warning", symbol in setOf(MenuSymbol.WARNING, MenuSymbol.AUTO_ALLOW), shown.warning)
        } }
    }

    @Test fun unknownIdsAndProvidersNeverBorrowAnotherProvidersMeaning() {
        CliCatalog.ids.forEach { cli ->
            val shown = permissionPresentation("almost-danger-full-access", "Native policy", cli)
            assertEquals("$cli unknown", MenuSymbol.UNKNOWN, shown.icon)
            assertEquals("Native policy", shown.title)
        }
        listOf("pi", "Future CLI").forEach { cli ->
            listOf("auto", "plan", "dontAsk", "always-approve", ":danger-full-access", "opencode-native").forEach { id ->
                assertEquals("$cli / $id", MenuSymbol.UNKNOWN, permissionPresentation(id, cli = cli).icon)
            }
        }
        val option = RequestChoice("opaque", "自动批准", "Native description", "danger-full-access", "never")
        val shown = permissionPresentation(option.id, cli = "Future CLI", option = option)
        assertEquals(MenuSymbol.UNKNOWN, shown.icon); assertFalse(shown.warning)
        assertEquals(option.label, shown.title); assertEquals(option.description, shown.description)
        assertEquals(MenuSymbol.UNKNOWN, permissionPresentation("yolo", cli = "Antigravity").icon)
    }

    @Test fun dshUsesActualMetadataRatherThanItsPresetName() {
        val native = RequestChoice("native-full", "Deployment full", "Native policy", "danger-full-access", "ask")
        val shown = permissionPresentation(native.id, cli = "DeepSeek Harness", option = native)
        assertEquals("Deployment full", shown.title)
        assertTrue(shown.warning); assertEquals(MenuSymbol.WARNING, shown.icon)
        assertTrue(shown.description.contains("不使用文件沙箱")); assertTrue(shown.description.contains("按原生规则请求审批"))
        assertFalse(shown.description.contains("不再逐次"))
        assertTrue(permissionPresentation("native-full", cli = "DeepSeek Harness", option = native.copy(approval = "never")).description.contains("工具不再逐次请求审批"))
        assertEquals(MenuSymbol.HAND, permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = native.copy(sandbox = "read-only")).icon)
        assertEquals(MenuSymbol.BLOCK, permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = native.copy(sandbox = "read-only", approval = "never")).icon)
        assertEquals("逐次审批", permissionPresentation("read-only", cli = "DeepSeek Harness", option = RequestChoice("read-only", "read-only", sandbox = "read-only", approval = "ask")).title)
        assertEquals("自动接受编辑", permissionPresentation("workspace-write", cli = "DeepSeek Harness", option = RequestChoice("workspace-write", "workspace-write", sandbox = "workspace-write", approval = "ask")).title)
        assertEquals("自动审批", permissionPresentation("auto", cli = "DeepSeek Harness", option = RequestChoice("auto", "Auto review", sandbox = "danger-full-access", approval = "ask")).title)
        assertEquals("规划模式", permissionPresentation("plan", cli = "DeepSeek Harness", option = RequestChoice("plan", "Plan mode", sandbox = "plan", approval = "ask")).title)
        assertEquals("跳过审批", permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = RequestChoice("danger-full-access", "danger-full-access", sandbox = "danger-full-access", approval = "never")).title)
        val unknown = permissionPresentation("auto", cli = "DeepSeek Harness")
        assertTrue(unknown.warning); assertEquals(MenuSymbol.UNKNOWN, unknown.icon); assertTrue(unknown.description.contains("不能假定受限"))
    }

    @Test fun automaticReviewIsNotUnconditionalApprovalOrAutomaticDenial() {
        val auto = permissionPresentation("auto", cli = "Claude Code")
        assertEquals("自动审批", auto.title); assertEquals(MenuSymbol.AUTO_ALLOW, auto.icon); assertTrue(auto.warning)
        val deny = permissionPresentation("dontAsk", cli = "Claude Code")
        assertEquals("不询问（自动拒绝）", deny.title); assertEquals(MenuSymbol.BLOCK, deny.icon)
        assertEquals(MenuSymbol.PLAN, permissionPresentation("plan", cli = "Claude Code").icon)
        val bypass = permissionPresentation("bypassPermissions", cli = "Claude Code")
        assertEquals("跳过审批", bypass.title); assertEquals(MenuSymbol.WARNING, bypass.icon); assertTrue(bypass.warning)
        val grok = permissionPresentation("always-approve", cli = "Grok Build", option = RequestChoice("always-approve", "自动批准"))
        assertEquals("跳过审批", grok.title); assertEquals(MenuSymbol.WARNING, grok.icon); assertTrue(grok.warning)
        assertTrue(grok.description.contains("独立沙箱"))
        val grokAuto = permissionPresentation("auto", cli = "Grok Build", option = RequestChoice("auto", "自动审批"))
        assertEquals("自动审批", grokAuto.title); assertEquals(MenuSymbol.AUTO_ALLOW, grokAuto.icon); assertTrue(grokAuto.warning)
        val grokPlan = permissionPresentation("plan", cli = "Grok Build", option = RequestChoice("plan", "规划模式"))
        assertEquals("规划模式", grokPlan.title); assertEquals(MenuSymbol.PLAN, grokPlan.icon); assertFalse(grokPlan.warning)
    }

    @Test fun establishedStandaloneCodexProfilesAndNativeLabelsRemainUsable() {
        assertEquals(MenuSymbol.HAND, permissionPresentation(":read-only").icon)
        assertEquals(MenuSymbol.EDIT, permissionPresentation(":workspace").icon)
        assertTrue(permissionPresentation(":danger-full-access").warning)
        assertEquals("Native policy", permissionPresentation("custom", "Native policy").title)
        assertEquals("CLI 默认", permissionPresentation("").title)
        assertEquals("权限待同步", permissionPresentation("", cli = "Grok Build").title)
    }

    @Test fun multilineCommandPermissionTitlesCompactHeaderAndDeduplicateSingleCommandJsonBody() {
        val multilineCmd = "python3 -c '\nimport json\npath = \"/root/.gemini/antigravity-cli/brain/31246312/transcript.jsonl\"\nprint(path)\n'"
        val rawInputJson = org.json.JSONObject().put("CommandLine", multilineCmd).toString()
        val (header, detailBody) = requestDialogPresentation(multilineCmd, rawInputJson)
        assertEquals("python3 -c '…", header)
        assertFalse(header.contains("\n"))
        assertEquals("```\n$multilineCmd\n```", detailBody)

        val (shortHeader, shortBody) = requestDialogPresentation("命令需要批准", "echo ok")
        assertEquals("命令需要批准", shortHeader)
        assertEquals("echo ok", shortBody)
    }
}
