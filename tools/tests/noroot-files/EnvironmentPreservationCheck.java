package io.github.modao7067.luma;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.tukaani.xz.*;

public final class EnvironmentPreservationCheck {
    private static int checks;
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
        checks++;System.out.println("PASS "+message);
    }
    private static void write(Path file,String text)throws Exception{
        Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    private static Map<String,String> snapshot(Path root)throws Exception{
        Map<String,String> result=new TreeMap<>();
        try(var entries=Files.walk(root)){
            for(Path path:entries.toList()){
                String name=root.relativize(path).toString();
                if(name.equals(".luma-ready"))continue;
                String data=Files.isSymbolicLink(path)?"link:"+Files.readSymbolicLink(path)
                    :Files.isDirectory(path)?"directory":HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                result.put(name,data+":"+Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS));
            }
        }
        return result;
    }
    private static Path assets(Path directory,boolean compressed)throws Exception{
        Files.createDirectories(directory);
        Path archive=directory.resolve(compressed?"debian-arm64.zip.xz":"debian-arm64.zip");
        Map<String,String> files=new LinkedHashMap<>();
        files.put("etc/debian_version","13\n");files.put("usr/bin/bash","#!/bin/sh\n");files.put("usr/bin/env","#!/bin/sh\n");
        files.put("root/user-data.txt","bundled initial home\n");
        long bytes=0;
        try(OutputStream raw=Files.newOutputStream(archive);
            OutputStream packaged=compressed?new XZOutputStream(raw,new LZMA2Options()):raw;
            ZipOutputStream zip=new ZipOutputStream(packaged)){
            for(var entry:files.entrySet()){
                byte[] data=entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);bytes+=data.length;
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(data);zip.closeEntry();
            }
        }
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        JSONObject manifest=new JSONObject().put("archive",archive.getFileName()).put("sha256",hash).put("unpackedBytes",bytes)
            .put("modes",new JSONArray().put(new JSONArray().put("usr/bin/bash").put(0755)).put(new JSONArray().put("usr/bin/env").put(0755)))
            .put("symlinks",new JSONArray().put(new JSONArray().put("bin").put("usr/bin")));
        write(directory.resolve("rootfs-manifest.json"),manifest.toString());
        write(directory.resolve("pnpm/package.json"),"{\"version\":\"test\"}");
        write(directory.resolve("pnpm/node_modules/@pnpm/exe.linux-arm64/pnpm"),"#!/bin/sh\n");
        write(directory.resolve("workspace/AGENTS.md"),"bundled instructions\n");
        return directory;
    }
    private static void rejected(Context c,EnvironmentInstaller.Progress progress)throws Exception{
        try{EnvironmentInstaller.install(c,progress);throw new AssertionError("installation should have stopped");}
        catch(IOException expected){}
    }
    private static List<Path> stages(Context c)throws Exception{
        try(var files=Files.list(LinuxEnvironment.root(c).toPath().getParent())){
            return files.filter(p->p.getFileName().toString().startsWith("debian.installing-")).sorted().toList();
        }
    }
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]);Files.createDirectories(base);
        for(boolean compressed:new boolean[]{false,true}){
            String type=compressed?"xz":"zip";
            Path asset=assets(base.resolve(type+"-assets"),compressed);
            Context c=new Context(base.resolve(type).toFile(),asset.toFile());
            Path root=LinuxEnvironment.root(c).toPath();
            Path legacy=root.resolveSibling("debian.installing");write(legacy.resolve("keep.txt"),"unfinished environment");
            Path outside=base.resolve(type+"-outside.txt");write(outside,"external data");Files.createSymbolicLink(legacy.resolve("outside"),outside);
            Map<String,String> legacyBefore=snapshot(legacy);
            EnvironmentInstaller.install(c,m->{});
            check(LinuxEnvironment.ready(c),type+" first install commits an already prepared environment");
            check(Files.readString(root.resolve("root/.config/pnpm/config.yaml")).contains("copy")&&Files.isExecutable(root.resolve("bin/bash")),type+" publishes package configuration and executable links");
            write(root.resolve("root/user-data.txt"),"personal home data");write(root.resolve("usr/user-package"),"upgraded package");
            Map<String,String> before=snapshot(root);
            EnvironmentInstaller.install(c,m->{throw new AssertionError("ready environment must skip installer");});
            check(snapshot(root).equals(before),type+" ready environment is unchanged");
            Files.delete(root.resolve(".luma-ready"));
            EnvironmentInstaller.install(c,m->{});
            check(LinuxEnvironment.ready(c)&&snapshot(root).equals(before),type+" lost marker preserves existing home data and upgraded packages");
            Files.delete(root.resolve(".luma-ready"));
            Context noAssets=new Context(base.resolve(type).toFile(),base.resolve("missing-assets").toFile());
            EnvironmentInstaller.install(noAssets,m->{});
            check(LinuxEnvironment.ready(c)&&snapshot(root).equals(before),type+" lost marker is recovered without replacing user data or packages");
            check(Files.isDirectory(legacy)&&snapshot(legacy).equals(legacyBefore)&&Files.readString(outside).equals("external data"),type+" retains legacy staging and external links");
            Files.delete(root.resolve(".luma-ready"));Files.delete(root.resolve("usr/bin/bash"));before=snapshot(root);
            rejected(c,m->{});
            check(snapshot(root).equals(before)&&!LinuxEnvironment.ready(c),type+" damaged existing environment is retained without reinstalling");
        }
        Path asset=assets(base.resolve("failure-assets"),false);
        Context failure=new Context(base.resolve("package-failure").toFile(),asset.toFile());
        Files.delete(asset.resolve("pnpm/package.json"));
        rejected(failure,m->{});
        check(!LinuxEnvironment.root(failure).exists(),"failed package preparation never publishes a partially initialized environment");
        Path failedStage=stages(failure).get(0);Map<String,String> failedBefore=snapshot(failedStage);
        write(asset.resolve("pnpm/package.json"),"{\"version\":\"test\"}");EnvironmentInstaller.install(failure,m->{});
        check(LinuxEnvironment.ready(failure)&&snapshot(failedStage).equals(failedBefore),"retry uses a new stage and retains all previous extracted files");
        String extracting=com.termux.terminal.LumaLang.tr("正在解压","Extracting Debian");
        String initializing=com.termux.terminal.LumaLang.tr("初始化工作区","Initializing workspace");
        Context cancelled=new Context(base.resolve("cancelled").toFile(),asset.toFile());
        try{rejected(cancelled,m->{if(m.startsWith(extracting))Thread.currentThread().interrupt();});}finally{Thread.interrupted();}
        Path cancelledStage=stages(cancelled).get(0);Map<String,String> cancelledBefore=snapshot(cancelledStage);
        EnvironmentInstaller.install(cancelled,m->{});
        check(LinuxEnvironment.ready(cancelled)&&snapshot(cancelledStage).equals(cancelledBefore),"interrupted extraction and retry retain the interrupted environment");
        Context collision=new Context(base.resolve("collision").toFile(),asset.toFile());Path occupied=LinuxEnvironment.root(collision).toPath();
        rejected(collision,m->{if(m.startsWith(initializing))try{write(occupied.resolve("keep.txt"),"concurrent existing environment");}catch(Exception e){throw new RuntimeException(e);}});
        check(Files.readString(occupied.resolve("keep.txt")).equals("concurrent existing environment")&&!LinuxEnvironment.ready(collision),"destination appearing during extraction is never replaced");
        check(stages(collision).stream().anyMatch(p->Files.isRegularFile(p.resolve(".luma-ready"))),"commit conflict retains the fully prepared staged environment");
        Context emptyCollision=new Context(base.resolve("empty-collision").toFile(),asset.toFile());
        rejected(emptyCollision,m->{if(m.startsWith(initializing))LinuxEnvironment.root(emptyCollision).mkdirs();});
        check(LinuxEnvironment.root(emptyCollision).isDirectory()&&!LinuxEnvironment.ready(emptyCollision),"even an existing empty destination is preserved");
        Context linked=new Context(base.resolve("linked").toFile(),asset.toFile());Path linkedRoot=LinuxEnvironment.root(linked).toPath();
        Files.createDirectories(linkedRoot.getParent());Files.createSymbolicLink(linkedRoot,base.resolve("missing-target"));rejected(linked,m->{});
        check(Files.isSymbolicLink(linkedRoot),"dangling environment symlink is preserved and rejected");
        Context file=new Context(base.resolve("file-target").toFile(),asset.toFile());Path fileRoot=LinuxEnvironment.root(file).toPath();write(fileRoot,"existing file");rejected(file,m->{});
        check(Files.readString(fileRoot).equals("existing file"),"non-directory environment path is preserved and rejected");
        Context selfHeal=new Context(base.resolve("self-heal").toFile(),asset.toFile());
        EnvironmentInstaller.install(selfHeal,m->{});
        Path healRoot=LinuxEnvironment.root(selfHeal).toPath();
        Path dpkg=healRoot.resolve("var/lib/dpkg");Files.createDirectories(dpkg.resolve("updates"));
        Files.createSymbolicLink(dpkg.resolve(".l2s.status0001"),Path.of("/data/local/tmp/luma-builder/var/lib/dpkg/.l2s.status0001.0001"));
        Files.createSymbolicLink(dpkg.resolve("status-old"),Path.of("/data/local/tmp/luma-builder/var/lib/dpkg/.l2s.status0001"));
        write(dpkg.resolve(".l2s.status0001.0001"),"stale builder snapshot\n");
        write(dpkg.resolve(".l2s.status0001.0002"),"Package: base-files\nStatus: install ok installed\n");
        write(dpkg.resolve("updates/0000"),"Package: gcc\nStatus: install ok unpacked\n");
        write(dpkg.resolve("status-new"),"uncommitted checkpoint\n");
        write(healRoot.resolve("root/.npmrc"),"package-import-method=copy\n");
        RuntimeAssets.repairDebianRoot(healRoot.toFile());
        check(Files.readString(dpkg.resolve("status")).contains("base-files")
            &&!Files.exists(dpkg.resolve(".l2s.status0001"),LinkOption.NOFOLLOW_LINKS)
            &&!Files.exists(dpkg.resolve("status-old"),LinkOption.NOFOLLOW_LINKS)
            &&!Files.exists(dpkg.resolve(".l2s.status0001.0001"))
            &&!Files.exists(dpkg.resolve(".l2s.status0001.0002"))
            &&!Files.exists(dpkg.resolve("status-new"))
            &&Files.isRegularFile(dpkg.resolve("updates/0000")),"dpkg self-heal restores displaced status, removes builder links, and retains updates log");
        check(Files.readString(healRoot.resolve("root/.npmrc")).equals("package-import-method=copy\n")
            &&Files.isDirectory(healRoot.resolve("var/log/apt"))
            &&Files.isDirectory(healRoot.resolve("var/lib/systemd"))
            &&Files.isDirectory(healRoot.resolve("run/lock"))
            &&Files.isSymbolicLink(healRoot.resolve("etc/mtab"))
            &&Files.readString(healRoot.resolve("etc/pip.conf")).contains("break-system-packages"),"Debian runtime skeleton, mtab, pip.conf, and user npmrc preservation verified");
        check(LinuxEnvironment.isChinaMainlandTimezone("Asia/Shanghai")
            &&LinuxEnvironment.isChinaMainlandTimezone("Asia/Urumqi")
            &&!LinuxEnvironment.isChinaMainlandTimezone("Asia/Hong_Kong")
            &&!LinuxEnvironment.isChinaMainlandTimezone("Asia/Singapore")
            &&!LinuxEnvironment.isChinaMainlandTimezone("America/New_York"),"mainland China timezone predicate matches only CN timezones");
        Path mirrorRoot=base.resolve("mirror-sync");
        write(mirrorRoot.resolve("etc/debian_version"),"13.7\n");
        write(mirrorRoot.resolve("etc/apt/sources.list"),
            "deb https://deb.debian.org/debian trixie main\n"+
            "deb https://deb.debian.org/debian trixie-updates main\n"+
            "deb https://deb.debian.org/debian-security trixie-security main\n");
        write(mirrorRoot.resolve("root/.npmrc"),"user-kept=true\n");
        RuntimeAssets.syncMirrorConfigs(mirrorRoot.toFile(),true);
        check(Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("mirrors.tuna.tsinghua.edu.cn/debian")
            &&Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("mirrors.tuna.tsinghua.edu.cn/debian-security")
            &&Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/")
            &&Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("break-system-packages = true")
            &&Files.readString(mirrorRoot.resolve("etc/uv/uv.toml")).contains("mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/")
            &&Files.readString(mirrorRoot.resolve("usr/local/etc/npmrc")).contains("registry.npmmirror.com")
            &&Files.readString(mirrorRoot.resolve("etc/npmrc")).contains("registry.npmmirror.com")
            &&Files.readString(mirrorRoot.resolve("root/.config/pnpm/config.yaml")).contains("registry.npmmirror.com")
            &&Files.readString(mirrorRoot.resolve("root/.bunfig.toml")).contains("registry.npmmirror.com")
            &&Files.readString(mirrorRoot.resolve("root/.cargo/config.toml")).contains("rsproxy.cn")
            &&Files.readString(mirrorRoot.resolve("etc/gemrc")).contains("mirrors.tuna.tsinghua.edu.cn/rubygems/")
            &&Files.readString(mirrorRoot.resolve("root/.npmrc")).equals("user-kept=true\n"),"China timezone adapts all package mirrors while keeping user .npmrc");
        RuntimeAssets.syncMirrorConfigs(mirrorRoot.toFile(),false);
        check(Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("deb.debian.org/debian")
            &&!Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("mirrors.tuna.tsinghua.edu.cn")
            &&Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("break-system-packages = true")
            &&!Files.exists(mirrorRoot.resolve("etc/uv/uv.toml"))
            &&!Files.exists(mirrorRoot.resolve("usr/local/etc/npmrc"))
            &&!Files.exists(mirrorRoot.resolve("etc/npmrc"))
            &&Files.readString(mirrorRoot.resolve("root/.config/pnpm/config.yaml")).equals("packageImportMethod: copy\n")
            &&!Files.exists(mirrorRoot.resolve("root/.bunfig.toml"))
            &&!Files.exists(mirrorRoot.resolve("root/.cargo/config.toml"))
            &&!Files.exists(mirrorRoot.resolve("etc/gemrc")),"non-China timezone reverts managed mirrors cleanly");
        write(mirrorRoot.resolve("etc/apt/sources.list"),
            "deb https://mirrors.aliyun.com/debian trixie main\n"+
            "deb https://mirrors.aliyun.com/debian trixie-updates main\n"+
            "deb https://mirrors.aliyun.com/debian-security trixie-security main\n");
        write(mirrorRoot.resolve("etc/pip.conf"),"[global]\nbreak-system-packages = true\nindex-url = https://mirrors.aliyun.com/pypi/simple/\n");
        write(mirrorRoot.resolve("etc/uv/uv.toml"),"[[index]]\nurl = \"https://mirrors.aliyun.com/pypi/simple/\"\ndefault = true\n");
        write(mirrorRoot.resolve("etc/gemrc"),":sources:\n- https://mirrors.aliyun.com/rubygems/\n");
        RuntimeAssets.syncMirrorConfigs(mirrorRoot.toFile(),true);
        check(Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("mirrors.tuna.tsinghua.edu.cn/debian")
            &&!Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("mirrors.aliyun.com")
            &&Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/")
            &&Files.readString(mirrorRoot.resolve("etc/uv/uv.toml")).contains("mirrors.tuna.tsinghua.edu.cn/pypi/web/simple/")
            &&Files.readString(mirrorRoot.resolve("etc/gemrc")).contains("mirrors.tuna.tsinghua.edu.cn/rubygems/"),"legacy Aliyun managed mirrors migrate to Tsinghua TUNA");
        write(mirrorRoot.resolve("etc/apt/sources.list"),
            "deb https://mirrors.aliyun.com/debian trixie main\n"+
            "deb https://mirrors.aliyun.com/debian trixie-updates main\n"+
            "deb https://mirrors.aliyun.com/debian-security trixie-security main\n");
        write(mirrorRoot.resolve("etc/pip.conf"),"[global]\nbreak-system-packages = true\nindex-url = https://mirrors.aliyun.com/pypi/simple/\n");
        write(mirrorRoot.resolve("etc/uv/uv.toml"),"[[index]]\nurl = \"https://mirrors.aliyun.com/pypi/simple/\"\ndefault = true\n");
        write(mirrorRoot.resolve("etc/gemrc"),":sources:\n- https://mirrors.aliyun.com/rubygems/\n");
        RuntimeAssets.syncMirrorConfigs(mirrorRoot.toFile(),false);
        check(Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).contains("deb.debian.org/debian")
            &&!Files.readString(mirrorRoot.resolve("etc/pip.conf")).contains("mirrors.aliyun.com")
            &&!Files.exists(mirrorRoot.resolve("etc/uv/uv.toml"))
            &&!Files.exists(mirrorRoot.resolve("etc/gemrc")),"legacy Aliyun managed mirrors revert cleanly for non-China timezones");
        write(mirrorRoot.resolve("etc/apt/sources.list"),"deb https://custom.example/debian trixie main\n");
        write(mirrorRoot.resolve("etc/pip.conf"),"[global]\nindex-url = https://custom.example/simple\n");
        write(mirrorRoot.resolve("root/.config/pnpm/config.yaml"),"packageImportMethod: copy\nregistry: https://custom.example/\n");
        RuntimeAssets.syncMirrorConfigs(mirrorRoot.toFile(),true);
        check(Files.readString(mirrorRoot.resolve("etc/apt/sources.list")).equals("deb https://custom.example/debian trixie main\n")
            &&Files.readString(mirrorRoot.resolve("etc/pip.conf")).equals("[global]\nindex-url = https://custom.example/simple\n")
            &&Files.readString(mirrorRoot.resolve("root/.config/pnpm/config.yaml")).equals("packageImportMethod: copy\nregistry: https://custom.example/\n"),"custom user mirror configs are never overwritten");
        System.out.println("ALL PASS "+checks+" environment preservation checks");
    }
}
