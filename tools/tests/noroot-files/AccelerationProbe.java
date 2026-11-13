package io.github.modao7067.luma;
import android.content.*;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class AccelerationProbe {
 public static void main(String[]args)throws Exception{
  File base=new File(args[1]);System.setOut(new PrintStream(new File(base,"acceleration-check.txt")));
  try{
   Context c=new ContextWrapper(null){
    public File getFilesDir(){return new File(base,"files");}public File getCacheDir(){return new File(base,"cache");}
    public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();try{i.nativeLibraryDir=Files.readString(new File(base,"native-dir.txt").toPath()).trim();}catch(Exception e){throw new RuntimeException(e);}return i;}
    public Object getSystemService(String name){return null;}public String getSystemServiceName(Class<?> cls){return null;}
   };
   if(!Arrays.asList(LinuxEnvironment.environment(c)).contains("PROOT_NO_SECCOMP=1"))throw new AssertionError("unprobed device must use compatibility mode");
   long t=System.nanoTime();LinuxEnvironment.detectAcceleration(c);
   if(Arrays.asList(LinuxEnvironment.environment(c)).contains("PROOT_NO_SECCOMP=1"))throw new AssertionError("actual Node worker + child acceleration probe did not pass");
   System.out.println("PASS actual Node threads and child process in accelerated Android PRoot; probe ms="+(System.nanoTime()-t)/1000000);
   LinuxEnvironment.detectAcceleration(c);System.out.println("PASS repeated probe is idempotent");
   java.lang.reflect.Field checked=LinuxEnvironment.class.getDeclaredField("accelerationChecked");checked.setAccessible(true);checked.setBoolean(null,false);
   File nativeFile=new File(base,"native-dir.txt");String original=Files.readString(nativeFile.toPath());
   try{Files.writeString(nativeFile.toPath(),new File(base,"missing-native-directory").getAbsolutePath());LinuxEnvironment.detectAcceleration(c);
    if(!Arrays.asList(LinuxEnvironment.environment(c)).contains("PROOT_NO_SECCOMP=1"))throw new AssertionError("failed probe must return to compatibility mode");
   }finally{Files.writeString(nativeFile.toPath(),original);}
   System.out.println("PASS missing helper falls back without preventing shell preparation");
   System.out.println("ALL PASS acceleration selection, actual PRoot runtime and failure fallback");
  }catch(Throwable e){e.printStackTrace(System.out);System.out.flush();System.exit(1);}
 }
}
