/*
 * Copyright 2021 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See THIRD_PARTY_NOTICES.md for the pinned upstream and adaptations.
 */
package io.github.modao7067.luma.gui.ui

import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.min

internal val LocalMenuActive = compositionLocalOf { true }
internal val LocalMenuViewportHeight = compositionLocalOf<() -> Int> { { Int.MAX_VALUE } }

@Composable
internal fun OriginMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 228.dp,
    horizontalAlignment: Alignment.Horizontal? = null,
    preferAbove: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalSessionInteractive.current) {
        SideEffect { if (expanded) onDismiss() }
        return
    }
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    if (expandedState.currentState || expandedState.targetState) {
        val density = LocalDensity.current
        val hostView = LocalView.current
        val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val edge = with(density) { 8.dp.roundToPx() }
        val top = safe.getTop(density) + edge
        val bottom = safe.getBottom(density) + edge
        val widthPx = with(density) { width.roundToPx() }
        val position = remember(widthPx, edge, top, bottom, horizontalAlignment, preferAbove, hostView) {
            StationaryMenuPosition(edge, top, bottom, horizontalAlignment, preferAbove) { action ->
                hostView.post { action() }
            }
        }
        val latestDismiss by rememberUpdatedState(onDismiss)
        Popup(
            popupPositionProvider = position,
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                flags = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            ),
        ) {
            ConfigureFloatingPopupIme()
            GuiWindowScale {
                Layout(
                    modifier = Modifier.then(position).testTag("action-menu-carrier").pointerInput(position) {
                        detectTapGestures { point ->
                            val size = position.surfaceSize
                            val y = position.surfaceY
                            if (point.x < 0 || point.y < y || point.x >= size.width || point.y >= y + size.height) latestDismiss()
                        }
                    },
                    content = {
                        CompositionLocalProvider(
                            LocalMenuActive provides expanded,
                            LocalMenuViewportHeight provides { position.contentMaxHeight },
                        ) {
                            MaterialDropdownContent(expandedState, position, content)
                        }
                    },
                ) { children, constraints ->
                    val frame = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val carrierHeight = (frame.height - top - bottom).coerceAtLeast(1)
                    val carrierWidth = min(widthPx, (frame.width - edge * 2).coerceAtLeast(1))
                    position.prepareMeasure(frame, carrierHeight)
                    val menu = children.single().measure(Constraints(
                        minWidth = carrierWidth, maxWidth = carrierWidth,
                        maxHeight = min(carrierHeight, position.contentMaxHeight),
                    ))
                    position.surfaceSize = IntSize(menu.width, menu.height)
                    layout(carrierWidth, carrierHeight) { menu.placeRelative(0, position.surfaceY) }
                }
            }
        }
    }
}

internal class StationaryMenuPosition(
    private val edge: Int,
    private val top: Int,
    private val bottom: Int,
    private val horizontalAlignment: Alignment.Horizontal? = null,
    private val preferAbove: Boolean = false,
    private val postRemeasure: (() -> Unit) -> Unit,
) : PopupPositionProvider, RemeasurementModifier {
    private var remeasurement: Remeasurement? = null
    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }
    private var frame = IntSize.Zero
    private var point: IntOffset? = null
    private var surfaceEdge = 0
    private var growsUp = false
    var surfaceSize = IntSize.Zero
    val surfaceY: Int get() = if (growsUp) surfaceEdge - surfaceSize.height else surfaceEdge
    var contentMaxHeight = Int.MAX_VALUE
        private set
    var pivot = TransformOrigin.Center
        private set

    fun prepareMeasure(size: IntSize, maxHeight: Int) {
        if (frame != size) {
            frame = size
            point = null
            contentMaxHeight = maxHeight
        }
    }

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        point?.let { return it }
        val w = surfaceSize.width.coerceAtLeast(1)
        val h = surfaceSize.height.coerceAtLeast(1)
        val safeBottom = (windowSize.height - bottom).coerceAtLeast(top + 1)
        val preferredX = horizontalAlignment?.let {
            anchorBounds.left + it.align(w, anchorBounds.width, layoutDirection)
        } ?: if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - w
        val maxX = (windowSize.width - w - edge).coerceAtLeast(edge)
        val alternativeX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - w else anchorBounds.left
        val x = when {
            horizontalAlignment != null -> preferredX.coerceIn(edge, maxX)
            preferredX in edge..maxX -> preferredX
            alternativeX in edge..maxX -> alternativeX
            else -> preferredX.coerceIn(edge, maxX)
        }
        val availableAbove = (anchorBounds.top - top).coerceIn(0, popupContentSize.height)
        val availableBelow = (safeBottom - anchorBounds.bottom).coerceAtLeast(0)
        growsUp = if (preferAbove) availableAbove > 0 else availableBelow < h && (availableAbove >= h || availableAbove > availableBelow)
        surfaceEdge = if (growsUp) availableAbove
            else (anchorBounds.bottom - top).coerceIn(0, (popupContentSize.height - 1).coerceAtLeast(0))
        contentMaxHeight = (if (growsUp) surfaceEdge else popupContentSize.height - surfaceEdge).coerceAtLeast(1)
        pivot = TransformOrigin(
            ((anchorBounds.center.x - x).toFloat() / w).coerceIn(0f, 1f),
            if (growsUp) 1f else 0f,
        )
        val p = IntOffset(x, top)
        point = p
        postRemeasure { remeasurement?.forceRemeasure() }
        return p
    }
}

@Composable
private fun MaterialDropdownContent(
    expandedState: MutableTransitionState<Boolean>,
    position: StationaryMenuPosition,
    content: @Composable ColumnScope.() -> Unit,
) {
    @Suppress("DEPRECATION") val transition = updateTransition(expandedState, "DropDownMenu")
    val scale by transition.animateFloat(transitionSpec = { spring(dampingRatio = .9f, stiffness = 1400f) }, label = "menu-scale") { if (it) 1f else .8f }
    val alpha by transition.animateFloat(transitionSpec = { spring(dampingRatio = 1f, stiffness = 3800f) }, label = "menu-alpha") { if (it) 1f else 0f }
    val inspecting = LocalInspectionMode.current
    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = if (inspecting) 1f else scale
            scaleY = if (inspecting) 1f else scale
            this.alpha = if (inspecting) 1f else alpha
            transformOrigin = position.pivot
        },
        shape = LumaCardShape(28.dp),
        color = MenuDefaults.containerColor,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun ConfigureFloatingPopupIme() {
    val root = LocalView.current.rootView
    SideEffect {
        val params = root.layoutParams as? WindowManager.LayoutParams
        if (params != null && params.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            (root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
        }
    }
}
