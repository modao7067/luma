package io.github.modao7067.luma.shared;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

public final class FontCacheInvalidatorCheck {
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);}
    static String apkKey(Path path, String fonts, String version, String secondaryDex, boolean reverse)throws Exception {
        String[] names={"assets/terminal-fonts-manifest.json","AndroidManifest.xml","classes.dex","classes5.dex"};
        String[] values={fonts,version,"unchanged-primary-dex",secondaryDex};
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){
            for(int i=0;i<names.length;i++){
                int n=reverse?names.length-1-i:i;
                ZipEntry entry=new ZipEntry(names[n]);entry.setTime(reverse?1234567890000L:1200000000000L);
                zip.putNextEntry(entry);zip.write(values[n].getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        try(ZipFile zip=new ZipFile(path.toFile())){return FontCacheInvalidator.fingerprint(zip);}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("luma-font-cache-check-");
        File marker=root.resolve("no-backup/fonts.sha256").toFile();
        File[] caches=new File[4];
        for(int i=0;i<caches.length;i++){
            caches[i]=root.resolve("cache-"+i).toFile();caches[i].mkdirs();
            Files.write(caches[i].toPath().resolve("com.android.skia.shaders_cache"),new byte[]{1});
            Files.write(caches[i].toPath().resolve("com.android.opengl.shaders_cache"),new byte[]{2});
            Files.createDirectories(caches[i].toPath().resolve("proot"));
            Files.write(caches[i].toPath().resolve("proot/active-temp"),new byte[]{3});
            Files.write(caches[i].toPath().resolve("unrelated.cache"),new byte[]{4});
        }
        Path wallpaper=root.resolve("files/wallpaper");Files.createDirectories(wallpaper.getParent());Files.write(wallpaper,new byte[]{9});
        String a=apkKey(root.resolve("a.zip"),"same-fonts","version-1","dex-one",false);
        String b=apkKey(root.resolve("b.zip"),"same-fonts","version-2","dex-one",false);
        check(!a.equals(b),"version-only upgrade invalidates even when fonts and DEX are unchanged");
        check(!a.equals(apkKey(root.resolve("code.zip"),"same-fonts","version-1","dex-two",false)),"secondary DEX changes are included");
        check(!a.equals(apkKey(root.resolve("fonts.zip"),"new-fonts","version-1","dex-one",false)),"font changes remain covered");
        check(a.equals(apkKey(root.resolve("repack.zip"),"same-fonts","version-1","dex-one",true)),"same APK payload with different ZIP order/time reuses caches");
        check(FontCacheInvalidator.refresh(marker,a,caches),"first upgraded startup invalidates old caches");
        for(File cache:caches){
            check(!new File(cache,"com.android.skia.shaders_cache").exists()&&!new File(cache,"com.android.opengl.shaders_cache").exists(),"CE/DE cache and code-cache shader entries removed");
            check(new File(cache,"proot/active-temp").isFile()&&new File(cache,"unrelated.cache").isFile(),"unrelated and PRoot caches preserved");
        }
        check(Files.readAllBytes(wallpaper)[0]==9,"wallpaper and data preserved");
        Path skia=caches[0].toPath().resolve("com.android.skia.shaders_cache");Files.write(skia,new byte[]{5});
        check(!FontCacheInvalidator.refresh(marker,a,caches)&&Files.exists(skia),"same APK on subsequent starts reuses graphics cache");
        check(FontCacheInvalidator.refresh(marker,b,caches)&&!Files.exists(skia),"same-font APK upgrade invalidates cache once");
        Path target=root.resolve("outside-cache");Files.write(target,new byte[]{8});Files.createSymbolicLink(skia,target);
        check(FontCacheInvalidator.refresh(marker,a,caches)&&Files.exists(target)&&!Files.exists(skia,LinkOption.NOFOLLOW_LINKS),"cache symlink removed without following its target");
        Files.createDirectory(skia);
        try{FontCacheInvalidator.refresh(marker,b,caches);throw new AssertionError("directory unexpectedly removed");}catch(IOException expected){}
        check(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.US_ASCII).equals(a),"failed cleanup does not mark the new APK as refreshed");
        Files.delete(skia);check(FontCacheInvalidator.refresh(marker,b,caches),"retry succeeds after cleanup failure");
        check(!FontCacheInvalidator.refresh(marker,b,caches),"successful retry is not repeated");
        check(Files.exists(root.resolve("no-backup/fonts.sha256.lock")),"cross-process sibling lock file is created");
        byte[] oversized=new byte[2048];java.util.Arrays.fill(oversized,(byte)'x');
        Files.write(marker.toPath(),oversized);
        check(FontCacheInvalidator.refresh(marker,b,caches),"oversized marker is rejected and rewritten safely");
        Path symlinkTarget=root.resolve("marker-target");Files.write(symlinkTarget,b.getBytes(StandardCharsets.US_ASCII));
        Files.delete(marker.toPath());Files.createSymbolicLink(marker.toPath(),symlinkTarget);
        check(FontCacheInvalidator.refresh(marker,b,caches)&&Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS),"symlink marker is not followed and is replaced by regular file");
        System.out.println("ALL PASS APK-upgrade graphics cache lifecycle");
    }
}
