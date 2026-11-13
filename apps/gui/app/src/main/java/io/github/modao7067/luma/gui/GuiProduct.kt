package io.github.modao7067.luma.gui

object GuiProduct {
    const val environment: String = BuildConfig.DEFAULT_ENVIRONMENT
    const val isRoot: Boolean = environment == "root"
    fun usesRootExecution(context: android.content.Context): Boolean =
        isRoot || io.github.modao7067.luma.gui.runtime.DebianRootMode.isEnabled(context)
    fun runtimePath(files: java.io.File, relative: String): String =
        if (isRoot) "/proc/1/root${files.absolutePath}/runtime/$relative" else "/opt/luma/$relative"
    fun agentRelative(env: String = environment): String =
        if (env == "root") "agent/root" else "agent"
    fun agentRuntimePath(env: String = environment): String =
        "runtime/${agentRelative(env)}"
}
