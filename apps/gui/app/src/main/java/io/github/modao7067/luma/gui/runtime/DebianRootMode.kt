package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.widget.Toast
import io.github.modao7067.luma.gui.GuiProduct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

internal object DebianRootMode {
    private const val DIR_NAME = "root-chroot"
    private const val ENABLED_FILE = "enabled"
    private const val ENTER_SCRIPT = "enter.sh"
    private const val SETUP_NS_SCRIPT = "setup-ns.sh"
    private const val DEFAULT_UBUNTU_LAUNCHER = "/data/local/linux/bin/ubuntu"

    internal data class SuResult(val exitCode: Int, val output: String, val missingBinary: Boolean = false, val timedOut: Boolean = false) {
        val isRootUid: Boolean get() = !missingBinary && !timedOut && exitCode == 0 && output.trim().lines().lastOrNull()?.trim() == "0"
    }

    private val switchMutex = Mutex()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cachedEnabled: Boolean? = null
    @Volatile internal var suRunnerOverrideForTesting: ((String, Long) -> SuResult)? = null
    @Volatile internal var prepareHookForTesting: ((Context, (String) -> Unit) -> Unit)? = null

    val isSupportedProduct: Boolean get() = !GuiProduct.isRoot

    fun isEnabled(context: Context): Boolean {
        if (!isSupportedProduct) return false
        cachedEnabled?.let { return it }
        val app = context.applicationContext ?: context
        val present = File(File(app.filesDir, DIR_NAME), ENABLED_FILE).isFile
        cachedEnabled = present
        return present
    }

    internal fun resetCacheForTesting() {
        cachedEnabled = null
    }

    fun observe(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runCatching { listener() }
            } else {
                mainHandler.post { if (listener in listeners) runCatching { listener() } }
            }
        }
    }

    fun rootLauncher(context: Context): String {
        val app = context.applicationContext ?: context
        if (isEnabled(app)) {
            return File(File(app.filesDir, DIR_NAME), ENTER_SCRIPT).absolutePath
        }
        return DEFAULT_UBUNTU_LAUNCHER
    }

    fun suExecutable(): String {
        val fixed = arrayOf("/system/bin/su", "/sbin/su", "/system/xbin/su", "/su/bin/su")
        for (path in fixed) {
            val file = File(path)
            if (file.isFile && file.canExecute()) return path
        }
        val rawPath = System.getenv("PATH").orEmpty()
        for (dir in rawPath.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, "su")
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return "su"
    }

    fun isSuBinaryVisible(): Boolean {
        val resolved = suExecutable()
        if (resolved != "su") return true
        return false
    }

    suspend fun probeRootActive(timeoutMs: Long = 5_000L): Boolean = withContext(Dispatchers.IO) {
        runSu("id -u", timeoutMs).isRootUid
    }

    internal fun runSu(command: String, timeoutMs: Long = 25_000L): SuResult {
        suRunnerOverrideForTesting?.let { return it(command, timeoutMs) }
        val process = try {
            ProcessBuilder(suExecutable(), "-c", command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return SuResult(exitCode = 127, output = "", missingBinary = true)
        }
        runCatching { process.outputStream.close() }
        val buffer = ByteArrayOutputStream()
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val chunk = ByteArray(4096)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > 512 * 1024) {
                            process.destroy()
                            break
                        }
                        buffer.write(chunk, 0, count)
                    }
                }
            }
        }, "LumaSuProbe").apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { reader.join(300) }
            return SuResult(exitCode = -1, output = buffer.toString("UTF-8"), timedOut = true)
        }
        runCatching { reader.join(1_000) }
        return SuResult(exitCode = process.exitValue(), output = buffer.toString("UTF-8"))
    }

    private const val OWNERSHIP_TIMEOUT_MS = 10 * 60_000L
    private val switchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val switchStateFlow = MutableStateFlow(RootSwitchState())
    val switchState: StateFlow<RootSwitchState> = switchStateFlow.asStateFlow()

    fun startSwitch(context: Context): Boolean {
        if (!isSupportedProduct) return false
        val app = context.applicationContext ?: context
        synchronized(switchStateFlow) {
            if (switchStateFlow.value.running || isEnabled(app)) return false
            switchStateFlow.value = RootSwitchState(running = true, progress = tr("正在请求 Root 授权…", "Requesting Root access…"))
        }
        switchScope.launch {
            val error = try {
                switchToRoot(app) { message -> switchStateFlow.update { it.copy(progress = message) } }
                null
            } catch (error: Throwable) {
                error.message?.takeIf { it.isNotBlank() } ?: tr("出了点问题，再试一次", "Something went wrong; please try again")
            }
            switchStateFlow.value = RootSwitchState(error = error)
            mainHandler.post {
                runCatching {
                    Toast.makeText(app, if (error == null) tr("已切换到 Root 模式", "Switched to Root mode") else tr("切换没成功：$error", "Switch failed: $error"), Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    suspend fun switchToRoot(context: Context, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(isSupportedProduct) { tr("专用 Root 版本不支持切换模式", "Mode switching is not supported in dedicated Root builds") }
        val app = context.applicationContext ?: context
        switchMutex.withLock { if (!isEnabled(app)) enableLocked(app, onProgress) }
    }

    fun refreshInstalled(context: Context) {
        val app = context.applicationContext ?: context
        if (!isEnabled(app)) return
        runCatching {
            val layout = writeScripts(app)
            writeAtomic(File(layout.runtime, ".root-mode"), "chroot\n", 0x1A4)
        }
        runCatching { NoRootRuntime.stageForRoot(app) }
    }

    private class Layout(val files: File, val cache: File, val rootfs: File, val state: File, val runtime: File, val uid: Int, val enter: File)

    private fun writeScripts(app: Context): Layout {
        val filesDir = app.filesDir
        val cacheDir = app.cacheDir
        val stateDir = File(filesDir, DIR_NAME).apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0x1C0) }
        }
        val runtimeDir = File(filesDir, "runtime").apply { mkdirs() }
        val rootfsDir = File(filesDir, "linux/debian")
        val uid = app.applicationInfo.uid
        writeAtomic(File(runtimeDir, "root-chroot-exec.py"), chrootExecPython, 0x1A4)
        writeAtomic(File(runtimeDir, "ns-holder.py"), namespaceHolderPython, 0x1A4)
        writeAtomic(File(runtimeDir, "guest-sync.py"), guestSyncPython, 0x1A4)
        writeAtomic(File(stateDir, SETUP_NS_SCRIPT), buildSetupNsScript(filesDir, cacheDir, rootfsDir, stateDir, uid), 0x1C0)
        val enter = writeAtomic(File(stateDir, ENTER_SCRIPT), buildEnterScript(filesDir, rootfsDir, stateDir), 0x1C0)
        return Layout(filesDir, cacheDir, rootfsDir, stateDir, runtimeDir, uid, enter)
    }

    private fun enableLocked(app: Context, onProgress: (String) -> Unit) {
        onProgress(tr("正在请求 Root 授权…", "Requesting Root access…"))
        val probe = runSu("id -u", 25_000L)
        if (!probe.isRootUid) {
            throw IOException(when {
                probe.missingBinary -> tr("没找到 Root。用临时 Root 的话，先激活再试", "Root binary not found. If using temporary root, activate it first")
                probe.timedOut -> tr("等 Root 授权超时了，确认弹窗后再试一次", "Timed out waiting for Root grant; approve the prompt and try again")
                else -> tr("没拿到 Root 授权，在 Root 管理器里允许 Luma 后再试", "Root access denied; grant Luma in your Root manager and try again")
            })
        }

        onProgress(tr("正在停止当前环境…", "Stopping current environment…"))
        val prepareOverride = prepareHookForTesting
        if (prepareOverride != null) prepareOverride(app, onProgress) else NoRootRuntime.prepareForRootChroot(app, onProgress)
        val layout = writeScripts(app)

        onProgress(tr("正在调整文件权限，可能要一两分钟…", "Updating file ownership (may take 1–2 minutes)…"))
        val owned = runSu(buildOwnershipScript(layout), OWNERSHIP_TIMEOUT_MS)
        if (owned.missingBinary || owned.timedOut || owned.exitCode != 0) {
            rollback(layout)
            throw IOException(if (owned.timedOut) tr("调整文件权限超时了", "Timed out updating file ownership") else lastLine(owned) ?: tr("调整文件权限失败", "Failed to update file ownership"))
        }

        onProgress(tr("正在准备环境文件…", "Preparing environment files…"))
        NoRootRuntime.stageForRoot(app)

        onProgress(tr("正在检查环境…", "Verifying environment…"))
        val preflight = "export LUMA_LANG=${quote(LumaI18n.langCode())}\n" +
            buildPreflightScript(layout.rootfs) + "\nexec " + quote(layout.enter.absolutePath) +
            " /usr/bin/python3 -I -c 'import os; print(os.getuid())'"
        val verify = runSu(preflight, 60_000L)
        if (!verify.isRootUid) {
            rollback(layout)
            throw IOException(lastLine(verify) ?: tr("环境检查没通过", "Environment verification failed"))
        }

        writeAtomic(File(layout.runtime, ".root-mode"), "chroot\n", 0x1A4)
        writeAtomic(File(layout.state, ENABLED_FILE), "1\n", 0x180)
        cachedEnabled = true
        notifyChanged()
    }

    private fun rollback(layout: Layout) {
        runSu(buildRollbackScript(layout.files, layout.cache, layout.rootfs, layout.state, layout.uid), OWNERSHIP_TIMEOUT_MS)
        File(layout.runtime, ".root-mode").delete()
        File(layout.runtime, "guest").deleteRecursively()
        File(layout.state, "ns.pid").delete()
    }

    private fun lastLine(result: SuResult): String? =
        result.output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim()

    private fun buildOwnershipScript(layout: Layout): String {
        val r = quote(layout.rootfs.absolutePath)
        val w = quote(File(layout.files, "workspace").absolutePath)
        val notInstalled = quote(tr("内置 Debian 尚未安装", "Built-in Debian is not installed yet"))
        val chownFailed = quote(tr("调整文件权限失败", "Failed to update file ownership"))
        return """
            tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
            ROOTFS=$r
            WORKSPACE=$w
            [ -f "${'$'}ROOTFS/.luma-ready" ] || { echo $notInstalled >&2; exit 126; }
            tb mkdir -p "${'$'}WORKSPACE"
            tb chown -hR 0:0 "${'$'}ROOTFS" "${'$'}WORKSPACE" || { echo $chownFailed >&2; exit 1; }
            if [ -f "${'$'}ROOTFS/etc/resolv.conf" ] && [ ! -L "${'$'}ROOTFS/etc/resolv.conf" ]; then
              tb chown ${layout.uid}:${layout.uid} "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null || true
            fi
        """.trimIndent()
    }

    private fun writeAtomic(target: File, content: String, mode: Int): File {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(content, StandardCharsets.UTF_8)
            runCatching { Os.chmod(temp.absolutePath, mode) }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException(tr("无法写入运行脚本：${target.name}", "Failed to write runtime script: ${target.name}"))
            }
            runCatching { Os.chmod(target.absolutePath, mode) }
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun buildPreflightScript(rootfs: File): String {
        val r = quote(rootfs.absolutePath)
        val notReady = quote(tr("内置 Debian 环境尚未就绪", "Built-in Debian environment is not ready yet"))
        return """
            tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
            ROOTFS=$r
            [ -f "${'$'}ROOTFS/.luma-ready" ] || { echo $notReady >&2; exit 126; }
            tb mkdir -p "${'$'}ROOTFS/etc/apt/apt.conf.d" "${'$'}ROOTFS/usr/local/bin" "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/storage/emulated/0"
            tb chmod 1777 "${'$'}ROOTFS/tmp" 2>/dev/null || true
            cat > "${'$'}ROOTFS/etc/apt/apt.conf.d/99luma-root" <<'LUMA_APT_EOF'
            APT::Sandbox::User "root";
            Acquire::Retries "2";
            Acquire::http::Pipeline-Depth "0";
            Acquire::https::Pipeline-Depth "0";
            LUMA_APT_EOF
            tb chmod 0644 "${'$'}ROOTFS/etc/apt/apt.conf.d/99luma-root" 2>/dev/null || true
            if [ -f "${'$'}ROOTFS/etc/group" ]; then
              tb grep -q '^aid_inet:' "${'$'}ROOTFS/etc/group" 2>/dev/null || printf '\naid_inet:x:3003:root,_apt\n' >> "${'$'}ROOTFS/etc/group"
              tb grep -q '^aid_net_raw:' "${'$'}ROOTFS/etc/group" 2>/dev/null || printf 'aid_net_raw:x:3004:root,_apt\n' >> "${'$'}ROOTFS/etc/group"
            fi
            if [ ! -L "${'$'}ROOTFS/sdcard" ]; then
              tb rmdir "${'$'}ROOTFS/sdcard" 2>/dev/null || true
              if [ ! -e "${'$'}ROOTFS/sdcard" ]; then
                tb ln -s /storage/emulated/0 "${'$'}ROOTFS/sdcard" 2>/dev/null || true
              fi
            fi
            tb ln -sfn /opt/luma/bin/android-sh "${'$'}ROOTFS/usr/local/bin/android-sh" 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildSetupNsScript(filesDir: File, cacheDir: File, rootfs: File, stateDir: File, uid: Int): String {
        val fDir = quote(filesDir.absolutePath)
        val cDir = quote(cacheDir.absolutePath)
        val rDir = quote(rootfs.absolutePath)
        val sDir = quote(stateDir.absolutePath)
        return """
            #!/system/bin/sh
            set -eu
            tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
            FILES_DIR=$fDir
            CACHE_DIR=$cDir
            ROOTFS=$rDir
            STATE_DIR=$sDir
            APP_UID=$uid

            holder_alive() {
              [ -r "${'$'}STATE_DIR/ns.pid" ] || return 1
              read -r h_pid h_stamp < "${'$'}STATE_DIR/ns.pid" 2>/dev/null || return 1
              [ -n "${'$'}{h_pid:-}" ] && [ -n "${'$'}{h_stamp:-}" ] && [ -r "/proc/${'$'}h_pid/stat" ] || return 1
              stat_line=${'$'}(cat "/proc/${'$'}h_pid/stat" 2>/dev/null) || return 1
              after=${'$'}{stat_line#*) }
              set -- ${'$'}after
              eval actual_stamp=\${'$'}{20:-}
              [ "${'$'}actual_stamp" = "${'$'}h_stamp" ] || return 1
              ns_h=${'$'}(tb readlink "/proc/${'$'}h_pid/ns/mnt" 2>/dev/null) || return 1
              ns_1=${'$'}(tb readlink "/proc/1/ns/mnt" 2>/dev/null) || return 1
              [ -n "${'$'}ns_h" ] && [ "${'$'}ns_h" != "${'$'}ns_1" ]
            }

            if holder_alive; then exit 0; fi
            tb rm -f "${'$'}STATE_DIR/ns.pid"

            exec tb unshare -m /system/bin/sh -ceu '
              tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
              FILES_DIR='"$fDir"'
              CACHE_DIR='"$cDir"'
              ROOTFS='"$rDir"'
              STATE_DIR='"$sDir"'
              APP_UID='"$uid"'

              tb mount -o rslave none /
              tb mkdir -p "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" "${'$'}ROOTFS/dev" "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/dev/shm" "${'$'}ROOTFS/dev/net"
              tb mount -t proc proc "${'$'}ROOTFS/proc" 2>/dev/null || tb mount -o bind /proc "${'$'}ROOTFS/proc"
              tb mount -o rbind /sys "${'$'}ROOTFS/sys" 2>/dev/null || tb mount -o bind /sys "${'$'}ROOTFS/sys" 2>/dev/null || true

              for dev_node in null zero full random urandom tty fuse net/tun; do
                if [ -e "/dev/${'$'}dev_node" ]; then
                  [ -e "${'$'}ROOTFS/dev/${'$'}dev_node" ] || : > "${'$'}ROOTFS/dev/${'$'}dev_node"
                  tb mount -o bind "/dev/${'$'}dev_node" "${'$'}ROOTFS/dev/${'$'}dev_node" 2>/dev/null || true
                fi
              done

              tb mount -t devpts -o newinstance,ptmxmode=0666,mode=0620,gid=5 devpts "${'$'}ROOTFS/dev/pts" 2>/dev/null \
                || tb mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "${'$'}ROOTFS/dev/pts"
              tb ln -sfn pts/ptmx "${'$'}ROOTFS/dev/ptmx"
              tb ln -sfn /proc/self/fd "${'$'}ROOTFS/dev/fd"
              tb ln -sfn /proc/self/fd/0 "${'$'}ROOTFS/dev/stdin"
              tb ln -sfn /proc/self/fd/1 "${'$'}ROOTFS/dev/stdout"
              tb ln -sfn /proc/self/fd/2 "${'$'}ROOTFS/dev/stderr"
              tb mount -t tmpfs -o mode=1777,nosuid,nodev,size=256m tmpfs "${'$'}ROOTFS/dev/shm"

              tb mkdir -p "${'$'}FILES_DIR/workspace" "${'$'}ROOTFS/workspace"
              tb mount -o bind "${'$'}FILES_DIR/workspace" "${'$'}ROOTFS/workspace"
              tb mkdir -p "${'$'}FILES_DIR/runtime" "${'$'}ROOTFS/opt/luma"
              tb mount -o bind "${'$'}FILES_DIR/runtime" "${'$'}ROOTFS/opt/luma"

              tb mkdir -p "${'$'}ROOTFS/storage/emulated/0"
              if [ ! -L "${'$'}ROOTFS/sdcard" ]; then
                tb rmdir "${'$'}ROOTFS/sdcard" 2>/dev/null || true
                if [ ! -e "${'$'}ROOTFS/sdcard" ]; then
                  tb ln -s /storage/emulated/0 "${'$'}ROOTFS/sdcard"
                fi
              fi
              if [ -d /storage/emulated/0 ]; then
                tb mount -o bind /storage/emulated/0 "${'$'}ROOTFS/storage/emulated/0" 2>/dev/null || true
              fi

              exec tb chroot "${'$'}ROOTFS" /usr/bin/python3 -I /opt/luma/ns-holder.py "/proc/1/root${'$'}STATE_DIR/ns.pid" "${'$'}APP_UID"
            '
        """.trimIndent() + "\n"
    }

    private fun buildEnterScript(filesDir: File, rootfs: File, stateDir: File): String {
        val fDir = quote(filesDir.absolutePath)
        val rDir = quote(rootfs.absolutePath)
        val sDir = quote(stateDir.absolutePath)
        return """
            #!/system/bin/sh
            set -eu
            tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
            if [ -x /system/bin/toybox ]; then TBX=/system/bin/toybox; else TBX=; fi
            FILES_DIR=$fDir
            ROOTFS=$rDir
            STATE_DIR=$sDir

            if [ -z "${'$'}{LUMA_MCS_APPLIED:-}" ]; then
              fctx=${'$'}(tb stat -c %C "${'$'}FILES_DIR" 2>/dev/null || true)
              pctx=${'$'}(cat /proc/self/attr/current 2>/dev/null | tb tr -d '\000' || true)
              mls=${'$'}{fctx#*:s0}
              if [ -n "${'$'}fctx" ] && [ "${'$'}mls" != "${'$'}fctx" ]; then mls="s0${'$'}mls"; else mls=""; fi
              dom=${'$'}{pctx%%:s0*}
              if [ -n "${'$'}dom" ] && [ -n "${'$'}mls" ] && [ "${'$'}{dom}:${'$'}{mls}" != "${'$'}pctx" ]; then
                if tb runcon "${'$'}{dom}:${'$'}{mls}" /system/bin/sh -c true 2>/dev/null; then
                  LUMA_MCS_APPLIED=1 exec tb runcon "${'$'}{dom}:${'$'}{mls}" /system/bin/sh "${'$'}0" "${'$'}@"
                fi
              fi
            fi

            msg_not_ready='Built-in Debian environment is not ready yet'
            msg_ns='Failed to create Debian chroot mount namespace'
            case "${'$'}{LUMA_LANG:-}" in
              zh*)
                msg_not_ready='内置 Debian 环境尚未就绪'
                msg_ns='创建 Debian chroot 挂载命名空间失败'
                ;;
            esac

            [ -f "${'$'}ROOTFS/.luma-ready" ] || { echo "${'$'}msg_not_ready" >&2; exit 126; }

            read_holder() {
              [ -r "${'$'}STATE_DIR/ns.pid" ] || return 1
              read -r h_pid h_stamp < "${'$'}STATE_DIR/ns.pid" 2>/dev/null || return 1
              [ -n "${'$'}{h_pid:-}" ] && [ -n "${'$'}{h_stamp:-}" ] && [ -r "/proc/${'$'}h_pid/stat" ] || return 1
              stat_line=${'$'}(cat "/proc/${'$'}h_pid/stat" 2>/dev/null) || return 1
              after=${'$'}{stat_line#*) }
              set -- ${'$'}after
              eval actual_stamp=\${'$'}{20:-}
              [ "${'$'}actual_stamp" = "${'$'}h_stamp" ] || return 1
              ns_h=${'$'}(tb readlink "/proc/${'$'}h_pid/ns/mnt" 2>/dev/null) || return 1
              ns_1=${'$'}(tb readlink "/proc/1/ns/mnt" 2>/dev/null) || return 1
              [ -n "${'$'}ns_h" ] && [ "${'$'}ns_h" != "${'$'}ns_1" ] || return 1
              HOLDER_PID=${'$'}h_pid
              return 0
            }

            HOLDER_PID=""
            if ! read_holder; then
              {
                tb flock 9
                if ! read_holder; then
                  /system/bin/sh "${'$'}STATE_DIR/$SETUP_NS_SCRIPT" 9>&-
                  n=0
                  until read_holder; do
                    n=${'$'}((n + 1)); [ "${'$'}n" -le 50 ] || break
                    tb sleep 0.1
                  done
                fi
              } 9>"${'$'}STATE_DIR/ns.lock"
              read_holder || { echo "${'$'}msg_ns" >&2; exit 125; }
            fi

            if [ -d /storage/emulated/0 ]; then
              tb nsenter -t "${'$'}HOLDER_PID" -m -- /system/bin/sh -c '
                if [ -x /system/bin/toybox ]; then MP="/system/bin/toybox mountpoint"; MT="/system/bin/toybox mount"; else MP=mountpoint; MT=mount; fi
                ${'$'}MP -q '"$rDir"'/storage/emulated/0 2>/dev/null || ${'$'}MT -o bind /storage/emulated/0 '"$rDir"'/storage/emulated/0 2>/dev/null || true
              ' 2>/dev/null || true
            fi

            GUEST_STAMP="${'$'}FILES_DIR/runtime/guest/stamp"
            if [ -r "${'$'}GUEST_STAMP" ] && ! tb cmp -s "${'$'}GUEST_STAMP" "${'$'}ROOTFS/etc/.luma-guest-stamp" 2>/dev/null; then
              tb nsenter -t "${'$'}HOLDER_PID" -m -- ${'$'}TBX chroot "${'$'}ROOTFS" /usr/bin/python3 -I /opt/luma/guest-sync.py >/dev/null 2>&1 || true
            fi

            exec tb nsenter -t "${'$'}HOLDER_PID" -m -- ${'$'}TBX chroot "${'$'}ROOTFS" /usr/bin/python3 -I /opt/luma/root-chroot-exec.py "${'$'}@"
        """.trimIndent() + "\n"
    }

    private fun buildRollbackScript(filesDir: File, cacheDir: File, rootfs: File, stateDir: File, uid: Int): String {
        val fDir = quote(filesDir.absolutePath)
        val cDir = quote(cacheDir.absolutePath)
        val rDir = quote(rootfs.absolutePath)
        val sDir = quote(stateDir.absolutePath)
        return """
            tb() { if [ -x /system/bin/toybox ]; then /system/bin/toybox "${'$'}@"; else "${'$'}@"; fi; }
            proc_stamp() {
              [ -r "/proc/${'$'}1/stat" ] || return 1
              stat_line=${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null) || return 1
              after=${'$'}{stat_line##*) }
              [ "${'$'}after" != "${'$'}stat_line" ] || return 1
              set -- ${'$'}after
              eval _st=\${'$'}{20:-}
              [ -n "${'$'}_st" ] || return 1
              printf '%s\n' "${'$'}_st"
            }
            FILES_DIR=$fDir
            CACHE_DIR=$cDir
            ROOTFS=$rDir
            STATE_DIR=$sDir
            APP_UID=$uid

            target_ns=""
            if [ -r "${'$'}STATE_DIR/ns.pid" ]; then
              read -r h_pid h_stamp < "${'$'}STATE_DIR/ns.pid" 2>/dev/null || { h_pid=""; h_stamp=""; }
              if [ -n "${'$'}{h_pid:-}" ] && [ -n "${'$'}{h_stamp:-}" ]; then
                actual_h_stamp=${'$'}(proc_stamp "${'$'}h_pid" 2>/dev/null || true)
                if [ -n "${'$'}actual_h_stamp" ] && [ "${'$'}actual_h_stamp" = "${'$'}h_stamp" ] && [ -e "/proc/${'$'}h_pid/ns/mnt" ]; then
                  ns_cand=${'$'}(tb readlink "/proc/${'$'}h_pid/ns/mnt" 2>/dev/null || true)
                  ns_init=${'$'}(tb readlink "/proc/1/ns/mnt" 2>/dev/null || true)
                  if [ -n "${'$'}ns_cand" ] && [ "${'$'}ns_cand" != "${'$'}ns_init" ]; then
                    target_ns=${'$'}ns_cand
                  fi
                fi
              fi
            fi

            if [ -n "${'$'}target_ns" ]; then
              targets=""
              for proc_ns in /proc/[0-9]*/ns/mnt; do
                p=${'$'}{proc_ns#/proc/}
                p=${'$'}{p%/ns/mnt}
                [ "${'$'}p" != "1" ] && [ "${'$'}p" != "${'$'}${'$'}" ] || continue
                cur_ns=${'$'}(tb readlink "${'$'}proc_ns" 2>/dev/null || true)
                if [ "${'$'}cur_ns" = "${'$'}target_ns" ]; then
                  p_stamp=${'$'}(proc_stamp "${'$'}p" 2>/dev/null || true)
                  if [ -n "${'$'}p_stamp" ]; then
                    targets="${'$'}targets ${'$'}p:${'$'}p_stamp"
                  fi
                fi
              done
              if [ -n "${'$'}targets" ]; then
                for pair in ${'$'}targets; do
                  p=${'$'}{pair%%:*}
                  st=${'$'}{pair#*:}
                  cur_ns=${'$'}(tb readlink "/proc/${'$'}p/ns/mnt" 2>/dev/null || true)
                  cur_st=${'$'}(proc_stamp "${'$'}p" 2>/dev/null || true)
                  if [ "${'$'}cur_ns" = "${'$'}target_ns" ] && [ "${'$'}cur_st" = "${'$'}st" ]; then
                    kill -TERM "${'$'}p" 2>/dev/null || true
                  fi
                done
                i=0
                while [ "${'$'}i" -lt 10 ]; do
                  alive=""
                  for pair in ${'$'}targets; do
                    p=${'$'}{pair%%:*}
                    st=${'$'}{pair#*:}
                    cur_ns=${'$'}(tb readlink "/proc/${'$'}p/ns/mnt" 2>/dev/null || true)
                    cur_st=${'$'}(proc_stamp "${'$'}p" 2>/dev/null || true)
                    if [ "${'$'}cur_ns" = "${'$'}target_ns" ] && [ "${'$'}cur_st" = "${'$'}st" ]; then
                      alive="${'$'}alive ${'$'}pair"
                    fi
                  done
                  [ -n "${'$'}alive" ] || break
                  tb usleep 100000 2>/dev/null || sleep 1
                  i=${'$'}((i + 1))
                done
                for pair in ${'$'}targets; do
                  p=${'$'}{pair%%:*}
                  st=${'$'}{pair#*:}
                  cur_ns=${'$'}(tb readlink "/proc/${'$'}p/ns/mnt" 2>/dev/null || true)
                  cur_st=${'$'}(proc_stamp "${'$'}p" 2>/dev/null || true)
                  if [ "${'$'}cur_ns" = "${'$'}target_ns" ] && [ "${'$'}cur_st" = "${'$'}st" ]; then
                    kill -KILL "${'$'}p" 2>/dev/null || true
                  fi
                done
              fi
            fi

            tb rm -f "${'$'}STATE_DIR/ns.pid" "${'$'}ROOTFS/etc/apt/apt.conf.d/99luma-root" 2>/dev/null || true
            files_ctx=${'$'}(tb stat -c %C "${'$'}FILES_DIR" 2>/dev/null || true)
            cache_ctx=${'$'}(tb stat -c %C "${'$'}CACHE_DIR" 2>/dev/null || true)
            for dir in "${'$'}ROOTFS" "${'$'}FILES_DIR/workspace" "${'$'}FILES_DIR/runtime"; do
              if [ -e "${'$'}dir" ]; then
                tb chown -hR "${'$'}APP_UID:${'$'}APP_UID" "${'$'}dir" 2>/dev/null || true
                if [ -n "${'$'}files_ctx" ] && [ "${'$'}files_ctx" != "?" ]; then
                  tb chcon -hR "${'$'}files_ctx" "${'$'}dir" 2>/dev/null || true
                fi
              fi
            done
            if [ -e "${'$'}CACHE_DIR/kitty-frames" ]; then
              tb chown -hR "${'$'}APP_UID:${'$'}APP_UID" "${'$'}CACHE_DIR/kitty-frames" 2>/dev/null || true
              if [ -n "${'$'}cache_ctx" ] && [ "${'$'}cache_ctx" != "?" ]; then
                tb chcon -hR "${'$'}cache_ctx" "${'$'}CACHE_DIR/kitty-frames" 2>/dev/null || true
              fi
            fi
        """.trimIndent()
    }

    private val chrootExecPython = """
import os, sys

try:
    os.setgroups([0, 3003, 3004, 9997])
except OSError:
    pass
os.umask(0o022)

tz = ''
try:
    with open('/etc/timezone', 'r', encoding='utf-8') as f:
        cand = f.read().strip()
        if cand and not cand.startswith('/') and '..' not in cand:
            tz = cand
except OSError:
    pass

env = {
    'HOME': '/root',
    'USER': 'root',
    'LOGNAME': 'root',
    'SHELL': '/bin/bash',
    'TERM': os.environ.get('TERM') or 'xterm-256color',
    'COLORTERM': 'truecolor',
    'LANG': 'C.UTF-8',
    'LC_ALL': 'C.UTF-8',
    'TMPDIR': '/tmp',
    'PNPM_HOME': '/root/.local/share/pnpm',
    'SSL_CERT_FILE': '/etc/ssl/certs/ca-certificates.crt',
    'NO_PROXY': '127.0.0.1,localhost,::1',
    'no_proxy': '127.0.0.1,localhost,::1',
    'PATH': '/opt/luma/bin:/root/.local/share/pnpm/bin:/root/.local/bin:/root/.kimi-code/bin:/root/.opencode/bin:/root/.grok/bin:/root/.qoder/bin:/root/.codebuddy/bin:/root/.cargo/bin:/root/.bun/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
    'LUMA_ENV': 'debian-chroot',
    'LUMA_WORKSPACE': '/workspace',
    'LUMA_BG_ANDROID_SH': '/opt/luma/bin/android-sh',
}
if os.environ.get('LUMA_LANG') in ('zh', 'en'):
    env['LUMA_LANG'] = os.environ['LUMA_LANG']
if tz:
    env['TZ'] = tz
if tz in ('Asia/Shanghai', 'Asia/Urumqi', 'Asia/Chongqing', 'Asia/Chungking', 'Asia/Harbin', 'Asia/Kashgar', 'PRC'):
    env['GOPROXY'] = 'https://goproxy.cn,direct'
    env['RUSTUP_DIST_SERVER'] = 'https://rsproxy.cn'
    env['RUSTUP_UPDATE_ROOT'] = 'https://rsproxy.cn/rustup'
    env['COREPACK_NPM_REGISTRY'] = 'https://registry.npmmirror.com'
    env['YARN_REGISTRY'] = 'https://registry.npmmirror.com'
    env['YARN_NPM_REGISTRY_SERVER'] = 'https://registry.npmmirror.com'
    env['NVM_NODEJS_ORG_MIRROR'] = 'https://npmmirror.com/mirrors/node'
    env['FNM_NODE_DIST_MIRROR'] = 'https://npmmirror.com/mirrors/node'
    env['NODE_MIRROR'] = 'https://npmmirror.com/mirrors/node/'

argv = sys.argv[1:] if len(sys.argv) > 1 else ['/bin/bash', '--login']
os.execvpe(argv[0], argv, env)
""".trimIndent() + "\n"

    private val namespaceHolderPython = """
import os, signal, sys

pid_file = sys.argv[1]
app_uid = int(sys.argv[2])

if os.fork() > 0:
    os._exit(0)
os.setsid()
if os.fork() > 0:
    os._exit(0)

os.chdir('/')
try:
    os.nice(19)
except OSError:
    pass

devnull = os.open(os.devnull, os.O_RDWR)
os.dup2(devnull, 0)
os.dup2(devnull, 1)
os.dup2(devnull, 2)
os.closerange(3, 4096)

with open('/proc/self/stat', 'r', encoding='utf-8') as sf:
    stat_text = sf.read()
stamp = stat_text[stat_text.rfind(')') + 2:].split()[19]
tmp_pid = pid_file + '.tmp'
with open(tmp_pid, 'w', encoding='utf-8') as pf:
    pf.write(f'{os.getpid()} {stamp}\n')
os.chmod(tmp_pid, 0o600)
try:
    os.chown(tmp_pid, app_uid, app_uid)
except OSError:
    pass
os.replace(tmp_pid, pid_file)

signal.signal(signal.SIGTERM, lambda *_: os._exit(0))
signal.signal(signal.SIGINT, lambda *_: os._exit(0))
while True:
    signal.pause()
""".trimIndent() + "\n"
    private val guestSyncPython = """
import hashlib, json, os

G = '/opt/luma/guest'

def read(path):
    with open(path, 'rb') as f:
        return f.read()

def put(path, data, mode=0o644):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + '.luma-tmp'
    with open(tmp, 'wb') as f:
        f.write(data)
    os.chmod(tmp, mode)
    os.replace(tmp, path)

put('/etc/profile.d/luma.sh', read(G + '/profile.sh'))

tz = read(G + '/timezone').decode('utf-8', 'replace').strip()
if tz and not tz.startswith('/') and '..' not in tz and os.path.isfile('/usr/share/zoneinfo/' + tz):
    try:
        current = read('/etc/timezone').decode('utf-8', 'replace').strip()
    except OSError:
        current = ''
    if current != tz:
        put('/etc/timezone', (tz + '\n').encode())
    target = '/usr/share/zoneinfo/' + tz
    if not (os.path.islink('/etc/localtime') and os.readlink('/etc/localtime') == target):
        try:
            os.remove('/etc/localtime')
        except FileNotFoundError:
            pass
        os.symlink(target, '/etc/localtime')

china_mirror = tz in ('Asia/Shanghai', 'Asia/Urumqi', 'Asia/Chongqing', 'Asia/Chungking', 'Asia/Harbin', 'Asia/Kashgar', 'PRC')

def norm(text):
    return text.replace('\r\n', '\n').strip()

def sync_managed(path, official, china, only_if_exists=False, mode=0o644):
    if os.path.islink(path):
        return
    exists = os.path.exists(path)
    if exists and not os.path.isfile(path):
        return
    if not exists and only_if_exists:
        return
    desired = china if china_mirror else official
    if not exists:
        if desired is not None:
            put(path, desired.encode('utf-8'), mode)
        return
    current = norm(read(path).decode('utf-8', 'replace'))
    matches_official = official is not None and current == norm(official)
    matches_china = current == norm(china)
    if not matches_official and not matches_china:
        return
    if desired is None:
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
        return
    if current != norm(desired):
        put(path, desired.encode('utf-8'), mode)

sync_managed(
    '/etc/apt/sources.list',
    'deb https://deb.debian.org/debian trixie main\ndeb https://deb.debian.org/debian trixie-updates main\ndeb https://deb.debian.org/debian-security trixie-security main\n',
    'deb https://mirrors.aliyun.com/debian trixie main\ndeb https://mirrors.aliyun.com/debian trixie-updates main\ndeb https://mirrors.aliyun.com/debian-security trixie-security main\n',
    only_if_exists=True,
)
sync_managed(
    '/etc/pip.conf',
    '[global]\nbreak-system-packages = true\n',
    '[global]\nbreak-system-packages = true\nindex-url = https://mirrors.aliyun.com/pypi/simple/\n',
)
sync_managed(
    '/etc/uv/uv.toml',
    None,
    '[[index]]\nurl = "https://mirrors.aliyun.com/pypi/simple/"\ndefault = true\n',
)
sync_managed(
    '/usr/local/etc/npmrc',
    None,
    'registry=https://registry.npmmirror.com/\ndisturl=https://npmmirror.com/mirrors/node\n',
)
sync_managed(
    '/etc/npmrc',
    None,
    'registry=https://registry.npmmirror.com/\ndisturl=https://npmmirror.com/mirrors/node\n',
)
sync_managed(
    '/root/.config/pnpm/config.yaml',
    'packageImportMethod: copy\n',
    'packageImportMethod: copy\nregistry: https://registry.npmmirror.com/\n',
)
sync_managed(
    '/root/.bunfig.toml',
    None,
    '[install]\nregistry = "https://registry.npmmirror.com/"\n',
)
sync_managed(
    '/root/.cargo/config.toml',
    None,
    '[source.crates-io]\nreplace-with = "rsproxy-sparse"\n\n[source.rsproxy-sparse]\nregistry = "sparse+https://rsproxy.cn/index/"\n',
)
sync_managed(
    '/etc/gemrc',
    None,
    ':sources:\n- https://mirrors.aliyun.com/rubygems/\n',
)

bashrc = '/root/.bashrc'
if not os.path.islink(bashrc):
    text = read(bashrc).decode('utf-8', 'replace') if os.path.isfile(bashrc) else ''
    if '# BEGIN LUMA SHELL TOOLS' not in text:
        backup = '/root/.bashrc.before-luma-shell-tools'
        if os.path.isfile(bashrc) and not os.path.exists(backup):
            put(backup, text.encode())
        put(bashrc, (text + '\n# BEGIN LUMA SHELL TOOLS\n[[ ! -r /opt/luma/shell/init.sh ]] || source /opt/luma/shell/init.sh\n# END LUMA SHELL TOOLS\n').encode())

managed = json.loads(read(G + '/managed-workspace.json'))
for name, digests in managed.items():
    if name.startswith('/') or '..' in name.split('/'):
        continue
    source = G + '/workspace/' + name
    target = '/workspace/' + name
    if not os.path.isfile(source) or os.path.isdir(target):
        continue
    allowed = digests if isinstance(digests, list) else [digests]
    if name == '.agents/skills/luma-environment/SKILL.md' or not os.path.exists(target) \
            or hashlib.sha256(read(target)).hexdigest() in allowed:
        put(target, read(source))

put('/etc/.luma-guest-stamp', read(G + '/stamp'))
""".trimIndent() + "\n"
}
