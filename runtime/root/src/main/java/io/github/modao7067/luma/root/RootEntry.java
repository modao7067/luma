package io.github.modao7067.luma.root;

import android.content.Context;
import java.io.File;

final class RootEntry {
    static final String DEFAULT_LAUNCHER = "/data/local/linux/bin/ubuntu";
    private RootEntry() {}

    static boolean usesBuiltinChroot(Context context) {
        if (context == null) return false;
        File dir = new File(context.getFilesDir(), "root-chroot");
        return new File(dir, "enabled").isFile() && new File(dir, "enter.sh").isFile();
    }

    static String launcher(Context context) {
        if (usesBuiltinChroot(context)) {
            return new File(context.getFilesDir(), "root-chroot/enter.sh").getAbsolutePath();
        }
        return DEFAULT_LAUNCHER;
    }

    static String suExecutable() {
        String[] fixed = {"/system/bin/su", "/sbin/su", "/system/xbin/su", "/su/bin/su"};
        for (String path : fixed) {
            File file = new File(path);
            if (file.isFile() && file.canExecute()) return path;
        }
        String rawPath = System.getenv("PATH");
        if (rawPath != null) {
            for (String dir : rawPath.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                File candidate = new File(dir, "su");
                if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
            }
        }
        return "su";
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
