package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> MenuChoiceList(
    choices: List<T>,
    key: (T) -> String,
    itemContent: @Composable (T) -> Unit,
) {
    val uniqueChoices = choices.distinctBy(key)
    if (uniqueChoices.size <= 10) {
        Column(Modifier.padding(vertical = 4.dp)) {
            uniqueChoices.forEach { itemContent(it) }
        }
    } else {
        val viewportHeight = LocalMenuViewportHeight.current
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val screenHeight = with(LocalDensity.current) { screenHeightDp.roundToPx() }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                val viewport = viewportHeight().let { if (it == Int.MAX_VALUE) screenHeight else it }
                val maxHeight = minOf(constraints.maxHeight, viewport).coerceAtLeast(1)
                val catalog = measurable.measure(constraints.copy(minHeight = 0, maxHeight = maxHeight))
                layout(catalog.width, catalog.height) { catalog.placeRelative(0, 0) }
            },
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(uniqueChoices, key = key, contentType = { "menu-choice" }) { itemContent(it) }
        }
    }
}
