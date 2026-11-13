package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

internal fun sendDeliveryFixture(delivery: String = "pending", mixed: Boolean = false): SessionView {
    val base = if (mixed) queueAgentsFixture() else sampleView().copy(
        summary = sampleView().summary.copy(cli = "DeepSeek Harness", title = "未命名会话", modelLabel = "DeepSeek-V4.1-Flash"),
        messages = emptyList(), queue = emptyList(), subAgents = emptyList(), activeTurnId = null,
        requests = emptyList(), capabilityNotice = null,
    )
    return base.copy(
        messages = base.messages + ConversationItem("delivery-local-1", "user", "你好", delivery = delivery),
        draft = "", sending = delivery in setOf("pending", "unknown"),
        uncertainSubmission = false, connected = true, loading = false,
        submissionRecovery = "",
    )
}

@Preview(name = "Sending · optimistic bubble and spinner", widthDp = 393, heightDp = 820)
@Composable internal fun SendPendingPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(selected = sendDeliveryFixture()), previewActions)
}
@Preview(name = "Unconfirmed · narrow large type", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable internal fun SendUnconfirmedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(selected = sendDeliveryFixture("unknown")), previewActions)
}
@Preview(name = "Pending with queue and concurrent agents", widthDp = 393, heightDp = 900)
@Composable internal fun SendPendingMixedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(selected = sendDeliveryFixture(mixed = true)), previewActions)
}

@Preview(name = "Rejected · edit without retry", widthDp = 393, heightDp = 820)
@Composable internal fun SendRejectedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(selected = sendDeliveryFixture("rejected").copy(draft = "保留当前输入", error = "fixture rejection")), previewActions)
}
