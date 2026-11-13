package io.github.modao7067.luma;
import android.content.Context;
import android.os.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import com.termux.terminal.TerminalSession;

public class InstallerCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String sha(Path path)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        return HexFormat.of().formatHex(md.digest());
    }
    static void rejected(Context context,String label)throws Exception {
        try{EnvironmentInstaller.install(context,message->{});throw new AssertionError(label+" accepted");}
        catch(IOException expected){check(!LinuxEnvironment.ready(context),label+" created ready marker");}
    }
    public static void main(String[] args)throws Exception{
        Path assets=Path.of(args[0]),scratch=Path.of(args[1]);Files.createDirectories(scratch);
        Context context=new Context(scratch.resolve("full").toFile(),assets.toFile());
        Path outside=scratch.resolve("keep.txt");Files.writeString(outside,"keep");
        Path interrupted=LinuxEnvironment.root(context).toPath().resolveSibling("debian.installing");
        Files.createDirectories(interrupted);Files.createSymbolicLink(interrupted.resolve("outside"),outside);
        EnvironmentInstaller.install(context,message->{});
        check(LinuxEnvironment.ready(context),"installation did not complete");
        check(Files.readString(outside).equals("keep"),"cleanup followed an external symlink");
        JSONObject manifest=new JSONObject(Files.readString(assets.resolve("rootfs-manifest.json")));
        Path root=LinuxEnvironment.root(context).toPath();int files=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(assets.resolve("debian-arm64.zip")))){
            ZipEntry entry;byte[] buffer=new byte[65536];
            while((entry=zip.getNextEntry())!=null){
                if(entry.isDirectory())continue;
                MessageDigest expected=MessageDigest.getInstance("SHA-256");int n;while((n=zip.read(buffer))!=-1)expected.update(buffer,0,n);
                check(sha(root.resolve(entry.getName())).equals(HexFormat.of().formatHex(expected.digest())),"file mismatch: "+entry.getName());files++;
            }
        }
        check(files==manifest.getInt("regularFiles"),"file count mismatch");
        JSONArray modes=manifest.getJSONArray("modes"),links=manifest.getJSONArray("symlinks");
        for(int i=0;i<modes.length();i++){JSONArray record=modes.getJSONArray(i);int mode=(Integer)Files.getAttribute(root.resolve(record.getString(0)),"unix:mode");check((mode&01777)==(record.getInt(1)&01777),"mode mismatch: "+record.getString(0));}
        for(int i=0;i<links.length();i++){JSONArray record=links.getJSONArray(i);check(Files.readSymbolicLink(root.resolve(record.getString(0))).toString().equals(record.getString(1)),"symlink mismatch");}
        Path skill=LinuxEnvironment.workspace(context).toPath().resolve(".agents/skills/luma-environment/SKILL.md");
        check(Files.isRegularFile(skill),"workspace skill missing");
        Path project=LinuxEnvironment.workspace(context).toPath().resolve("user-project.txt");Files.writeString(project,"user data");
        EnvironmentInstaller.install(context,message->{});check(Files.readString(project).equals("user data"),"repeat installation touched user data");
        Context cancelled=new Context(scratch.resolve("cancelled").toFile(),assets.toFile());
        Thread.currentThread().interrupt();rejected(cancelled,"cancelled installation");Thread.interrupted();
        Path malicious=scratch.resolve("malicious-assets");Files.createDirectories(malicious);
        Path archive=malicious.resolve("debian-arm64.zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("../../escape.txt"));zip.write("escape".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        JSONObject bad=new JSONObject().put("sha256",sha(archive)).put("unpackedBytes",6).put("modes",new JSONArray()).put("symlinks",new JSONArray());
        Files.writeString(malicious.resolve("rootfs-manifest.json"),bad.toString());
        rejected(new Context(scratch.resolve("zip-slip").toFile(),malicious.toFile()),"ZIP path traversal");
        check(!Files.exists(scratch.resolve("zip-slip/files/escape.txt")),"ZIP escaped staging");
        bad.put("sha256","0".repeat(64));Files.writeString(malicious.resolve("rootfs-manifest.json"),bad.toString());
        rejected(new Context(scratch.resolve("checksum").toFile(),malicious.toFile()),"checksum mismatch");
        Environment.storage=scratch.resolve("phone-storage").toFile();Environment.storage.mkdirs();
        Environment.granted=false;TerminalSession privateOnly=LinuxEnvironment.session(context,null);
        String binding=Environment.storage.getAbsolutePath()+":/sdcard";
        check(!Arrays.asList(privateOnly.args).contains(binding),"shared storage bound without authorization");
        Environment.granted=true;TerminalSession shared=LinuxEnvironment.session(context,null);
        check(Arrays.asList(shared.args).contains(binding),"authorized shared storage not bound");
        check(Arrays.asList(shared.args).contains(LinuxEnvironment.workspace(context).getAbsolutePath()+":/workspace"),"private workspace not bound");
        check(shared.executable.startsWith("/readonly-apk/"),"helper executes from writable directory");
        check(Arrays.stream(shared.environment).anyMatch(x->x.startsWith("PROOT_LOADER=/readonly-apk/")),"loader is not from APK");
        System.out.println("PASS: "+files+" files, "+links.length()+" symlinks and all modes restored; Skill copied; retry, cancellation, checksum, ZIP traversal, private workspace and conditional storage binding checked.");
    }
}
