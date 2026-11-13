package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

internal fun menuMotionSpec(entering: Boolean) = tween<Float>(if (entering) 200 else 120, easing = FastOutSlowInEasing)
