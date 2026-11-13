package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.modao7067.luma.gui.ui.shapes.addSquircleRect
import kotlin.math.min

internal data class LumaCardShape(val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val px = with(density) { radius.toPx() }
        return if (px <= 0f) Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        else if (px >= min(size.width, size.height) * .5f) {
            RoundedCornerShape(radius).createOutline(size, layoutDirection, density)
        } else Outline.Generic(Path().apply { addSquircleRect(size.width, size.height, px) })
    }
}
