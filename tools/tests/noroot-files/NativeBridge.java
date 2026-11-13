package io.github.modao7067.luma;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
final class NativeBridge {
    static File mapGuestPath(Context c,String path){
        if(path.equals("/workspace"))return LinuxEnvironment.workspace(c);
        return new File(LinuxEnvironment.root(c),path.replaceFirst("^/",""));
    }
    static String mapGuestArgument(Context c,String value){return value;}
    static void sendEvent(OutputStream out,JSONObject event)throws IOException{
        out.write((event.toString()+"\n").getBytes(StandardCharsets.UTF_8));
    }
}
