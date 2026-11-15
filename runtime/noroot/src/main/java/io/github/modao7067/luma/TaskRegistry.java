package io.github.modao7067.luma;

import android.content.Context;
import android.os.FileObserver;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskRegistry implements AutoCloseable {
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final long KEEP_LOG_BYTES = 1024L * 1024L;
    private static final int PROBE_WINDOW_BYTES = 64 * 1024;
    private static final int PROBE_READ_BUDGET = 256 * 1024;
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> BLOCKED_ENV_KEYS = new HashSet<>(Arrays.asList(
        "LD_PRELOAD", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_NO_SECCOMP", "LUMA_TASK_ID"
    ));

    private final Context context;
    private final File serviceDir;
    private final ConcurrentHashMap<String, ManagedTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService spawner;
    private final Object registryLock = new Object();
    private final ProcessOps processOps;
    private final ExecutorService logWorker;
    private final FileObserver logObserver;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    static final class Cancellation {
        private volatile boolean cancelled;
        private volatile Object monitor;
        void cancel() {
            cancelled = true;
            Object lock = monitor;
            if (lock != null) synchronized (lock) { lock.notifyAll(); }
        }
        boolean isCancelled() { return cancelled; }
        void attach(Object lock) { monitor = lock; }
        void detach() { monitor = null; }
    }

    interface ProcessOps {
        Process start(ProcessBuilder builder) throws IOException;
        void kill(int pid, String token, long graceMs, boolean linux);
    }

    private static final class SpawnAttempt {
        FutureTask<Void> future;
        volatile boolean cancelled;
        boolean inProgress;
        final AtomicBoolean cleanupClaimed = new AtomicBoolean();
    }

    static final class ManagedTask {
        final String id;
        final String name;
        final String backend;
        final List<String> command;
        final String cwd;
        final Map<String, String> guestEnv;
        final String bindHost;
        final int bindPort;
        final boolean lan;
        final String basePath;
        final String probeType;
        final String probePath;
        final String probeOkStatus;
        final Pattern probeRegex;
        final long startupTimeoutMs;
        final String restartPolicy;
        final int maxRestarts;
        final long gracePeriodMs;
        final File logFile;
        final File recordFile;

        volatile Process process;
        volatile File pidFile;
        volatile int pid = -1;
        volatile long starttime = -1L;
        volatile String ownerToken;
        volatile String detectedUrl;
        volatile String phase = "starting";
        volatile String ready = "unknown";
        volatile int exitCode = -1;
        volatile String reason = "";
        volatile long startedAt;
        volatile long updatedAt;
        volatile long exitedAt;
        volatile int restartCount;
        volatile boolean stopRequested;
        volatile boolean taskRemoved;
        SpawnAttempt launch;
        volatile boolean startupLogMatched;
        final Object stateLock = new Object();
        final Object logLock = new Object();
        final AtomicBoolean logWorkPending = new AtomicBoolean();
        long logRevision;
        long logGeneration;
        long probeLogGeneration = -1L;
        long probeLogOffset;
        byte[] probeWindow;
        int probeWindowSize;
        volatile boolean probeLogPending;

        ManagedTask(
            String id, String name, String backend, List<String> command, String cwd,
            Map<String, String> guestEnv, String bindHost, int bindPort, boolean lan, String basePath,
            String probeType, String probePath, String probeOkStatus, Pattern probeRegex,
            long startupTimeoutMs, String restartPolicy, int maxRestarts, long gracePeriodMs,
            File logFile, File recordFile
        ) {
            this.id = id;
            this.name = name;
            this.backend = backend;
            this.command = command;
            this.cwd = cwd;
            this.guestEnv = guestEnv;
            this.bindHost = bindHost;
            this.bindPort = bindPort;
            this.lan = lan;
            this.basePath = basePath;
            this.probeType = probeType;
            this.probePath = probePath;
            this.probeOkStatus = probeOkStatus;
            this.probeRegex = probeRegex;
            this.startupTimeoutMs = startupTimeoutMs;
            this.restartPolicy = restartPolicy;
            this.maxRestarts = maxRestarts;
            this.gracePeriodMs = gracePeriodMs;
            this.logFile = logFile;
            this.recordFile = recordFile;
            this.ready = "none".equals(probeType) ? "unknown" : "no";
        }
    }

    TaskRegistry(Context context) { this(context, null, null); }

    TaskRegistry(Context context, ExecutorService launchExecutor, ProcessOps ops) {
        this.context = context;
        this.processOps = ops != null ? ops : new ProcessOps() {
            public Process start(ProcessBuilder builder) throws IOException { return builder.start(); }
            public void kill(int pid, String token, long grace, boolean linux) { killTaskProcesses(pid, token, grace, linux); }
        };
        this.serviceDir = new File(context.getCacheDir(), "services");
        if (!serviceDir.isDirectory()) serviceDir.mkdirs();
        try { Os.chmod(serviceDir.getAbsolutePath(), 0700); } catch (Exception ignored) {}
        this.spawner = launchExecutor != null ? launchExecutor : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LumaServiceSpawner");
            t.setDaemon(true);
            return t;
        });
        this.logWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LumaLogMaintenance");
            t.setDaemon(true);
            return t;
        });
        reapOrphansOnStartup();
        this.logObserver = new FileObserver(serviceDir, FileObserver.MODIFY | FileObserver.CLOSE_WRITE
                | FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.DELETE | FileObserver.MOVED_FROM) {
            @Override public void onEvent(int event, String path) { onLogEvent(event, path); }
        };
        logObserver.startWatching();
    }

    private void onLogEvent(int event, String path) {
        if (closed || path == null || !path.endsWith(".log")) return;
        ManagedTask task = tasks.get(path.substring(0, path.length() - 4));
        if (task == null) return;
        if ((event & (FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
            synchronized (task.logLock) { task.logGeneration++; }
        }
        synchronized (task.stateLock) {
            task.logRevision++;
            task.stateLock.notifyAll();
        }
        scheduleLogMaintenance(task);
    }

    private void scheduleLogMaintenance(ManagedTask task) {
        if (closed || !task.logWorkPending.compareAndSet(false, true)) return;
        try {
            logWorker.execute(() -> {
                long revision;
                synchronized (task.stateLock) { revision = task.logRevision; }
                try {
                    if (!closed && tasks.get(task.id) == task) rotateLogIfNeeded(task);
                } finally {
                    synchronized (task.stateLock) {
                        task.logWorkPending.set(false);
                        if (revision != task.logRevision && tasks.get(task.id) == task) scheduleLogMaintenance(task);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) { task.logWorkPending.set(false); }
    }

    private void reapOrphansOnStartup() {
        File[] files = serviceDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            try {
                JSONObject rec = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                String phase = rec.optString("phase", "exited");
                int oldPid = rec.optInt("pid", -1);
                long oldStart = rec.optLong("starttime", -1L);
                String token = rec.optString("ownerToken", "");
                if (!"exited".equals(phase)) {
                    boolean killed = false;
                    if (oldPid > 1 && !token.isEmpty() && verifyProcessIdentity(oldPid, oldStart, token)) {
                        processOps.kill(oldPid, token, 0L, true);
                        killed = true;
                    } else if (!token.isEmpty()) {
                        List<Integer> tagged = findProcessesByToken(token, -1);
                        if (!tagged.isEmpty()) {
                            for (int p : tagged) try { Os.kill(p, OsConstants.SIGKILL); } catch (Exception ignored) {}
                            killed = true;
                        }
                    }
                    rec.put("phase", "exited");
                    rec.put("ready", "no");
                    rec.put("reason", killed ? "orphan_reaped" : "previous_session_ended");
                    rec.put("exitedAt", System.currentTimeMillis());
                    RuntimeAssets.write(file, rec.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                file.delete();
            }
        }
    }

    JSONObject startTask(JSONObject req) throws Exception {
        if (closed) throw new IOException(com.termux.terminal.LumaLang.tr("Luma 已停止", "Luma has stopped"));
        String requestedId = req.optString("id", "").trim();
        String id = requestedId.isEmpty() ? "svc-" + seq.getAndIncrement() + "-" + randomHex(3) : sanitizeId(requestedId);
        ManagedTask existing = tasks.get(id);
        if (existing != null && !"exited".equals(existing.phase)) {
            throw new IOException(com.termux.terminal.LumaLang.tr("服务 ID 已在运行：", "Service ID is already running: ") + id);
        }
        String name = req.optString("name", id);
        String backend = req.optString("backend", "linux");
        if (!"linux".equals(backend) && !"android".equals(backend)) {
            throw new IOException(com.termux.terminal.LumaLang.tr("不支持的后端：", "Unsupported backend: ") + backend);
        }
        JSONArray argsJson = req.optJSONArray("args");
        if (argsJson == null || argsJson.length() == 0) {
            throw new IOException(com.termux.terminal.LumaLang.tr("缺少服务启动命令 args", "Missing service start command args"));
        }
        ArrayList<String> rawCommand = new ArrayList<>(argsJson.length());
        for (int i = 0; i < argsJson.length(); i++) rawCommand.add(argsJson.getString(i));
        String cwd = req.optString("cwd", "/workspace");

        JSONObject listen = req.optJSONObject("listen");
        boolean lan = req.optBoolean("lan", listen != null && ("0.0.0.0".equals(listen.optString("host")) || "::".equals(listen.optString("host"))));
        String bindHost = listen != null && !listen.optString("host", "").isEmpty()
            ? listen.getString("host")
            : (lan ? "0.0.0.0" : "127.0.0.1");
        int requestedPort = listen != null ? listen.optInt("port", -1) : req.optInt("port", -1);
        String basePath = normalizeBasePath(listen != null ? listen.optString("basePath", req.optString("basePath", "")) : req.optString("basePath", ""));

        int assignedPort = requestedPort;
        if (requestedPort == 0) {
            assignedPort = allocateFreePort(bindHost);
        } else if (requestedPort > 0) {
            if (!isPortAvailable(bindHost, requestedPort)) {
                throw new IOException("port_in_use: " + com.termux.terminal.LumaLang.tr("端口已被占用 ", "Port already in use ") + bindHost + ":" + requestedPort);
            }
        }

        ArrayList<String> resolvedCommand = new ArrayList<>(rawCommand.size());
        for (String arg : rawCommand) {
            resolvedCommand.add(assignedPort > 0 ? arg.replace("{port}", Integer.toString(assignedPort)) : arg);
        }

        Map<String, String> guestEnv = new LinkedHashMap<>();
        if (assignedPort > 0) {
            guestEnv.put("PORT", Integer.toString(assignedPort));
            guestEnv.put("HOST", bindHost);
        }
        JSONObject envJson = req.optJSONObject("env");
        if (envJson != null) {
            for (Iterator<String> it = envJson.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!ENV_KEY.matcher(k).matches() || BLOCKED_ENV_KEYS.contains(k)) continue;
                guestEnv.put(k, envJson.optString(k, ""));
            }
        }

        JSONObject probe = req.optJSONObject("probe");
        String defaultProbe = assignedPort > 0 ? "http" : "none";
        String probeType = probe != null ? probe.optString("type", defaultProbe) : req.optString("probeType", defaultProbe);
        String probePath = probe != null ? probe.optString("path", "/") : req.optString("probePath", "/");
        if (!probePath.startsWith("/")) probePath = "/" + probePath;
        String probeOkStatus = probe != null ? probe.optString("okStatus", "any") : req.optString("probeOkStatus", "any");
        String regexStr = probe != null ? probe.optString("regex", "") : req.optString("probeRegex", "");
        Pattern probeRegex = regexStr.isEmpty() ? null : Pattern.compile(regexStr);
        long startupTimeoutMs = Math.max(1000L, Math.min(600_000L,
            probe != null ? probe.optLong("startupTimeoutMs", 60_000L) : req.optLong("startupTimeoutMs", 60_000L)));

        String restartPolicy = req.optString("restartPolicy", "never");
        int maxRestarts = Math.max(0, Math.min(10, req.optInt("maxRestarts", 3)));
        long gracePeriodMs = Math.max(200L, Math.min(30_000L, req.optLong("gracePeriodMs", 3000L)));

        File logFile = new File(serviceDir, id + ".log");
        File recordFile = new File(serviceDir, id + ".json");

        ManagedTask task = new ManagedTask(
            id, name, backend, resolvedCommand, cwd, guestEnv, bindHost, assignedPort, lan, basePath,
            probeType, probePath, probeOkStatus, probeRegex, startupTimeoutMs, restartPolicy, maxRestarts,
            gracePeriodMs, logFile, recordFile
        );
        synchronized (registryLock) {
            if (closed) throw new IOException(com.termux.terminal.LumaLang.tr("Luma 已停止", "Luma has stopped"));
            ManagedTask current = tasks.get(id);
            if (current != null && !"exited".equals(current.phase)) throw new IOException(com.termux.terminal.LumaLang.tr("服务 ID 已在运行：", "Service ID is already running: ") + id);
            tasks.put(id, task);
        }
        try {
            if (logFile.exists() && !logFile.delete()) throw new IOException(com.termux.terminal.LumaLang.tr("无法重置服务日志", "Failed to reset service log"));
            if (!logFile.createNewFile()) throw new IOException(com.termux.terminal.LumaLang.tr("无法创建服务日志", "Failed to create service log"));
            Os.chmod(logFile.getAbsolutePath(), 0600);
            spawnTaskInstance(task);
            return toJson(task);
        } catch (Exception e) {
            synchronized (task.stateLock) {
                if (task.launch == null) markStartFailureLocked(task, "spawn_error");
            }
            throw e;
        }
    }

    private void spawnTaskInstance(ManagedTask task) throws Exception {
        SpawnAttempt attempt = new SpawnAttempt();
        attempt.future = new FutureTask<>(() -> { launchTask(task, attempt); return null; });
        synchronized (task.stateLock) {
            if (closed || task.stopRequested) { markStartFailureLocked(task, "spawn_cancelled"); throw new IOException(com.termux.terminal.LumaLang.tr("服务已取消", "Service cancelled")); }
            task.launch = attempt;
        }
        try {
            spawner.execute(attempt.future);
            attempt.future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelLaunch(task, attempt, "spawn_timeout");
            throw new IOException(com.termux.terminal.LumaLang.tr("服务派生超时", "Service spawn timed out"), e);
        } catch (InterruptedException e) {
            cancelLaunch(task, attempt, "spawn_cancelled");
            Thread.currentThread().interrupt();
            throw e;
        } catch (CancellationException e) {
            throw new IOException(com.termux.terminal.LumaLang.tr("服务启动已取消", "Service startup cancelled"), e);
        } catch (RejectedExecutionException e) {
            synchronized (task.stateLock) { markStartFailureLocked(task, "spawn_error"); }
            throw new IOException(com.termux.terminal.LumaLang.tr("服务派生器已关闭", "Service spawner is shut down"), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException(cause);
        }
    }

    private void markStartFailureLocked(ManagedTask task, String reason) {
        task.phase = "exited";
        task.ready = "no";
        if (task.reason.isEmpty()) task.reason = reason;
        task.updatedAt = task.exitedAt = System.currentTimeMillis();
        saveRecordLocked(task);
        task.stateLock.notifyAll();
    }

    private void cancelLaunch(ManagedTask task, SpawnAttempt attempt, String reason) {
        boolean clean;
        synchronized (task.stateLock) {
            if (task.launch != attempt) return;
            attempt.cancelled = true;
            attempt.future.cancel(false);
            task.stopRequested = true;
            task.reason = reason;
            clean = !attempt.inProgress && task.process != null;
            if (!attempt.inProgress && (task.process == null || !task.process.isAlive())) markStartFailureLocked(task, reason);
            else { task.phase = "stopping"; task.stateLock.notifyAll(); }
        }
        if (clean) stopOwnedProcess(task, attempt, 0L);
    }

    private void stopOwnedProcess(ManagedTask task, SpawnAttempt attempt, long graceMs) {
        if (attempt != null && !attempt.cleanupClaimed.compareAndSet(false, true)) return;
        Process proc = task.process;
        processOps.kill(task.pid, task.ownerToken, graceMs, "linux".equals(task.backend));
        if (proc != null && proc.isAlive()) proc.destroyForcibly();
    }

    private void launchTask(ManagedTask task, SpawnAttempt attempt) throws Exception {
        String token = task.id + ":" + randomHex(8);
        synchronized (task.stateLock) {
            if (closed || task.stopRequested || attempt.cancelled) { markStartFailureLocked(task, "spawn_cancelled"); return; }
            attempt.inProgress = true;
        }
        Process proc = null;
        File pidFile = null;
        boolean monitored = false;
        try {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            pidFile = File.createTempFile("svc-" + task.id + "-", ".pid", serviceDir);
            ArrayList<String> launch = new ArrayList<>();
            launch.add(nativeDir + "/libluma_exec.so");
            launch.add(pidFile.getAbsolutePath());

            Map<String, String> envWithToken = new LinkedHashMap<>(task.guestEnv);
            envWithToken.put("LUMA_TASK_ID", token);
            List<String> lanIps = LinuxEnvironment.lanIpv4Addresses(context);
            if (!lanIps.isEmpty()) envWithToken.put("LUMA_LAN_IPV4", lanIps.get(0));

            boolean linux = "linux".equals(task.backend);
            if (linux) {
                Collections.addAll(launch, LinuxEnvironment.command(context, task.command, task.cwd, envWithToken));
            } else {
                String exe = task.command.get(0);
                if (!exe.startsWith("/")) exe = task.cwd + "/" + exe;
                File mappedExe = NativeBridge.mapGuestPath(context, exe);
                launch.add("/system/bin/linker64");
                launch.add(mappedExe.getAbsolutePath());
                for (int i = 1; i < task.command.size(); i++) {
                    launch.add(NativeBridge.mapGuestArgument(context, task.command.get(i)));
                }
            }

            ProcessBuilder builder = new ProcessBuilder(launch);
            builder.directory(NativeBridge.mapGuestPath(context, task.cwd));
            builder.redirectInput(new File("/dev/null"));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(task.logFile));

            Map<String, String> hostEnv = builder.environment();
            hostEnv.clear();
            hostEnv.put("PATH", "/system/bin");
            hostEnv.put("HOME", new File(LinuxEnvironment.root(context), "root").getAbsolutePath());
            hostEnv.put("TMPDIR", new File(LinuxEnvironment.root(context), "tmp").getAbsolutePath());
            hostEnv.put("LUMA_TASK_ID", token);
            if (linux) {
                for (String entry : LinuxEnvironment.environment(context)) {
                    int eq = entry.indexOf('=');
                    hostEnv.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            } else {
                hostEnv.put("LD_LIBRARY_PATH", nativeDir);
                for (Map.Entry<String, String> e : envWithToken.entrySet()) {
                    hostEnv.put(e.getKey(), e.getValue());
                }
            }

            synchronized (task.logLock) {
                task.probeLogOffset = task.logFile.length();
                task.probeLogGeneration = task.logGeneration;
                task.probeWindowSize = 0;
                task.startupLogMatched = false;
            }
            if (closed || task.stopRequested || attempt.cancelled) throw new IOException(com.termux.terminal.LumaLang.tr("服务已取消", "Service cancelled"));
            proc = processOps.start(builder);
            synchronized (task.stateLock) {
                task.process = proc;
                task.pidFile = pidFile;
                task.ownerToken = token;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pidFile.length() == 0L && proc.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            int pid = readPidFile(pidFile);
            synchronized (task.stateLock) {
                task.pid = pid;
                task.starttime = pid > 0 ? readProcStarttime(pid) : -1L;
                task.startedAt = task.updatedAt = System.currentTimeMillis();
                task.exitCode = -1;
                task.exitedAt = 0L;
                if (!closed && !task.stopRequested && !attempt.cancelled) {
                    task.phase = "starting";
                    task.ready = "none".equals(task.probeType) ? "unknown" : "no";
                    task.reason = "";
                }
                saveRecordLocked(task);
                task.stateLock.notifyAll();
            }
            startMonitorThreads(task, proc, pid, token);
            monitored = true;
        } catch (Exception e) {
            if (proc != null) {
                int pid = pidFile == null ? -1 : readPidFile(pidFile);
                processOps.kill(pid, token, 0L, "linux".equals(task.backend));
                proc.destroyForcibly();
            }
            synchronized (task.stateLock) { markStartFailureLocked(task, "spawn_error"); }
            throw e;
        } finally {
            boolean abort;
            synchronized (task.stateLock) {
                attempt.inProgress = false;
                abort = closed || task.stopRequested || attempt.cancelled;
                task.stateLock.notifyAll();
            }
            if (monitored && abort) stopOwnedProcess(task, attempt, 0L);
            if (!monitored && pidFile != null) pidFile.delete();
        }
    }

    private void startMonitorThreads(ManagedTask task, Process proc, int pid, String token) {
        Thread waiter = new Thread(() -> {
            int code = -1;
            try {
                code = proc.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (task.pidFile != null) task.pidFile.delete();
            boolean shouldRestart = false;
            long backoffMs = 0L;
            synchronized (task.stateLock) {
                if (task.process != proc) return;
                boolean wasRunning = "running".equals(task.phase);
                task.exitCode = code;
                task.exitedAt = System.currentTimeMillis();
                task.updatedAt = task.exitedAt;
                if (task.taskRemoved) {
                    task.phase = "exited";
                    task.ready = "no";
                    task.reason = "app_task_removed";
                } else if (task.stopRequested) {
                    task.phase = "exited";
                    task.ready = "no";
                    if (task.reason.isEmpty()) task.reason = "user_stop";
                } else if ("start_timeout".equals(task.reason)) {
                    task.phase = "exited";
                    task.ready = "no";
                } else if (wasRunning && "on-failure".equals(task.restartPolicy) && code != 0 && task.restartCount < task.maxRestarts && !closed) {
                    task.restartCount++;
                    shouldRestart = true;
                    backoffMs = Math.min(10_000L, 500L * (1L << (task.restartCount - 1)));
                    task.phase = "starting";
                    task.ready = "none".equals(task.probeType) ? "unknown" : "no";
                    task.reason = "restarting_" + task.restartCount;
                } else {
                    task.phase = "exited";
                    task.ready = "no";
                    task.reason = code == 0 ? "unexpected_exit0" : "crash";
                }
                saveRecordLocked(task);
                task.stateLock.notifyAll();
            }
            scheduleLogMaintenance(task);
            processOps.kill(pid, token, 0L, false);
            if (shouldRestart) {
                try {
                    synchronized (task.stateLock) {
                        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
                        while (!closed && !task.stopRequested) {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0L) break;
                            TimeUnit.NANOSECONDS.timedWait(task.stateLock, remaining);
                        }
                        if (closed || task.stopRequested) {
                            task.reason = closed || task.taskRemoved ? "app_task_removed" : "user_stop";
                            markStartFailureLocked(task, task.reason);
                            return;
                        }
                    }
                    spawnTaskInstance(task);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    synchronized (task.stateLock) {
                        task.reason = closed || task.taskRemoved ? "app_task_removed"
                            : task.stopRequested ? "user_stop" : "spawn_error";
                        markStartFailureLocked(task, task.reason);
                    }
                }
            }
        }, "LumaSvcWait-" + task.id);
        waiter.setDaemon(true);
        waiter.start();

        Thread prober = new Thread(() -> runProbeLoop(task, proc, pid, token), "LumaSvcProbe-" + task.id);
        prober.setDaemon(true);
        prober.start();
    }

    private void runProbeLoop(ManagedTask task, Process proc, int pid, String token) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(task.startupTimeoutMs);
        try {
            if ("none".equals(task.probeType)) Thread.sleep(150);
            while (!closed && task.process == proc && proc.isAlive() && !task.stopRequested) {
                long revision;
                synchronized (task.stateLock) { revision = task.logRevision; }
                boolean ok = "none".equals(task.probeType) || checkSingleProbe(task);
                boolean timedOut = false;
                synchronized (task.stateLock) {
                    if (closed || task.process != proc || task.stopRequested || !proc.isAlive()
                            || !"starting".equals(task.phase)) return;
                    if (ok) {
                        task.phase = "running";
                        task.ready = "none".equals(task.probeType) ? "unknown" : "yes";
                        task.updatedAt = System.currentTimeMillis();
                        saveRecordLocked(task);
                        task.stateLock.notifyAll();
                        return;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        task.reason = "start_timeout";
                        task.phase = "stopping";
                        task.ready = "no";
                        saveRecordLocked(task);
                        task.stateLock.notifyAll();
                        timedOut = true;
                    } else if ("log".equals(task.probeType)) {
                        if (!task.startupLogMatched && !task.probeLogPending && revision == task.logRevision) {
                            TimeUnit.NANOSECONDS.timedWait(task.stateLock, remaining);
                        }
                    } else {
                        long retryAt = System.nanoTime() + Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200));
                        long delay;
                        while (!closed && !task.stopRequested && task.process == proc && "starting".equals(task.phase)
                                && (delay = retryAt - System.nanoTime()) > 0L) {
                            TimeUnit.NANOSECONDS.timedWait(task.stateLock, delay);
                        }
                    }
                }
                if (timedOut) {
                    processOps.kill(pid, token, 1000L, "linux".equals(task.backend));
                    proc.destroyForcibly();
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (task.logLock) {
                if (task.process == proc) { task.probeWindow = null; task.probeWindowSize = 0; }
            }
        }
    }

    private boolean checkLogProbe(ManagedTask task) throws IOException {
        if (task.probeRegex == null) return false;
        synchronized (task.logLock) {
            if (task.startupLogMatched) return true;
            task.probeLogPending = false;
            if (!task.logFile.isFile()) return false;
            try (RandomAccessFile log = new RandomAccessFile(task.logFile, "r")) {
                long length = log.length();
                if (task.probeLogGeneration != task.logGeneration || task.probeLogOffset > length) {
                    task.probeLogGeneration = task.logGeneration;
                    task.probeLogOffset = 0L;
                    task.probeWindowSize = 0;
                }
                if (task.probeWindow == null) task.probeWindow = new byte[PROBE_WINDOW_BYTES];
                log.seek(task.probeLogOffset);
                byte[] chunk = new byte[8192];
                int budget = PROBE_READ_BUDGET;
                while (task.probeLogOffset < length && budget > 0) {
                    int count = log.read(chunk, 0, (int) Math.min(chunk.length, Math.min(budget, length - task.probeLogOffset)));
                    if (count <= 0) break;
                    int keep = Math.min(task.probeWindowSize, PROBE_WINDOW_BYTES - count);
                    System.arraycopy(task.probeWindow, task.probeWindowSize - keep, task.probeWindow, 0, keep);
                    System.arraycopy(chunk, 0, task.probeWindow, keep, count);
                    task.probeWindowSize = keep + count;
                    task.probeLogOffset += count;
                    budget -= count;
                    Matcher match = task.probeRegex.matcher(new String(task.probeWindow, 0,
                        task.probeWindowSize, StandardCharsets.UTF_8));
                    if (match.find()) {
                        if (match.end() == task.probeWindowSize && task.probeLogOffset < length) continue;
                        if (match.groupCount() >= 1 && match.group(1) != null) task.detectedUrl = match.group(1);
                        else if (match.group(0).startsWith("http")) task.detectedUrl = match.group(0);
                        task.startupLogMatched = true;
                        return true;
                    }
                }
                task.probeLogPending = task.probeLogOffset < length;
            }
            return false;
        }
    }

    private boolean checkSingleProbe(ManagedTask task) {
        if ("log".equals(task.probeType)) {
            try { return checkLogProbe(task); } catch (IOException ignored) { return false; }
        }
        int port = task.bindPort;
        if (port <= 0) return false;
        if ("tcp".equals(task.probeType)) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try (Socket s = new Socket()) {
            s.setSoTimeout(1500);
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            OutputStream out = s.getOutputStream();
            String req = "GET " + task.probePath + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\nUser-Agent: luma-probe/1.0\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null || !statusLine.startsWith("HTTP/")) return false;
            String[] parts = statusLine.split(" ");
            if (parts.length < 2) return false;
            int status = Integer.parseInt(parts[1].trim());
            if ("2xx".equals(task.probeOkStatus)) return status >= 200 && status < 300;
            if ("<500".equals(task.probeOkStatus)) return status >= 100 && status < 500;
            return status >= 100 && status < 600;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isTaskHealthy(String id, long timeoutMs) {
        ManagedTask task = tasks.get(id);
        if (task == null) return false;
        String phase;
        synchronized (task.stateLock) { phase = task.phase; }
        if ("starting".equals(phase)) return true;
        if (!"running".equals(phase)) return false;
        if ("none".equals(task.probeType) || "log".equals(task.probeType)) return true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(200L, timeoutMs));
        while (true) {
            if (checkSingleProbe(task)) return true;
            if (System.nanoTime() >= deadline) return false;
            try { Thread.sleep(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    JSONObject stopOwnedCliBridge(String id) throws Exception {
        if (!"luma-native-cli-gui".equals(id) && !"luma-native-cli-gui-v3".equals(id) &&
            !"luma-antigravity-gui".equals(id) && !"luma-acp-cli-gui".equals(id) &&
            !"luma-codebuddy-gui-v1".equals(id)) throw new IOException(com.termux.terminal.LumaLang.tr("无效 CLI 桥服务标识", "Invalid CLI bridge service ID"));
        synchronized (registryLock) {
            ManagedTask task = tasks.get(id);
            if (task == null) throw new IOException(com.termux.terminal.LumaLang.tr("无法确认 CLI 桥服务身份；未停止任何服务", "Cannot verify CLI bridge service identity; nothing stopped"));
            synchronized (task.stateLock) {
                String launch = task.command.size() == 4 ? task.command.get(3) : "";
                String expected = "exec /usr/bin/python3 -I -B '/opt/luma/agent/cli-bridge-v[0-9]+/main\\.py' [0-9]+ '/opt/luma/agent/cli-bridge-token' '/opt/luma/agent/cli-sessions'";
                if (!"linux".equals(task.backend) || task.command.size() != 4 ||
                    !"/bin/bash".equals(task.command.get(0)) || !"--login".equals(task.command.get(1)) ||
                    !"-c".equals(task.command.get(2)) || !launch.matches(expected)) {
                    throw new IOException(com.termux.terminal.LumaLang.tr("CLI 桥服务命令身份不匹配；未停止任何服务", "CLI bridge service command mismatch; nothing stopped"));
                }
                if ("exited".equals(task.phase)) return toJson(task);
                if (task.process == null || task.ownerToken == null || task.ownerToken.isEmpty())
                    throw new IOException(com.termux.terminal.LumaLang.tr("无法确认 CLI 桥服务进程归属；未停止任何服务", "Cannot verify CLI bridge process ownership; nothing stopped"));
            }
            return stopTask(id, 3000L, false);
        }
    }

    JSONObject stopTask(String id, long graceOverrideMs, boolean taskRemoved) throws IOException {
        ManagedTask task = tasks.get(id);
        if (task == null) throw new IOException(com.termux.terminal.LumaLang.tr("未找到服务：", "Service not found: ") + id);
        Process proc;
        int pid;
        String token;
        long graceMs;
        synchronized (task.stateLock) {
            if ("exited".equals(task.phase)) return toJson(task);
            task.stopRequested = true;
            task.taskRemoved = taskRemoved;
            task.phase = "stopping";
            task.reason = taskRemoved ? "app_task_removed" : "user_stop";
            task.updatedAt = System.currentTimeMillis();
            saveRecordLocked(task);
            task.stateLock.notifyAll();
            proc = task.process;
            pid = task.pid;
            token = task.ownerToken;
            graceMs = graceOverrideMs >= 0 ? graceOverrideMs : task.gracePeriodMs;
        }
        SpawnAttempt attempt;
        boolean launching;
        synchronized (task.stateLock) {
            attempt = task.launch;
            if (attempt != null) { attempt.cancelled = true; attempt.future.cancel(false); }
            launching = attempt != null && attempt.inProgress;
            if (!launching && (proc == null || !proc.isAlive())) markStartFailureLocked(task, task.reason);
        }
        if (!launching && proc != null) stopOwnedProcess(task, attempt, graceMs);
        if (!launching && proc != null) {
            try { proc.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (proc.isAlive()) proc.destroyForcibly();
        }
        synchronized (task.stateLock) {
            long deadline = System.currentTimeMillis() + 1000L;
            while (!"exited".equals(task.phase) && System.currentTimeMillis() < deadline) {
                try { task.stateLock.wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return toJson(task);
        }
    }

    private void killTaskProcesses(int rootPid, String token, long graceMs, boolean linuxProot) {
        if (graceMs > 0) {
            if (linuxProot && token != null && !token.isEmpty()) {
                List<Integer> guestPids = findProcessesByToken(token, rootPid);
                if (!guestPids.isEmpty()) {
                    for (int p : guestPids) {
                        try { Os.kill(p, OsConstants.SIGTERM); } catch (Exception ignored) {}
                    }
                } else if (rootPid > 1) {
                    try { Os.kill(rootPid, OsConstants.SIGTERM); } catch (Exception ignored) {}
                }
            } else if (rootPid > 1) {
                try { Os.kill(-rootPid, OsConstants.SIGTERM); } catch (Exception ignored) {}
            }
            long deadline = System.currentTimeMillis() + graceMs;
            while (System.currentTimeMillis() < deadline) {
                boolean rootAlive = rootPid > 1 && new File("/proc/" + rootPid).isDirectory();
                List<Integer> remaining = (token != null && !token.isEmpty()) ? findProcessesByToken(token, -1) : Collections.emptyList();
                if (!rootAlive && remaining.isEmpty()) return;
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (rootPid > 1) {
            try { Os.kill(-rootPid, OsConstants.SIGKILL); } catch (Exception ignored) {}
            try { Os.kill(rootPid, OsConstants.SIGKILL); } catch (Exception ignored) {}
        }
        if (token != null && !token.isEmpty()) {
            for (int p : findProcessesByToken(token, -1)) {
                try { Os.kill(-p, OsConstants.SIGKILL); } catch (Exception ignored) {}
                try { Os.kill(p, OsConstants.SIGKILL); } catch (Exception ignored) {}
            }
        }
    }

    JSONObject waitTask(String id, String waitFor, long timeoutMs) throws Exception {
        return waitTask(id, waitFor, timeoutMs, new Cancellation());
    }

    JSONObject waitTask(String id, String waitFor, long timeoutMs, Cancellation cancellation) throws Exception {
        ManagedTask task = tasks.get(id);
        if (task == null) throw new IOException(com.termux.terminal.LumaLang.tr("未找到服务：", "Service not found: ") + id);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(100L, Math.min(600_000L, timeoutMs)));
        cancellation.attach(task.stateLock);
        try {
            synchronized (task.stateLock) {
                while (!closed && !cancellation.isCancelled() && !"exited".equals(task.phase)) {
                    if ("ready".equals(waitFor) && "running".equals(task.phase)
                            && ("yes".equals(task.ready) || "unknown".equals(task.ready))) break;
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) break;
                    TimeUnit.NANOSECONDS.timedWait(task.stateLock, remaining);
                }
                if (cancellation.isCancelled()) throw new IOException(com.termux.terminal.LumaLang.tr("客户端已断开", "Client disconnected"));
                JSONObject out = toJson(task);
                boolean satisfied = "exited".equals(waitFor)
                    ? "exited".equals(task.phase)
                    : ("running".equals(task.phase) && ("yes".equals(task.ready) || "unknown".equals(task.ready)));
                out.put("waitSatisfied", satisfied);
                return out;
            }
        } finally { cancellation.detach(); }
    }

    JSONObject listTasks() throws Exception {
        JSONArray arr = new JSONArray();
        ArrayList<String> ids = new ArrayList<>(tasks.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            ManagedTask t = tasks.get(id);
            if (t != null) arr.put(toJson(t));
        }
        return new JSONObject().put("services", arr);
    }

    JSONObject getStatus(String id) throws Exception {
        ManagedTask task = tasks.get(id);
        if (task == null) throw new IOException(com.termux.terminal.LumaLang.tr("未找到服务：", "Service not found: ") + id);
        return toJson(task);
    }

    String readTailLog(String id, int maxBytes) {
        ManagedTask task = tasks.get(id);
        if (task == null || !task.logFile.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(task.logFile, "r")) {
            long len = raf.length();
            if (len <= 0) return "";
            int count = (int) Math.min(len, Math.max(512, maxBytes));
            byte[] buf = new byte[count];
            raf.seek(len - count);
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    void streamLogs(String id, long offset, long tailBytes, boolean follow, OutputStream output) throws Exception {
        streamLogs(id, offset, tailBytes, follow, output, new Cancellation());
    }

    void streamLogs(String id, long offset, long tailBytes, boolean follow, OutputStream output,
                    Cancellation cancellation) throws Exception {
        ManagedTask task = tasks.get(id);
        if (task == null) throw new IOException(com.termux.terminal.LumaLang.tr("未找到服务：", "Service not found: ") + id);
        long pos = Math.max(0L, offset);
        long generation;
        synchronized (task.logLock) {
            generation = task.logGeneration;
            if (tailBytes > 0 && task.logFile.isFile()) pos = Math.max(0L, task.logFile.length() - tailBytes);
        }
        long seenRevision;
        synchronized (task.stateLock) { seenRevision = task.logRevision; }
        boolean finalDrain = false;
        byte[] buf = new byte[8192];
        cancellation.attach(task.stateLock);
        try {
            while (!closed && !cancellation.isCancelled()) {
                int count = 0;
                synchronized (task.logLock) {
                    if (generation != task.logGeneration) { generation = task.logGeneration; pos = 0L; }
                    if (task.logFile.isFile()) {
                        try (RandomAccessFile log = new RandomAccessFile(task.logFile, "r")) {
                            long length = log.length();
                            if (pos > length) pos = 0L;
                            if (length > pos) {
                                log.seek(pos);
                                count = log.read(buf, 0, (int) Math.min(buf.length, length - pos));
                                if (count > 0) pos += count;
                            }
                        }
                    }
                }
                if (count > 0) {
                    NativeBridge.sendEvent(output, new JSONObject()
                        .put("stdout", Base64.encodeToString(buf, 0, count, Base64.NO_WRAP)).put("offset", pos));
                    continue;
                }
                if (!follow || finalDrain) break;
                synchronized (task.stateLock) {
                    if ("exited".equals(task.phase)) {
                        finalDrain = true;
                    } else {
                        while (!closed && !cancellation.isCancelled() && !"exited".equals(task.phase)
                                && seenRevision == task.logRevision) task.stateLock.wait();
                        seenRevision = task.logRevision;
                    }
                }
            }
            if (!cancellation.isCancelled() && !closed) {
                NativeBridge.sendEvent(output, new JSONObject().put("offset", pos).put("exit", 0));
            }
        } finally { cancellation.detach(); }
    }

    private JSONObject toJson(ManagedTask task) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", task.id);
            obj.put("name", task.name);
            obj.put("backend", task.backend);
            obj.put("phase", task.phase);
            obj.put("ready", task.ready);
            obj.put("pid", task.pid);
            obj.put("cwd", task.cwd);
            obj.put("command", new JSONArray(task.command));
            obj.put("restartCount", task.restartCount);
            obj.put("startedAt", task.startedAt);
            obj.put("updatedAt", task.updatedAt);
            JSONObject exitObj = new JSONObject();
            exitObj.put("code", task.exitCode);
            exitObj.put("reason", task.reason);
            exitObj.put("at", task.exitedAt);
            obj.put("exit", exitObj);

            if (task.bindPort > 0) {
                obj.put("listen", new JSONObject()
                    .put("host", task.bindHost)
                    .put("port", task.bindPort)
                    .put("lan", task.lan)
                    .put("basePath", task.basePath));
            }
            JSONArray access = new JSONArray();
            String primaryUrl = null;
            if (task.bindPort > 0) {
                primaryUrl = "http://127.0.0.1:" + task.bindPort + task.basePath;
                access.put(new JSONObject()
                    .put("url", primaryUrl)
                    .put("scope", "loopback")
                    .put("source", "configured"));
                if (task.lan) {
                    for (String ip : LinuxEnvironment.lanIpv4Addresses(context)) {
                        access.put(new JSONObject()
                            .put("url", "http://" + ip + ":" + task.bindPort + task.basePath)
                            .put("scope", "lan")
                            .put("source", "network"));
                    }
                }
            }
            if (task.detectedUrl != null && !task.detectedUrl.isEmpty()) {
                if (primaryUrl == null) primaryUrl = task.detectedUrl;
                access.put(new JSONObject()
                    .put("url", task.detectedUrl)
                    .put("scope", "detected")
                    .put("source", "log"));
            }
            obj.put("access", access);
            if (primaryUrl != null) obj.put("baseUrl", primaryUrl);
            return obj;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void saveRecordLocked(ManagedTask task) {
        try {
            if (tasks.get(task.id) != task) return;
            JSONObject rec = toJson(task);
            rec.put("starttime", task.starttime);
            rec.put("ownerToken", task.ownerToken == null ? "" : task.ownerToken);
            RuntimeAssets.write(task.recordFile, rec.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { android.util.Log.w("Luma", "Unable to save service record " + task.id, e); }
    }

    private void rotateLogIfNeeded(ManagedTask task) {
        synchronized (task.logLock) {
            try {
                if (!task.logFile.isFile() || task.logFile.length() <= MAX_LOG_BYTES) return;
                try (RandomAccessFile log = new RandomAccessFile(task.logFile, "rw")) {
                    long length = log.length();
                    if (length <= MAX_LOG_BYTES) return;
                    if ("starting".equals(task.phase) && "log".equals(task.probeType)) {
                        do { checkLogProbe(task); } while (task.probeLogOffset < length && task.probeLogPending
                            && !task.startupLogMatched && "starting".equals(task.phase) && !closed);
                        if ("starting".equals(task.phase) && !task.startupLogMatched) return;
                    }
                    byte[] tail = new byte[(int) KEEP_LOG_BYTES];
                    log.seek(length - KEEP_LOG_BYTES);
                    log.readFully(tail);
                    log.seek(0L);
                    log.write(tail);
                    log.setLength(KEEP_LOG_BYTES);
                    task.logGeneration++;
                }
            } catch (IOException ignored) {}
        }
    }

    private static boolean isPortAvailable(String host, int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(host, port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int allocateFreePort(String host) throws IOException {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, 0), 1);
            return ss.getLocalPort();
        }
    }

    private static String normalizeBasePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty() || "/".equals(p)) return "";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String sanitizeId(String raw) throws IOException {
        if (!raw.matches("^[A-Za-z0-9._-]{1,64}$")) {
            throw new IOException(com.termux.terminal.LumaLang.tr("非法服务 ID（仅限字母、数字、.、_、-）：", "Invalid service ID (letters, digits, ., _, - only): ") + raw);
        }
        return raw;
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    private static int readPidFile(File file) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readProcStarttime(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            int rparen = stat.lastIndexOf(')');
            if (rparen < 0) return -1L;
            String[] fields = stat.substring(rparen + 2).trim().split("\\s+");
            return fields.length > 19 ? Long.parseLong(fields[19]) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static boolean verifyProcessIdentity(int pid, long expectedStarttime, String expectedToken) {
        if (pid <= 1 || !new File("/proc/" + pid).isDirectory()) return false;
        if (expectedStarttime > 0 && readProcStarttime(pid) != expectedStarttime) return false;
        return hasEnvToken(pid, expectedToken);
    }

    private static boolean hasEnvToken(int pid, String expectedToken) {
        try {
            byte[] env = Files.readAllBytes(new File("/proc/" + pid + "/environ").toPath());
            String needle = "LUMA_TASK_ID=" + expectedToken;
            int start = 0;
            for (int i = 0; i <= env.length; i++) {
                if (i == env.length || env[i] == 0) {
                    if (i > start) {
                        String entry = new String(env, start, i - start, StandardCharsets.UTF_8);
                        if (needle.equals(entry)) return true;
                    }
                    start = i + 1;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static List<Integer> findProcessesByToken(String expectedToken, int excludePid) {
        File proc = new File("/proc");
        String[] entries = proc.list();
        if (entries == null) return Collections.emptyList();
        ArrayList<Integer> matched = new ArrayList<>();
        int myPid = android.os.Process.myPid();
        for (String entry : entries) {
            if (entry.isEmpty() || !Character.isDigit(entry.charAt(0))) continue;
            try {
                int pid = Integer.parseInt(entry);
                if (pid <= 1 || pid == myPid || pid == excludePid) continue;
                if (hasEnvToken(pid, expectedToken)) matched.add(pid);
            } catch (NumberFormatException ignored) {}
        }
        return matched;
    }

    @Override
    public void close() {
        synchronized (registryLock) { closed = true; }
        logObserver.stopWatching();
        logWorker.shutdownNow();
        for (ManagedTask task : tasks.values()) synchronized (task.stateLock) { task.stateLock.notifyAll(); }
        for (String id : new ArrayList<>(tasks.keySet())) {
            try { stopTask(id, 500L, true); } catch (Exception ignored) {}
        }
        spawner.shutdown();
        try {
            if (!spawner.awaitTermination(5, TimeUnit.SECONDS)) spawner.shutdownNow();
        } catch (InterruptedException e) { spawner.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
