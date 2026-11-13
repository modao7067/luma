package io.github.modao7067.luma.shared;

import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

public final class FontFactoryCheck {
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
    static void apk(File file,String contents)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
            zip.putNextEntry(new ZipEntry("assets/terminal-fonts-manifest.json"));
            zip.write("same-fonts".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));zip.write(contents.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
    public static void main(String[]args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/font-factory-check.txt"));
        try {
            Path root=Files.createTempDirectory(Path.of(args[1]),"font-factory-");
            ApplicationInfo info=new ApplicationInfo();
            File source=root.resolve("fixture.zip").toFile();info.sourceDir=source.toString();
            info.dataDir=root.resolve("credential").toString();info.deviceProtectedDataDir=root.resolve("device").toString();
            Path ce=Path.of(info.dataDir,"cache"),de=Path.of(info.deviceProtectedDataDir,"cache");Files.createDirectories(ce);Files.createDirectories(de);
            Path old=ce.resolve("com.android.opengl.shaders_cache"),skia=de.resolve("com.android.skia.shaders_cache");
            Files.write(old,new byte[]{1});Files.write(skia,new byte[]{2});
            Path keep=ce.resolve("keep-user-cache");Files.write(keep,new byte[]{3});
            apk(source,"font-version-one");
            LumaAppComponentFactory factory=new LumaAppComponentFactory();ClassLoader loader=FontFactoryCheck.class.getClassLoader();
            check(factory.instantiateClassLoader(loader,info)==loader,"startup factory preserves the application class loader");
            check(!Files.exists(old)&&!Files.exists(skia)&&Files.exists(keep),"first startup clears only known CE/DE graphics caches");
            Files.write(skia,new byte[]{4});factory.instantiateClassLoader(loader,info);
            check(Files.exists(skia),"unchanged APK reuses persistent cache on next startup");
            apk(source,"font-version-two");factory.instantiateClassLoader(loader,info);
            check(!Files.exists(skia)&&Files.exists(keep),"same-font APK upgrade invalidates device-protected Skia cache once");
            info.sourceDir=root.resolve("missing.zip").toString();
            check(factory.instantiateClassLoader(loader,info)==loader,"cache maintenance failure cannot block application startup");
            System.out.println("ALL PASS actual Android startup factory with isolated cache fixtures");
        }catch(Throwable e){e.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
