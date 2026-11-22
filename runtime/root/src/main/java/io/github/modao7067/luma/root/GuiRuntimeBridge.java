package io.github.modao7067.luma.root;

import android.content.Context;
import java.io.File;
import java.io.IOException;

public final class GuiRuntimeBridge {
    private GuiRuntimeBridge() {}
    public static void copyFile(Context context, String path, File destination, long limit) throws IOException {
        TerminalLinkReader.copy(context, path, destination, limit);
    }
    public static void copyFile(Context context, String path, File destination, long limit, String allowedPrefix) throws IOException {
        TerminalLinkReader.copy(context, path, destination, limit, allowedPrefix);
    }
}
