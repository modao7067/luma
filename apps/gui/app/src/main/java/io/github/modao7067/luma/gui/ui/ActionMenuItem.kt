package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.R

@Composable
internal fun MenuIcon(
    symbol: MenuSymbol,
    warning: Boolean = false,
    size: Dp = 20.dp,
    rotationDegrees: Float = 0f,
    rotationProvider: (() -> Float)? = null,
    containerLuminance: Float = MaterialTheme.colorScheme.surface.luminance(),
) {
    val resource = when (symbol) {
        MenuSymbol.TERMINAL -> R.drawable.ic_menu_terminal
        MenuSymbol.BROWSER -> R.drawable.ic_menu_language
        MenuSymbol.SEARCH -> R.drawable.luma_menu_search
        MenuSymbol.FOLDER -> R.drawable.ic_menu_folder
        MenuSymbol.SKILL -> R.drawable.ic_menu_extension_rounded
        MenuSymbol.PLUGINS -> R.drawable.ic_menu_widgets_rounded
        MenuSymbol.MCP -> R.drawable.ic_menu_hub_rounded
        MenuSymbol.EXTENSION -> R.drawable.ic_menu_extension
        MenuSymbol.TOOLBOX -> R.drawable.ic_menu_home_repair_service
        MenuSymbol.NEW_CHAT -> R.drawable.ic_menu_add_comment
        MenuSymbol.CAMERA -> R.drawable.ic_menu_photo_camera
        MenuSymbol.IMAGE -> R.drawable.ic_menu_image
        MenuSymbol.PASTE -> R.drawable.ic_menu_content_paste
        MenuSymbol.FILE -> R.drawable.ic_menu_description
        MenuSymbol.CODE -> R.drawable.ic_menu_code
        MenuSymbol.DATA -> R.drawable.ic_menu_data_object
        MenuSymbol.TABLE -> R.drawable.ic_menu_table_chart
        MenuSymbol.ARTICLE -> R.drawable.ic_menu_article
        MenuSymbol.TEXT -> R.drawable.ic_menu_notes
        MenuSymbol.DOWNLOAD -> R.drawable.ic_menu_download
        MenuSymbol.OPEN_EXTERNAL -> R.drawable.ic_menu_open_in_new
        MenuSymbol.PDF -> R.drawable.ic_menu_picture_as_pdf
        MenuSymbol.AUDIO -> R.drawable.ic_menu_audio_file
        MenuSymbol.VIDEO -> R.drawable.ic_menu_video_file
        MenuSymbol.APK -> R.drawable.ic_menu_android
        MenuSymbol.ARCHIVE -> R.drawable.ic_menu_folder_zip
        MenuSymbol.COMPRESS -> R.drawable.ic_menu_compress
        MenuSymbol.DIFF -> R.drawable.ic_menu_difference
        MenuSymbol.SEND -> R.drawable.ic_menu_send
        MenuSymbol.STOP -> R.drawable.ic_menu_stop
        MenuSymbol.REFRESH -> R.drawable.ic_menu_refresh
        MenuSymbol.EDIT -> R.drawable.ic_menu_edit
        MenuSymbol.EDIT_DOCUMENT -> R.drawable.ic_menu_edit_document
        MenuSymbol.DELETE -> R.drawable.ic_menu_delete
        MenuSymbol.MODEL -> R.drawable.ic_menu_smart_toy
        MenuSymbol.THINKING -> R.drawable.ic_menu_psychology
        MenuSymbol.LIGHTBULB -> R.drawable.ic_menu_lightbulb
        MenuSymbol.SPEED -> R.drawable.ic_menu_speed
        MenuSymbol.LOCK -> R.drawable.ic_menu_lock
        MenuSymbol.WARNING -> R.drawable.ic_menu_warning_amber
        MenuSymbol.SETTINGS -> R.drawable.ic_menu_settings
        MenuSymbol.BATTERY -> R.drawable.ic_menu_battery_saver
        MenuSymbol.NOTIFICATIONS -> R.drawable.ic_menu_notifications
        MenuSymbol.ACCESSIBILITY -> R.drawable.ic_menu_accessibility_new
        MenuSymbol.CHECK -> R.drawable.ic_menu_check
        MenuSymbol.DOWN -> R.drawable.ic_menu_expand_more
        MenuSymbol.UP -> R.drawable.ic_menu_expand_less
        MenuSymbol.HAND -> R.drawable.ic_menu_pan_tool
        MenuSymbol.SHIELD -> R.drawable.ic_menu_verified_user
        MenuSymbol.PLAN -> R.drawable.ic_menu_fact_check
        MenuSymbol.BLOCK -> R.drawable.ic_menu_block
        MenuSymbol.RULES -> R.drawable.ic_menu_rule
        MenuSymbol.UNKNOWN -> R.drawable.ic_menu_help_outline
        MenuSymbol.AUTO_ALLOW -> R.drawable.ic_menu_bolt
    }
    val tint = if (warning) {
        if (containerLuminance > 0.5f) Color(0xFF996000) else Color(0xFFFFC76B)
    } else {
        LocalContentColor.current
    }
    Icon(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .then(
                if (rotationProvider != null) {
                    Modifier.graphicsLayer { rotationZ = rotationProvider() }
                } else if (rotationDegrees != 0f) {
                    Modifier.graphicsLayer { rotationZ = rotationDegrees }
                } else {
                    Modifier
                },
            ),
        tint = tint,
    )
}

@Composable
internal fun ActionMenuItem(
    label: String,
    icon: MenuSymbol,
    description: String = "",
    badge: String = "",
    selected: Boolean = false,
    warning: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: MenuSymbol? = null,
    iconContainer: Boolean = false,
    statusContent: (@Composable () -> Unit)? = null,
    statusAtEdge: Boolean = false,
    selectionAtStart: Boolean = false,
    badgeOnDescription: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val active = LocalMenuActive.current
    val contentAlpha = if (enabled) 1f else 0.38f
    val rowContainer = if (selected) colors.secondaryContainer else Color.Transparent
    val foreground = (when {
        destructive -> colors.error
        selected -> colors.onSecondaryContainer
        else -> colors.onSurface
    }).copy(alpha = contentAlpha)
    val hasChevron = trailing == MenuSymbol.UP || trailing == MenuSymbol.DOWN
    val chevronFractionState = if (hasChevron) {
        animateFloatAsState(
            targetValue = if (trailing == MenuSymbol.UP) 1f else 0f,
            animationSpec = revealSpring(),
            label = "menu-chevron-rotation",
        )
    } else null
    val rowShape = CircleShape
    val trailingContent: (@Composable () -> Unit)? = if (
        (statusAtEdge && statusContent != null) || (!selectionAtStart && selected) || trailing != null ||
        (!selectionAtStart && (badge.isNotBlank() || statusContent != null))
    ) ({
            if (statusAtEdge && statusContent != null) {
                statusContent()
            } else if ((!selectionAtStart && selected) || trailing != null) {
                CompositionLocalProvider(
                    LocalContentColor provides (if (selected) colors.primary else colors.onSurfaceVariant).copy(alpha = contentAlpha),
                ) {
                    if (selected && !selectionAtStart) {
                        MenuIcon(MenuSymbol.CHECK, size = 19.dp)
                    } else if (hasChevron && chevronFractionState != null) {
                        MenuIcon(MenuSymbol.DOWN, size = 20.dp, rotationProvider = { revealFraction(chevronFractionState.value) * 180f })
                    } else {
                        MenuIcon(trailing!!, size = 19.dp)
                    }
                }
            } else if (!selectionAtStart && (badge.isNotBlank() || statusContent != null)) {
                Spacer(Modifier.width(19.dp))
            }
    }) else null
    val haptic = LocalHapticFeedback.current
    val morphRows = LocalMorphRows.current
    val morphRow = remember(morphRows) { morphRows?.let { MorphRows.Row(active && enabled, onClick) } }
    if (morphRows != null && morphRow != null) {
        DisposableEffect(morphRows, morphRow) {
            morphRows.rows.add(morphRow)
            onDispose { morphRows.rows.remove(morphRow) }
        }
        SideEffect {
            morphRow.enabled = active && enabled
            morphRow.onClick = onClick
        }
    }
    val held = morphRows != null && morphRows.held === morphRow
    DropdownMenuItem(
        onClick = {
            haptic.tick()
            onClick()
        },
        enabled = active && enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .then(if (morphRow != null) Modifier.onGloballyPositioned { morphRow.coordinates = it } else Modifier)
            .clip(rowShape)
            .then(if (selected) Modifier.background(rowContainer, rowShape) else Modifier)
            .then(if (held) Modifier.background(colors.onSurface.copy(alpha = 0.1f), rowShape) else Modifier)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                this.role = Role.Button
            },
        contentPadding = PaddingValues(start = 12.dp, end = if (selectionAtStart && badge.isNotBlank()) 19.dp else 12.dp, top = 7.dp, bottom = 7.dp),
        colors = MenuDefaults.itemColors(
            textColor = foreground, leadingIconColor = foreground, trailingIconColor = foreground,
            disabledTextColor = foreground, disabledLeadingIconColor = foreground, disabledTrailingIconColor = foreground,
        ),
        leadingIcon = {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides foreground) {
                    if (selectionAtStart && selected) {
                        CompositionLocalProvider(LocalContentColor provides colors.primary.copy(alpha = contentAlpha)) {
                            MenuIcon(MenuSymbol.CHECK, size = 20.dp)
                        }
                    } else if (iconContainer) {
                        val containerBg = if (selected) colors.primary.copy(alpha = contentAlpha) else colors.surfaceContainerHighest.copy(alpha = contentAlpha)
                        Surface(
                            shape = CircleShape,
                            color = containerBg,
                            contentColor = (if (selected) colors.onPrimary else colors.onSurfaceVariant).copy(alpha = contentAlpha),
                        ) {
                            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                MenuIcon(icon, warning && enabled, size = 19.dp, containerLuminance = containerBg.luminance())
                            }
                        }
                    } else {
                        MenuIcon(icon, warning && enabled, size = 20.dp)
                    }
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = foreground,
                        maxLines = 1,
                        overflow = if (statusAtEdge) androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = (!badgeOnDescription && badge.isNotBlank()) || statusContent != null),
                    )
                    if (statusContent != null && !statusAtEdge) {
                        statusContent()
                    } else if (badge.isNotBlank() && !badgeOnDescription) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.62f else 0.38f),
                            maxLines = 1,
                            overflow = if (selectionAtStart) androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
                            textAlign = if (selectionAtStart) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Unspecified,
                            modifier = if (selectionAtStart) Modifier.layout { measurable, constraints ->
                                val maxBadgeWidth = ((constraints.maxWidth - 6.dp.roundToPx()) / 2).coerceAtLeast(0)
                                val badgePlaceable = measurable.measure(constraints.copy(maxWidth = maxBadgeWidth))
                                layout(badgePlaceable.width, badgePlaceable.height) {
                                    badgePlaceable.placeRelative(0, 0)
                                }
                            } else Modifier,
                        )
                    }
                }
                if (description.isNotBlank() && badgeOnDescription) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            description,
                            Modifier.weight(1f).alignByBaseline(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.88f else 0.38f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (badge.isNotBlank()) {
                            Text(
                                badge,
                                Modifier.alignByBaseline().layout { measurable, constraints ->
                                    val maxBadgeWidth = ((constraints.maxWidth - 6.dp.roundToPx()) * 0.6f).toInt().coerceAtLeast(0)
                                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxBadgeWidth))
                                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.62f else 0.38f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                } else if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.88f else 0.38f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingIcon = trailingContent,
    )
}
