package io.github.modao7067.luma.gui.ui

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Magnifier::class)
class SelectionMagnifierShadow {
    @Implementation fun show(x: Float, y: Float) {}
    @Implementation fun show(x: Float, y: Float, windowX: Float, windowY: Float) {}
    @Implementation fun update() {}
    @Implementation fun dismiss() {}
}
