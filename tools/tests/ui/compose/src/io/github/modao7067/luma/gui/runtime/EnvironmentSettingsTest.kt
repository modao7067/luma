package io.github.modao7067.luma.gui.runtime

import android.app.*
import android.content.*
import android.content.pm.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.robolectric.util.ReflectionHelpers
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [EnvironmentStorageShadow::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EnvironmentSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val serviceName = "io.github.modao7067.luma.BackgroundAccessibilityService"
    @Suppress("UNCHECKED_CAST") private fun serviceClass() = Class.forName(serviceName) as Class<out android.app.Service>
    private fun enabledService(pkg: String) = AccessibilityServiceInfo().also { info ->
        ReflectionHelpers.setField(info, "mResolveInfo", ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = pkg; name = serviceName }
        })
    }
    @Before fun initialStoragePermission() { EnvironmentStorageShadow.granted = false }
    @Test fun intentsUseOnlyTheCurrentApplicationAndTheOriginalSystemActions() {
        val pkg = "fixture.new.noroot"
        val storage = environmentSettingsIntent(EnvironmentSetting.STORAGE, pkg)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, storage.action)
        assertEquals(Uri.parse("package:$pkg"), storage.data)
        val battery = environmentSettingsIntent(EnvironmentSetting.BATTERY, pkg)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
        assertEquals(Uri.parse("package:$pkg"), battery.data)
        val notification = environmentSettingsIntent(EnvironmentSetting.NOTIFICATIONS, pkg)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notification.action)
        assertEquals(pkg, notification.getStringExtra(Settings.EXTRA_APP_PACKAGE)); assertNull(notification.data)
        val accessibility = environmentSettingsIntent(EnvironmentSetting.ACCESSIBILITY, pkg)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accessibility.action)
        assertNull(accessibility.component); assertNull(accessibility.data)
        assertNull(accessibility.extras)
    }
    @Test fun productionReadReflectsSystemFactsWithoutStartingAnyRuntimeService() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        EnvironmentStorageShadow.granted = false
        power.setIgnoringBatteryOptimizations(app.packageName, false); notifications.setNotificationsEnabled(false)
        manager.setEnabledAccessibilityServiceList(emptyList())
        val access = AndroidEnvironmentSettingsAccess(app)
        val before = access.read()
        assertFalse(before.storageGranted); assertFalse(before.batteryIgnored); assertFalse(before.notificationsEnabled); assertFalse(before.accessibilityEnabled)
        EnvironmentStorageShadow.granted = true
        power.setIgnoringBatteryOptimizations(app.packageName, true); notifications.setNotificationsEnabled(true)
        manager.setEnabledAccessibilityServiceList(listOf(enabledService(app.packageName)))
        val after = access.read()
        assertTrue(after.storageGranted); assertTrue(after.batteryIgnored); assertTrue(after.notificationsEnabled); assertTrue(after.accessibilityEnabled)
        assertFalse(after.accessibilityConnected)
        manager.setEnabledAccessibilityServiceList(listOf(enabledService("another.application")))
        assertFalse(access.read().accessibilityEnabled)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun aRealAccessibilityConnectionChangeNotifiesReadOnlyObserversAndCloseDropsQueuedCallbacks() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val service = Robolectric.buildService(serviceClass()).create()
        var changed = 0
        val access = AndroidEnvironmentSettingsAccess(app)
        val observer = access.observe { changed++ }
        val connect = serviceClass().getDeclaredMethod("onServiceConnected").apply { isAccessible = true }
        try {
            connect.invoke(service.get()); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(access.read().accessibilityConnected); assertEquals(1, changed)
            service.get().onUnbind(Intent()); observer.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(access.read().accessibilityConnected); assertEquals(1, changed)
            connect.invoke(service.get()); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, changed)
        } finally { observer.close(); service.destroy() }
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun openingAnActionIsExplicitAndHasNoFallbackServiceOrAuthorizationSideEffect() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val access = AndroidEnvironmentSettingsAccess(app)
        access.read(); assertNull(shadowOf(app).nextStartedActivity)
        access.open(EnvironmentSetting.NOTIFICATIONS)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun wakeLockPreferenceTogglesWithoutStartingServiceAndKeepAliveRoutesToGui() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val access = AndroidEnvironmentSettingsAccess(app)
        var changed = 0
        val observer = access.observe { changed++ }
        try {
            assertFalse(access.read().wakeLockEnabled)
            access.setWakeLock(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(access.read().wakeLockEnabled)
            assertTrue(changed >= 1)
            access.setWakeLock(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(access.read().wakeLockEnabled)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            observer.close()
        }

        NoRootRuntime.updateKeepAliveStatus("")
        val initial = io.github.modao7067.luma.ShellService.buildKeepAliveNotification(app)
        assertTrue(initial.actions.isNullOrEmpty())
        assertEquals("后台环境运行中", initial.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("io.github.modao7067.luma.gui.MainActivity", shadowOf(initial.contentIntent).savedIntent.component?.className)

        NoRootRuntime.updateKeepAliveStatus("2 个会话执行中 · 1 个待处理")
        val updated = io.github.modao7067.luma.ShellService.buildKeepAliveNotification(app)
        assertEquals("2 个会话执行中 · 1 个待处理", updated.extras.getString(Notification.EXTRA_TEXT))
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun shizukuRelayStagesExistingWorkspaceFilesAndSyncsCreatedOutputsBack() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val workspaceDir = java.io.File(app.filesDir, "workspace").apply { mkdirs() }
        val inputFile = java.io.File(workspaceDir, "input.txt").apply { writeText("from-workspace") }
        val untouchedFile = java.io.File(workspaceDir, "untouched.txt").apply { writeText("stay-same") }
        val outputFile = java.io.File(workspaceDir, "sub/shot.png")
        outputFile.delete()

        var relayRootDir: java.io.File? = null
        io.github.modao7067.luma.ShizukuBridge.RelaySession(app).use { relay ->
            relayRootDir = relay.relayRoot()
            val rewritten = relay.rewriteArguments(
                listOf("sh", "-c", "cat /workspace/input.txt && screencap -p /workspace/sub/shot.png && ls /workspace/untouched.txt"),
                "/workspace"
            )
            assertEquals(3, rewritten.size)
            val script = rewritten[2]
            val relayedInput = java.io.File(relay.relayRoot(), "workspace/input.txt")
            val relayedUntouched = java.io.File(relay.relayRoot(), "workspace/untouched.txt")
            val relayedOutput = java.io.File(relay.relayRoot(), "workspace/sub/shot.png")
            assertTrue(script.contains(relayedInput.absolutePath))
            assertTrue(script.contains(relayedUntouched.absolutePath))
            assertTrue(script.contains(relayedOutput.absolutePath))
            assertTrue(relayedInput.isFile)
            assertEquals("from-workspace", relayedInput.readText())
            assertTrue(relayedUntouched.isFile)

            val originalMtime = relayedUntouched.lastModified()
            relayedUntouched.writeText("diff-same")
            relayedUntouched.setLastModified(originalMtime)
            relayedInput.writeText("modified-by-shizuku")
            relayedOutput.parentFile?.mkdirs()
            relayedOutput.writeBytes(byteArrayOf(1, 2, 3, 4))
            relay.syncBack()
        }
        assertEquals("modified-by-shizuku", inputFile.readText())
        assertEquals("diff-same", untouchedFile.readText())
        assertTrue(outputFile.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), outputFile.readBytes())
        assertFalse(relayRootDir!!.exists())

        val outsideSecret = java.io.File(app.filesDir, "outside-secret.txt").apply { writeText("secret") }
        val symlinkDest = java.io.File(workspaceDir, "evil-link.txt")
        java.nio.file.Files.deleteIfExists(symlinkDest.toPath())
        java.nio.file.Files.createSymbolicLink(symlinkDest.toPath(), outsideSecret.toPath())
        try {
            io.github.modao7067.luma.ShizukuBridge.RelaySession(app).use { relay ->
                val staged = java.io.File(relay.relayRoot(), "workspace/evil-link.txt")
                staged.parentFile?.mkdirs()
                staged.writeText("overwritten")
                var failed = false
                try { relay.syncBack() } catch (_: java.io.IOException) { failed = true }
                assertTrue(failed)
            }
            assertEquals("secret", outsideSecret.readText())
        } finally {
            java.nio.file.Files.deleteIfExists(symlinkDest.toPath())
            outsideSecret.delete()
        }
    }
    @Test fun shizukuRelayStaleCleanupPreservesLongRunningActiveSessionAndRemovesAbandonedDirs() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val workspaceDir = java.io.File(app.filesDir, "workspace").apply { mkdirs() }
        val longOutput = java.io.File(workspaceDir, "long/result.txt")
        longOutput.delete()

        val staleTime = System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(20)
        val first = io.github.modao7067.luma.ShizukuBridge.RelaySession(app)
        var abandoned: java.io.File? = null
        try {
            first.stageGuestPath("/workspace")
            val relayedOut = java.io.File(first.relayRoot(), "workspace/long/result.txt")
            relayedOut.parentFile?.mkdirs()
            relayedOut.writeText("survives-past-ten-minutes")
            relayedOut.setLastModified(staleTime)
            relayedOut.parentFile?.setLastModified(staleTime)
            java.io.File(first.relayRoot(), "workspace").setLastModified(staleTime)
            first.relayRoot().setLastModified(staleTime)

            abandoned = java.io.File(first.relayRoot().parentFile, "abandoned-session").apply {
                mkdirs()
                java.io.File(this, "stale.txt").apply {
                    writeText("orphan")
                    setLastModified(staleTime)
                }
                setLastModified(staleTime)
            }

            io.github.modao7067.luma.ShizukuBridge.RelaySession(app).use { second ->
                second.stageGuestPath("/workspace")
            }
            io.github.modao7067.luma.ShizukuBridge.RelaySession.cleanupStaleRelays(app)

            assertFalse(abandoned.exists())
            assertTrue(first.relayRoot().isDirectory)
            assertTrue(relayedOut.isFile)

            first.syncBack()
        } finally {
            first.close()
            abandoned?.deleteRecursively()
        }
        assertTrue(longOutput.isFile)
        assertEquals("survives-past-ten-minutes", longOutput.readText())
        assertFalse(first.relayRoot().exists())
    }
    @Test fun debianRootModeSwitchIsOneWayAndRollsOwnershipBackWhenTheSelfCheckFails() {
        assumeTrue(io.github.modao7067.luma.gui.GuiProduct.environment == "noroot")
        val rootfs = java.io.File(app.filesDir, "linux/debian").apply { mkdirs() }
        java.io.File(rootfs, ".luma-ready").writeText("Luma Debian environment v1\n")
        var selfCheckFails = true
        app.applicationInfo.nativeLibraryDir = "/data/app/test/lib/arm64"
        val suCommands = mutableListOf<String>()
        var rootModeSeenBeforeVerify = false
        var prepared = 0
        DebianRootMode.resetCacheForTesting()
        DebianRootMode.suRunnerOverrideForTesting = { cmd, _ ->
            suCommands += cmd
            if (cmd.contains("print(os.getuid())")) {
                rootModeSeenBeforeVerify = java.io.File(app.filesDir, "runtime/.root-mode").exists()
            }
            if (selfCheckFails && cmd.contains("print(os.getuid())")) DebianRootMode.SuResult(1, "chroot: 自检失败\n")
            else DebianRootMode.SuResult(0, "0\n")
        }
        DebianRootMode.prepareHookForTesting = { _, onProgress ->
            prepared++
            onProgress("测试环境就绪")
        }
        try {
            assertFalse(DebianRootMode.isEnabled(app))
            assertEquals("/data/local/linux/bin/ubuntu", DebianRootMode.rootLauncher(app))

            val failure = runCatching { kotlinx.coroutines.runBlocking { DebianRootMode.switchToRoot(app) } }.exceptionOrNull()
            assertEquals("chroot: 自检失败", failure?.message)
            assertFalse(rootModeSeenBeforeVerify)
            assertFalse(DebianRootMode.isEnabled(app))
            assertFalse(io.github.modao7067.luma.gui.GuiProduct.usesRootExecution(app))
            assertFalse(java.io.File(app.filesDir, "runtime/.root-mode").exists())
            val toRoot = suCommands.indexOfFirst { it.contains("chown -hR 0:0") }
            val check = suCommands.indexOfFirst { it.contains("print(os.getuid())") }
            assertTrue("ownership must change before the self-check: $suCommands", toRoot in 0 until check)
            val rollbackCmd = suCommands.last()
            assertTrue(rollbackCmd.contains("chown -hR \"\$APP_UID:\$APP_UID\""))
            assertTrue(rollbackCmd.contains("read -r h_pid h_stamp"))
            assertTrue(rollbackCmd.contains("tb stat -c %C"))
            assertTrue(rollbackCmd.contains("tb chcon -hR"))
            assertFalse(rollbackCmd.contains("restorecon"))

            selfCheckFails = false
            suCommands.clear()
            kotlinx.coroutines.runBlocking { DebianRootMode.switchToRoot(app) }
            assertFalse(rootModeSeenBeforeVerify)
            assertEquals(2, prepared)
            assertTrue(DebianRootMode.isEnabled(app))
            assertTrue(io.github.modao7067.luma.gui.GuiProduct.usesRootExecution(app))
            assertFalse(suCommands.any { it.contains("\$APP_UID:\$APP_UID") })
            val enter = java.io.File(app.filesDir, "root-chroot/enter.sh")
            assertEquals(enter.absolutePath, DebianRootMode.rootLauncher(app))
            assertTrue(enter.readText().contains("nsenter"))
            assertTrue(enter.readText().contains("/opt/luma/guest-sync.py"))
            assertTrue(enter.readText().contains("tb flock 9"))
            assertFalse(enter.readText().contains("-- tb "))
            val setup = java.io.File(app.filesDir, "root-chroot/setup-ns.sh").readText()
            assertFalse(setup.contains("--make-rslave"))
            assertTrue(setup.contains("tb mount -o rslave none /"))
            assertFalse(setup.contains("rm -rf \"\$ROOTFS/sdcard\""))
            assertTrue(setup.contains("tb rmdir \"\$ROOTFS/sdcard\""))
            assertFalse(setup.contains("kitty-frames"))
            assertFalse(suCommands.any { it.contains("rm -rf \"\$ROOTFS/sdcard\"") })
            assertTrue(java.io.File(app.filesDir, "runtime/.root-mode").isFile)
            val chrootExec = java.io.File(app.filesDir, "runtime/root-chroot-exec.py").readText()
            val guestSync = java.io.File(app.filesDir, "runtime/guest-sync.py").readText()
            assertTrue(chrootExec.contains("https://goproxy.cn,direct"))
            assertTrue(chrootExec.contains("https://rsproxy.cn"))
            assertTrue(guestSync.contains("mirrors.tuna.tsinghua.edu.cn/debian"))
            assertTrue(guestSync.contains("mirrors.tuna.tsinghua.edu.cn/debian-security"))
            assertTrue(guestSync.contains("mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/"))
            assertTrue(guestSync.contains("mirrors.tuna.tsinghua.edu.cn/rubygems/"))
            assertTrue(guestSync.contains("legacy_china"))
            assertTrue(guestSync.contains("registry.npmmirror.com"))
            assertTrue(guestSync.contains("rsproxy.cn"))
            assertFalse(java.io.File(app.filesDir, "runtime/ns-holder.py").readText().contains("chown(full"))

            val computeCheck = java.io.File(app.filesDir, "runtime/bin/luma-compute-check").readText()
            assertTrue(computeCheck.contains("/opt/luma/.root-mode"))
            assertTrue(computeCheck.contains("LUMA_ENV==='debian-chroot'"))
            assertTrue(computeCheck.contains("/proc/1/root/system"))
            assertTrue(computeCheck.contains("libluma_compute.so"))
            assertTrue(computeCheck.contains("native-client.cjs"))
            assertEquals("/data/app/test/lib/arm64", java.io.File(app.filesDir, "runtime/native-lib-dir").readText().trim())
            assertTrue(java.io.File(app.filesDir, "runtime/host-paths.json").isFile)
            val rootSkill = java.io.File(app.filesDir, "runtime/SKILL.md").readText()
            val guestSkill = java.io.File(app.filesDir, "runtime/guest/workspace/.agents/skills/luma-environment/SKILL.md").readText()
            val guestAgents = java.io.File(app.filesDir, "runtime/guest/workspace/AGENTS.md").readText()
            assertTrue(rootSkill.contains("Debian Root Chroot"))
            assertEquals(rootSkill, guestSkill)
            assertTrue(guestAgents.contains("Root Chroot"))
            val managedJson = org.json.JSONObject(java.io.File(app.filesDir, "runtime/guest/managed-workspace.json").readText())
            assertEquals(2, managedJson.getJSONArray("AGENTS.md").length())

            suCommands.clear()
            kotlinx.coroutines.runBlocking { DebianRootMode.switchToRoot(app) }
            assertTrue(suCommands.isEmpty())
            assertFalse(DebianRootMode.startSwitch(app))
        } finally {
            DebianRootMode.suRunnerOverrideForTesting = null
            DebianRootMode.prepareHookForTesting = null
            java.io.File(app.filesDir, "root-chroot").deleteRecursively()
            java.io.File(app.filesDir, "runtime").deleteRecursively()
            DebianRootMode.resetCacheForTesting()
        }
    }
}
