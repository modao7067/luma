package io.github.modao7067.luma;

import android.content.Context;
import java.io.*;
import java.util.Arrays;

final class TerminalLinkReader {
    static void copy(Context context,String path,File destination,long limit)throws IOException {copy(context,path,0,destination,limit,false);}
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight)throws IOException {copy(context,path,pid,destination,limit,highlight,80);}
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight,int columns)throws IOException {
        ProcessBuilder builder=new ProcessBuilder(LinuxEnvironment.command(context,
                Arrays.asList("/usr/local/bin/node","/opt/luma/bin/luma-read-file",path,Long.toString(limit),highlight ? "ansi" : "raw",Integer.toString(columns)),"/workspace"));
        builder.environment().clear();
        for(String entry:LinuxEnvironment.environment(context)){
            int equals=entry.indexOf('=');builder.environment().put(entry.substring(0,equals),entry.substring(equals+1));
        }
        TerminalFileTransfer.copy(builder,destination,limit);
    }
}
