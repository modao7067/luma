package io.github.modao7067.luma;

import android.content.Context;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;

final class RuntimeAssets {
    static File directory(Context c){return new File(c.getFilesDir(),"runtime");}
    static boolean isRootMode(Context c){
        return new File(c.getFilesDir(),"root-chroot/enabled").isFile()
            || new File(directory(c),".root-mode").isFile();
    }
    static synchronized void ensure(Context c)throws Exception {
        ensureRuntime(c);
        ensureGuest(c);
    }
    static synchronized void ensureRuntime(Context c)throws Exception {
        cancelled();
        copy(c,"runtime",directory(c));
        if(isRootMode(c)){
            write(new File(directory(c),"SKILL.md"),read(c,"root-mode/SKILL.md"));
        }
        Os.chmod(new File(directory(c),"SKILL.md").getAbsolutePath(),0644);
        String nativeDir=c.getApplicationInfo().nativeLibraryDir;
        if(nativeDir!=null && !nativeDir.isEmpty()){
            File nativeFile=new File(directory(c),"native-lib-dir");
            write(nativeFile,(nativeDir+"\n").getBytes(StandardCharsets.UTF_8));
            Os.chmod(nativeFile.getAbsolutePath(),0644);
        }
        File hostPathsFile=new File(directory(c),"host-paths.json");
        JSONObject hostPaths=new JSONObject()
            .put("workspace",LinuxEnvironment.workspace(c).getAbsolutePath())
            .put("runtime",directory(c).getAbsolutePath())
            .put("rootfs",LinuxEnvironment.root(c).getAbsolutePath());
        write(hostPathsFile,(hostPaths+"\n").getBytes(StandardCharsets.UTF_8));
        Os.chmod(hostPathsFile.getAbsolutePath(),0644);
        File bin=new File(directory(c),"bin");
        Files.deleteIfExists(new File(bin,"sd-cli").toPath());
        File androidSh=new File(bin,"android-sh");
        write(androidSh,(
            "#!/bin/bash\n"+
            "if [ \"$(id -u)\" != \"0\" ] || [ ! -d /proc/1/root/system ]; then\n"+
            "  printf 'android-sh 只在 Root 模式下可用\\n' >&2\n"+
            "  exit 1\n"+
            "fi\n"+
            "export PATH=/data/adb/runtime/bin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:/system/bin:/system/xbin:/odm/bin:/vendor/bin\n"+
            "if [ \"$#\" -eq 0 ]; then exec /usr/bin/nsenter -t 1 -m -r -- /system/bin/sh; fi\n"+
            "printf -v command '%q ' \"$@\"\n"+
            "exec /usr/bin/nsenter -t 1 -m -r -- /system/bin/sh -c \"$command\"\n"
        ).getBytes(StandardCharsets.UTF_8));
        Os.chmod(androidSh.getAbsolutePath(),0755);
        String computeBody=
            "#!/usr/bin/env node\n"+
            "const fs=require('node:fs'),cp=require('node:child_process');\n"+
            "if(process.getuid&&process.getuid()===0&&fs.existsSync('/opt/luma/.root-mode')){\n"+
            "  let dir='';\n"+
            "  try{dir=fs.readFileSync('/opt/luma/native-lib-dir','utf8').trim();}catch{}\n"+
            "  if(!dir){process.stderr.write('未找到原生库目录\\n');process.exit(1);}\n"+
            "  const r=cp.spawnSync('/opt/luma/bin/android-sh',[\n"+
            "    '/system/bin/sh','-c','d=\"$1\"; e=\"$2\"; shift 2; LD_LIBRARY_PATH=\"$d\" exec \"$e\" \"$@\"',\n"+
            "    '_',dir,dir+'/libluma_compute.so',...process.argv.slice(2)\n"+
            "  ],{stdio:'inherit'});\n"+
            "  process.exit(r.status!==null?r.status:1);\n"+
            "}\n"+
            "require('/opt/luma/lib/native-client.cjs');\n";
        String androidRunBody=
            "#!/usr/bin/env node\n"+
            "const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');\n"+
            "if(process.getuid&&process.getuid()===0&&fs.existsSync('/opt/luma/.root-mode')){\n"+
            "  const args=process.argv.slice(2);\n"+
            "  if(!args.length||args[0]==='--help'||args[0]==='-h'){\n"+
            "    process.stdout.write('用法: android-run /workspace/program [参数]\\n');\n"+
            "    process.exit(args.length?0:1);\n"+
            "  }\n"+
            "  let p={};try{p=JSON.parse(fs.readFileSync('/opt/luma/host-paths.json','utf8'));}catch{}\n"+
            "  const mapPath=raw=>{\n"+
            "    if(!raw.startsWith('/'))return raw;\n"+
            "    if(raw==='/workspace'||raw.startsWith('/workspace/'))return p.workspace+raw.slice('/workspace'.length);\n"+
            "    if(raw==='/opt/luma'||raw.startsWith('/opt/luma/'))return p.runtime+raw.slice('/opt/luma'.length);\n"+
            "    if(raw==='/sdcard'||raw.startsWith('/sdcard/')||raw.startsWith('/storage/')||raw.startsWith('/system/')||raw.startsWith('/vendor/')||raw.startsWith('/apex/')||raw.startsWith('/data/')||raw.startsWith('/proc/')||raw.startsWith('/sys/')||raw.startsWith('/dev/'))return raw;\n"+
            "    return p.rootfs?p.rootfs+raw:raw;\n"+
            "  };\n"+
            "  const exe=mapPath(args[0].startsWith('/')?args[0]:path.resolve(process.cwd(),args[0]));\n"+
            "  const mapped=args.slice(1).map(mapPath);\n"+
            "  const r=cp.spawnSync('/opt/luma/bin/android-sh',['/system/bin/linker64',exe,...mapped],{stdio:'inherit'});\n"+
            "  process.exit(r.status!==null?r.status:1);\n"+
            "}\n"+
            "require('/opt/luma/lib/native-client.cjs');\n";
        String adbBody=
            "#!/usr/bin/env node\n"+
            "const fs=require('node:fs'),cp=require('node:child_process');\n"+
            "if(process.getuid&&process.getuid()===0&&fs.existsSync('/opt/luma/.root-mode')){\n"+
            "  const args=process.argv.slice(2);\n"+
            "  if(!args.length||args[0]==='--help'||args[0]==='-h'){\n"+
            "    process.stdout.write('Root 模式已转发至 android-sh（宿主 root，无需 Shizuku）：\\n  adb-shell <command> [args...]\\n  adb-shell -c \"<script>\"\\n  adb-shell --status\\n');\n"+
            "    process.exit(args.length?0:1);\n"+
            "  }\n"+
            "  if(args[0]==='--status'){\n"+
            "    process.stdout.write('Root 模式：通过 android-sh 直接在宿主执行（uid=0，无需 Shizuku）\\n');\n"+
            "    const r=cp.spawnSync('/opt/luma/bin/android-sh',['id'],{stdio:'inherit'});\n"+
            "    process.exit(r.status!==null?r.status:1);\n"+
            "  }\n"+
            "  const forwarded=args[0]==='-c'?['/system/bin/sh',...args]:args;\n"+
            "  const r=cp.spawnSync('/opt/luma/bin/android-sh',forwarded,{stdio:'inherit'});\n"+
            "  process.exit(r.status!==null?r.status:1);\n"+
            "}\n"+
            "require('/opt/luma/lib/native-client.cjs');\n";
        for(String command:new String[]{"luma-compute-check","android-run","adb-shell","shizuku","rish"}){
            File launcher=new File(bin,command);
            String body=command.equals("luma-compute-check") ? computeBody : command.equals("android-run") ? androidRunBody : adbBody;
            write(launcher,body.getBytes(StandardCharsets.UTF_8));
            Os.chmod(launcher.getAbsolutePath(),0755);
        }
        Os.chmod(new File(bin,"luma-tools").getAbsolutePath(),0755);
        File lumactl=new File(bin,"lumactl");
        if(lumactl.isFile())Os.chmod(lumactl.getAbsolutePath(),0755);
        if(new File(directory(c),"pnpm/node_modules/@pnpm/exe.linux-arm64/pnpm").isFile())for(String command:new String[]{"pnpm","pnpx"}){
            File launcher=new File(bin,command);
            write(launcher,("#!/bin/sh\nexec /opt/luma/pnpm/node_modules/@pnpm/exe.linux-arm64/pnpm "+(command.equals("pnpx")?"dlx ":"")+"\"$@\"\n").getBytes(StandardCharsets.UTF_8));
            Os.chmod(launcher.getAbsolutePath(),0755);
        }
        write(new File(directory(c),"socket-name"),(LinuxEnvironment.socketName(c)+"\n").getBytes(StandardCharsets.UTF_8));
    }
    static synchronized void ensureGuest(Context c)throws Exception {
        File root=LinuxEnvironment.root(c);
        File mount=new File(root,"opt/luma");if(!mount.isDirectory() && !mount.mkdirs())throw new IOException("无法准备运行目录");
        repairDebianRoot(root);
        write(new File(root,"etc/profile.d/luma.sh"),profileScript(c,root).getBytes(StandardCharsets.UTF_8));
        File bashrc=new File(LinuxEnvironment.root(c),"root/.bashrc");
        if(!Files.isSymbolicLink(bashrc.toPath())){
            String shellConfig=bashrc.isFile()?new String(Files.readAllBytes(bashrc.toPath()),StandardCharsets.UTF_8):"";
            if(!shellConfig.contains("# BEGIN LUMA SHELL TOOLS")){
                File backup=new File(bashrc.getParentFile(),".bashrc.before-luma-shell-tools");
                if(bashrc.isFile()&&!backup.exists())write(backup,shellConfig.getBytes(StandardCharsets.UTF_8));
                write(bashrc,(shellConfig+"\n# BEGIN LUMA SHELL TOOLS\n[[ ! -r /opt/luma/shell/init.sh ]] || source /opt/luma/shell/init.sh\n# END LUMA SHELL TOOLS\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        JSONObject previous=new JSONObject(new String(read(c,"managed-workspace.json"),StandardCharsets.UTF_8));
        for(java.util.Iterator<String> it=previous.keys();it.hasNext();){
            String name=it.next();File target=new File(LinuxEnvironment.workspace(c),name);byte[] data=read(c,"workspace/"+name);
            if(name.equals(".agents/skills/luma-environment/SKILL.md") || !target.exists()
                    || hash(Files.readAllBytes(target.toPath())).equals(previous.getString(name)))write(target,data);
        }
    }
    static String profileScript(Context c,File root){
        String tz=LinuxEnvironment.timezoneId();
        String tzExport=(!tz.isEmpty() && new File(root,"usr/share/zoneinfo/"+tz).isFile()) ? "export TZ="+tz+"\n" : "";
        String profileScript=
            "export PNPM_HOME=/root/.local/share/pnpm\n"+
            "export PATH=/opt/luma/bin:$PNPM_HOME/bin:$PATH\n"+
            "export LUMA_NATIVE_SOCKET="+LinuxEnvironment.socketName(c)+"\n"+
            tzExport+
            "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n"+
            "export NO_PROXY=127.0.0.1,localhost,::1\n"+
            "export no_proxy=127.0.0.1,localhost,::1\n"+
            "if [ -n \"${BASH_VERSION:-}\" ]; then\n"+
            "  case $- in *i*)\n"+
            "    PS0=$'\\e]133;C\\a'\"${PS0-}\"\n"+
            "    __luma_command_finished() {\n"+
            "      local result=$?\n"+
            "      if [ \"${__luma_seen_prompt:-0}\" = 1 ]; then printf '\\033]133;D;%s\\007' \"$result\"; fi\n"+
            "      __luma_seen_prompt=1\n"+
            "      return \"$result\"\n"+
            "    }\n"+
            "    PROMPT_COMMAND=(__luma_command_finished \"${PROMPT_COMMAND[@]}\")\n"+
            "  ;; esac\n"+
            "fi\n";
        return profileScript;
    }
    static synchronized void stageForRoot(Context c)throws Exception {
        ensureRuntime(c);
        File root=LinuxEnvironment.root(c);
        File guest=new File(directory(c),"guest");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] profile=profileScript(c,root).getBytes(StandardCharsets.UTF_8);
        byte[] timezone=(LinuxEnvironment.timezoneId()+"\n").getBytes(StandardCharsets.UTF_8);
        JSONObject previous=new JSONObject(new String(read(c,"managed-workspace.json"),StandardCharsets.UTF_8));
        JSONObject rootManaged=new JSONObject();
        write(new File(guest,"profile.sh"),profile);digest.update(profile);
        write(new File(guest,"timezone"),timezone);digest.update(timezone);
        for(java.util.Iterator<String> it=previous.keys();it.hasNext();){
            String name=it.next();
            byte[] data;
            if(name.equals(".agents/skills/luma-environment/SKILL.md")){
                data=read(c,"root-mode/SKILL.md");
                rootManaged.put(name,new JSONArray().put(previous.getString(name)).put(hash(data)));
            } else if(name.equals("AGENTS.md")){
                data=read(c,"root-mode/AGENTS.md");
                rootManaged.put(name,new JSONArray().put(previous.getString(name)).put(hash(data)));
            } else {
                data=read(c,"workspace/"+name);
                rootManaged.put(name,previous.getString(name));
            }
            write(new File(guest,"workspace/"+name),data);
            digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update(data);
        }
        byte[] managed=(rootManaged.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        write(new File(guest,"managed-workspace.json"),managed);digest.update(managed);
        StringBuilder stamp=new StringBuilder();for(byte b:digest.digest())stamp.append(String.format("%02x",b&255));
        write(new File(guest,"stamp"),(stamp+"\n").getBytes(StandardCharsets.UTF_8));
    }
    static void installBundledPackages(Context c,File root)throws Exception {
        cancelled();
        File pnpm=new File(directory(c),"pnpm");
        File installed=new File(pnpm,".complete");
        String pnpmVersion=new JSONObject(new String(read(c,"pnpm/package.json"),StandardCharsets.UTF_8)).getString("version");
        if(!installed.isFile() || !new String(Files.readAllBytes(installed.toPath()),StandardCharsets.UTF_8).equals(pnpmVersion)){
            copy(c,"pnpm",pnpm);
            for(String name:new String[]{"node_modules/@pnpm/exe.linux-arm64/pnpm","dist/node-gyp-bin/node-gyp","dist/node_modules/node-gyp/bin/node-gyp.js","dist/node_modules/node-gyp/gyp/gyp_main.py","dist/node_modules/node-gyp/gyp/gyp"}){
                File executable=new File(pnpm,name);if(executable.isFile())Os.chmod(executable.getAbsolutePath(),0755);
            }
            write(installed,pnpmVersion.getBytes(StandardCharsets.UTF_8));
        }
        File pnpmConfig=new File(root,"root/.config/pnpm/config.yaml");
        if(!pnpmConfig.exists())write(pnpmConfig,"packageImportMethod: copy\n".getBytes(StandardCharsets.UTF_8));
        File home=new File(root,"root/.local/share/pnpm");if(!home.isDirectory() && !home.mkdirs())throw new IOException("无法创建 pnpm 目录");
    }
    static void prepareDebianSkeleton(File root)throws Exception {
        if(!new File(root,"etc/debian_version").isFile())return;
        for(String path:new String[]{"var/log","var/log/apt","var/lib/systemd","var/cache/debconf","var/cache/apt/archives","var/lib/apt/lists","run"}){
            File dir=new File(root,path);
            if(!dir.isDirectory()){if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("无法创建目录："+dir);Os.chmod(dir.getAbsolutePath(),0755);}
        }
        for(String path:new String[]{"var/cache/apt/archives/partial","var/lib/apt/lists/partial"}){
            File dir=new File(root,path);
            if(!dir.isDirectory()){if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("无法创建目录："+dir);Os.chmod(dir.getAbsolutePath(),0700);}
        }
        File runLock=new File(root,"run/lock");
        if(!runLock.isDirectory()){if(!runLock.mkdirs()&&!runLock.isDirectory())throw new IOException("无法创建目录："+runLock);Os.chmod(runLock.getAbsolutePath(),01777);}
        for(String path:new String[]{"run/utmp","var/log/wtmp","var/log/lastlog"}){
            File file=new File(root,path);
            if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){write(file,new byte[0]);Os.chmod(file.getAbsolutePath(),0664);}
        }
        File btmp=new File(root,"var/log/btmp");
        if(!Files.exists(btmp.toPath(),LinkOption.NOFOLLOW_LINKS)){write(btmp,new byte[0]);Os.chmod(btmp.getAbsolutePath(),0600);}
        Path mtab=new File(root,"etc/mtab").toPath();
        if(!Files.exists(mtab,LinkOption.NOFOLLOW_LINKS))Os.symlink("/proc/self/mounts",mtab.toString());
        File pipConf=new File(root,"etc/pip.conf");
        if(!Files.exists(pipConf.toPath(),LinkOption.NOFOLLOW_LINKS)){
            write(pipConf,"[global]\nbreak-system-packages = true\n".getBytes(StandardCharsets.UTF_8));
            Os.chmod(pipConf.getAbsolutePath(),0644);
        }
    }
    static void repairDebianRoot(File root)throws Exception {
        if(!new File(root,"etc/debian_version").isFile())return;
        prepareDebianSkeleton(root);
        File npmrc=new File(root,"root/.npmrc");
        if(npmrc.isFile()&&!Files.isSymbolicLink(npmrc.toPath())){
            String text=new String(Files.readAllBytes(npmrc.toPath()),StandardCharsets.UTF_8).trim();
            if(text.equals("package-import-method=copy"))Files.deleteIfExists(npmrc.toPath());
        }
        String tz=LinuxEnvironment.timezoneId();
        if(!tz.isEmpty()&&new File(root,"usr/share/zoneinfo/"+tz).isFile()){
            File tzFile=new File(root,"etc/timezone");
            if(!tzFile.isFile()||!new String(Files.readAllBytes(tzFile.toPath()),StandardCharsets.UTF_8).trim().equals(tz)){
                write(tzFile,(tz+"\n").getBytes(StandardCharsets.UTF_8));
            }
            Path localtime=new File(root,"etc/localtime").toPath();
            String target="/usr/share/zoneinfo/"+tz;
            if(!Files.isSymbolicLink(localtime)||!target.equals(Files.readSymbolicLink(localtime).toString())){
                Files.deleteIfExists(localtime);
                Os.symlink(target,localtime.toString());
            }
        }
        File dpkg=new File(root,"var/lib/dpkg");
        if(dpkg.isDirectory()){
            Path statusOld=new File(dpkg,"status-old").toPath();
            Path l2s=new File(dpkg,".l2s.status0001").toPath();
            if(isBrokenOrForeignDpkgLink(statusOld))Files.deleteIfExists(statusOld);
            if(isBrokenOrForeignDpkgLink(l2s))Files.deleteIfExists(l2s);
            if(!Files.exists(l2s,LinkOption.NOFOLLOW_LINKS)){
                Files.deleteIfExists(new File(dpkg,".l2s.status0001.0001").toPath());
            }
            Path status=new File(dpkg,"status").toPath();
            Path displaced=new File(dpkg,".l2s.status0001.0002").toPath();
            if(!Files.exists(status,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(displaced,LinkOption.NOFOLLOW_LINKS)){
                Files.move(displaced,status);
                Os.chmod(status.toString(),0644);
                if(new File(dpkg,"updates/0000").isFile()){
                    Files.deleteIfExists(new File(dpkg,"status-new").toPath());
                }
            }
        }
    }
    private static boolean isBrokenOrForeignDpkgLink(Path link)throws IOException {
        if(!Files.isSymbolicLink(link))return false;
        String target=Files.readSymbolicLink(link).toString();
        return target.startsWith("/data/local/tmp/")||!Files.exists(link);
    }
    private static String hash(byte[] data)throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static byte[] read(Context c,String asset)throws IOException {
        try(InputStream in=c.getAssets().open(asset);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
    private static void copy(Context c,String asset,File target)throws IOException {
        cancelled();
        String[] names=c.getAssets().list(asset);
        if(names!=null && names.length>0){if(!target.isDirectory() && !target.mkdirs())throw new IOException("无法创建运行目录");for(String name:names)copy(c,asset+"/"+name,new File(target,name));}
        else {
            File parent=target.getParentFile();if(!parent.isDirectory() && !parent.mkdirs())throw new IOException("无法创建目录");
            File temp=File.createTempFile("luma-",".tmp",parent);boolean script=false;
            try {
                try(InputStream in=c.getAssets().open(asset);FileOutputStream out=new FileOutputStream(temp)){
                    byte[] buffer=new byte[131072];int n;boolean first=true;
                    while((n=in.read(buffer))!=-1){cancelled();if(first){script=n>1 && buffer[0]=='#' && buffer[1]=='!';first=false;}out.write(buffer,0,n);}out.getFD().sync();
                }
                cancelled();
                if(!temp.renameTo(target)){
                    Files.deleteIfExists(target.toPath());
                    if(!temp.renameTo(target))throw new IOException("无法更新运行文件："+target);
                }
                if(script)try{Os.chmod(target.getPath(),0755);}catch(Exception e){throw new IOException(e);}
            } finally {temp.delete();}
        }
    }
    private static void cancelled()throws IOException{if(Thread.currentThread().isInterrupted())throw new IOException("已停止");}
    static void write(File target,byte[] data)throws IOException {
        File parent=target.getParentFile();if(!parent.isDirectory() && !parent.mkdirs())throw new IOException("无法创建目录");
        File temp=File.createTempFile("luma-",".tmp",parent);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.getFD().sync();}
            if(!temp.renameTo(target)){
                Files.deleteIfExists(target.toPath());
                if(!temp.renameTo(target))throw new IOException("无法更新运行文件："+target);
            }
        } finally {temp.delete();}
    }
}
