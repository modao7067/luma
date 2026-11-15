package io.github.modao7067.luma.shared;

import android.content.Context;
import android.os.Process;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class ForegroundTerminalTracker {
    private ForegroundTerminalTracker() {}

    private static File markerFile(Context context) {
        return new File(new File(context.getFilesDir(), "runtime"), "foreground-terminal.json");
    }

    private static String processStart() {
        try {
            String raw = new String(Files.readAllBytes(new File("/proc/self/stat").toPath()), StandardCharsets.UTF_8);
            int end = raw.lastIndexOf(')');
            if (end < 0) return "";
            String[] fields = raw.substring(end + 1).trim().split("\\s+");
            if (fields.length <= 19 || "Z".equals(fields[0])) return "";
            return fields[19];
        } catch (Exception ignored) {
            return "";
        }
    }

    public static synchronized void record(Context context, String token, ManagedTerminalRequest request) {
        if (context == null || token == null || request == null) return;
        File file = markerFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = null;
        try {
            tmp = File.createTempFile("foreground-terminal-", ".tmp", parent);
            JSONObject value = new JSONObject()
                .put("token", token)
                .put("pid", Process.myPid())
                .put("processStart", processStart())
                .put("windowId", request.windowId)
                .put("sessionId", request.sessionId)
                .put("instanceId", request.instanceId)
                .put("updatedAt", System.currentTimeMillis());
            Files.write(tmp.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp.toPath());
                } catch (Exception ignored) {}
            }
        }
    }

    public static synchronized void clear(Context context, String token) {
        if (context == null || token == null) return;
        File file = markerFile(context);
        if (!file.isFile()) return;
        try {
            JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (token.equals(value.optString("token"))) file.delete();
        } catch (Exception ignored) {}
    }
}
