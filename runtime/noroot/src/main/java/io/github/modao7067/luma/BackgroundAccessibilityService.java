package io.github.modao7067.luma;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

public final class BackgroundAccessibilityService extends AccessibilityService {
    private static volatile boolean connected;
    private static final java.util.Set<Runnable> connectionListeners = new java.util.concurrent.CopyOnWriteArraySet<>();

    public static void addConnectionListener(Runnable listener) { connectionListeners.add(listener); }
    public static void removeConnectionListener(Runnable listener) { connectionListeners.remove(listener); }
    private static void setConnected(boolean value) {
        if (connected == value) return;
        connected = value;
        for (Runnable listener : connectionListeners) {
            try { listener.run(); }
            catch (RuntimeException error) { android.util.Log.w("Luma", "Environment status observer failed"); }
        }
    }

    public static boolean isConnected() { return connected; }

    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() == null) continue;
            ServiceInfo service = info.getResolveInfo().serviceInfo;
            if (service != null && context.getPackageName().equals(service.packageName)
                    && BackgroundAccessibilityService.class.getName().equals(service.name)) return true;
        }
        return false;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        setConnected(true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(Intent intent) {
        setConnected(false);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        setConnected(false);
        super.onDestroy();
    }
}
