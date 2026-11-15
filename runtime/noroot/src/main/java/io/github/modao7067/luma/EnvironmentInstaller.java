package io.github.modao7067.luma;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.tukaani.xz.XZInputStream;

final class EnvironmentInstaller {
    interface Progress {void update(String message);}
    private static void cancelled() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException(com.termux.terminal.LumaLang.tr("环境准备已停止，下次启动可以重试","Environment setup stopped; you can retry next launch"));
    }
    private static String assetText(Context c,String name) throws IOException {
        try(InputStream in=c.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void directory(File f) throws IOException {
        if(!f.isDirectory() && !f.mkdirs())throw new IOException(com.termux.terminal.LumaLang.tr("无法创建目录：","Failed to create directory: ")+f);
    }
    private static File child(File root,String relative) throws IOException {
        if(relative.isEmpty() || relative.startsWith("/"))throw new IOException(com.termux.terminal.LumaLang.tr("无效归档路径","Invalid archive path"));
        File target=new File(root,relative);
        if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException(com.termux.terminal.LumaLang.tr("归档路径越界","Archive path escapes target root"));
        return target;
    }
    private static void syncDirectory(File directory) throws Exception {
        FileDescriptor fd=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        try{Os.fsync(fd);}finally{Os.close(fd);}
    }
    private static void markReady(File root) throws Exception {
        cancelled();
        RuntimeAssets.write(new File(root,".luma-ready"),"Luma Debian environment v1\n".getBytes(StandardCharsets.UTF_8));
        syncDirectory(root);
    }
    private static void recoverExisting(File root,Progress progress) throws Exception {
        if(Files.isSymbolicLink(root.toPath()) || !root.isDirectory())
            throw new IOException(com.termux.terminal.LumaLang.tr("已有环境路径异常，已保留原文件，请手动修复","Existing environment path is invalid; files preserved, repair manually"));
        for(String path:new String[]{"etc/debian_version","usr/bin/bash","usr/bin/env"}){
            File file=new File(root,path);
            if(!file.isFile() || !file.canRead())
                throw new IOException(com.termux.terminal.LumaLang.isZh()?("已有 Debian 环境缺少必要文件："+path+"；已保留整个环境，请手动修复"):("Existing Debian environment is missing required file: "+path+"; environment preserved, repair manually"));
        }
        if(!new File(root,"usr/bin/bash").canExecute() || !new File(root,"usr/bin/env").canExecute()
                || !new File(root,"root").isDirectory())
            throw new IOException(com.termux.terminal.LumaLang.tr("已有 Debian 环境不完整，已保留整个环境，请手动修复","Existing Debian environment is incomplete; environment preserved, repair manually"));
        progress.update(com.termux.terminal.LumaLang.tr("保留已有 Debian 环境，恢复就绪标记…","Preserving existing Debian environment and restoring ready marker…"));
        markReady(root);
    }
    static synchronized void install(Context c,Progress progress) throws Exception {
        cancelled();
        File target=LinuxEnvironment.root(c);
        if(Files.isSymbolicLink(target.toPath()))throw new IOException(com.termux.terminal.LumaLang.tr("已有环境路径异常，已保留原文件，请手动修复","Existing environment path is invalid; files preserved, repair manually"));
        if(LinuxEnvironment.ready(c))return;
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            recoverExisting(target,progress);
            return;
        }
        JSONObject manifest=new JSONObject(assetText(c,"rootfs-manifest.json"));
        String archive=manifest.optString("archive","debian-arm64.zip");
        if(!archive.equals("debian-arm64.zip")&&!archive.equals("debian-arm64.zip.xz"))throw new IOException(com.termux.terminal.LumaLang.tr("不支持的内置环境格式","Unsupported bundled environment format"));
        progress.update(com.termux.terminal.LumaLang.tr("校验内置 Debian 环境…","Verifying bundled Debian environment…"));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[131072];int n;
        try(InputStream in=c.getAssets().open(archive)){
            while((n=in.read(buffer))!=-1){cancelled();digest.update(buffer,0,n);}
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));
        if(!hash.toString().equals(manifest.getString("sha256")))throw new IOException(com.termux.terminal.LumaLang.tr("内置环境校验失败，请重新获取安装包","Bundled environment checksum failed; please obtain a fresh APK"));
        directory(target.getParentFile());
        File stage=Files.createTempDirectory(target.getParentFile().toPath(),"debian.installing-").toFile();
        long total=manifest.getLong("unpackedBytes");
        if(stage.getUsableSpace()<total+128L*1024*1024)throw new IOException(com.termux.terminal.LumaLang.isZh()?("存储空间不足，至少需要 "+((total+128L*1024*1024)/1024/1024)+" MiB 可用空间"):("Insufficient storage; at least "+((total+128L*1024*1024)/1024/1024)+" MiB required"));
        long written=0;int lastPercent=-1;
        try(InputStream packaged=c.getAssets().open(archive);
            InputStream decoded=archive.endsWith(".xz")?new XZInputStream(packaged,32*1024):packaged;
            ZipInputStream zip=new ZipInputStream(decoded)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                cancelled();
                File file=child(stage,entry.getName());
                if(entry.isDirectory()){directory(file);continue;}
                directory(file.getParentFile());
                try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){
                    while((n=zip.read(buffer))!=-1){cancelled();out.write(buffer,0,n);written+=n;}
                }
                if(written>total)throw new IOException(com.termux.terminal.LumaLang.tr("归档大小与清单不符","Archive size does not match manifest"));
                int percent=(int)(written*100/Math.max(total,1));
                if(percent!=lastPercent){lastPercent=percent;progress.update(com.termux.terminal.LumaLang.tr("正在解压 Debian… ","Extracting Debian… ")+percent+"%");}
                zip.closeEntry();
            }
            while(decoded.read(buffer)!=-1)cancelled();
        }
        if(written!=total)throw new IOException(com.termux.terminal.LumaLang.tr("环境文件不完整","Environment archive is incomplete"));
        JSONArray modes=manifest.getJSONArray("modes");
        for(int i=0;i<modes.length();i++){
            cancelled();JSONArray record=modes.getJSONArray(i);
            Os.chmod(child(stage,record.getString(0)).getAbsolutePath(),record.getInt(1)&01777);
        }
        JSONArray links=manifest.getJSONArray("symlinks");
        for(int i=0;i<links.length();i++){
            cancelled();JSONArray record=links.getJSONArray(i);
            String linkName=record.getString(0),linkTarget=record.getString(1);
            if(linkName.contains(".l2s.")||linkTarget.contains(".l2s.")||linkTarget.startsWith("/data/local/tmp/"))continue;
            File file=child(stage,linkName);
            directory(file.getParentFile());Os.symlink(linkTarget,file.getAbsolutePath());
        }
        progress.update(com.termux.terminal.LumaLang.tr("初始化工作区和 Agent 环境说明…","Initializing workspace and Agent environment guide…"));
        copyWorkspace(c,"workspace",LinuxEnvironment.workspace(c));
        RuntimeAssets.installBundledPackages(c,stage);
        markReady(stage);
        cancelled();
        Files.move(stage.toPath(),target.toPath());
        syncDirectory(target.getParentFile());
    }
    private static void copyWorkspace(Context c,String source,File target) throws IOException {
        cancelled();String[] names=c.getAssets().list(source);
        if(names!=null && names.length>0){
            directory(target);for(String name:names)copyWorkspace(c,source+"/"+name,new File(target,name));return;
        }
        if(target.exists())return;
        directory(target.getParentFile());
        File temporary=File.createTempFile("luma-seed-",".tmp",target.getParentFile());
        try {
            try(InputStream in=c.getAssets().open(source);FileOutputStream out=new FileOutputStream(temporary)){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){cancelled();out.write(b,0,n);}out.getFD().sync();
            }
            cancelled();
            if(!target.exists() && !temporary.renameTo(target))throw new IOException(com.termux.terminal.LumaLang.tr("无法初始化工作区文件","Failed to initialize workspace files"));
        } finally {temporary.delete();}
    }
}
