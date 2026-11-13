package com.termux.terminal;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class JniCwdCheck {
    public static boolean run(File directory)throws Exception {
        int[] child={0};
        int master=JNI.createSubprocess("/system/bin/sh",directory.getAbsolutePath(),new String[]{"/system/bin/sh","-c","pwd"},new String[]{"PATH=/system/bin"},child,24,80,0,0);
        if(master<0||child[0]<=0)throw new AssertionError("isolated shell fixture failed");
        String cwd;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(master)),StandardCharsets.UTF_8))){cwd=reader.readLine();}
        return JNI.waitFor(child[0])==0&&directory.getAbsolutePath().equals(cwd);
    }
}
