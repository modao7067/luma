package io.github.modao7067.luma;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
public final class RuntimeShellProbe {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);System.out.println("PASS "+m);}
 public static void main(String[]args)throws Exception{
  File base=new File(args[1]);System.setOut(new PrintStream(new File(base,"runtime-check.txt")));
  try{
   File fixture=Files.createTempDirectory(base.toPath(),"runtime-fixture-").toFile();
   AssetManager am=AssetManager.class.getDeclaredConstructor().newInstance();AssetManager.class.getMethod("addAssetPath",String.class).invoke(am,args[0]);
   Context c=new ContextWrapper(null){public File getFilesDir(){return new File(fixture,"files");}public AssetManager getAssets(){return am;}public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();return i;}};
   File root=LinuxEnvironment.root(c);new File(root,"root").mkdirs();File home=new File(root,"root"),bashrc=new File(home,".bashrc");
   String custom="# custom shell configuration\nalias mine='echo retained'\n";Files.writeString(bashrc.toPath(),custom);
   File agent=new File(LinuxEnvironment.workspace(c),"AGENTS.md");agent.getParentFile().mkdirs();Files.writeString(agent.toPath(),"user project instructions\n");
   RuntimeAssets.ensure(c);
   String after=Files.readString(bashrc.toPath());check(after.startsWith(custom)&&after.contains("source /opt/luma/shell/init.sh"),"existing shell content retained and managed loader appended");
   check(Files.readString(new File(home,".bashrc.before-luma-shell-tools").toPath()).equals(custom),"original shell configuration backed up exactly");
   check(Files.readString(agent.toPath()).equals("user project instructions\n"),"custom workspace instructions retained");
   check(!new File(root,"usr/bin/python3").exists(),"upgrade initialization installs no Linux packages or Python");
   check(new File(RuntimeAssets.directory(c),"bin/luma-read-file").isFile()&&new File(RuntimeAssets.directory(c),"shell/init.sh").isFile()&&!new File(RuntimeAssets.directory(c),"shell/ble.zip").exists(),"native Readline loader deployed without ble archive");
   RuntimeAssets.ensure(c);check(Files.readString(bashrc.toPath()).equals(after),"repeated initialization does not duplicate loader or change shell content");
   Files.delete(bashrc.toPath());File outside=new File(fixture,"symlink-target");Files.writeString(outside.toPath(),"custom symlink config\n");Files.createSymbolicLink(bashrc.toPath(),outside.toPath());
   RuntimeAssets.ensure(c);check(Files.isSymbolicLink(bashrc.toPath())&&Files.readString(outside.toPath()).equals("custom symlink config\n"),"symbolic-link shell configuration remains untouched");
   System.out.println("ALL PASS "+checks+" runtime preservation and offline loader checks");
  }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
 }
}
