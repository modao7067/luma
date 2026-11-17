package io.github.modao7067.luma.gui.session
import io.github.modao7067.luma.gui.agent.obj
import org.junit.Assert.*
import org.junit.Test
class NativeSettingsTest {
    @Test fun emptyRpcAckIsNotSettingConfirmation() { assertFalse(NativeSettings.matches(obj("model" to "new"), obj())) }
    @Test fun requiresMatchingNativeValues() {
        val expected = obj("model" to "new", "effort" to "high", "permissions" to ":workspace", "threadId" to "one")
        assertFalse(NativeSettings.matches(expected, obj("model" to "old", "effort" to "high")))
        assertTrue(NativeSettings.matches(expected, obj("model" to "new", "effort" to "high", "activePermissionProfile" to obj("id" to ":workspace"))))
    }
    @Test fun clearingSpeedIsNativeDefaultNotMissingData() {
        assertFalse(NativeSettings.matches(obj("serviceTier" to org.json.JSONObject.NULL), obj()))
        assertTrue(NativeSettings.matches(obj("serviceTier" to org.json.JSONObject.NULL), obj("serviceTier" to "default")))
    }
    @Test fun collaborationModeMustMatchToConfirm() {
        val expected = obj("collaborationMode" to obj("mode" to "plan"))
        assertFalse(NativeSettings.matches(expected, obj()))
        assertFalse(NativeSettings.matches(expected, obj("collaborationMode" to obj("mode" to "default"))))
        assertTrue(NativeSettings.matches(expected, obj("collaborationMode" to obj("mode" to "plan"))))
    }
}
