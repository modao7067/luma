package io.github.modao7067.luma.root;

import android.content.Context;
import java.io.*;

final class TerminalLinkReader {
    static void copy(Context context,String path,File destination,long limit)throws IOException {copy(context,path,0,destination,limit,false);}
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight)throws IOException {copy(context,path,pid,destination,limit,highlight,80);}
    static void copy(Context context,String path,int pid,File destination,long limit,boolean highlight,int columns)throws IOException {
        boolean builtin=RootEntry.usesBuiltinChroot(context);
        String entry=RootEntry.launcher(context);
        String command="exec "+(builtin?RootEntry.quote(entry):entry)+" /usr/bin/python3 -I -c "
                +TerminalFileTransfer.quote(TerminalFileTransfer.SCRIPT)+" "+TerminalFileTransfer.quote(path)+" "+limit+" "+pid+" "+(highlight ? "ansi" : "raw")+" "+columns;
        TerminalFileTransfer.copy(new ProcessBuilder(RootEntry.suExecutable(),"-c",command),destination,limit);
    }
}
