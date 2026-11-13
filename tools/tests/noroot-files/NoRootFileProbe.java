package io.github.modao7067.luma;
import android.content.*;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class NoRootFileProbe {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);System.out.println("PASS "+m);}
 public static void main(String[] args)throws Exception{
  File base=new File(args[1]);System.setOut(new PrintStream(new File(base,"reader-check.txt")));
  try{
   Context c=new ContextWrapper(null){
    public File getFilesDir(){return new File(base,"files");}public File getCacheDir(){return new File(base,"cache");}
    public ApplicationInfo getApplicationInfo(){ApplicationInfo i=new ApplicationInfo();i.uid=android.system.Os.getuid();try{i.nativeLibraryDir=Files.readString(new File(base,"native-dir.txt").toPath()).trim();}catch(Exception e){throw new RuntimeException(e);}return i;}
    public Object getSystemService(String name){return null;}
    public String getSystemServiceName(Class<?> cls){return null;}
   };
   File raw=new File(base,"copy.png");TerminalLinkReader.copy(c,"/workspace/preview-sample.png",raw,64L*1024*1024);
   check(Arrays.equals(Files.readAllBytes(new File(base,"expected.png").toPath()),Files.readAllBytes(raw.toPath())),"actual Android PRoot binary file transport matches every byte");
   File relative=new File(base,"relative.png");TerminalLinkReader.copy(c,"preview-sample.png",relative,64L*1024*1024);
   check(Arrays.equals(Files.readAllBytes(raw.toPath()),Files.readAllBytes(relative.toPath())),"guest-relative path resolves within the NoRoot workspace");
   File md=new File(base,"markdown.ansi");TerminalLinkReader.copy(c,"/workspace/preview-sample.md",0,md,9L*1024*1024,true,38);
   String s=Files.readString(md.toPath()),plain=s.replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]","");
   check(plain.contains("排版预览")&&plain.contains("• ")&&plain.contains("│ ")&&!plain.contains("```"),"NoRoot reader invokes actual bundled Glow and formats Markdown");
   check(s.contains("38;2;"),"soft colors reach the Android transport");
   boolean missing=false;try{TerminalLinkReader.copy(c,"/workspace/not-present.md",raw,1024);}catch(IOException e){missing=e.getMessage().contains("找不到文件");}check(missing,"missing file reports the actual guest error");
   boolean large=false;try{TerminalLinkReader.copy(c,"/workspace/preview-sample.png",raw,1);}catch(IOException e){large=true;}check(large,"binary size limit is enforced before reading");
   boolean dir=false;try{TerminalLinkReader.copy(c,"/workspace",raw,1024);}catch(IOException e){dir=true;}check(dir,"directory is rejected");
   check(!new File(base,"files/linux/debian/usr/bin/python3").exists(),"test guest has no Python entry point; transport still succeeded");
   System.out.println("ALL PASS "+checks+" actual NoRoot Android PRoot reader checks");
  }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
 }
}
