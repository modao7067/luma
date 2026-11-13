-keep class com.termux.terminal.JNI {
    public static native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep public class io.github.modao7067.luma.MainActivity
-keep public class io.github.modao7067.luma.SetupActivity
-keep public class io.github.modao7067.luma.ShellService

-keep class io.github.modao7067.luma.ghostty.GhosttyNative { *; }
-keep class io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator { *; }
-keep class io.github.modao7067.luma.ghostty.GhosttyImageDecoder { *; }

-keep class com.termux.view.TerminalPreviewScreen { public *; }
-keep class com.termux.view.BundledTerminalFonts {
    public static void initialize(android.content.res.AssetManager);
}

-keep class org.scilab.forge.jlatexmath.** { *; }

-keep class io.github.modao7067.luma.gui.GuiProduct { *; }

-keep class moe.shizuku.api.BinderContainer { *; }
-keep public class rikka.shizuku.ShizukuProvider { *; }
