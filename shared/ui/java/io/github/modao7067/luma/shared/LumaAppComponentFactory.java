package io.github.modao7067.luma.shared;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;

public final class LumaAppComponentFactory extends AppComponentFactory {
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, ApplicationInfo info) {
        try (ZipFile apk = new ZipFile(info.sourceDir)) {
            if (info.dataDir == null || info.deviceProtectedDataDir == null)
                throw new IOException("Application cache directories unavailable");
            File credential = new File(info.dataDir), device = new File(info.deviceProtectedDataDir);
            boolean refreshed = FontCacheInvalidator.refresh(
                new File(credential, "no_backup/terminal-font-cache.sha256"),
                FontCacheInvalidator.fingerprint(apk),
                new File(credential, "cache"), new File(credential, "code_cache"),
                new File(device, "cache"), new File(device, "code_cache"));
            if (refreshed) Log.i("LumaGraphics", "APK graphics cache generation updated");
        } catch (Exception failure) {
            Log.w("LumaGraphics", "APK graphics cache refresh deferred", failure);
        }
        return super.instantiateClassLoader(loader, info);
    }
}
