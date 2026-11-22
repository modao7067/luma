package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.*

@Composable
internal fun ModelMenu(expanded: Boolean, dismiss: () -> Unit, state: CockpitState, view: SessionView, actions: CockpitActions, preferAbove: Boolean = false, anchor: MorphAnchor? = null) {
    val model = state.models.find { it.id == view.summary.model }
    val idle = !view.sending && !view.uncertainSubmission && !view.configuring && view.connected && view.activeTurnId == null && view.queue.isEmpty()
    MorphMenu(expanded, dismiss, anchor, width = 300.dp, placement = MorphPlacement.End, preferAbove = preferAbove) {
        var section by remember { mutableStateOf<String?>(null) }
        AnimatedVisibility(
            visible = !idle,
            enter = menuUnfoldEnter(),
            exit = menuFoldExit(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                shape = LumaCardShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    when {
                        view.sending -> tr("等待送达确认后可修改", "Editable after delivery is confirmed")
                        view.uncertainSubmission -> tr("核对送达状态后可修改", "Editable after verifying delivery status")
                        view.configuring -> tr("正在同步 CLI 设置…", "Syncing CLI settings…")
                        else -> tr("任务与队列结束后可修改", "Editable after active turn and queue finish")
                    },
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        val speeds = model?.speeds.orEmpty()
        val selectedSpeed = when {
            speeds.any { it.id == view.speed } -> view.speed
            view.speed.isBlank() && speeds.any { it.id == "default" } -> "default"
            else -> view.speed
        }
        val rows = buildList {
            add("模型" to (model?.label ?: view.summary.model))
            add("思考强度" to view.effort)
            if (speeds.isNotEmpty()) add("速度" to (speeds.find { it.id == selectedSpeed }?.label ?: view.speed))
        }
        rows.forEach { (name, value) ->
            val icon = when (name) {
                "模型" -> MenuSymbol.MODEL
                "思考强度" -> MenuSymbol.THINKING
                else -> MenuSymbol.SPEED
            }
            val currentProvider = if (name == "模型" && '/' in view.summary.model) view.summary.model.substringBefore('/') else ""
            ActionMenuItem(
                label = when (name) {
                    "模型" -> tr("模型", "Model")
                    "思考强度" -> if (view.summary.cli == "OpenCode") tr("思考强度 / 变体", "Reasoning / Variant") else tr("思考强度", "Reasoning effort")
                    else -> tr("速度", "Speed")
                },
                icon = icon,
                description = value,
                badge = currentProvider,
                badgeOnDescription = name == "模型",
                trailing = if (section == name) MenuSymbol.UP else MenuSymbol.DOWN,
                enabled = true,
                onClick = { section = if (section == name) null else name },
            )
            MenuSectionReveal(
                expanded = section == name,
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                cornerRadius = 16.dp,
                horizontalPadding = 10.dp,
                verticalPadding = 4.dp,
            ) {
                val options = when (name) {
                    "模型" -> remember(state.models) { state.models.distinctBy { it.id }.map { RequestChoice(it.id, it.label) } }
                    "思考强度" -> remember(model?.efforts) { model?.efforts.orEmpty().distinct().map { RequestChoice(it, it) } }
                    else -> remember(model?.speeds) { model?.speeds.orEmpty().distinctBy { it.id } }
                }
                if (options.isEmpty()) {
                    Text(
                        tr("CLI 未提供可选项", "No options provided by CLI"),
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MenuChoiceList(options, key = { it.id }) { option ->
                        val selected = when (name) {
                            "模型" -> view.summary.model
                            "思考强度" -> view.effort
                            else -> selectedSpeed
                        }
                        val providerId = if (name == "模型" && '/' in option.id) option.id.substringBefore('/') else ""
                        ActionMenuItem(
                            label = option.label,
                            icon = icon,
                            badge = providerId,
                            selected = option.id == selected,
                            selectionAtStart = name == "模型",
                            enabled = idle,
                            onClick = {
                                when (name) {
                                    "模型" -> actions.configure(
                                        option.id,
                                        view.effort.takeIf { it in state.models.find { m -> m.id == option.id }?.efforts.orEmpty() }
                                            ?: state.models.find { it.id == option.id }?.defaultEffort.orEmpty(),
                                    )
                                    "思考强度" -> actions.configure(view.summary.model, option.id)
                                    else -> actions.configureSpeed(option.id)
                                }
                                dismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}
