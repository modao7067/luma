package android.os;
import java.io.File;
public abstract class FileObserver {
    public static final int MODIFY=2,CLOSE_WRITE=8,CREATE=256,MOVED_TO=128,DELETE=512,MOVED_FROM=64;
    public FileObserver(File path,int mask){}
    public abstract void onEvent(int event,String path);
    public void startWatching(){}
    public void stopWatching(){}
}
