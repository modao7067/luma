package com.termux.view;

import android.content.Context;

import java.io.File;
import java.io.IOException;

public final class TerminalCursorTrailPrefs {
    private static final String DISABLED_MARKER = "terminal-cursor-trail.disabled";

    private TerminalCursorTrailPrefs() {}

    public static boolean isEnabled(Context context) {
        return !markerFile(context).isFile();
    }

    public static void setEnabled(Context context, boolean enabled) {
        File marker = markerFile(context);
        if (enabled) {
            if (marker.exists()) {
                marker.delete();
            }
            return;
        }
        if (!marker.exists()) {
            try {
                File parent = marker.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                marker.createNewFile();
            } catch (IOException ignored) {
            }
        }
    }

    private static File markerFile(Context context) {
        return new File(context.getFilesDir(), DISABLED_MARKER);
    }
}
