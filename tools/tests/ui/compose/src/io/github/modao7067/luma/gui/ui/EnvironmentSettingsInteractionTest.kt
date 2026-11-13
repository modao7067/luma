package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.modao7067.luma.gui.runtime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EnvironmentSettingsInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private class Access : EnvironmentSettingsAccess {
        var state = EnvironmentSettingsState(false, false, false, false, false)
        var reads = 0; var closed = 0; var readFails = false; var openFails = false
        var change: (() -> Unit)? = null
        val opens = mutableListOf<EnvironmentSetting>()
        val wakeLockToggles = mutableListOf<Boolean>()
        val rootSwitchFlow = kotlinx.coroutines.flow.MutableStateFlow(RootSwitchState())
        var rootSwitchStarts = 0
        var rootActive = true
        override fun read(): EnvironmentSettingsState { reads++; check(!readFails); return state }
        override fun observe(onChanged: () -> Unit): AutoCloseable { change = onChanged; return AutoCloseable { closed++; change = null } }
        override fun open(setting: EnvironmentSetting) { if (openFails) throw android.content.ActivityNotFoundException(); opens += setting }
        override fun setWakeLock(enabled: Boolean) { wakeLockToggles += enabled; state = state.copy(wakeLockEnabled = enabled) }
        override val rootSwitch get() = rootSwitchFlow
        override fun startRootSwitch() { rootSwitchStarts++ }
        override suspend fun checkRootActive(): Boolean = rootActive
    }
    private fun show(access: Access, environment: String = "noroot", initial: String = "main", dismiss: () -> Unit = {}, toggle: (String, Boolean) -> Unit = { _, _ -> }) {
        rule.setContent { MaterialTheme {
            SettingsDialog(setOf("Codex"), toggle, dismiss, environment, initial, access)
        } }
    }
    @Test fun noRootNavigationKeepsProvidersAndReturnsToSettingsBeforeClosing() {
        val access = Access(); var dismissed = 0; var toggled: String? = null
        show(access, dismiss = { dismissed++ }, toggle = { cli, _ -> toggled = cli })
        rule.runOnIdle { assertEquals(0, access.reads); assertTrue(access.opens.isEmpty()) }
        rule.onNodeWithText("环境设置").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("手机文件访问").assertExists()
        rule.onNodeWithText("未授权 · /sdcard").assertExists()
        rule.onNodeWithTag("settings-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("CLI 工具").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Codex").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("Codex", toggled); assertEquals(0, dismissed) }
        rule.onNodeWithTag("settings-back").performSemanticsAction(SemanticsActions.OnClick) { it() }; rule.onNodeWithText("环境设置").assertExists()
        rule.onNodeWithTag("settings-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(1, dismissed); assertTrue(access.opens.isEmpty()); assertTrue(access.closed > 0) }
    }
    @Test fun rootNeverShowsTheEntryOrRestoresAnEnvironmentPageAndDoesNotReadNoRootApis() {
        val access = Access(); show(access, "root", "environment")
        rule.onNodeWithText("环境设置").assertDoesNotExist(); rule.onNodeWithText("手机文件访问").assertDoesNotExist()
        rule.onNodeWithText("CLI 工具").assertExists()
        rule.runOnIdle { assertEquals(0, access.reads); assertTrue(access.opens.isEmpty()) }
    }
    @Test fun allSystemActionsAreUserInitiatedAndUseTheirOwnRows() {
        val access = Access(); show(access, initial = "environment")
        rule.runOnIdle { assertTrue(access.opens.isEmpty()) }
        for ((title, action) in listOf("手机文件访问" to EnvironmentSetting.STORAGE, "Shizuku 提权" to EnvironmentSetting.SHIZUKU, "忽略电池优化" to EnvironmentSetting.BATTERY, "通知" to EnvironmentSetting.NOTIFICATIONS, "无障碍保活" to EnvironmentSetting.ACCESSIBILITY)) {
            rule.onNodeWithText(title).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            rule.runOnIdle { assertEquals(action, access.opens.last()) }
        }
        rule.runOnIdle { assertEquals(5, access.opens.size) }
    }
    @Test fun shizukuRowReflectsInstallRunAndAuthorizationStates() {
        val access = Access(); show(access, initial = "environment")
        rule.onNodeWithText("未安装 · 点击查看").performScrollTo().assertExists()
        rule.runOnIdle { access.state = access.state.copy(shizukuInstalled = true); access.change!!.invoke() }
        rule.onNodeWithText("未运行 · 点击打开 Shizuku").performScrollTo().assertExists()
        rule.runOnIdle { access.state = access.state.copy(shizukuRunning = true); access.change!!.invoke() }
        rule.onNodeWithText("未授权 · 点击授权").performScrollTo().assertExists()
        rule.runOnIdle { access.state = access.state.copy(shizukuAuthorized = true, shizukuUid = 2000); access.change!!.invoke() }
        rule.onNodeWithText("已连接 · ADB Shell (UID 2000)").performScrollTo().assertExists()
        rule.runOnIdle { access.state = access.state.copy(shizukuUid = 0); access.change!!.invoke() }
        rule.onNodeWithText("已连接 · Root (UID 0)").performScrollTo().assertExists()
    }
    @Test fun resumeRefreshesPermissionsAndLateAccessibilityConnectionRefreshesWithoutPolling() {
        val access = Access(); show(access, initial = "environment")
        rule.onNodeWithText("未授权 · /sdcard").assertExists()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        access.state = EnvironmentSettingsState(true, true, true, true, false, listOf("192.0.2.12"))
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("已授权 · /sdcard").assertExists(); rule.onNodeWithText("已允许").assertExists()
        rule.onNodeWithText("已开启，待系统连接").performScrollTo().assertExists()
        rule.runOnIdle { access.state = access.state.copy(accessibilityConnected = true); access.change!!.invoke() }
        rule.onNodeWithText("已连接").assertExists()
        rule.onNodeWithText("192.0.2.12").performScrollTo().assertExists()
        rule.onNodeWithTag("settings-back").performSemanticsAction(SemanticsActions.OnClick) { it() }; rule.waitForIdle()
        rule.runOnIdle { assertNull(access.change); assertEquals(1, access.closed) }
    }
    @Test fun unavailableSystemSettingsAndReadFailuresAreVisibleWithoutInventedPermissionStates() {
        val access = Access().apply { readFails = true; openFails = true }
        show(access, initial = "environment")
        rule.onNodeWithText("无法读取环境状态").performScrollTo().assertExists()
        rule.onAllNodesWithText("读取失败").assertCountEquals(8)
        rule.onNodeWithText("未授权 · /sdcard").assertDoesNotExist()
        rule.onNodeWithText("手机文件访问").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("无法打开系统设置").performScrollTo().assertExists()
        rule.runOnIdle { assertTrue(access.opens.isEmpty()) }
    }
    @Test fun wakeLockRowTogglesInPlaceWithoutOpeningSystemSettings() {
        val access = Access(); show(access, initial = "environment")
        rule.onNode(hasText("后台唤醒锁") and hasText("已关闭")).performScrollTo().assertExists()
        rule.onNodeWithText("后台唤醒锁").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf(true), access.wakeLockToggles); assertTrue(access.opens.isEmpty()) }
        rule.onNode(hasText("后台唤醒锁") and hasText("已开启")).performScrollTo().assertExists()
    }
    @Test fun rootModeIsAOneWayButtonBehindAShortConfirmationAndHidesKeepAliveRowsAfterwards() {
        val access = Access(); show(access, initial = "environment")
        val dialogSwitch = hasText("切换", substring = false) and hasAnySibling(hasText("取消", substring = false))
        rule.onNodeWithText("Debian 13 · ARM64 · PRoot").performScrollTo().assertExists()
        rule.onNodeWithText("Shizuku 提权").performScrollTo().assertExists()

        rule.onNodeWithText("切换").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("切换到 Root 模式？").assertExists()
        rule.onNodeWithText("切换后无法撤销。想换回普通模式只能清除 Luma 的数据，工作区文件会一起删掉。").assertExists()
        rule.onNodeWithText("之后每次使用都需要 Root，临时 Root 重启后要重新激活。").assertExists()
        rule.onNodeWithText("取消").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("切换到 Root 模式？").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, access.rootSwitchStarts) }

        rule.onNodeWithText("切换").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNode(dialogSwitch).performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("切换到 Root 模式？").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, access.rootSwitchStarts) }

        access.rootSwitchFlow.value = RootSwitchState(running = true, progress = "正在调整文件权限，可能要一两分钟…")
        rule.onNodeWithText("正在调整文件权限，可能要一两分钟…").performScrollTo().assertExists()
        rule.onNodeWithText("切换").assertIsNotEnabled()

        access.rootSwitchFlow.value = RootSwitchState(error = "没拿到 Root 授权，在 Root 管理器里允许 Luma 后再试")
        rule.onNodeWithText("切换没成功：没拿到 Root 授权，在 Root 管理器里允许 Luma 后再试").performScrollTo().assertExists()
        rule.onNodeWithText("切换").assertIsEnabled()
        rule.onNodeWithText("Shizuku 提权").performScrollTo().assertExists()

        access.rootSwitchFlow.value = RootSwitchState(running = true, progress = "正在检查环境…")
        rule.waitForIdle()
        access.state = access.state.copy(rootModeEnabled = true)
        access.rootSwitchFlow.value = RootSwitchState()
        rule.waitForIdle()
        rule.onNodeWithText("运行中").performScrollTo().assertExists()
        rule.onNodeWithText("切换").assertDoesNotExist()
        rule.onNodeWithText("Shizuku 提权").assertDoesNotExist()
        rule.onNodeWithText("忽略电池优化").assertDoesNotExist()
        rule.onNodeWithText("无障碍保活").assertDoesNotExist()
        rule.onNodeWithText("后台唤醒锁").assertDoesNotExist()
        rule.onNodeWithText("手机文件访问").assertExists()
        rule.onNodeWithText("Debian 13 · ARM64 · Chroot").performScrollTo().assertExists()

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        access.rootActive = false
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText("没检测到 Root，激活后就能继续用").performScrollTo().assertExists()
        rule.onNodeWithText("切换").assertDoesNotExist()
    }
}
