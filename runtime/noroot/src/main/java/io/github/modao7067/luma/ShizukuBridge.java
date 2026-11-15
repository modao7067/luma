package io.github.modao7067.luma;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import moe.shizuku.api.BinderContainer;
import rikka.shizuku.ShizukuProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShizukuBridge {
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    public static final String WEBSITE_URL = "https://shizuku.rikka.app/";

    private static final String SERVICE_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    private static final String REMOTE_PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";

    private static final int SHIZUKU_API_VERSION = 13;

    private static final int TRANSACTION_getVersion = 3;
    private static final int TRANSACTION_getUid = 4;
    private static final int TRANSACTION_newProcess = 8;
    private static final int TRANSACTION_getSELinuxContext = 9;
    private static final int TRANSACTION_attachApplicationV11 = 14;
    private static final int TRANSACTION_requestPermission = 15;
    private static final int TRANSACTION_checkSelfPermission = 16;
    private static final int TRANSACTION_shouldShowRequestPermissionRationale = 17;
    private static final int TRANSACTION_attachApplicationV13 = 18;

    private static final int TRANSACTION_bindApplication = 2;
    private static final int TRANSACTION_dispatchRequestPermissionResult = 3;
    private static final int TRANSACTION_showPermissionConfirmation = 10001;

    private static final int REMOTE_getOutputStream = 1;
    private static final int REMOTE_getInputStream = 2;
    private static final int REMOTE_getErrorStream = 3;
    private static final int REMOTE_waitFor = 4;
    private static final int REMOTE_exitValue = 5;
    private static final int REMOTE_destroy = 6;
    private static final int REMOTE_alive = 7;

    private static final String ATTACH_API_VERSION = "shizuku:attach-api-version";
    private static final String ATTACH_PACKAGE_NAME = "shizuku:attach-package-name";
    private static final String BIND_SERVER_UID = "shizuku:attach-reply-uid";
    private static final String BIND_SERVER_VERSION = "shizuku:attach-reply-version";
    private static final String BIND_SERVER_SECONTEXT = "shizuku:attach-reply-secontext";
    private static final String BIND_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted";
    private static final String BIND_SHOULD_SHOW_RATIONALE = "shizuku:attach-reply-should-show-request-permission-rationale";
    private static final String PERMISSION_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed";

    private static final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, PermissionWaiter> permissionWaiters = new ConcurrentHashMap<>();
    private static final AtomicInteger nextRequestCode = new AtomicInteger(1000);

    private static volatile IBinder serviceBinder;
    private static volatile boolean attached;
    private static volatile int cachedServerUid = -1;
    private static volatile int cachedServerVersion = -1;
    private static volatile String cachedSELinuxContext;
    private static volatile boolean cachedPermissionGranted;
    private static volatile boolean cachedShouldShowRationale;

    private static final Binder APPLICATION_BINDER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == TRANSACTION_bindApplication) {
                data.enforceInterface(APPLICATION_DESCRIPTOR);
                Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                if (bundle != null) {
                    cachedServerUid = bundle.getInt(BIND_SERVER_UID, -1);
                    cachedServerVersion = bundle.getInt(BIND_SERVER_VERSION, -1);
                    cachedSELinuxContext = bundle.getString(BIND_SERVER_SECONTEXT, null);
                    cachedPermissionGranted = bundle.getBoolean(BIND_PERMISSION_GRANTED, false);
                    cachedShouldShowRationale = bundle.getBoolean(BIND_SHOULD_SHOW_RATIONALE, false);
                    attached = true;
                }
                notifyListeners();
                return true;
            } else if (code == TRANSACTION_dispatchRequestPermissionResult) {
                data.enforceInterface(APPLICATION_DESCRIPTOR);
                int requestCode = data.readInt();
                Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                boolean allowed = bundle != null && bundle.getBoolean(PERMISSION_REPLY_ALLOWED, false);
                cachedPermissionGranted = allowed;
                PermissionWaiter waiter = permissionWaiters.remove(requestCode);
                if (waiter != null) {
                    waiter.complete(allowed);
                }
                notifyListeners();
                return true;
            } else if (code == TRANSACTION_showPermissionConfirmation) {
                data.enforceInterface(APPLICATION_DESCRIPTOR);
                return true;
            } else if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(APPLICATION_DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static final class PermissionWaiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean allowed;
        void complete(boolean result) {
            this.allowed = result;
            this.latch.countDown();
        }
    }

    private ShizukuBridge() {}

    public static IBinder peekBinder() {
        return serviceBinder;
    }

    public static void addListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) listeners.remove(listener);
    }

    private static void notifyListeners() {
        Handler handler = new Handler(Looper.getMainLooper());
        for (Runnable listener : listeners) {
            handler.post(listener);
        }
    }

    public static synchronized void onBinderReceived(IBinder newBinder, Context context) {
        if (newBinder == null || !newBinder.pingBinder()) return;
        if (serviceBinder == newBinder && attached) return;
        serviceBinder = newBinder;
        attached = false;
        try {
            newBinder.linkToDeath(() -> {
                synchronized (ShizukuBridge.class) {
                    if (serviceBinder == newBinder) {
                        serviceBinder = null;
                        attached = false;
                        cachedServerUid = -1;
                        cachedServerVersion = -1;
                        cachedSELinuxContext = null;
                        cachedPermissionGranted = false;
                    }
                }
                for (PermissionWaiter waiter : permissionWaiters.values()) {
                    waiter.complete(false);
                }
                permissionWaiters.clear();
                notifyListeners();
            }, 0);
        } catch (RemoteException e) {
            serviceBinder = null;
            return;
        }
        String packageName = context != null ? context.getPackageName() : "io.github.modao7067.luma";
        attachApplication(newBinder, packageName);
        notifyListeners();
    }

    private static void attachApplication(IBinder binder, String packageName) {
        boolean sent = false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(APPLICATION_BINDER);
            Bundle args = new Bundle();
            args.putString(ATTACH_PACKAGE_NAME, packageName);
            args.putInt(ATTACH_API_VERSION, SHIZUKU_API_VERSION);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            sent = binder.transact(TRANSACTION_attachApplicationV13, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
            sent = false;
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (!sent) {
            Parcel fallbackData = Parcel.obtain();
            Parcel fallbackReply = Parcel.obtain();
            try {
                fallbackData.writeInterfaceToken(SERVICE_DESCRIPTOR);
                fallbackData.writeStrongBinder(APPLICATION_BINDER);
                fallbackData.writeString(packageName);
                binder.transact(TRANSACTION_attachApplicationV11, fallbackData, fallbackReply, 0);
                fallbackReply.readException();
            } catch (Throwable ignored) {
            } finally {
                fallbackReply.recycle();
                fallbackData.recycle();
            }
        }
    }

    private static IBinder ensureBinder(Context context) {
        IBinder current = serviceBinder;
        if (current != null && current.pingBinder()) {
            return current;
        }
        if (context != null) {
            try {
                Uri uri = Uri.parse("content://" + context.getPackageName() + ".shizuku");
                Bundle reply = context.getContentResolver().call(uri, ShizukuProvider.METHOD_GET_BINDER, null, new Bundle());
                if (reply != null) {
                    reply.setClassLoader(BinderContainer.class.getClassLoader());
                    BinderContainer container = reply.getParcelable(ShizukuProvider.EXTRA_BINDER);
                    if (container != null && container.binder != null && container.binder.pingBinder()) {
                        onBinderReceived(container.binder, context);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        current = serviceBinder;
        return (current != null && current.pingBinder()) ? current : null;
    }

    public static boolean isInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isRunning(Context context) {
        return ensureBinder(context) != null;
    }

    public static boolean isAuthorized(Context context) {
        IBinder binder = ensureBinder(context);
        if (binder == null) return false;
        try {
            boolean granted = transactBoolean(binder, TRANSACTION_checkSelfPermission);
            cachedPermissionGranted = granted;
            return granted;
        } catch (Throwable e) {
            return cachedPermissionGranted;
        }
    }

    public static int serverUid(Context context) {
        IBinder binder = ensureBinder(context);
        if (binder == null) return -1;
        if (cachedServerUid != -1) return cachedServerUid;
        try {
            int uid = transactInt(binder, TRANSACTION_getUid);
            cachedServerUid = uid;
            return uid;
        } catch (Throwable e) {
            return cachedServerUid;
        }
    }

    public static int serverVersion(Context context) {
        IBinder binder = ensureBinder(context);
        if (binder == null) return -1;
        if (cachedServerVersion != -1) return cachedServerVersion;
        try {
            int ver = transactInt(binder, TRANSACTION_getVersion);
            cachedServerVersion = ver;
            return ver;
        } catch (Throwable e) {
            return cachedServerVersion;
        }
    }

    public static String seLinuxContext(Context context) {
        IBinder binder = ensureBinder(context);
        if (binder == null) return null;
        if (cachedSELinuxContext != null) return cachedSELinuxContext;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            binder.transact(TRANSACTION_getSELinuxContext, data, reply, 0);
            reply.readException();
            String ctx = reply.readString();
            cachedSELinuxContext = ctx;
            return ctx;
        } catch (Throwable e) {
            return cachedSELinuxContext;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void requestPermission(Context context, int requestCode) throws RemoteException {
        IBinder binder = ensureBinder(context);
        if (binder == null) throw new RemoteException(com.termux.terminal.LumaLang.tr("Shizuku 服务未运行", "Shizuku service is not running"));
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(requestCode);
            binder.transact(TRANSACTION_requestPermission, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean requestPermissionAndAwait(Context context, long timeoutMs) {
        if (isAuthorized(context)) return true;
        IBinder binder = ensureBinder(context);
        if (binder == null) return false;
        if (cachedShouldShowRationale) {
            try {
                cachedShouldShowRationale = transactBoolean(binder, TRANSACTION_shouldShowRequestPermissionRationale);
            } catch (Throwable ignored) {}
            if (cachedShouldShowRationale) return false;
        }
        int code = nextRequestCode.getAndIncrement();
        PermissionWaiter waiter = new PermissionWaiter();
        permissionWaiters.put(code, waiter);
        try {
            requestPermission(context, code);
            if (waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return waiter.allowed || isAuthorized(context);
            }
            return isAuthorized(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable e) {
            return isAuthorized(context);
        } finally {
            permissionWaiters.remove(code);
        }
    }

    public static void openOrRequestAuthorization(Context context) {
        if (isRunning(context) && !isAuthorized(context)) {
            IBinder binder = ensureBinder(context);
            boolean rationale = false;
            if (binder != null) {
                try {
                    rationale = transactBoolean(binder, TRANSACTION_shouldShowRequestPermissionRationale);
                } catch (Throwable ignored) {}
            }
            if (!rationale) {
                try {
                    requestPermission(context, nextRequestCode.getAndIncrement());
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        if (isInstalled(context)) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (launch != null) {
                if (!(context instanceof Activity)) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return;
            }
        }
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL));
        if (!(context instanceof Activity)) web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(web);
    }

    public static Process newProcess(Context context, String[] cmd, String[] env, String dir) throws IOException {
        IBinder binder = ensureBinder(context);
        if (binder == null) {
            if (!isInstalled(context)) {
                throw new IOException(com.termux.terminal.LumaLang.tr("未安装 Shizuku。请先安装并启动 Shizuku（https://shizuku.rikka.app），或在 Luma「设置 → 环境设置 → Shizuku 提权」中查看状态。", "Shizuku is not installed. Install and start Shizuku (https://shizuku.rikka.app), or check status in Luma Settings -> Environment -> Shizuku."));
            }
            throw new IOException(com.termux.terminal.LumaLang.tr("Shizuku 服务未运行。请先打开 Shizuku 应用并启动服务（无线调试或连接电脑激活），再重试。", "Shizuku service is not running. Open the Shizuku app and start the service (via Wireless debugging or ADB), then retry."));
        }
        if (!isAuthorized(context)) {
            boolean granted = requestPermissionAndAwait(context, 15_000L);
            if (!granted) {
                throw new IOException(com.termux.terminal.LumaLang.tr("Shizuku 未授权。请在弹出的授权框中点击允许，或在 Luma「设置 → 环境设置 → Shizuku 提权」及 Shizuku 应用中开启授权。", "Shizuku permission not granted. Allow access in the prompt, or enable permission in Luma Settings -> Environment -> Shizuku or the Shizuku app."));
            }
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStringArray(cmd);
            data.writeStringArray(env);
            data.writeString(dir);
            binder.transact(TRANSACTION_newProcess, data, reply, 0);
            reply.readException();
            IBinder processBinder = reply.readStrongBinder();
            if (processBinder == null) {
                throw new IOException(com.termux.terminal.LumaLang.tr("Shizuku 无法创建远端进程", "Shizuku failed to create remote process"));
            }
            return new RemoteProcessAdapter(processBinder);
        } catch (RemoteException e) {
            throw new IOException(com.termux.terminal.LumaLang.tr("Shizuku 通信失败：", "Shizuku IPC failed: ") + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        } catch (SecurityException e) {
            throw new IOException(com.termux.terminal.LumaLang.tr("Shizuku 权限被拒绝：", "Shizuku permission denied: ") + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactBoolean(IBinder binder, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static int transactInt(IBinder binder, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static final class RemoteProcessAdapter extends Process {
        private final IBinder remote;
        private OutputStream os;
        private InputStream is;
        private InputStream es;

        RemoteProcessAdapter(IBinder remote) {
            this.remote = remote;
        }

        private ParcelFileDescriptor transactPfd(int code) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
                remote.transact(code, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public synchronized OutputStream getOutputStream() {
            if (os == null) {
                try {
                    ParcelFileDescriptor pfd = transactPfd(REMOTE_getOutputStream);
                    os = pfd != null ? new ParcelFileDescriptor.AutoCloseOutputStream(pfd) : OutputStream.nullOutputStream();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            return os;
        }

        @Override
        public synchronized InputStream getInputStream() {
            if (is == null) {
                try {
                    ParcelFileDescriptor pfd = transactPfd(REMOTE_getInputStream);
                    is = pfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(pfd) : InputStream.nullInputStream();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            return is;
        }

        @Override
        public synchronized InputStream getErrorStream() {
            if (es == null) {
                try {
                    ParcelFileDescriptor pfd = transactPfd(REMOTE_getErrorStream);
                    es = pfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(pfd) : InputStream.nullInputStream();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            return es;
        }

        @Override
        public int waitFor() throws InterruptedException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
                remote.transact(REMOTE_waitFor, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public int exitValue() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
                remote.transact(REMOTE_exitValue, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public void destroy() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
                remote.transact(REMOTE_destroy, data, reply, 0);
                reply.readException();
            } catch (Throwable ignored) {
            } finally {
                reply.recycle();
                data.recycle();
            }
            closeQuietly(os);
            closeQuietly(is);
            closeQuietly(es);
        }

        @Override
        public boolean isAlive() {
            if (!remote.pingBinder()) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
                remote.transact(REMOTE_alive, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } catch (Throwable e) {
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private static void closeQuietly(java.io.Closeable c) {
            if (c != null) {
                try { c.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static final Set<String> SHELL_OPERATORS = new HashSet<>(Arrays.asList(
        "|", "||", "&", "&&", ";", "<", "<<", ">", ">>", "2>", "2>>", "2>&1", "&>", "!", "(", ")"
    ));

    private static final Pattern EMBEDDED_GUEST_PATH = Pattern.compile(
        "(?<![A-Za-z0-9_.\\-/])(/(?:workspace|root|tmp)(?:/[^\\s'\"`;|&<>()]+)*)"
    );

    public static String[] defaultAdbEnvironment() {
        return new String[]{
            "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_STORAGE=/storage",
            "EXTERNAL_STORAGE=/sdcard",
            "HOME=/data/local/tmp",
            "TMPDIR=/data/local/tmp",
            "SHELL=/system/bin/sh",
            "TERM=xterm-256color"
        };
    }

    public static String shellQuoteIfNeeded(String token) {
        if (token.isEmpty()) return "''";
        if (SHELL_OPERATORS.contains(token)) return token;
        boolean safe = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/' || c == ':' || c == '=' || c == '+' || c == ',' || c == '@')) {
                safe = false;
                break;
            }
        }
        if (safe) return token;
        return "'" + token.replace("'", "'\"'\"'") + "'";
    }

    public static String[] buildShellCommand(List<String> rewrittenArgs) {
        if (rewrittenArgs == null || rewrittenArgs.isEmpty()) {
            return new String[]{"/system/bin/sh"};
        }
        if (rewrittenArgs.size() == 1) {
            return new String[]{"/system/bin/sh", "-c", rewrittenArgs.get(0)};
        }
        if (rewrittenArgs.size() == 2 && "-c".equals(rewrittenArgs.get(0))) {
            return new String[]{"/system/bin/sh", "-c", rewrittenArgs.get(1)};
        }
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < rewrittenArgs.size(); i++) {
            if (i > 0) script.append(' ');
            script.append(shellQuoteIfNeeded(rewrittenArgs.get(i)));
        }
        return new String[]{"/system/bin/sh", "-c", script.toString()};
    }

    public static final class RelaySession implements AutoCloseable {
        private final Context context;
        private final File relayRoot;
        private final Map<File, FileSnapshot> stagedSnapshots = new HashMap<>();
        private boolean synced;
        private boolean closed;

        private static final class FileSnapshot {
            final long length;
            final long lastModified;
            final byte[] sha256;

            private FileSnapshot(long length, long lastModified, byte[] sha256) {
                this.length = length;
                this.lastModified = lastModified;
                this.sha256 = sha256;
            }

            static FileSnapshot of(File file) throws IOException {
                byte[] digest = sha256(file);
                return new FileSnapshot(file.length(), file.lastModified(), digest);
            }

            boolean matches(File file) {
                if (!file.isFile() || file.length() != length) return false;
                if (file.lastModified() == lastModified) return true;
                try {
                    return MessageDigest.isEqual(sha256, sha256(file));
                } catch (IOException e) {
                    return false;
                }
            }

            private static byte[] sha256(File file) throws IOException {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = Files.newInputStream(file.toPath())) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
                    }
                    return md.digest();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        }

        public RelaySession(Context context) {
            this.context = context;
            File cacheBase = context.getExternalCacheDir();
            if (cacheBase == null) cacheBase = new File(context.getCacheDir(), "external-cache");
            File parent = new File(cacheBase, "shizuku-relay");
            cleanStaleRelayDirs(parent);
            this.relayRoot = new File(parent, UUID.randomUUID().toString());
        }

        public File relayRoot() {
            return relayRoot;
        }

        private static void cleanStaleRelayDirs(File parent) {
            File[] children = parent.listFiles();
            if (children == null) return;
            long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
            for (File child : children) {
                if (child.lastModified() < cutoff) {
                    deleteRecursively(child);
                }
            }
        }

        private static boolean isRelayedGuestPath(String path) {
            return path.equals("/workspace") || path.startsWith("/workspace/")
                || path.equals("/root") || path.startsWith("/root/")
                || path.equals("/tmp") || path.startsWith("/tmp/");
        }

        private File hostBaseForPrefix(String prefix) {
            if ("workspace".equals(prefix)) return LinuxEnvironment.workspace(context);
            if ("root".equals(prefix)) return new File(LinuxEnvironment.root(context), "root");
            if ("tmp".equals(prefix)) return new File(LinuxEnvironment.root(context), "tmp");
            throw new IllegalArgumentException("Unknown relay prefix: " + prefix);
        }

        private String prefixForGuestPath(String path) {
            if (path.equals("/workspace") || path.startsWith("/workspace/")) return "workspace";
            if (path.equals("/root") || path.startsWith("/root/")) return "root";
            if (path.equals("/tmp") || path.startsWith("/tmp/")) return "tmp";
            return null;
        }

        public String stageGuestPath(String guestPath) throws IOException {
            String prefix = prefixForGuestPath(guestPath);
            if (prefix == null) return guestPath;
            File hostFile = NativeBridge.mapGuestPath(context, guestPath);
            File hostBase = hostBaseForPrefix(prefix).getCanonicalFile();
            String baseCanonical = hostBase.getPath();
            String fileCanonical = hostFile.getPath();
            if (!fileCanonical.equals(baseCanonical) && !fileCanonical.startsWith(baseCanonical + File.separator)) {
                throw new IOException(com.termux.terminal.LumaLang.tr("路径越界：", "Path escapes root: ") + guestPath);
            }
            String relative = fileCanonical.equals(baseCanonical) ? "" : fileCanonical.substring(baseCanonical.length() + 1);
            File relayBase = new File(relayRoot, prefix);
            File relayFile = relative.isEmpty() ? relayBase : new File(relayBase, relative);
            File parent = relayFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException(com.termux.terminal.LumaLang.tr("无法创建 Shizuku 中转目录", "Failed to create Shizuku relay directory"));
            }
            if (hostFile.isDirectory()) {
                if (!relayFile.isDirectory() && !relayFile.mkdirs()) {
                    throw new IOException(com.termux.terminal.LumaLang.tr("无法创建 Shizuku 中转目录", "Failed to create Shizuku relay directory"));
                }
            } else if (hostFile.isFile() && !stagedSnapshots.containsKey(relayFile)) {
                Files.copy(hostFile.toPath(), relayFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                stagedSnapshots.put(relayFile, FileSnapshot.of(relayFile));
            }
            return relayFile.getAbsolutePath();
        }

        public String prepareWorkingDirectory(String cwd) throws IOException {
            if (cwd == null || cwd.isEmpty()) cwd = "/workspace";
            if (isRelayedGuestPath(cwd)) {
                String staged = stageGuestPath(cwd);
                File dir = new File(staged);
                if (dir.isDirectory() || dir.mkdirs()) return dir.getAbsolutePath();
            }
            if (cwd.equals("/sdcard") || cwd.startsWith("/sdcard/")) {
                return cwd;
            }
            return "/sdcard";
        }

        public String rewriteArgument(String arg, String cwd) throws IOException {
            if (arg == null || arg.isEmpty()) return arg;
            if (isRelayedGuestPath(arg)) {
                return stageGuestPath(arg);
            }
            int eq = arg.indexOf('=');
            if (arg.startsWith("-") && eq > 0) {
                String value = arg.substring(eq + 1);
                if (isRelayedGuestPath(value)) {
                    return arg.substring(0, eq + 1) + stageGuestPath(value);
                }
            }
            if (!arg.startsWith("/") && !arg.startsWith("-") && cwd != null && isRelayedGuestPath(cwd)
                    && arg.indexOf(' ') < 0 && arg.indexOf(';') < 0 && arg.indexOf('|') < 0 && arg.indexOf('&') < 0) {
                String candidate = cwd.endsWith("/") ? cwd + arg : cwd + "/" + arg;
                try {
                    File hostCandidate = NativeBridge.mapGuestPath(context, candidate);
                    if (hostCandidate.isFile()) {
                        stageGuestPath(candidate);
                    }
                } catch (IOException ignored) {
                }
            }
            Matcher matcher = EMBEDDED_GUEST_PATH.matcher(arg);
            StringBuffer sb = null;
            while (matcher.find()) {
                if (sb == null) sb = new StringBuffer();
                String matchedPath = matcher.group(1);
                String replacement = stageGuestPath(matchedPath);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            if (sb != null) {
                matcher.appendTail(sb);
                return sb.toString();
            }
            return arg;
        }

        public List<String> rewriteArguments(List<String> args, String cwd) throws IOException {
            List<String> result = new ArrayList<>(args.size());
            for (String arg : args) {
                result.add(rewriteArgument(arg, cwd));
            }
            return result;
        }

        public synchronized void syncBack() throws IOException {
            if (synced || closed) return;
            synced = true;
            for (String prefix : new String[]{"workspace", "root", "tmp"}) {
                File relayBase = new File(relayRoot, prefix);
                if (!relayBase.isDirectory()) continue;
                File hostBase = hostBaseForPrefix(prefix).getCanonicalFile();
                syncDirectory(relayBase, relayBase, hostBase);
            }
        }

        private void syncDirectory(File relayBase, File currentRelayDir, File hostBase) throws IOException {
            File[] children = currentRelayDir.listFiles();
            if (children == null) return;
            String basePath = relayBase.getCanonicalPath();
            for (File child : children) {
                if (Files.isSymbolicLink(child.toPath())) continue;
                if (child.isDirectory()) {
                    syncDirectory(relayBase, child, hostBase);
                } else if (child.isFile()) {
                    FileSnapshot snapshot = stagedSnapshots.get(child);
                    if (snapshot != null && snapshot.matches(child)) {
                        continue;
                    }
                    String childPath = child.getCanonicalPath();
                    if (!childPath.startsWith(basePath + File.separator)) continue;
                    String relative = childPath.substring(basePath.length() + 1);
                    File dest = new File(hostBase, relative);
                    File destParent = dest.getParentFile();
                    if (destParent != null && !destParent.isDirectory() && !destParent.mkdirs()) {
                        throw new IOException(com.termux.terminal.LumaLang.tr("无法创建目标目录：", "Failed to create destination directory: ") + destParent);
                    }
                    RuntimeAssets.write(dest, Files.readAllBytes(child.toPath()));
                }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            deleteRecursively(relayRoot);
        }

        private static void deleteRecursively(File file) {
            if (file == null || !file.exists()) return;
            try {
                if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File child : children) deleteRecursively(child);
                    }
                }
                Files.deleteIfExists(file.toPath());
            } catch (IOException ignored) {
            }
        }
    }
}
