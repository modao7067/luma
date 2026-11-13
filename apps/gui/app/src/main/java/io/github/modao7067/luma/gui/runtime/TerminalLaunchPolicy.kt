package io.github.modao7067.luma.gui.runtime

import java.util.UUID

internal object TerminalLaunchPolicy {
    fun sessionWindowId(environment: String, cli: String, threadId: String): String =
        UUID.nameUUIDFromBytes("luma-session-terminal:$environment:$cli:$threadId".toByteArray(Charsets.UTF_8))
            .toString().replace("-", "")

    fun mode(environment: String, shell: Boolean, managedRoot: Boolean = false): String? =
        if (environment == "noroot" || (environment == "root" && managedRoot)) if (shell) "ordinary" else "session" else null
}
