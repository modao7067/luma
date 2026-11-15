package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.ConversationItem

internal fun userBubbleColors(colors: ColorScheme): Pair<Color, Color> =
    if (colors.surface.luminance() < 0.5f) {
        Color(0xFF222220) to Color(0xFFF0EFEC)
    } else {
        Color(0xFFF0EFEC) to Color(0xFF141413)
    }

@Composable
internal fun UserMessageBubble(item: ConversationItem, actions: CockpitActions) {
    val colors = MaterialTheme.colorScheme
    val (bubbleBg, bubbleFg) = userBubbleColors(colors)
    Column(
        Modifier.fillMaxWidth().testTag("user-message:${item.id}"),
        horizontalAlignment = Alignment.End,
    ) {
        val media = messageMedia(item)
        var mediaVisible by remember(item.id, media) { mutableStateOf(media.isNotEmpty()) }
        if (media.isNotEmpty()) {
            MessageMedia(media, user = true, onVisibilityChanged = { visible -> mediaVisible = visible })
            if (mediaVisible && item.text.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
        if (item.text.isNotEmpty()) Surface(
            shape = LumaCardShape(18.dp),
            color = bubbleBg,
            modifier = Modifier.widthIn(max = 316.dp).testTag("user-bubble:${item.id}"),
        ) {
            LumaSelectionContainer {
                Text(item.text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyLarge, color = bubbleFg)
            }
        }
        UserDeliveryCaption(item, actions)
    }
}

@Composable
private fun UserDeliveryCaption(item: ConversationItem, actions: CockpitActions) {
    val recoverable = item.delivery == "rejected" || item.canRecover
    val caption = when (item.delivery) {
        "pending", "unknown" -> tr("发送中", "Sending")
        "rejected" -> tr("未发送", "Not sent")
        else -> null
    }
    var retainedDelivery by remember(item.id) { mutableStateOf(item.delivery) }
    var retainedRecoverable by remember(item.id) { mutableStateOf(recoverable) }
    SideEffect {
        if (caption != null) {
            retainedDelivery = item.delivery
            retainedRecoverable = recoverable
        }
    }
    AnimatedVisibility(
        visible = caption != null,
        enter = expandVertically(spatialSizeSpring()) + fadeIn(),
        exit = shrinkVertically(spatialSizeSpring()) + fadeOut(),
    ) {
        val effectiveText = caption ?: if (retainedDelivery == "rejected") tr("未发送", "Not sent") else tr("发送中", "Sending")
        val isSending = effectiveText == "发送中" || effectiveText == "Sending"
        Row(
            Modifier.widthIn(max = 316.dp).padding(top = 4.dp, start = 8.dp, end = 8.dp)
                .testTag("user-delivery:${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                effectiveText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            if (retainedDelivery == "rejected" || retainedRecoverable) {
                IconButton(
                    onClick = { actions.recoverSubmission(item.id) },
                    enabled = recoverable,
                    modifier = Modifier.size(32.dp).semantics { contentDescription = tr("编辑未发送消息", "Edit unsent message") },
                ) {
                    MenuIcon(MenuSymbol.EDIT, size = 14.dp)
                }
            }
        }
    }
}
