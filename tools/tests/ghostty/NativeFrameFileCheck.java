package io.github.modao7067.luma.ghostty;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import android.system.Os;

public final class NativeFrameFileCheck {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS "+message);}
    static boolean query(long handle,String path,String medium){
        String packet="\033_Ga=q,t="+medium+",f=32,s=1,v=1,i=300;"+Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8))+"\033\\";
        byte[]b=packet.getBytes(StandardCharsets.US_ASCII);GhosttyNative.feed(handle,b,0,b.length);
        byte[]reply=GhosttyNative.takeReplies(handle);String text=new String(reply,StandardCharsets.US_ASCII);System.out.println("REPLY "+text.replace('\033','!'));return text.contains(";OK");
    }
    public static void main(String[]args)throws Exception{
        GhosttyNative.load(args[0]);File cache=new File(args[2],"file-cache");cache.mkdir();GhosttyNative.configureFrameDirectory(cache);
        Path dir=new File(cache,"kitty-frames").toPath();int[]colors=new int[256];java.util.Arrays.fill(colors,0xff000000);
        long h=GhosttyNative.create(66,51,1000,colors,0xffffffff,0xff000000);check(h!=0,"terminal with scoped file capability");
        byte[]rgba=new byte[]{(byte)255,0,0,(byte)255};Path valid=dir.resolve("tty-graphics-protocol-px-1-1-1.rgba");Files.write(valid,rgba);
        check(query(h,valid.toString(),"t")&&!Files.exists(valid),"temporary file is read and unlinked after consumption");
        Path outside=cache.toPath().resolve("tty-graphics-protocol-px-1-1-2.rgba");Files.write(outside,rgba);
        check(!query(h,outside.toString(),"f")&&Files.exists(outside),"outside-directory path rejected without deleting target");
        Files.createSymbolicLink(valid,outside);check(!query(h,valid.toString(),"f"),"symlink leaf rejected");Files.delete(valid);
        Files.createLink(valid,outside);check(!query(h,valid.toString(),"f"),"hardlinked leaf rejected");Files.delete(valid);
        Files.write(valid,rgba);Os.chown(valid.toString(),12345,12345);check(!query(h,valid.toString(),"f"),"different-UID regular file rejected");Files.delete(valid);
        Path wrong=dir.resolve("secrets.rgba");Files.write(wrong,rgba);check(!query(h,wrong.toString(),"f"),"non-frame filename rejected");
        check(!query(h,dir+"/../"+outside.getFileName(),"f"),"path traversal rejected");
        check(!query(h,"/proc/self/maps","f"),"procfs path rejected before open");
        Files.write(valid,rgba);check(query(h,valid.toString(),"f")&&Files.exists(valid),"explicit file medium leaves a valid frame file owned by the producer");
        GhosttyNative.destroy(h);System.out.println("FRAME FILE OK");
    }
}
