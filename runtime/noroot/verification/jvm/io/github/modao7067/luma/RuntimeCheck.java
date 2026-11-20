package io.github.modao7067.luma;
import android.content.Context;
import android.os.Environment;
import com.termux.terminal.TerminalSession;
import java.nio.file.*;
import java.util.*;
public class RuntimeCheck {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        Path assets=Path.of(args[0]),scratch=Path.of(args[1]);
        Context context=new Context(scratch.toFile(),assets.toFile());
        RuntimeAssets.ensure(context);
        Path runtime=RuntimeAssets.directory(context).toPath();
        Path binary=runtime.resolve("pnpm/node_modules/@pnpm/exe.linux-arm64/pnpm");
        check(Files.mismatch(binary,assets.resolve("pnpm/node_modules/@pnpm/exe.linux-arm64/pnpm"))==-1,"pnpm binary mismatch");
        check(Files.isExecutable(binary),"pnpm not executable");
        for(String cmd:new String[]{"android-run","luma-compute-check","adb-shell","shizuku","rish","pnpm","pnpx","luma-tools","lumactl"})check(Files.isExecutable(runtime.resolve("bin/"+cmd)),"command missing: "+cmd);
        Path skill=LinuxEnvironment.workspace(context).toPath().resolve(".agents/skills/luma-environment/SKILL.md");
        check(Files.mismatch(skill,assets.resolve("workspace/.agents/skills/luma-environment/SKILL.md"))==-1,"skill not initialized");
        Files.writeString(skill,"outdated Luma instructions");
        Path projectInstructions=LinuxEnvironment.workspace(context).toPath().resolve("AGENTS.md");
        Files.writeString(projectInstructions,"custom project instructions");
        long stamp=Files.getLastModifiedTime(binary).toMillis();
        RuntimeAssets.ensure(context);
        check(Files.mismatch(skill,assets.resolve("workspace/.agents/skills/luma-environment/SKILL.md"))==-1,"Luma Skill not overwritten on update");
        check(Files.readString(projectInstructions).equals("custom project instructions"),"project instructions overwritten");
        check(stamp==Files.getLastModifiedTime(binary).toMillis(),"pnpm recopied on every startup");
        Environment.storage=scratch.resolve("phone").toFile();Environment.storage.mkdirs();Environment.granted=false;
        TerminalSession session=LinuxEnvironment.session(context,null);
        check(Arrays.asList(session.args).contains(runtime+":/opt/luma"),"runtime not bound");
        check(Arrays.asList(session.args).contains("PNPM_HOME=/root/.local/share/pnpm"),"pnpm home missing");
        check(!Arrays.asList(session.args).contains("NPM_CONFIG_PACKAGE_IMPORT_METHOD=copy"),"npm env should not be polluted by pnpm option");
        Path pnpmYaml=LinuxEnvironment.root(context).toPath().resolve("root/.config/pnpm/config.yaml");
        check(Files.readString(pnpmYaml).contains("packageImportMethod: copy"),"pnpm 12 config.yaml missing");
        check(Arrays.asList(session.args).contains("NO_PROXY=127.0.0.1,localhost,::1"),"NO_PROXY missing");
        check(Arrays.asList(session.args).contains("LUMA_NATIVE_SOCKET=luma.native.1234"),"UID socket missing");
        Map<String,String> customEnv=new LinkedHashMap<>();customEnv.put("PORT","32123");customEnv.put("LUMA_TASK_ID","svc-1:deadbeef");
        String[] svcArgs=LinuxEnvironment.command(context,Arrays.asList("node","server.js"),"/workspace",customEnv);
        check(Arrays.asList(svcArgs).contains("PORT=32123"),"custom guest env PORT missing");
        check(Arrays.asList(svcArgs).contains("LUMA_TASK_ID=svc-1:deadbeef"),"task token env missing");
        Path npmPackage=LinuxEnvironment.root(context).toPath().resolve("usr/local/lib/node_modules/npm/package.json");
        byte[] originalNpm=Files.readAllBytes(npmPackage);
        Files.writeString(npmPackage,"{\"version\":\"11.19.1\"}");
        Path missingPnpm=runtime.resolve("pnpm"),savedPnpm=runtime.resolve("pnpm.saved");
        Files.move(missingPnpm,savedPnpm);
        byte[] savedPnpmYaml=Files.readAllBytes(pnpmYaml);
        Files.deleteIfExists(pnpmYaml);
        EnvironmentInstaller.install(context,message->{});
        RuntimeAssets.ensure(context);
        check(Files.readString(npmPackage).contains("11.19.1"),"existing npm replaced during update");
        check(!Files.exists(missingPnpm),"missing Linux package installed during update");
        check(!Files.exists(pnpmYaml),"Linux package configuration recreated during update");
        Files.move(savedPnpm,missingPnpm);
        Files.write(pnpmYaml,savedPnpmYaml);
        Files.write(npmPackage,originalNpm);
        System.out.println("PASS: fresh-only packages, existing npm preserved, missing pnpm not repaired, Luma Skill overwritten, project instructions preserved, runtime bind and guest env checked");
    }
}
