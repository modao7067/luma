package android.util;
public class Log {
    public static int w(String tag,String message,Throwable e){e.printStackTrace();return 0;}
    public static int i(String tag,String message){return 0;}
}
