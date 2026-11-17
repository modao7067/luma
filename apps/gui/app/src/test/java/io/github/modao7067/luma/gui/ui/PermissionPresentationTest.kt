package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import io.github.modao7067.luma.gui.session.CliCatalog
import io.github.modao7067.luma.gui.session.RequestChoice
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PermissionPresentationTest {
    @Test fun groupsRemainIndependentWhenNativeValuesOverlap() {
        val options = listOf(
            RequestChoice("default", "Default", configId = "permissions", configName = "Permissions", selected = true),
            RequestChoice("auto", "Auto", configId = "permissions", configName = "Permissions", selected = false),
            RequestChoice("default", "Build", configId = "workflow", configName = "Workflow", selected = false),
            RequestChoice("plan", "Plan", configId = "workflow", configName = "Workflow", selected = true),
        )
        val groups = permissionMenuGroups("CodeBuddy Code", options, false)
        assertEquals(listOf("workflow", "permissions"), groups.map { it.first().configId })
        assertEquals(listOf("plan", "default"), groups.first().map { it.id })
        assertEquals(listOf("default", "auto"), groups.last().map { it.id })
        assertEquals(listOf("Plan", "Default"), groups.flatten().filter { isPermissionSelected(it, "default") }.map { it.label })
        assertTrue(isPermissionSelected(RequestChoice("default", "Legacy"), "default"))
        assertFalse(isPermissionSelected(options[2], "default"))

        val workflowFirstYolo = listOf(
            RequestChoice("build", "Build", configId = "workflow", configName = "Workflow", selected = true),
            RequestChoice("plan", "Plan", configId = "workflow", configName = "Workflow", selected = false),
            RequestChoice("default", "Default", configId = "permissions", configName = "Permissions", selected = false),
            RequestChoice("bypassPermissions", "Bypass permissions", configId = "permissions", configName = "Permissions", selected = true),
        )
        val buildYoloBtn = composerPermissionButtonState("CodeBuddy Code", "build", workflowFirstYolo, false, false)
        assertEquals(MenuSymbol.WARNING, buildYoloBtn.symbol)
        assertTrue(buildYoloBtn.warning)
        assertFalse(buildYoloBtn.planHighlight)
        assertEquals("权限：Bypass permissions", buildYoloBtn.description)

        val planYoloOptions = workflowFirstYolo.map {
            when (it.id) {
                "build" -> it.copy(selected = false)
                "plan" -> it.copy(selected = true)
                else -> it
            }
        }
        val planYoloBtn = composerPermissionButtonState("CodeBuddy Code", "plan", planYoloOptions, false, false)
        assertEquals(MenuSymbol.PLAN, planYoloBtn.symbol)
        assertTrue(planYoloBtn.warning)
        assertFalse(planYoloBtn.planHighlight)
        assertEquals("权限：Plan · Bypass permissions", planYoloBtn.description)

        val duplicateIds = listOf(
            RequestChoice("off", "First off", configId = "first", configName = "First", selected = false),
            RequestChoice("on", "First on", configId = "first", configName = "First", selected = true),
            RequestChoice("off", "Second off", configId = "second", configName = "Second", selected = true),
            RequestChoice("on", "Second on", configId = "second", configName = "Second", selected = false),
        )
        val dupBtn = composerPermissionButtonState("Future CLI", "on", duplicateIds, false, false)
        assertEquals("权限：First on", dupBtn.description)

        val openCodeOptions = listOf(RequestChoice("build", "Build"), RequestChoice("plan", "Plan"))
        val openCodePlan = composerPermissionButtonState("OpenCode", "plan", openCodeOptions, false, false)
        assertEquals(MenuSymbol.PLAN, openCodePlan.symbol)
        assertTrue(openCodePlan.planHighlight)
        assertEquals("权限：Plan", openCodePlan.description)
    }
    @Test fun everyVerifiedNativeCatalogUsesTheSameSemanticSymbols() {
        val matrix = mapOf(
            "Codex" to mapOf(":read-only" to MenuSymbol.LOCK, ":workspace" to MenuSymbol.FOLDER, ":danger-full-access" to MenuSymbol.WARNING),
            "OpenCode" to mapOf("build" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN),
            "pi" to mapOf("" to MenuSymbol.UNKNOWN),
            "DeepSeek Harness" to mapOf("read-only" to MenuSymbol.LOCK, "workspace-write" to MenuSymbol.FOLDER, "danger-full-access" to MenuSymbol.WARNING, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN),
            "Kimi Code" to mapOf("default" to MenuSymbol.HAND, "manual" to MenuSymbol.HAND, "plan" to MenuSymbol.PLAN, "auto" to MenuSymbol.AUTO_ALLOW, "yolo" to MenuSymbol.WARNING),
            "Claude Code" to mapOf("default" to MenuSymbol.HAND, "manual" to MenuSymbol.HAND, "acceptEdits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "dontAsk" to MenuSymbol.BLOCK, "bypassPermissions" to MenuSymbol.WARNING),
            "CodeBuddy Code" to mapOf("default" to MenuSymbol.HAND, "acceptEdits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "dontAsk" to MenuSymbol.BLOCK, "bypassPermissions" to MenuSymbol.WARNING, "fullAccess" to MenuSymbol.WARNING),
            "Antigravity" to mapOf("default" to MenuSymbol.HAND, "accept-edits" to MenuSymbol.EDIT, "auto" to MenuSymbol.AUTO_ALLOW, "plan" to MenuSymbol.PLAN, "always-proceed" to MenuSymbol.WARNING),
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
        assertEquals(MenuSymbol.LOCK, permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = native.copy(sandbox = "read-only")).icon)
        assertEquals(MenuSymbol.BLOCK, permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = native.copy(sandbox = "read-only", approval = "never")).icon)
        assertEquals("read-only", permissionPresentation("read-only", cli = "DeepSeek Harness", option = RequestChoice("read-only", "read-only", sandbox = "read-only", approval = "ask")).title)
        assertEquals("workspace-write", permissionPresentation("workspace-write", cli = "DeepSeek Harness", option = RequestChoice("workspace-write", "workspace-write", sandbox = "workspace-write", approval = "ask")).title)
        assertEquals("Auto review", permissionPresentation("auto", cli = "DeepSeek Harness", option = RequestChoice("auto", "Auto review", sandbox = "danger-full-access", approval = "ask")).title)
        assertEquals("Plan mode", permissionPresentation("plan", cli = "DeepSeek Harness", option = RequestChoice("plan", "Plan mode", sandbox = "plan", approval = "ask")).title)
        assertEquals("danger-full-access", permissionPresentation("danger-full-access", cli = "DeepSeek Harness", option = RequestChoice("danger-full-access", "danger-full-access", sandbox = "danger-full-access", approval = "never")).title)
        val unknown = permissionPresentation("auto", cli = "DeepSeek Harness")
        assertTrue(unknown.warning); assertEquals(MenuSymbol.UNKNOWN, unknown.icon); assertTrue(unknown.description.contains("不能假定受限"))
    }

    @Test fun automaticReviewIsNotUnconditionalApprovalOrAutomaticDenial() {
        val auto = permissionPresentation("auto", cli = "Claude Code", option = RequestChoice("auto", "Auto"))
        assertEquals("Auto", auto.title); assertEquals(MenuSymbol.AUTO_ALLOW, auto.icon); assertTrue(auto.warning)
        val deny = permissionPresentation("dontAsk", cli = "Claude Code", option = RequestChoice("dontAsk", "Don't ask"))
        assertEquals("Don't ask", deny.title); assertEquals(MenuSymbol.BLOCK, deny.icon)
        assertEquals(MenuSymbol.PLAN, permissionPresentation("plan", cli = "Claude Code").icon)
        val bypass = permissionPresentation("bypassPermissions", cli = "Claude Code", option = RequestChoice("bypassPermissions", "Bypass permissions"))
        assertEquals("Bypass permissions", bypass.title); assertEquals(MenuSymbol.WARNING, bypass.icon); assertTrue(bypass.warning)
        val codeBuddyBypass = permissionPresentation("bypassPermissions", cli = "CodeBuddy Code", option = RequestChoice("bypassPermissions", "Bypass permissions"))
        val codeBuddyFull = permissionPresentation("fullAccess", cli = "CodeBuddy Code", option = RequestChoice("fullAccess", "Full access"))
        assertEquals("Bypass permissions", codeBuddyBypass.title); assertEquals(MenuSymbol.WARNING, codeBuddyBypass.icon); assertTrue(codeBuddyBypass.warning)
        assertEquals("Full access", codeBuddyFull.title); assertEquals(MenuSymbol.WARNING, codeBuddyFull.icon); assertTrue(codeBuddyFull.warning)
        val grok = permissionPresentation("always-approve", cli = "Grok Build", option = RequestChoice("always-approve", "Always approve"))
        assertEquals("Always approve", grok.title); assertEquals(MenuSymbol.WARNING, grok.icon); assertTrue(grok.warning)
        assertTrue(grok.description.contains("独立沙箱"))
        val grokAuto = permissionPresentation("auto", cli = "Grok Build", option = RequestChoice("auto", "Auto"))
        assertEquals("Auto", grokAuto.title); assertEquals(MenuSymbol.AUTO_ALLOW, grokAuto.icon); assertTrue(grokAuto.warning)
        val grokPlan = permissionPresentation("plan", cli = "Grok Build", option = RequestChoice("plan", "Plan"))
        assertEquals("Plan", grokPlan.title); assertEquals(MenuSymbol.PLAN, grokPlan.icon); assertFalse(grokPlan.warning)
        val kimiWebManual = permissionPresentation("manual", cli = "Kimi Code", option = RequestChoice("manual", "Always Ask", approval = "always"))
        assertEquals("Always Ask", kimiWebManual.title); assertEquals(MenuSymbol.HAND, kimiWebManual.icon); assertFalse(kimiWebManual.warning)
        val kimiWebYolo = permissionPresentation("yolo", cli = "Kimi Code", option = RequestChoice("yolo", "Ask When Needed", approval = "when_needed"))
        assertEquals("Ask When Needed", kimiWebYolo.title); assertEquals(MenuSymbol.AUTO_ALLOW, kimiWebYolo.icon); assertTrue(kimiWebYolo.warning)
        val kimiWebAuto = permissionPresentation("auto", cli = "Kimi Code", option = RequestChoice("auto", "Never Ask", approval = "never"))
        assertEquals("Never Ask", kimiWebAuto.title); assertEquals(MenuSymbol.WARNING, kimiWebAuto.icon); assertTrue(kimiWebAuto.warning)
    }

    @Test fun establishedStandaloneCodexProfilesAndNativeLabelsRemainUsable() {
        assertEquals(MenuSymbol.LOCK, permissionPresentation(":read-only").icon)
        assertEquals(MenuSymbol.FOLDER, permissionPresentation(":workspace").icon)
        assertTrue(permissionPresentation(":danger-full-access").warning)
        assertEquals("Native policy", permissionPresentation("custom", "Native policy").title)
        assertEquals("CLI 默认", permissionPresentation("").title)
        assertEquals("权限待同步", permissionPresentation("", cli = "Grok Build").title)
        assertEquals("权限待同步", permissionPresentation("", cli = "OpenCode").title)
        val openCodeAgents = OpenCodeProjection.agents(JSONArray("""[
            {"id":"build","name":"Build","mode":"primary","hidden":false,"description":"Default"},
            {"id":"plan","name":"Plan","mode":"primary","hidden":false,"description":"Read-only"},
            {"id":"explore","name":"Explore","mode":"subagent","hidden":false},
            {"id":"title","name":"Title","mode":"primary","hidden":true}
        ]"""))
        assertEquals(listOf("build", "plan"), openCodeAgents.map { it.id })
        assertEquals(listOf("plan", "build"), permissionMenuGroups("OpenCode", openCodeAgents, false).single().map { it.id })
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
