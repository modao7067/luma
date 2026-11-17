package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.RequestChoice

internal enum class MenuSymbol { TERMINAL, BROWSER, SEARCH, FOLDER, SKILL, EXTENSION, PLUGINS, MCP, NEW_CHAT, IMAGE, CAMERA, PASTE, FILE, CODE, DATA, TABLE, ARTICLE, TEXT, DOWNLOAD, OPEN_EXTERNAL, PDF, AUDIO, VIDEO, APK, ARCHIVE, COMPRESS, DIFF, SEND, STOP, REFRESH, EDIT, DELETE, MODEL, THINKING, SPEED, LOCK, WARNING, SETTINGS, BATTERY, NOTIFICATIONS, ACCESSIBILITY, CHECK, DOWN, UP, HAND, SHIELD, PLAN, BLOCK, RULES, UNKNOWN, AUTO_ALLOW }
internal data class PermissionPresentation(val title: String, val description: String, val icon: MenuSymbol, val warning: Boolean = false)
internal data class ComposerPermissionButtonState(
    val symbol: MenuSymbol,
    val warning: Boolean,
    val planHighlight: Boolean,
    val description: String,
)

private enum class PermissionMeaning(val icon: MenuSymbol, val warning: Boolean = false) {
    READ_ONLY(MenuSymbol.LOCK),
    WORKSPACE(MenuSymbol.FOLDER),
    RULES(MenuSymbol.RULES),
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
        ":read-only" -> PermissionMeaning.READ_ONLY
        ":workspace" -> PermissionMeaning.WORKSPACE
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
        "default", "manual" -> PermissionMeaning.REVIEW
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
    "OpenCode" -> when (id) {
        "plan" -> PermissionMeaning.PLAN
        "build" -> PermissionMeaning.AUTO_REVIEW
        else -> PermissionMeaning.UNKNOWN
    }
    "Antigravity" -> when (id) {
        "default" -> PermissionMeaning.REVIEW
        "accept-edits" -> PermissionMeaning.EDIT
        "auto" -> PermissionMeaning.AUTO_REVIEW
        "plan" -> PermissionMeaning.PLAN
        "always-proceed" -> PermissionMeaning.BYPASS
        else -> PermissionMeaning.UNKNOWN
    }
    else -> PermissionMeaning.UNKNOWN
}

internal fun isPlanPermissionOption(cli: String, option: RequestChoice): Boolean =
    nativePermissionMeaning(cli, option.id) == PermissionMeaning.PLAN

internal fun isPermissionSelected(option: RequestChoice, permission: String): Boolean =
    option.selected ?: (option.id == permission)

internal fun permissionMenuGroups(cli: String, options: List<RequestChoice>, independentPlan: Boolean): List<List<RequestChoice>> {
    val groups = options.groupBy { it.configId }.values.toList()
    if (independentPlan) return groups
    return groups.map { group -> group.sortedBy { !isPlanPermissionOption(cli, it) } }
        .sortedBy { group -> group.none { isPlanPermissionOption(cli, it) } }
}

internal fun composerPermissionButtonState(
    cli: String,
    permission: String,
    permissions: List<RequestChoice>,
    planModeSupported: Boolean,
    planMode: Boolean,
    skipPermissionsActive: Boolean = false,
): ComposerPermissionButtonState {
    val selectedOptions = permissions.filter { isPermissionSelected(it, permission) }
    val optionPlanSelected = !planModeSupported && selectedOptions.any { isPlanPermissionOption(cli, it) }
    val isPlanActive = planMode || optionPlanSelected
    val nonPlanSelected = selectedOptions.filterNot { isPlanPermissionOption(cli, it) }
    val primaryOption = nonPlanSelected.maxByOrNull { option ->
        val pres = permissionPresentation(option.id, option.label, cli, option)
        val knownMeaning = nativePermissionMeaning(cli, option.id) != PermissionMeaning.UNKNOWN ||
            (cli == "DeepSeek Harness" && option.sandbox.isNotBlank())
        when {
            pres.warning -> 3
            knownMeaning -> 2
            option.id == permission -> 1
            else -> 0
        }
    } ?: selectedOptions.firstOrNull { it.id == permission } ?: selectedOptions.firstOrNull()
    val primaryPresentation = permissionPresentation(
        primaryOption?.id ?: permission,
        primaryOption?.label.orEmpty(),
        cli,
        primaryOption,
    )
    val anyWarning = if (selectedOptions.isNotEmpty()) {
        selectedOptions.any { permissionPresentation(it.id, it.label, cli, it).warning } || skipPermissionsActive
    } else {
        primaryPresentation.warning || skipPermissionsActive
    }
    val planHighlight = isPlanActive && !anyWarning
    val symbol = if (isPlanActive) MenuSymbol.PLAN else primaryPresentation.icon
    val description = if (planMode || (optionPlanSelected && nonPlanSelected.isNotEmpty())) {
        tr("权限：Plan · ${primaryPresentation.title}", "Permissions: Plan · ${primaryPresentation.title}")
    } else {
        tr("权限：${primaryPresentation.title}", "Permissions: ${primaryPresentation.title}")
    }
    return ComposerPermissionButtonState(
        symbol = symbol,
        warning = anyWarning,
        planHighlight = planHighlight,
        description = description,
    )
}

internal fun permissionPresentation(id: String, nativeLabel: String = "", cli: String = "", option: RequestChoice? = null): PermissionPresentation {
    if (id.isEmpty()) return if (cli in setOf("Codex", "Claude Code", "CodeBuddy Code", "DeepSeek Harness", "Kimi Code", "Qoder CLI", "Grok Build", "Antigravity", "OpenCode")) {
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
            sandbox == "read-only" -> PermissionMeaning.READ_ONLY
            sandbox == "workspace-write" -> PermissionMeaning.WORKSPACE
            sandbox == "danger-full-access" -> PermissionMeaning.BYPASS
            else -> PermissionMeaning.UNKNOWN
        }
        val title = option?.label?.takeIf(String::isNotBlank) ?: nativeLabel.takeIf(String::isNotBlank) ?: id
        return PermissionPresentation(
            title,
            listOf(boundary, review, option?.description.orEmpty()).filter { it.isNotBlank() }.joinToString(tr("；", "; ")),
            meaning.icon,
            meaning.warning || sandbox.isEmpty(),
        )
    }

    val meaning = when {
        cli == "Kimi Code" && option?.approval == "always" -> PermissionMeaning.REVIEW
        cli == "Kimi Code" && option?.approval == "when_needed" -> PermissionMeaning.AUTO_REVIEW
        cli == "Kimi Code" && option?.approval == "never" -> PermissionMeaning.BYPASS
        else -> nativePermissionMeaning(cli, id)
    }
    val title = option?.label?.takeIf(String::isNotBlank) ?: nativeLabel.takeIf(String::isNotBlank) ?: id
    val description = option?.description?.takeIf(String::isNotBlank) ?: when {
        (cli == "Codex" || cli.isEmpty()) && id == ":read-only" -> tr("仅允许读取；写入与命令受 CLI 权限限制", "Read-only; writes and commands restricted by CLI permissions")
        (cli == "Codex" || cli.isEmpty()) && id == ":workspace" -> tr("允许工作区内读写；外部访问受 CLI 配置限制", "Read/write inside workspace; external access governed by CLI config")
        (cli == "Codex" || cli.isEmpty()) && id == ":danger-full-access" -> tr("不受沙箱限制，风险较高", "Unrestricted access without sandbox; use with caution")
        cli == "Grok Build" && id == "always-approve" -> tr("自动批准普通工具请求；原生拒绝规则、钩子与独立沙箱配置仍适用", "Auto-approves standard tool calls; native deny rules, hooks, and sandbox still apply")
        else -> ""
    }
    return PermissionPresentation(title, description, meaning.icon, meaning.warning)
}
