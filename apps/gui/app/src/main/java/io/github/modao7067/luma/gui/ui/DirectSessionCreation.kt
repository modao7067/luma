package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.CockpitActions

internal fun CockpitActions.createDefaultCliSession(cli: String = "Codex") =
    createCliSession(cli, title = "", environment = environment, cwd = "/workspace", model = "")
