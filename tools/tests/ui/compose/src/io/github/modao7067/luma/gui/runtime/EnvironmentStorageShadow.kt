package io.github.modao7067.luma.gui.runtime

import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

@Implements(Environment::class)
class EnvironmentStorageShadow : ShadowEnvironment() {
    companion object {
        var granted = false
        @Implementation @JvmStatic fun isExternalStorageManager(): Boolean = granted
    }
}
