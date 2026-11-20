package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject

internal data class RequestSheetPalette(
    val dark: Boolean,
    val cardBg: Color,
    val cardBorder: Color,
    val insetBg: Color,
    val selectedRowBg: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primaryBg: Color,
    val primaryText: Color,
    val secondaryPillBg: Color,
    val secondaryPillText: Color,
    val dangerPillBg: Color,
    val dangerText: Color,
    val handleColor: Color,
)

@Composable
internal fun rememberRequestSheetPalette(): RequestSheetPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(dark, scheme.primary, scheme.onPrimary, scheme.error) {
        if (dark) {
            RequestSheetPalette(
                dark = true,
                cardBg = Color(0xFF1C1C1B),
                cardBorder = Color(0x1FFFFFFF),
                insetBg = Color(0xFF141413),
                selectedRowBg = scheme.primary.copy(alpha = 0.14f),
                hairline = Color(0x1AFFFFFF),
                textPrimary = Color(0xFFF0EFEC),
                textSecondary = Color(0xFFC3C2B7),
                textMuted = Color(0xFF898781),
                primaryBg = scheme.primary,
                primaryText = scheme.onPrimary,
                secondaryPillBg = Color(0x14FFFFFF),
                secondaryPillText = Color(0xFFE5E4DF),
                dangerPillBg = scheme.error.copy(alpha = 0.14f),
                dangerText = scheme.error,
                handleColor = Color(0x38FFFFFF),
            )
        } else {
            RequestSheetPalette(
                dark = false,
                cardBg = Color(0xFFFFFFFF),
                cardBorder = Color(0x140B0B0B),
                insetBg = Color(0xFFF6F6F4),
                selectedRowBg = scheme.primary.copy(alpha = 0.09f),
                hairline = Color(0x170B0B0B),
                textPrimary = Color(0xFF141413),
                textSecondary = Color(0xFF52514E),
                textMuted = Color(0xFF898781),
                primaryBg = scheme.primary,
                primaryText = scheme.onPrimary,
                secondaryPillBg = Color(0xFFF2F1EE),
                secondaryPillText = Color(0xFF1F1E1D),
                dangerPillBg = scheme.error.copy(alpha = 0.10f),
                dangerText = scheme.error,
                handleColor = Color(0x260B0B0B),
            )
        }
    }
}

private enum class ApprovalChoiceRole {
    PrimaryAllow,
    ScopedAllow,
    Neutral,
    Negative,
    Destructive,
}

private data class ClassifiedChoice(
    val raw: RequestChoice,
    val displayLabel: String,
    val role: ApprovalChoiceRole,
)

private val standardEnglishChoiceMap = mapOf(
    "allow" to "允许",
    "allow once" to "允许一次",
    "allow_once" to "允许一次",
    "approve" to "批准",
    "approve once" to "允许一次",
    "accept" to "接受",
    "allow always" to "始终允许",
    "always allow" to "始终允许",
    "allow_always" to "始终允许",
    "approve always" to "始终允许",
    "always approve" to "始终允许",
    "approve_always" to "本会话允许",
    "allow for session" to "本会话允许",
    "allow for this session" to "本会话允许",
    "approve for session" to "本会话允许",
    "approve for this session" to "本会话允许",
    "approve_for_session" to "本会话允许",
    "reject" to "拒绝",
    "reject once" to "拒绝本次",
    "deny" to "拒绝",
    "decline" to "拒绝",
    "cancel" to "取消",
)

private val standardChoiceEnMap = mapOf(
    "allow" to "Allow", "允许" to "Allow",
    "allow once" to "Allow once", "allow_once" to "Allow once", "approve once" to "Allow once", "允许一次" to "Allow once", "仅允许一次" to "Allow once",
    "approve" to "Approve", "批准" to "Approve",
    "accept" to "Accept", "接受" to "Accept",
    "allow always" to "Always allow", "always allow" to "Always allow", "allow_always" to "Always allow", "approve always" to "Always allow", "always approve" to "Always allow", "始终允许" to "Always allow",
    "allow for session" to "Allow for session", "allow for this session" to "Allow for session", "approve for session" to "Allow for session", "approve for this session" to "Allow for session", "approve_for_session" to "Allow for session", "approve_always" to "Allow for session", "本会话允许" to "Allow for session",
    "reject" to "Reject", "deny" to "Deny", "decline" to "Decline", "拒绝" to "Reject",
    "reject once" to "Reject once", "拒绝本次" to "Reject once",
    "cancel" to "Cancel", "取消" to "Cancel",
)

internal fun normalizeChoiceDisplayLabel(label: String): String {
    val trimmed = label.trim()
    return if (LumaI18n.isZh()) standardEnglishChoiceMap[trimmed.lowercase()] ?: trimmed
    else standardChoiceEnMap[trimmed.lowercase()] ?: standardChoiceEnMap[trimmed] ?: trimmed
}

private fun classifyApprovalChoices(choices: List<RequestChoice>): List<ClassifiedChoice> {
    var primaryAssigned = false
    return choices.map { choice ->
        val idLower = choice.id.trim().lowercase()
        val display = normalizeChoiceDisplayLabel(choice.label)
        val labelLower = display.lowercase()
        val rawRole = when {
            idLower in setOf("abandoned", "rejectandexitplan") ||
                "中断" in display || "退出计划" in display || "放弃" in display || "abort" in labelLower ->
                ApprovalChoiceRole.Destructive
            idLower in setOf("deny", "reject", "decline", "no", "cancel", "cancelled") ||
                idLower.startsWith("luma:cancel") || idLower.endsWith("-reject") || idLower.endsWith(":cancel") ||
                display.startsWith("拒绝") || display.startsWith("取消") || display.startsWith("不授予") ||
                display.startsWith("不允许") || labelLower.startsWith("deny") ||
                labelLower.startsWith("reject") || labelLower.startsWith("decline") || labelLower.startsWith("cancel") ->
                ApprovalChoiceRole.Negative
            idLower in setOf("always", "session", "approve-for-session", "approve_for_session", "approve_always", "allowall", "allow_always") ||
                idLower.startsWith("grant:") || idLower.startsWith("always:") ||
                "本会话" in display || "记住" in display || "始终" in display || "后续" in display ||
                "保存" in display || "always" in labelLower || "session" in labelLower ->
                ApprovalChoiceRole.ScopedAllow
            idLower in setOf("allow", "once", "approve-once", "approve_once", "accept", "yes", "approved", "allow_once") ||
                "allow-once" in idLower || "once" in idLower ||
                display.startsWith("允许") || display.startsWith("批准") || display.startsWith("仅允许") ||
                display.startsWith("仅本轮允许") || display == "确认" || display == "执行" || display == "同意" ||
                display == "接受" || labelLower.startsWith("allow") || labelLower.startsWith("approve") ||
                labelLower.startsWith("accept") || labelLower.startsWith("confirm") ->
                ApprovalChoiceRole.PrimaryAllow
            else -> ApprovalChoiceRole.Neutral
        }
        val finalRole = if (rawRole == ApprovalChoiceRole.PrimaryAllow) {
            if (!primaryAssigned) {
                primaryAssigned = true
                ApprovalChoiceRole.PrimaryAllow
            } else {
                ApprovalChoiceRole.ScopedAllow
            }
        } else {
            rawRole
        }
        ClassifiedChoice(choice, display, finalRole)
    }.let { mapped ->
        val negCount = mapped.count { it.role == ApprovalChoiceRole.Negative || it.role == ApprovalChoiceRole.Destructive }
        if (negCount < 2) return@let mapped
        val withoutLumaCancel = mapped.filterNot {
            it.raw.id.startsWith("luma:cancel", ignoreCase = true) && (it.displayLabel == "取消" || it.displayLabel.equals("Cancel", ignoreCase = true))
        }
        val remainingNeg = withoutLumaCancel.count {
            it.role == ApprovalChoiceRole.Negative || it.role == ApprovalChoiceRole.Destructive
        }
        if (remainingNeg < 2) return@let withoutLumaCancel
        val hasNonCancelNeg = withoutLumaCancel.any {
            (it.role == ApprovalChoiceRole.Negative || it.role == ApprovalChoiceRole.Destructive) &&
                !(it.raw.id.equals("cancel", ignoreCase = true) && (it.displayLabel == "取消" || it.displayLabel.equals("Cancel", ignoreCase = true)))
        }
        if (hasNonCancelNeg) {
            withoutLumaCancel.filterNot { it.raw.id.equals("cancel", ignoreCase = true) && (it.displayLabel == "取消" || it.displayLabel.equals("Cancel", ignoreCase = true)) }
        } else {
            withoutLumaCancel
        }
    }
}

private fun horizontalSortKey(role: ApprovalChoiceRole): Int = when (role) {
    ApprovalChoiceRole.Destructive -> 0
    ApprovalChoiceRole.Negative -> 1
    ApprovalChoiceRole.Neutral -> 2
    ApprovalChoiceRole.ScopedAllow -> 3
    ApprovalChoiceRole.PrimaryAllow -> 4
}

internal data class StructuredApprovalBody(
    val leadText: String = "",
    val codeBlock: String = "",
    val cwd: String = "",
    val extraCode: String = "",
    val markdownFallback: String = "",
    val kindHint: String = "",
)

private val commandTitlePrefixRegex =
    Regex("^(?:Run|Execute|Bash|Shell|Command|Read|Write|Edit)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
private val fencedBodyWithOptionalLeadRegex =
    Regex("^(?:([\\s\\S]*?)\\n\\s*\\n)?```[^\\n]*\\n([\\s\\S]*?)\\n```\\s*$")
private val machineQuestionKeyRegex =
    Regex("^(?:\\d+|(?:q|question|item|step|field|prop)_?\\d+)$", RegexOption.IGNORE_CASE)
private val bareApprovalToolNames = setOf(
    "bash", "sh", "zsh", "pwsh", "shell", "exec", "execute", "run", "run_command", "run_code",
    "write", "write_file", "edit", "edit_file", "str_replace_editor", "patch", "apply_patch",
    "read", "read_file", "tool",
)

private fun isGenericApprovalTitle(cleanTitle: String): Boolean =
    cleanTitle.isEmpty() ||
        cleanTitle == "工具授权" || cleanTitle == "工具权限确认" || cleanTitle == "工具" ||
        cleanTitle.equals("Tool authorization", ignoreCase = true) ||
        cleanTitle.equals("Tool permission", ignoreCase = true) ||
        cleanTitle.lowercase() in bareApprovalToolNames

private fun titleRepeatsTarget(cleanTitle: String, target: String): Boolean {
    if (cleanTitle.isEmpty() || target.isEmpty()) return false
    val unquoted = cleanTitle.trim().trim('`', '"', '\'')
    if (unquoted == target) return true
    val withoutEllipsis = unquoted.removeSuffix("…").removeSuffix("...").trim()
    if (withoutEllipsis.length >= 12 && target.startsWith(withoutEllipsis)) return true
    val strippedPrefix = commandTitlePrefixRegex.matchEntire(unquoted)?.groupValues?.getOrNull(1)?.trim()?.trim('`', '"', '\'')
    return strippedPrefix != null && strippedPrefix == target
}

private fun parseJsonObjectWithTrailing(trimmed: String): Pair<JSONObject, String>? {
    if (!trimmed.startsWith("{")) return null
    val nl = trimmed.indexOf('\n')
    if (nl > 1) {
        val firstLine = trimmed.substring(0, nl).trim()
        val tail = trimmed.substring(nl + 1).trim()
        if (firstLine.endsWith("}")) {
            runCatching { JSONObject(firstLine) }.getOrNull()?.let { return it to tail }
        }
    }
    runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it to "" }
    return null
}

private fun JSONObject.safeTrimmedString(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

internal fun parseStructuredApprovalBody(title: String, rawBody: String): Pair<String, StructuredApprovalBody> {
    val cleanTitle = title.trim()
    val trimmed = rawBody.trim()

    if (trimmed.startsWith("{")) {
        val jsonAndTail = parseJsonObjectWithTrailing(trimmed)
        if (jsonAndTail != null) {
            val (obj, trailingDisplay) = jsonAndTail
            val command = sequenceOf("command", "CommandLine", "cmd", "script", "code")
                .map { obj.safeTrimmedString(it) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val filePath = sequenceOf("file_path", "fileName", "path", "target_file", "TargetFile", "AbsolutePath", "DirectoryPath", "url")
                .map { obj.safeTrimmedString(it) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val summary = sequenceOf("toolSummary", "summary")
                .map { obj.safeTrimmedString(it) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val description = sequenceOf("description", "reason", "explanation", "intention")
                .map { obj.safeTrimmedString(it) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val justification = obj.safeTrimmedString("justification")
            val sandboxPermissions = obj.safeTrimmedString("sandbox_permissions")
            val sandboxNote = if (sandboxPermissions.isNotEmpty()) tr("请求沙箱权限：$sandboxPermissions", "Requested sandbox: $sandboxPermissions") else ""
            val cwd = sequenceOf("cwd", "Cwd", "workdir", "working_directory")
                .map { obj.safeTrimmedString(it) }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            val isDaemon = obj.optBoolean("IsDaemon", false) ||
                obj.optBoolean("is_background", false) ||
                obj.optBoolean("run_in_background", false)
            val ignoredKeys = setOf(
                "command", "CommandLine", "cmd", "script", "code",
                "file_path", "fileName", "path", "target_file", "TargetFile", "AbsolutePath", "DirectoryPath", "url",
                "toolSummary", "summary",
                "description", "reason", "explanation", "intention",
                "justification", "sandbox_permissions",
                "cwd", "Cwd", "workdir", "working_directory",
                "WaitMsBeforeAsync", "timeout", "timeout_ms",
                "IsDaemon", "is_background", "run_in_background",
            )
            val rest = JSONObject()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in ignoredKeys) rest.put(key, obj.opt(key))
            }
            val restPretty = if (rest.length() > 0) formatMultilineJsonObject(rest) ?: rest.toString(2) else ""
            val combinedExtra = listOf(restPretty, trailingDisplay).filter { it.isNotBlank() }.joinToString("\n\n")
            val primaryTarget = command.ifEmpty { filePath }
            val kindHint = when {
                command.isNotEmpty() -> "command"
                filePath.isNotEmpty() -> "file"
                else -> ""
            }
            if (primaryTarget.isNotEmpty()) {
                val genericOrRedundantTitle = isGenericApprovalTitle(cleanTitle) || titleRepeatsTarget(cleanTitle, primaryTarget)
                val resolvedTitle = if (genericOrRedundantTitle) {
                    when {
                        summary.isNotEmpty() -> summary
                        description.isNotEmpty() && description.length <= 64 && '\n' !in description -> description
                        command.isNotEmpty() -> if (isDaemon) tr("后台执行终端命令", "Run background command") else tr("执行终端命令", "Run terminal command")
                        else -> {
                            val base = filePath.trimEnd('/').substringAfterLast('/').ifEmpty { filePath }
                            tr("访问路径 · $base", "Access path · $base")
                        }
                    }
                } else {
                    if (cleanTitle.length > 80) cleanTitle.take(80) + "…" else cleanTitle
                }
                val lead = listOf(summary, description, justification, sandboxNote)
                    .filter { it.isNotBlank() && it != resolvedTitle }
                    .distinct()
                    .joinToString("\n")
                return resolvedTitle to StructuredApprovalBody(
                    leadText = lead,
                    codeBlock = primaryTarget,
                    cwd = cwd,
                    extraCode = combinedExtra,
                    kindHint = kindHint,
                )
            } else {
                val genericTitle = cleanTitle.isEmpty() ||
                    cleanTitle == "工具授权" || cleanTitle == "工具" ||
                    cleanTitle.equals("Tool authorization", ignoreCase = true) ||
                    cleanTitle.equals("Tool permission", ignoreCase = true)
                val resolvedTitle = when {
                    genericTitle -> summary.ifEmpty { tr("工具权限确认", "Tool permission") }
                    cleanTitle.length > 80 -> cleanTitle.take(80) + "…"
                    else -> cleanTitle
                }
                val lead = listOf(summary, description, justification, sandboxNote)
                    .filter { it.isNotBlank() && it != resolvedTitle }
                    .distinct()
                    .joinToString("\n")
                val baseCode = when {
                    restPretty.isNotEmpty() -> restPretty
                    lead.isEmpty() -> formatMultilineJsonObject(obj) ?: obj.toString(2)
                    else -> ""
                }
                val code = listOf(baseCode, trailingDisplay).filter { it.isNotBlank() }.joinToString("\n\n")
                return resolvedTitle to StructuredApprovalBody(leadText = lead, codeBlock = code, cwd = cwd)
            }
        }
        val fallbackHeader = if (cleanTitle.isEmpty()) tr("工具权限确认", "Tool permission") else if (cleanTitle.length > 80) cleanTitle.take(80) + "…" else cleanTitle
        return fallbackHeader to StructuredApprovalBody(codeBlock = trimmed)
    }

    if (trimmed.isEmpty()) {
        val isCommandLikeTitle = '\n' in cleanTitle || cleanTitle.length > 72
        return if (isCommandLikeTitle) {
            tr("执行终端命令", "Run terminal command") to StructuredApprovalBody(codeBlock = cleanTitle, kindHint = "command")
        } else {
            cleanTitle.ifEmpty { tr("工具权限确认", "Tool permission") } to StructuredApprovalBody()
        }
    }

    if (trimmed.startsWith("[")) {
        val prettyArray = runCatching { JSONArray(trimmed).toString(2) }.getOrDefault(trimmed)
        val header = cleanTitle.ifEmpty { tr("工具权限确认", "Tool permission") }.let { if (it.length > 80) it.take(80) + "…" else it }
        return header to StructuredApprovalBody(codeBlock = prettyArray)
    }

    val fencedMatch = fencedBodyWithOptionalLeadRegex.matchEntire(trimmed)
    if (fencedMatch != null) {
        val rawLead = fencedMatch.groupValues[1].trim().replace("`", "")
        val fencedCode = fencedMatch.groupValues[2].trimEnd()
        val repeats = titleRepeatsTarget(cleanTitle, fencedCode)
        val header = when {
            repeats || cleanTitle.isEmpty() || cleanTitle == "工具授权" || cleanTitle.equals("Tool authorization", ignoreCase = true) -> tr("执行终端命令", "Run terminal command")
            cleanTitle.length > 80 -> cleanTitle.take(80) + "…"
            else -> cleanTitle
        }
        return header to StructuredApprovalBody(
            leadText = rawLead,
            codeBlock = fencedCode,
            kindHint = if (repeats) "command" else "",
        )
    }

    val header = cleanTitle.ifEmpty { tr("工具权限确认", "Tool permission") }.let { if (it.length > 80) it.take(80) + "…" else it }
    val hasMarkdownSyntax = "```" in trimmed || trimmed.startsWith("#") || "\n#" in trimmed || "\n- " in trimmed || "\n* " in trimmed || "**" in trimmed
    return if (hasMarkdownSyntax) {
        header to StructuredApprovalBody(markdownFallback = trimmed)
    } else {
        header to StructuredApprovalBody(leadText = trimmed)
    }
}

internal fun requestBannerTitle(request: UserRequest): String {
    if (request.questions.isNotEmpty() && request.title.trim().equals("Answer", ignoreCase = true)) {
        val firstQ = request.questions.first()
        return firstQ.prompt.trim().ifEmpty { firstQ.header.trim() }.ifEmpty { tr("需要确认选项", "Confirmation required") }
    }
    val (dialogTitle, structured) = parseStructuredApprovalBody(request.title, request.body)
    return if (dialogTitle in setOf("执行终端命令", "后台执行终端命令", "工具权限确认", "Run terminal command", "Run background command", "Tool permission") && structured.codeBlock.isNotBlank()) {
        val firstLine = structured.codeBlock.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isNotEmpty()) "$dialogTitle · $firstLine" else dialogTitle
    } else {
        dialogTitle
    }
}

private fun approvalEyebrow(
    request: UserRequest,
    dialogTitle: String,
    structuredBody: StructuredApprovalBody,
): String {
    if (request.externalUrl != null) return tr("账号授权", "Account sign-in")
    if (structuredBody.kindHint == "command") return tr("命令审批", "Command approval")
    if (structuredBody.kindHint == "file") return tr("文件变更", "File changes")
    val probe = "${request.title} $dialogTitle".lowercase()
    return when {
        "命令" in probe || "bash" in probe || "shell" in probe || "exec" in probe || "command" in probe -> tr("命令审批", "Command approval")
        "文件" in probe || "edit" in probe || "write" in probe || "patch" in probe || "file" in probe -> tr("文件变更", "File changes")
        "计划" in probe || "plan" in probe -> tr("计划审阅", "Plan review")
        "mcp" in probe || "外部工具" in probe -> tr("外部工具确认", "External tool")
        else -> tr("权限确认", "Permission")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestSheet(
    request: UserRequest,
    actions: CockpitActions,
    canRespond: Boolean = true,
    reconnecting: Boolean = false,
    closing: Boolean = false,
    onDismiss: () -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    if (!LocalSessionInteractive.current) {
        SideEffect(latestDismiss)
        return
    }
    val hostContext = LocalContext.current
    val palette = rememberRequestSheetPalette()
    if (LocalInspectionMode.current) {
        if (closing) {
            SideEffect(latestDismiss)
            return
        }
        BackHandler(enabled = !closing) { latestDismiss() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (palette.dark) 0.54f else 0.32f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            RequestSheetCard(
                request = request,
                actions = actions,
                hostContext = hostContext,
                palette = palette,
                canRespond = canRespond,
                reconnecting = reconnecting,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val sheetProgress = remember { Animatable(0f) }
    val dragOffsetPx = remember { Animatable(0f) }
    var cardHeightPx by remember { mutableIntStateOf(0) }
    var dismissing by remember { mutableStateOf(false) }
    val minDismissPx = with(density) { 96.dp.toPx() }
    val dismissVelocityPx = with(density) { 900.dp.toPx() }
    val baseScrimAlpha = if (palette.dark) 0.56f else 0.36f

    suspend fun finishLocalDismiss(initialVelocity: Float = 0f) {
        try {
            sheetProgress.animateTo(0f, PrismSprings.sheet(), initialVelocity = initialVelocity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        latestDismiss()
        withFrameNanos {}
        if (!closing) {
            dismissing = false
            sheetProgress.animateTo(1f, PrismSprings.sheet())
        }
    }

    fun animateDismiss(velocityY: Float = 0f) {
        if (dismissing || closing) return
        dismissing = true
        focusManager.clearFocus()
        scope.launch {
            val h = cardHeightPx.toFloat().coerceAtLeast(1f)
            if (dragOffsetPx.value > 0f) {
                val remain = (sheetProgress.value - dragOffsetPx.value / h).coerceIn(0f, 1f)
                sheetProgress.snapTo(remain)
                dragOffsetPx.snapTo(0f)
            }
            val launchVelocity = if (velocityY > 0f) -(velocityY / h).coerceIn(0f, 12f) else 0f
            finishLocalDismiss(launchVelocity)
        }
    }

    fun settleDrag(velocityY: Float) {
        if (dismissing || closing) return
        val threshold = if (cardHeightPx > 0) maxOf(minDismissPx, cardHeightPx * 0.28f) else minDismissPx
        if (dragOffsetPx.value > threshold || (dragOffsetPx.value > minDismissPx * 0.35f && velocityY > dismissVelocityPx)) {
            animateDismiss(velocityY)
        } else if (dragOffsetPx.value > 0f) {
            scope.launch {
                dragOffsetPx.animateTo(0f, PrismSprings.sheetPx(), initialVelocity = velocityY.coerceIn(-4000f, 4000f))
            }
        }
    }

    LaunchedEffect(closing) {
        if (!closing) {
            if (!dismissing) {
                sheetProgress.animateTo(1f, PrismSprings.sheet())
            }
        } else {
            dismissing = true
            focusManager.clearFocus()
            val h = cardHeightPx.toFloat().coerceAtLeast(1f)
            if (dragOffsetPx.value > 0f) {
                val remain = (sheetProgress.value - dragOffsetPx.value / h).coerceIn(0f, 1f)
                sheetProgress.snapTo(remain)
                dragOffsetPx.snapTo(0f)
            }
            try {
                sheetProgress.animateTo(0f, PrismSprings.sheet())
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            latestDismiss()
        }
    }

    PredictiveBackHandler(enabled = !closing && !dismissing) { progress ->
        try {
            progress.collect { backEvent ->
                val p = backEvent.progress.coerceIn(0f, 1f)
                sheetProgress.snapTo(1f - p * 0.32f)
            }
            dismissing = true
            focusManager.clearFocus()
            finishLocalDismiss()
        } catch (cancelled: CancellationException) {
            if (!closing && !dismissing) {
                scope.launch { sheetProgress.animateTo(1f, PrismSprings.sheet()) }
            }
            throw cancelled
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && dragOffsetPx.value > 0f) {
                    val next = (dragOffsetPx.value + available.y).coerceAtLeast(0f)
                    val consumedY = next - dragOffsetPx.value
                    scope.launch { dragOffsetPx.snapTo(next) }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && !closing && !dismissing) {
                    val next = (dragOffsetPx.value + available.y).coerceAtLeast(0f)
                    scope.launch { dragOffsetPx.snapTo(next) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffsetPx.value > 0f) {
                    settleDrag(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        if (!closing && !dismissing) {
            val next = (dragOffsetPx.value + delta).coerceAtLeast(0f)
            scope.launch { dragOffsetPx.snapTo(next) }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("request-sheet-scrim")
                .drawBehind {
                    val h = cardHeightPx.toFloat().coerceAtLeast(1f)
                    val effective = (sheetProgress.value - dragOffsetPx.value / h).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = baseScrimAlpha * effective))
                }
                .pointerInput(closing, dismissing) {
                    detectTapGestures {
                        if (!closing && !dismissing) animateDismiss()
                    }
                },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .consumeWindowInsets(WindowInsets.navigationBars.union(WindowInsets.ime))
                .onSizeChanged { cardHeightPx = it.height }
                .graphicsLayer {
                    val h = size.height.coerceAtLeast(1f)
                    val effective = (sheetProgress.value - dragOffsetPx.value / h).coerceIn(0f, 1f)
                    translationY = (1f - effective) * h
                }
                .then(if (closing || dismissing) Modifier.clearAndSetSemantics {} else Modifier)
                .pointerInput(Unit) { detectTapGestures {} }
                .nestedScroll(nestedScrollConnection)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    enabled = !closing && !dismissing,
                    onDragStopped = { velocity -> settleDrag(velocity) },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            RequestSheetCard(
                request = request,
                actions = actions,
                hostContext = hostContext,
                palette = palette,
                canRespond = canRespond,
                reconnecting = reconnecting,
            )
        }
    }
}

internal fun defaultQuestionIndices(question: RequestQuestion): List<Int> {
    if (question.defaultSelection.isEmpty() || question.options.isEmpty()) return emptyList()
    val used = HashSet<Int>()
    val result = ArrayList<Int>()
    for (sel in question.defaultSelection) {
        val idx = question.optionValues.indexOf(sel).takeIf { it >= 0 && it !in used }
            ?: question.options.withIndex().firstOrNull { (i, label) -> i !in used && label == sel }?.index
        if (idx != null) {
            used.add(idx)
            result.add(idx)
            if (!question.multiple) break
        }
    }
    return result
}

internal fun formatEnteredQuestionAnswers(
    questions: List<RequestQuestion>,
    selectedIndices: Map<String, List<Int>>,
    freeText: Map<String, String>,
    otherSelected: Map<String, Boolean>,
): Map<String, List<String>> = questions.associate { q ->
    val isOther = q.options.isEmpty() || otherSelected[q.id] == true
    val useEncoded = q.optionValues.size == q.options.size && q.options.isNotEmpty()
    val typedRaw = if (isOther) freeText[q.id]?.trim()?.takeIf { it.isNotEmpty() } else null
    val typed = if (typedRaw != null && useEncoded) "\u0000raw:$typedRaw" else typedRaw
    val indices = (selectedIndices[q.id] ?: defaultQuestionIndices(q)).filter { it in q.options.indices }
    val chosen = indices.map { idx -> if (useEncoded) q.optionValues[idx] else q.options[idx] }
    q.id to (if (typed != null && !q.multiple) listOf(typed) else chosen + listOfNotNull(typed))
}

@Composable
private fun RequestSheetCard(
    request: UserRequest,
    actions: CockpitActions,
    hostContext: android.content.Context,
    palette: RequestSheetPalette,
    canRespond: Boolean,
    reconnecting: Boolean,
) {
    val answers = remember(request.id) { mutableStateMapOf<String, List<Int>>() }
    val freeText = remember(request.id) { mutableStateMapOf<String, String>() }
    val otherSelected = remember(request.id) { mutableStateMapOf<String, Boolean>() }
    var stepIndex by remember(request.id, request.questions.size) { mutableIntStateOf(0) }
    if (request.questions.isNotEmpty() && stepIndex > request.questions.lastIndex) {
        stepIndex = request.questions.lastIndex
    }

    fun selection(question: RequestQuestion): List<Int> =
        (answers[question.id] ?: defaultQuestionIndices(question)).filter { it in question.options.indices }

    fun questionSatisfied(question: RequestQuestion): Boolean {
        if (!question.required) return true
        val typedNonBlank = freeText[question.id].orEmpty().isNotBlank()
        if (question.options.isEmpty()) return typedNonBlank
        if (otherSelected[question.id] == true && !question.multiple) return typedNonBlank
        return selection(question).isNotEmpty() || (otherSelected[question.id] == true && typedNonBlank)
    }

    fun enteredAnswers(): Map<String, List<String>> =
        formatEnteredQuestionAnswers(request.questions, answers, freeText, otherSelected)

    val externalLogin = request.externalUrl != null
    val browserIntent = remember(request.id, request.externalUrl) { request.externalUrl?.let(::googleSignInIntent) }
    var browserOpened by remember(request.id, request.externalUrl) { mutableStateOf(false) }
    var browserUnavailable by remember(request.id, request.externalUrl) { mutableStateOf(false) }

    val langCode = LumaI18n.langCode()
    val (dialogTitle, structuredBody) = remember(request.title, request.body, langCode) {
        parseStructuredApprovalBody(request.title, request.body)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 500.dp)
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        shape = LumaCardShape(28.dp),
        color = palette.cardBg,
        contentColor = palette.textPrimary,
        border = BorderStroke(0.5.dp, palette.cardBorder),
        tonalElevation = 0.dp,
        shadowElevation = if (palette.dark) 18.dp else 12.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .then(
                    if (request.questions.isEmpty()) Modifier.verticalScroll(rememberScrollState())
                    else Modifier,
                )
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(palette.handleColor),
                )
            }

            if (request.questions.isNotEmpty()) {
                QuestionSheetContent(
                    request = request,
                    actions = actions,
                    palette = palette,
                    canRespond = canRespond,
                    reconnecting = reconnecting,
                    stepIndex = stepIndex,
                    onStepChange = { stepIndex = it },
                    selection = ::selection,
                    onSelectOptions = { qId, values -> answers[qId] = values },
                    freeText = { freeText[it].orEmpty() },
                    onFreeTextChange = { qId, value -> freeText[qId] = value },
                    otherSelected = { otherSelected[it] == true },
                    onOtherSelectedChange = { qId, active -> otherSelected[qId] = active },
                    questionSatisfied = ::questionSatisfied,
                    enteredAnswers = ::enteredAnswers,
                    structuredBody = structuredBody,
                )
            } else {
                ApprovalSheetContent(
                    request = request,
                    actions = actions,
                    palette = palette,
                    canRespond = canRespond,
                    reconnecting = reconnecting,
                    externalLogin = externalLogin,
                    browserIntent = browserIntent,
                    browserOpened = browserOpened,
                    browserUnavailable = browserUnavailable,
                    onOpenBrowser = {
                        browserIntent?.let { intent ->
                            browserOpened = openGoogleSignIn(hostContext, intent)
                            browserUnavailable = !browserOpened
                        }
                    },
                    dialogTitle = dialogTitle,
                    structuredBody = structuredBody,
                )
            }
        }
    }
}

@Composable
private fun DisconnectedRecoveryBanner(
    palette: RequestSheetPalette,
    reconnecting: Boolean,
    answering: Boolean,
    externalLogin: Boolean,
    onReconnect: () -> Unit,
) {
    Surface(
        shape = LumaCardShape(14.dp),
        color = palette.insetBg,
        border = BorderStroke(0.5.dp, palette.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    reconnecting -> tr("正在重新连接…", "Reconnecting…")
                    answering -> if (externalLogin) tr("取消结果待确认，请重连核对。", "Cancellation unconfirmed; reconnect to verify.") else tr("答复结果待确认，请重连核对。", "Reply unconfirmed; reconnect to verify.")
                    else -> if (externalLogin) tr("连接已断开，请重新连接以继续登录。", "Disconnected; reconnect to continue sign-in.") else tr("连接已断开，重连后可答复。", "Disconnected; reconnect to reply.")
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                color = palette.textSecondary,
            )
            SheetCapsuleButton(
                label = tr("重新连接", "Reconnect"),
                enabled = !reconnecting,
                containerColor = palette.secondaryPillBg,
                contentColor = palette.primaryBg,
                minHeight = 32.dp,
                horizontalPadding = 12.dp,
                fontSize = 12.5.sp,
                onClick = onReconnect,
            )
        }
    }
}

@Composable
private fun ApprovalSheetContent(
    request: UserRequest,
    actions: CockpitActions,
    palette: RequestSheetPalette,
    canRespond: Boolean,
    reconnecting: Boolean,
    externalLogin: Boolean,
    browserIntent: android.content.Intent?,
    browserOpened: Boolean,
    browserUnavailable: Boolean,
    onOpenBrowser: () -> Unit,
    dialogTitle: String,
    structuredBody: StructuredApprovalBody,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (canRespond) palette.primaryBg else palette.textMuted),
            )
            Text(
                approvalEyebrow(request, dialogTitle, structuredBody),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                ),
                color = palette.textMuted,
            )
        }
        Text(
            dialogTitle,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = palette.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (!canRespond) {
        DisconnectedRecoveryBanner(
            palette = palette,
            reconnecting = reconnecting,
            answering = request.answering,
            externalLogin = externalLogin,
            onReconnect = actions::reconnect,
        )
    }

    ApprovalBodyBlock(structuredBody = structuredBody, palette = palette)

    if (externalLogin) {
        when {
            browserIntent == null -> Text(
                tr("登录链接无效，请取消后重新连接。", "Invalid sign-in URL; cancel and reconnect."),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = palette.dangerText,
            )
            browserUnavailable -> Text(
                tr("无法打开浏览器，请检查系统浏览器后重试。", "Unable to open browser; check system browser and try again."),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = palette.dangerText,
            )
            browserOpened && canRespond && !request.answering -> Text(
                tr("等待官方 ACP 确认登录…", "Waiting for official ACP to confirm sign-in…"),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = palette.textSecondary,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            request.choices.forEach { choice ->
                SheetCapsuleButton(
                    label = choice.label,
                    enabled = canRespond && !request.answering,
                    containerColor = palette.secondaryPillBg,
                    contentColor = palette.secondaryPillText,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                    onClick = { actions.answer(request.id, choice.id, emptyMap()) },
                )
            }
            SheetCapsuleButton(
                label = tr("打开 Google 登录", "Sign in with Google"),
                enabled = canRespond && !request.answering && browserIntent != null,
                containerColor = palette.primaryBg,
                contentColor = palette.primaryText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1.35f),
                minHeight = 46.dp,
                onClick = onOpenBrowser,
            )
        }
        if (request.answering && canRespond) {
            Text(
                tr("正在取消登录…", "Cancelling sign-in…"),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = palette.textMuted,
            )
        }
        return
    }

    if (request.choices.isNotEmpty()) {
        ApprovalChoicesSection(
            choices = request.choices,
            palette = palette,
            enabled = canRespond && !request.answering,
            onSelect = { choiceId -> actions.answer(request.id, choiceId, emptyMap()) },
        )
    }

    if (request.answering && canRespond) {
        Text(
            tr("正在提交答复…", "Submitting reply…"),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = palette.textMuted,
        )
    }
}

@Composable
private fun ApprovalBodyBlock(
    structuredBody: StructuredApprovalBody,
    palette: RequestSheetPalette,
) {
    if (structuredBody.leadText.isNotBlank()) {
        Text(
            structuredBody.leadText,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = palette.textSecondary,
        )
    }
    if (structuredBody.codeBlock.isNotBlank()) {
        var expanded by remember(structuredBody.codeBlock, structuredBody.extraCode) { mutableStateOf(false) }
        val codeLines = remember(structuredBody.codeBlock) { structuredBody.codeBlock.lines() }
        val hasOverflow = codeLines.size > 6 || structuredBody.codeBlock.length > 280 || structuredBody.extraCode.isNotBlank()
        val maxLinesCap = if (codeLines.size <= 6 && structuredBody.extraCode.isBlank()) 4 else 6
        var codeOverflowsCollapsed by remember(structuredBody.codeBlock, maxLinesCap) {
            mutableStateOf(codeLines.size > maxLinesCap)
        }
        val expandProgress by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = spatialSpring(),
            label = "approval-code-expand",
        )
        Surface(
            shape = LumaCardShape(14.dp),
            color = palette.insetBg,
            border = BorderStroke(0.5.dp, palette.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    if (!hasOverflow) {
                        Text(
                            structuredBody.codeBlock,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                            ),
                            color = palette.textPrimary,
                        )
                    } else {
                        val fadeAlpha = if (codeOverflowsCollapsed) (1f - expandProgress).coerceIn(0f, 1f) else 0f
                        Layout(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .drawWithContent {
                                    drawContent()
                                    if (fadeAlpha > 0.01f) {
                                        val fadeH = minOf(22.dp.toPx(), size.height * 0.45f)
                                        val startY = (size.height - fadeH).coerceAtLeast(0f)
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Transparent, palette.insetBg.copy(alpha = fadeAlpha)),
                                                startY = startY,
                                                endY = size.height,
                                            ),
                                            topLeft = Offset(0f, startY),
                                            size = Size(size.width, fadeH),
                                        )
                                    }
                                },
                            content = {
                                Text(
                                    structuredBody.codeBlock,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                    ),
                                    color = palette.textPrimary,
                                    onTextLayout = { result ->
                                        codeOverflowsCollapsed = result.lineCount > maxLinesCap
                                    },
                                )
                                if (structuredBody.extraCode.isNotBlank()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                            .graphicsLayer { alpha = expandProgress.coerceIn(0f, 1f) }
                                            .then(
                                                if (!expanded && expandProgress <= 0.01f) Modifier.clearAndSetSemantics {}
                                                else Modifier,
                                            ),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                        Text(
                                            structuredBody.extraCode,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.5.sp,
                                                lineHeight = 16.sp,
                                            ),
                                            color = palette.textSecondary,
                                        )
                                    }
                                }
                            },
                        ) { measurables, constraints ->
                            val loose = constraints.copy(minHeight = 0)
                            val codePlaceable = measurables[0].measure(loose)
                            val extraPlaceable = measurables.getOrNull(1)?.measure(loose)
                            val maxCollapsedPx = (18.sp * maxLinesCap).roundToPx()
                            val collapsedH = minOf(codePlaceable.height, maxCollapsedPx)
                            val expandedH = codePlaceable.height + (extraPlaceable?.height ?: 0)
                            val currentH = if (expandedH <= collapsedH) {
                                expandedH
                            } else {
                                (collapsedH + (expandedH - collapsedH) * expandProgress.coerceIn(0f, 1f)).roundToInt()
                            }
                            val width = maxOf(constraints.minWidth, codePlaceable.width, extraPlaceable?.width ?: 0)
                            layout(width, currentH) {
                                codePlaceable.placeRelative(0, 0)
                                extraPlaceable?.placeRelative(0, codePlaceable.height)
                            }
                        }
                    }
                }
                if (structuredBody.cwd.isNotBlank() || hasOverflow) {
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasOverflow) Modifier.clickable { expanded = !expanded }
                                else Modifier,
                            )
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (structuredBody.cwd.isNotBlank()) tr("目录 · ${structuredBody.cwd}", "Dir · ${structuredBody.cwd}") else tr("参数详情", "Parameters"),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hasOverflow) {
                            Text(
                                if (expanded) tr("收起", "Collapse") else tr("展开详情", "Details"),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = palette.primaryBg,
                            )
                        }
                    }
                }
            }
        }
    }
    if (structuredBody.markdownFallback.isNotBlank()) {
        Surface(
            shape = LumaCardShape(14.dp),
            color = palette.insetBg,
            border = BorderStroke(0.5.dp, palette.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                CompositionLocalProvider(LocalContentColor provides palette.textPrimary) {
                    NativeMarkdown(structuredBody.markdownFallback, editorial = false)
                }
            }
        }
    }
}

@Composable
private fun ApprovalChoicesSection(
    choices: List<RequestChoice>,
    palette: RequestSheetPalette,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val langCode = LumaI18n.langCode()
    val classified = remember(choices, langCode) { classifyApprovalChoices(choices) }
    val hasMultiline = remember(classified) { classified.any { '\n' in it.displayLabel } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableDp = maxWidth
        val canInlineTwo = classified.size == 2 && !hasMultiline &&
            classified.all { it.displayLabel.length <= 10 } && availableDp >= 240.dp
        val canInlineThree = classified.size == 3 && !hasMultiline &&
            classified.any { it.role == ApprovalChoiceRole.PrimaryAllow } &&
            classified.all { it.displayLabel.length <= 7 } && availableDp >= 285.dp

        when {
            classified.size == 1 && !hasMultiline -> {
                val item = classified.single()
                val isPrimary = item.role == ApprovalChoiceRole.PrimaryAllow
                SheetCapsuleButton(
                    label = item.displayLabel,
                    enabled = enabled,
                    containerColor = if (isPrimary) palette.primaryBg else palette.secondaryPillBg,
                    contentColor = if (isPrimary) palette.primaryText else palette.secondaryPillText,
                    fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 46.dp,
                    onClick = { onSelect(item.raw.id) },
                )
            }

            canInlineTwo || canInlineThree -> {
                val ordered = remember(classified) { classified.sortedBy { horizontalSortKey(it.role) } }
                val compactThree = ordered.size == 3
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compactThree) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ordered.forEach { item ->
                        val (bg, fg, weightFont) = pillVisuals(item.role, palette)
                        val widthWeight = if (compactThree) {
                            (item.displayLabel.length.coerceIn(2, 6) + 3f)
                        } else {
                            if (item.role == ApprovalChoiceRole.PrimaryAllow) 1.18f else 1f
                        }
                        SheetCapsuleButton(
                            label = item.displayLabel,
                            enabled = enabled,
                            containerColor = bg,
                            contentColor = fg,
                            fontWeight = weightFont,
                            fontSize = if (compactThree) 13.5.sp else 14.sp,
                            horizontalPadding = if (compactThree) 10.dp else 14.dp,
                            minHeight = if (compactThree) 44.dp else 46.dp,
                            modifier = Modifier.weight(widthWeight),
                            onClick = { onSelect(item.raw.id) },
                        )
                    }
                }
            }

            else -> {
                val primary = classified.firstOrNull { it.role == ApprovalChoiceRole.PrimaryAllow }
                val listItems = if (primary != null) classified.filterNot { it.raw.id == primary.raw.id } else classified
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (listItems.isNotEmpty()) {
                        Surface(
                            shape = LumaCardShape(16.dp),
                            color = palette.insetBg,
                            border = BorderStroke(0.5.dp, palette.hairline),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                listItems.forEachIndexed { idx, item ->
                                    if (idx > 0) {
                                        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                    }
                                    GroupedActionRow(
                                        item = item,
                                        palette = palette,
                                        enabled = enabled,
                                        onClick = { onSelect(item.raw.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (primary != null) {
                        SheetCapsuleButton(
                            label = primary.displayLabel,
                            enabled = enabled,
                            containerColor = palette.primaryBg,
                            contentColor = palette.primaryText,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(),
                            minHeight = 48.dp,
                            onClick = { onSelect(primary.raw.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun pillVisuals(
    role: ApprovalChoiceRole,
    palette: RequestSheetPalette,
): Triple<Color, Color, FontWeight> = when (role) {
    ApprovalChoiceRole.PrimaryAllow -> Triple(palette.primaryBg, palette.primaryText, FontWeight.SemiBold)
    ApprovalChoiceRole.Destructive -> Triple(palette.dangerPillBg, palette.dangerText, FontWeight.Medium)
    ApprovalChoiceRole.Negative,
    ApprovalChoiceRole.ScopedAllow,
    ApprovalChoiceRole.Neutral -> Triple(palette.secondaryPillBg, palette.secondaryPillText, FontWeight.Medium)
}

@Composable
private fun GroupedActionRow(
    item: ClassifiedChoice,
    palette: RequestSheetPalette,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val labelColor = when {
        !enabled -> palette.textMuted.copy(alpha = 0.55f)
        item.role == ApprovalChoiceRole.Destructive -> palette.dangerText
        item.role == ApprovalChoiceRole.Negative -> palette.textSecondary
        else -> palette.textPrimary
    }
    val annotated = remember(item.displayLabel, labelColor, palette.textMuted) {
        if ('\n' !in item.displayLabel) {
            buildAnnotatedString { append(item.displayLabel) }
        } else {
            val firstNewline = item.displayLabel.indexOf('\n')
            val head = item.displayLabel.substring(0, firstNewline)
            val tail = item.displayLabel.substring(firstNewline)
            buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)) {
                    append(head)
                }
                withStyle(SpanStyle(color = palette.textMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) {
                    append(tail)
                }
            }
        }
    }
    val latestOnClick by rememberUpdatedState(onClick)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = labelColor,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                if (enabled) onClick { latestOnClick(); true }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = annotated,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (item.role == ApprovalChoiceRole.ScopedAllow || item.role == ApprovalChoiceRole.Neutral) {
                        FontWeight.Medium
                    } else {
                        FontWeight.Normal
                    },
                ),
                color = labelColor,
                maxLines = if ('\n' in item.displayLabel) 5 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            ChevronRightGlyph(
                color = if (enabled) palette.textMuted else palette.textMuted.copy(alpha = 0.4f),
            )
        }
    }
}

private val questionBoilerplateLeadRegex =
    Regex("^The agent has (?:a|\\d+) questions?\\.?$", RegexOption.IGNORE_CASE)

@Composable
private fun ColumnScope.QuestionSheetContent(
    request: UserRequest,
    actions: CockpitActions,
    palette: RequestSheetPalette,
    canRespond: Boolean,
    reconnecting: Boolean,
    stepIndex: Int,
    onStepChange: (Int) -> Unit,
    selection: (RequestQuestion) -> List<Int>,
    onSelectOptions: (String, List<Int>) -> Unit,
    freeText: (String) -> String,
    onFreeTextChange: (String, String) -> Unit,
    otherSelected: (String) -> Boolean,
    onOtherSelectedChange: (String, Boolean) -> Unit,
    questionSatisfied: (RequestQuestion) -> Boolean,
    enteredAnswers: () -> Map<String, List<String>>,
    structuredBody: StructuredApprovalBody,
) {
    val total = request.questions.size
    val currentQuestion = request.questions[stepIndex]
    val (rawHeader, rawPrompt) = remember(currentQuestion.header, currentQuestion.prompt) {
        requestQuestionCaption(currentQuestion.header, currentQuestion.prompt)
    }
    val humanHeader = rawHeader.takeUnless { machineQuestionKeyRegex.matches(it) }.orEmpty()
    val heroPrompt = rawPrompt.ifBlank { humanHeader }.ifBlank {
        request.title.takeUnless { it.equals("Answer", ignoreCase = true) }.orEmpty().ifBlank { tr("请确认以下选项", "Please confirm your choices") }
    }
    val eyebrowLabel = buildString {
        if (total > 1) {
            append(tr("第 ${stepIndex + 1} / $total 项", "Question ${stepIndex + 1} / $total"))
            if (humanHeader.isNotBlank() && rawPrompt.isNotBlank()) append(" · $humanHeader")
        } else {
            val tag = if (humanHeader.isNotBlank() && rawPrompt.isNotBlank()) humanHeader else tr("选项确认", "Prompt")
            append(tag)
        }
        if (currentQuestion.multiple) append(tr(" · 可多选", " · Multi-select"))
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (canRespond) palette.primaryBg else palette.textMuted),
            )
            Text(
                eyebrowLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                ),
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (total > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(total) { idx ->
                    val active = idx <= stepIndex
                    Box(
                        Modifier
                            .size(width = if (idx == stepIndex) 16.dp else 8.dp, height = 3.5.dp)
                            .clip(CircleShape)
                            .background(if (active) palette.primaryBg else palette.hairline)
                            .clickable(enabled = idx < stepIndex || questionSatisfied(currentQuestion)) {
                                onStepChange(idx)
                            },
                    )
                }
            }
        }
    }

    val questionScrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val currentOtherActive = currentQuestion.options.isEmpty() || otherSelected(currentQuestion.id)
    LaunchedEffect(stepIndex) {
        questionScrollState.scrollTo(0)
    }
    LaunchedEffect(currentQuestion.id, currentOtherActive, imeVisible) {
        if (currentOtherActive && (currentQuestion.options.isNotEmpty() || imeVisible)) {
            var settledFrames = 0
            var lastMax = -1
            var frameCount = 0
            while (settledFrames < 14 && frameCount < 45) {
                withFrameNanos { }
                frameCount++
                val target = questionScrollState.maxValue
                if (target > 0 && questionScrollState.value < target) {
                    questionScrollState.scrollTo(target)
                }
                if (target == lastMax && target > 0) {
                    settledFrames++
                } else {
                    settledFrames = 0
                    lastMax = target
                }
            }
        }
    }

    val boilerplateLead = remember(structuredBody.leadText) {
        questionBoilerplateLeadRegex.matches(structuredBody.leadText.trim())
    }
    val initialChoiceFocus = remember(request.id) { FocusRequester() }
    val initialChoice = request.questions.firstOrNull()?.let { q -> defaultQuestionIndices(q).firstOrNull()?.let { q.id to it } }
    if (initialChoice != null && stepIndex == 0) {
        LaunchedEffect(request.id) {
            withFrameNanos { }
            runCatching { initialChoiceFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(questionScrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!canRespond) {
            DisconnectedRecoveryBanner(
                palette = palette,
                reconnecting = reconnecting,
                answering = request.answering,
                externalLogin = false,
                onReconnect = actions::reconnect,
            )
        }

        if (structuredBody.leadText.isNotBlank() && !boilerplateLead) {
            Text(
                structuredBody.leadText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = palette.textSecondary,
            )
        }

        AnimatedContent(
            targetState = stepIndex,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(PrismSprings.pagePushOffset()) { it / 5 } + fadeIn(tween(220, easing = PrismCurves.EaseOutCubic))) togetherWith
                        (slideOutHorizontally(PrismSprings.pagePushOffset()) { -it / 5 } + fadeOut(tween(180, easing = PrismCurves.EaseInCubic)))
                } else {
                    (slideInHorizontally(PrismSprings.pagePushOffset()) { -it / 5 } + fadeIn(tween(220, easing = PrismCurves.EaseOutCubic))) togetherWith
                        (slideOutHorizontally(PrismSprings.pagePushOffset()) { it / 5 } + fadeOut(tween(180, easing = PrismCurves.EaseInCubic)))
                }
            },
            label = "question-step",
        ) { targetStep ->
            val question = request.questions[targetStep]
            val (_, qPrompt) = requestQuestionCaption(question.header, question.prompt)
            val qHeader = question.header.trim()
            val displayPrompt = qPrompt.ifBlank { qHeader }.ifBlank { heroPrompt }
            val otherInputFocus = remember(request.id, question.id) { FocusRequester() }
            val currentSelection = selection(question)
            val otherActive = otherSelected(question.id)

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    displayPrompt,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.5.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = palette.textPrimary,
                )

                Surface(
                    shape = LumaCardShape(16.dp),
                    color = palette.insetBg,
                    border = BorderStroke(0.5.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (question.options.isEmpty()) {
                            InlineAnswerField(
                                value = freeText(question.id),
                                onValueChange = { onFreeTextChange(question.id, it) },
                                placeholder = tr("输入你的回答…", "Type your answer…"),
                                secret = question.secret,
                                enabled = !request.answering,
                                palette = palette,
                                focusRequester = otherInputFocus,
                            )
                        } else {
                            question.options.forEachIndexed { idx, option ->
                                if (idx > 0) {
                                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                }
                                val chosen = idx in currentSelection && (!otherActive || question.multiple)
                                val focusMod = if (question.id == initialChoice?.first && idx == initialChoice.second) {
                                    Modifier.focusRequester(initialChoiceFocus).focusProperties { canFocus = true }
                                } else {
                                    Modifier
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (chosen) palette.selectedRowBg else Color.Transparent)
                                        .then(focusMod)
                                        .clickable(
                                            enabled = !request.answering,
                                            role = if (question.multiple) Role.Checkbox else Role.RadioButton,
                                        ) {
                                            if (question.multiple) {
                                                val next = if (idx in currentSelection) currentSelection - idx else currentSelection + idx
                                                onSelectOptions(question.id, next)
                                            } else {
                                                onOtherSelectedChange(question.id, false)
                                                onSelectOptions(question.id, listOf(idx))
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SelectionGlyph(
                                        selected = chosen,
                                        multiple = question.multiple,
                                        accent = palette.primaryBg,
                                        accentCheck = palette.primaryText,
                                        muted = palette.textMuted,
                                    )
                                    Text(
                                        option,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = palette.textPrimary,
                                    )
                                }
                            }

                            if (question.allowOther) {
                                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (otherActive) palette.selectedRowBg else Color.Transparent)
                                        .clickable(
                                            enabled = !request.answering,
                                            role = if (question.multiple) Role.Checkbox else Role.RadioButton,
                                        ) {
                                            if (question.multiple) {
                                                onOtherSelectedChange(question.id, !otherActive)
                                            } else {
                                                val next = !otherActive
                                                onOtherSelectedChange(question.id, next)
                                                if (next) onSelectOptions(question.id, emptyList())
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SelectionGlyph(
                                        selected = otherActive,
                                        multiple = question.multiple,
                                        accent = palette.primaryBg,
                                        accentCheck = palette.primaryText,
                                        muted = palette.textMuted,
                                    )
                                    Text(
                                        tr("其他（自定义输入）", "Other (custom input)"),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            fontWeight = if (otherActive) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (otherActive) palette.textPrimary else palette.textSecondary,
                                    )
                                }
                                AnimatedVisibility(
                                    visible = otherActive,
                                    enter = collapsibleEnter(),
                                    exit = collapsibleExit(),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        LaunchedEffect(otherActive) {
                                            if (otherActive) runCatching { otherInputFocus.requestFocus() }
                                        }
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = palette.hairline,
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                        )
                                        InlineAnswerField(
                                            value = freeText(question.id),
                                            onValueChange = { onFreeTextChange(question.id, it) },
                                            placeholder = tr("输入补充或自定义回答…", "Add notes or custom answer…"),
                                            secret = question.secret,
                                            enabled = !request.answering,
                                            palette = palette,
                                            focusRequester = otherInputFocus,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val nativeChoices = remember(request.choices, request.submitChoice, request.skipChoice) {
        if (request.submitChoice != null) emptyList()
        else request.choices.filterNot { it.id == request.skipChoice }
    }
    val onLastStep = stepIndex >= total - 1
    val allSatisfied = request.questions.all(questionSatisfied)

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        request.skipChoice?.let { skip ->
            SheetCapsuleButton(
                label = tr("跳过", "Skip"),
                enabled = canRespond && !request.answering,
                containerColor = palette.secondaryPillBg,
                contentColor = palette.secondaryPillText,
                horizontalPadding = 16.dp,
                minHeight = 46.dp,
                onClick = { actions.answer(request.id, skip, emptyMap()) },
            )
            Spacer(Modifier.width(8.dp))
        }
        FractionalWidthReveal(visible = stepIndex > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetCapsuleButton(
                    label = tr("上一项", "Back"),
                    enabled = !request.answering && stepIndex > 0,
                    containerColor = palette.secondaryPillBg,
                    contentColor = palette.secondaryPillText,
                    horizontalPadding = 16.dp,
                    minHeight = 46.dp,
                    onClick = { if (stepIndex > 0) onStepChange(stepIndex - 1) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        SheetCapsuleButton(
            label = if (!onLastStep) tr("下一项", "Next") else tr("提交", "Submit"),
            enabled = if (!onLastStep) {
                !request.answering && questionSatisfied(currentQuestion)
            } else {
                canRespond && !request.answering && request.submitChoice != null && allSatisfied
            },
            containerColor = palette.primaryBg,
            contentColor = palette.primaryText,
            fontWeight = FontWeight.SemiBold,
            animateLabel = total > 1,
            modifier = Modifier.weight(1f),
            minHeight = 46.dp,
            onClick = {
                if (!onLastStep) {
                    onStepChange(stepIndex + 1)
                } else {
                    request.submitChoice?.let { submit ->
                        actions.answer(request.id, submit, enteredAnswers())
                    }
                }
            },
        )
    }

    if (onLastStep && nativeChoices.isNotEmpty()) {
        ApprovalChoicesSection(
            choices = nativeChoices,
            palette = palette,
            enabled = canRespond && !request.answering && allSatisfied,
            onSelect = { choiceId -> actions.answer(request.id, choiceId, enteredAnswers()) },
        )
    }

    if (request.answering && canRespond) {
        Text(
            tr("正在提交答复…", "Submitting reply…"),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = palette.textMuted,
        )
    }
}

@Composable
private fun FractionalWidthReveal(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val fraction by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spatialSpring(),
        label = "pill-split-fraction",
    )
    if (!visible && fraction <= 0.001f) return
    Layout(
        modifier = Modifier
            .clipToBounds()
            .graphicsLayer {
                val f = fraction.coerceIn(0f, 1f)
                alpha = f
                val scale = 0.86f + 0.14f * f
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier),
        content = content,
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0))
        val w = (placeable.width * fraction.coerceIn(0f, 1f)).roundToInt()
        layout(w, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
}

@Composable
private fun InlineAnswerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean,
    enabled: Boolean,
    palette: RequestSheetPalette,
    focusRequester: FocusRequester,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = palette.textPrimary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        cursorBrush = SolidColor(palette.primaryBg),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .heightIn(min = 44.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = palette.textMuted,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun SheetCapsuleButton(
    label: String,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Medium,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    minHeight: androidx.compose.ui.unit.Dp = 46.dp,
    animateLabel: Boolean = false,
    onClick: () -> Unit,
) {
    val latestOnClick by rememberUpdatedState(onClick)
    val haptic = LocalHapticFeedback.current
    val effectiveContainer = if (enabled) containerColor else containerColor.copy(alpha = 0.38f)
    val effectiveContent = if (enabled) contentColor else contentColor.copy(alpha = 0.48f)
    Surface(
        onClick = {
            haptic.tick()
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = effectiveContainer,
        contentColor = effectiveContent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .heightIn(min = minHeight)
            .elasticPress(enabled = enabled, stretch = false)
            .semantics {
                if (enabled) onClick { latestOnClick(); true }
            },
    ) {
        Box(
            Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (animateLabel) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "capsule-label",
                ) { targetLabel ->
                    Text(
                        targetLabel,
                        modifier = if (targetLabel != label) Modifier.clearAndSetSemantics {} else Modifier,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                        ),
                        color = effectiveContent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                    ),
                    color = effectiveContent,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SelectionGlyph(
    selected: Boolean,
    multiple: Boolean,
    accent: Color,
    accentCheck: Color,
    muted: Color,
) {
    Canvas(Modifier.size(18.dp)) {
        val strokePx = 1.5.dp.toPx()
        if (multiple) {
            val radius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            if (selected) {
                drawRoundRect(color = accent, cornerRadius = radius)
                val path = Path().apply {
                    moveTo(size.width * 0.27f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.69f)
                    lineTo(size.width * 0.75f, size.height * 0.34f)
                }
                drawPath(
                    path = path,
                    color = accentCheck,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                val inset = strokePx / 2f
                drawRoundRect(
                    color = muted.copy(alpha = 0.7f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = radius,
                    style = Stroke(width = strokePx),
                )
            }
        } else {
            val r = (size.minDimension - strokePx) / 2f
            drawCircle(
                color = if (selected) accent else muted.copy(alpha = 0.7f),
                radius = r,
                style = Stroke(width = strokePx),
            )
            if (selected) {
                drawCircle(color = accent, radius = size.minDimension * 0.26f)
            }
        }
    }
}

@Composable
private fun ChevronRightGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.22f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            lineTo(size.width * 0.36f, size.height * 0.78f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
