package android.os;
import java.io.File;
public class Environment {
    public static boolean granted;
    public static File storage;
    public static boolean isExternalStorageManager(){return granted;}
    public static File getExternalStorageDirectory(){return storage;}
}
