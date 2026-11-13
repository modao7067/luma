import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class PackagedStartupCheck {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[1]);
        System.setOut(new PrintStream(work.resolve("packaged-startup.txt").toString()));
        try {
            String factoryName = Files.readString(work.resolve("startup-factory.txt")).trim();
            Object instance = Class.forName(factoryName).getDeclaredConstructor().newInstance();
            check(instance instanceof AppComponentFactory, "manifest factory resolves from actual APK");
            AppComponentFactory factory = (AppComponentFactory) instance;
            Path fixture = Files.createTempDirectory(work, "app-data-fixture-");
            ApplicationInfo info = new ApplicationInfo();
            String previousApk = Files.readString(work.resolve("previous-apk.txt")).trim();
            info.sourceDir = previousApk.isEmpty() ? args[0] : previousApk;
            if (!previousApk.isEmpty()) {
                try (java.util.zip.ZipFile oldApk = new java.util.zip.ZipFile(previousApk);
                     java.util.zip.ZipFile newApk = new java.util.zip.ZipFile(args[0])) {
                    byte[] oldFonts = oldApk.getInputStream(oldApk.getEntry("assets/terminal-fonts-manifest.json")).readAllBytes();
                    byte[] newFonts = newApk.getInputStream(newApk.getEntry("assets/terminal-fonts-manifest.json")).readAllBytes();
                    check(Files.readString(work.resolve("allow-font-change.txt")).equals("yes")||java.util.Arrays.equals(oldFonts,newFonts), "upgrade font policy matches the requested fixture");
                }
            }
            info.dataDir = fixture.resolve("credential").toString();
            info.deviceProtectedDataDir = fixture.resolve("device").toString();
            ArrayList<Path> shaders = new ArrayList<>(), retained = new ArrayList<>();
            for (String root : new String[] {info.dataDir, info.deviceProtectedDataDir}) {
                for (String name : new String[] {"cache", "code_cache"}) {
                    Path directory = Path.of(root, name); Files.createDirectories(directory);
                    Path shader = directory.resolve("com.android.skia.shaders_cache");
                    Files.write(shader, new byte[] {1}); shaders.add(shader);
                    Path keep = directory.resolve("keep-test-data");
                    Files.write(keep, new byte[] {2}); retained.add(keep);
                }
            }
            Path workspace = Path.of(info.dataDir, "files", "workspace", "keep-test-workspace");
            Files.createDirectories(workspace.getParent()); Files.write(workspace, new byte[] {3});
            ClassLoader loader = PackagedStartupCheck.class.getClassLoader();
            check(factory.instantiateClassLoader(loader, info) == loader, "optimized factory preserves class loader");
            for (Path shader : shaders) check(!Files.exists(shader), "first load invalidates mock graphics cache " + shader.getParent().getFileName());
            for (Path keep : retained) check(Files.exists(keep), "ordinary mock cache retained");
            check(Files.exists(workspace), "mock workspace retained");
            Path marker = Path.of(info.dataDir, "no_backup", "terminal-font-cache.sha256");
            check(Files.isRegularFile(marker) && Files.size(marker) == 64, "actual APK cache fingerprint written");
            byte[] firstMarker = Files.readAllBytes(marker);
            for (Path shader : shaders) Files.write(shader, new byte[] {4});
            check(factory.instantiateClassLoader(loader, info) == loader, "second load preserves class loader");
            for (Path shader : shaders) check(Files.exists(shader), "unchanged APK reuses mock graphics cache");
            check(java.util.Arrays.equals(firstMarker, Files.readAllBytes(marker)), "APK content fingerprint stable");
            check(Files.exists(workspace), "mock workspace retained on repeated load");
            if (!previousApk.isEmpty()) {
                info.sourceDir = args[0];
                check(factory.instantiateClassLoader(loader, info) == loader, "APK upgrade preserves class loader");
                for (Path shader : shaders) check(!Files.exists(shader), "APK upgrade invalidates mock graphics cache");
                check(!java.util.Arrays.equals(firstMarker, Files.readAllBytes(marker)), "APK upgrade changes cache generation");
                for (Path shader : shaders) Files.write(shader, new byte[] {5});
                factory.instantiateClassLoader(loader, info);
                for (Path shader : shaders) check(Files.exists(shader), "new APK next cold start reuses graphics cache");
                check(Files.exists(workspace), "APK upgrade retains mock workspace");
            }

            System.out.println("ALL PASS packaged startup: " + checks + " assertions; actual APK classes, isolated data; no install/Activity/service");
            System.out.flush(); System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace(System.out); System.out.flush(); System.exit(1);
        }
    }
}
