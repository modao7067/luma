package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.RequestChoice

internal enum class MenuSymbol { TERMINAL, BROWSER, SEARCH, FOLDER, SKILL, EXTENSION, PLUGINS, MCP, NEW_CHAT, IMAGE, CAMERA, PASTE, FILE, CODE, DATA, TABLE, ARTICLE, TEXT, DOWNLOAD, OPEN_EXTERNAL, PDF, AUDIO, VIDEO, APK, ARCHIVE, COMPRESS, DIFF, SEND, STOP, REFRESH, EDIT, DELETE, MODEL, THINKING, SPEED, LOCK, WARNING, SETTINGS, BATTERY, NOTIFICATIONS, ACCESSIBILITY, CHECK, DOWN, UP, HAND, SHIELD, PLAN, BLOCK, RULES, UNKNOWN, AUTO_ALLOW }
internal data class PermissionPresentation(val title: String, val description: String, val icon: MenuSymbol, val warning: Boolean = false)

private enum class PermissionMeaning(val icon: MenuSymbol, val warning: Boolean = false) {
    REVIEW(MenuSymbol.HAND),
    EDIT(MenuSymbol.EDIT),
    AUTO_REVIEW(MenuSymbol.AUTO_ALLOW, true),
    PLAN(MenuSymbol.PLAN),
    DENY_UNAPPROVED(MenuSymbol.BLOCK),
    BYPASS(MenuSymbol.WARNING, true),
    UNKNOWN(MenuSymbol.UNKNOWN),
}

private fun nativePermissionMeaning(cli: String, id: String): PermissionMeaning = when (cli) {
    "Codex", "" -> when (id) {
        ":read-only" -> PermissionMeaning.REVIEW
        ":workspace" -> PermissionMeaning.EDIT
        ":danger-full-access" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "Claude Code" -> when (id) {
        "default", "manual" -> PermissionMeaning.REVIEW
        "acceptEdits" -> PermissionMeaning.EDIT
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "dontAsk" -> PermissionMeaning.DENY_UNAPPROVED
        "bypassPermissions" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "CodeBuddy Code" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "acceptEdits" -> PermissionMeaning.EDIT
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "dontAsk" -> PermissionMeaning.DENY_UNAPPROVED
        "bypassPermissions", "fullAccess" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "Kimi Code" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "yolo" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "Qoder CLI" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "acceptEdits", "accept_edits" -> PermissionMeaning.EDIT
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "dontAsk", "dont_ask" -> PermissionMeaning.DENY_UNAPPROVED
        "yolo", "bypassPermissions", "bypass_permissions" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "Grok Build" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "always-approve", "bypassPermissions" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    "OpenCode" -> if (id == "opencode-native") PermissionMeaning.AUTO_REVIEW else PermissionMeaning.UNKNOWN
    "Antigravity" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "accept-edits" -> PermissionMeaning.EDIT
        "plan" -> PermissionMeaning.PLAN
        "always-proceed" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    else -> PermissionMeaning.UNKNOWN
}

private val genericEnglishPermissionLabels = setOf(
    "default", "manual", "accept edits", "acceptedits", "accept_edits", "accept-edits",
    "auto edit", "auto-edit", "auto", "auto review", "plan", "plan mode",
    "don't ask", "dont ask", "dontask", "dont_ask",
    "bypass permissions", "bypasspermissions", "bypass_permissions", "full access", "fullaccess",
    "yolo", "always-proceed", "always-approve", "allow-all", "on", "off",
    "read-only", "workspace-write", "danger-full-access",
)

private fun canonicalMeaningTitle(meaning: PermissionMeaning): String? = when (meaning) {
    PermissionMeaning.REVIEW -> tr("逐次审批", "Ask each time")
    PermissionMeaning.EDIT -> tr("自动接受编辑", "Auto-accept edits")
    PermissionMeaning.AUTO_REVIEW -> tr("自动审批", "Auto-approve")
    PermissionMeaning.PLAN -> tr("规划模式", "Plan mode")
    PermissionMeaning.DENY_UNAPPROVED -> tr("不询问（自动拒绝）", "Don't ask (auto-deny)")
    PermissionMeaning.BYPASS -> tr("跳过审批", "Bypass approvals")
    PermissionMeaning.UNKNOWN -> null
}

internal fun permissionPresentation(id: String, nativeLabel: String = "", cli: String = "", option: RequestChoice? = null): PermissionPresentation {
    if (id.isEmpty()) return if (cli in setOf("Codex", "Claude Code", "CodeBuddy Code", "DeepSeek Harness", "Kimi Code", "Qoder CLI", "Grok Build", "Antigravity")) {
        PermissionPresentation(tr("权限待同步", "Syncing permissions"), "", MenuSymbol.REFRESH)
    } else PermissionPresentation(tr("CLI 默认", "CLI default"), tr("使用 CLI 当前配置", "Use current CLI configuration"), MenuSymbol.UNKNOWN)

    if (cli == "DeepSeek Harness") {
        val sandbox = option?.sandbox.orEmpty()
        val approval = option?.approval.orEmpty()
        val isAutoReview = id == "auto" && sandbox in setOf("auto", "danger-full-access") && approval == "ask"
        val boundary = when {
            sandbox == "plan" -> tr("原生计划模式（只读探索，执行前需审批计划）", "Native plan mode (read-only exploration; approve plan before execution)")
            sandbox == "read-only" -> tr("原生只读沙箱", "Native read-only sandbox")
            sandbox == "workspace-write" -> tr("原生工作区写入沙箱", "Native workspace-write sandbox")
            isAutoReview || sandbox == "danger-full-access" -> tr("不使用文件沙箱", "No filesystem sandbox")
            else -> tr("沙箱组合未由原生目录声明，不能假定受限", "Sandbox mode not declared by native catalog; cannot assume restricted")
        }
        val review = when {
            sandbox == "plan" -> tr("调用 exit_plan_mode 提交计划审批", "Calls exit_plan_mode for plan approval")
            isAutoReview -> tr("由原生模型自动审查风险（高风险自动拦截）", "Native model reviews risk automatically (blocks high-risk actions)")
            approval == "ask" -> tr("按原生规则请求审批", "Prompts for approval per native rules")
            approval == "never" -> tr("工具不再逐次请求审批", "Tools run without per-call prompts")
            else -> tr("审批组合未由原生目录声明", "Approval policy not declared by native catalog")
        }
        val meaning = when {
            sandbox == "plan" -> PermissionMeaning.PLAN
            isAutoReview -> PermissionMeaning.AUTO_REVIEW
            sandbox == "read-only" && approval == "never" -> PermissionMeaning.DENY_UNAPPROVED
            sandbox == "read-only" -> PermissionMeaning.REVIEW
            sandbox == "workspace-write" -> PermissionMeaning.EDIT
            sandbox == "danger-full-access" -> PermissionMeaning.BYPASS
            else -> PermissionMeaning.UNKNOWN
        }
        val rawLabel = option?.label?.takeIf(String::isNotBlank) ?: nativeLabel.takeIf(String::isNotBlank)
        val standardDshPreset = id in setOf("read-only", "workspace-write", "auto", "plan", "danger-full-access")
        val title = if (standardDshPreset || rawLabel == null || rawLabel == id || rawLabel.lowercase() in genericEnglishPermissionLabels) {
            canonicalMeaningTitle(meaning) ?: rawLabel ?: id
        } else {
            rawLabel
        }
        return PermissionPresentation(
            title,
            listOf(boundary, review, option?.description.orEmpty()).filter { it.isNotBlank() }.joinToString(tr("；", "; ")),
            meaning.icon,
            meaning.warning || sandbox.isEmpty(),
        )
    }

    val meaning = nativePermissionMeaning(cli, id)
    val rawLabel = option?.label?.takeIf { it.isNotBlank() && it != id } ?: nativeLabel.takeIf(String::isNotBlank)
    val title = canonicalMeaningTitle(meaning) ?: rawLabel ?: id
    val description = option?.description?.takeIf(String::isNotBlank) ?: when {
        (cli == "Codex" || cli.isEmpty()) && id == ":read-only" -> tr("仅允许读取；写入与命令受 CLI 权限限制", "Read-only; writes and commands restricted by CLI permissions")
        (cli == "Codex" || cli.isEmpty()) && id == ":workspace" -> tr("允许工作区内读写；外部访问受 CLI 配置限制", "Read/write inside workspace; external access governed by CLI config")
        (cli == "Codex" || cli.isEmpty()) && id == ":danger-full-access" -> tr("不受沙箱限制，风险较高", "Unrestricted access without sandbox; use with caution")
        cli == "OpenCode" && id == "opencode-native" -> tr("由当前 Agent 与会话规则共同决定", "Governed by active agent and session rules")
        cli == "Grok Build" && id == "always-approve" -> tr("自动批准普通工具请求；原生拒绝规则、钩子与独立沙箱配置仍适用", "Auto-approves standard tool calls; native deny rules, hooks, and sandbox still apply")
        else -> ""
    }
    return PermissionPresentation(title, description, meaning.icon, meaning.warning)
}
