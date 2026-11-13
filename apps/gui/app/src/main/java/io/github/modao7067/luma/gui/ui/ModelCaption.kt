package io.github.modao7067.luma.gui.ui

internal fun modelEffortLabel(model: String, effort: String): String =
    listOf(model, effort).filter { it.isNotBlank() }.joinToString("·")
