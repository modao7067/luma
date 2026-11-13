package io.github.modao7067.luma;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class EnvironmentInstallProbe {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);System.out.println("PASS "+m);System.out.flush();}
 public static void main(String[]args)throws Exception{
  File base=new File(args[1]);System.setOut(new PrintStream(new File(base,"install-check.txt")));
  try{
   AssetManager am=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[0]);
   Context c=new ContextWrapper(null){public File getFilesDir(){return new File(base,"fresh-data");}public AssetManager getAssets(){return am;}public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();return i;}};
   File root=LinuxEnvironment.root(c);check(!root.exists(),"fixture starts without an installed environment");
   File custom=new File(LinuxEnvironment.workspace(c),"AGENTS.md");custom.getParentFile().mkdirs();Files.writeString(custom.toPath(),"keep personal instructions\n");
   long start=System.nanoTime();
   EnvironmentInstaller.install(c,s->{if(s.contains("校验")||s.endsWith(" 100%"))System.out.println(s);});
   check(LinuxEnvironment.ready(c),"verified XZ archive extracts completely and commits ready marker");
   System.out.println("Extraction elapsed ms "+(System.nanoTime()-start)/1000000);System.out.flush();
   check(new File(root,"usr/bin/python3").isFile()&&Files.isSymbolicLink(new File(root,"usr/bin/python3").toPath()),"Python entry point restored as an executable symlink");
   for(String path:new String[]{"usr/local/bin/node","usr/bin/git","usr/bin/curl","usr/bin/gawk","usr/bin/batcat","usr/bin/glow","usr/bin/fzf","usr/bin/rg","usr/bin/fdfind","usr/bin/jq","usr/bin/make","usr/bin/ssh"})
    check(new File(root,path).canExecute(),"bundled executable and mode: "+path);
   check(new File(root,"root/.config/bat/themes/Luma-Morandi.tmTheme").isFile(),"default Morandi theme included");
   check(Files.readString(custom.toPath()).equals("keep personal instructions\n"),"fresh install preserves existing workspace instructions");
   check(new File(c.getFilesDir(),"runtime/pnpm/.complete").isFile(),"offline pnpm installation completed");
   RuntimeAssets.ensure(c);
   check(new File(c.getFilesDir(),"runtime/shell/init.sh").isFile()&&!new File(c.getFilesDir(),"runtime/shell/ble.zip").exists(),"native Readline loader deployed without ble archive");
   check(new File(c.getFilesDir(),"runtime/bin/luma-read-file").canExecute(),"preview reader deployed executable");
   check(new File(root,"var/log/apt").isDirectory()&&new File(root,"var/lib/systemd").isDirectory()&&new File(root,"run/lock").isDirectory(),"Debian runtime directories initialized");
   check(Files.isSymbolicLink(new File(root,"etc/mtab").toPath())&&new File(root,"etc/pip.conf").isFile(),"mtab symlink and PEP 668 pip.conf initialized");
   check(new File(root,"var/lib/dpkg/status").isFile()&&!Files.exists(new File(root,"var/lib/dpkg/.l2s.status0001").toPath(),LinkOption.NOFOLLOW_LINKS)&&!new File(root,"var/lib/dpkg/.l2s.status0001.0001").exists(),"dpkg status is clean regular file without build-host l2s artifacts");
   File marker=new File(root,"root/keep-upgrade.txt");Files.writeString(marker.toPath(),"keep installed packages and data");
   EnvironmentInstaller.install(c,s->{throw new AssertionError("ready environment must not be reinstalled");});
   check(Files.readString(marker.toPath()).equals("keep installed packages and data"),"ready environment skips extraction on upgrade");
   check(!new File(root,"var/cache/apt/pkgcache.bin").exists()&&!new File(root,"root/.bash_history").exists(),"APT cache and test history excluded");
   System.out.println("ALL PASS "+checks+" fresh environment install and upgrade checks; production installer with final APK assets, isolated ART fixture");
  }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
 }
}
