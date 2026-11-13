package android.system;
import java.nio.file.*;
import java.io.FileDescriptor;
import java.nio.channels.FileChannel;
import java.util.IdentityHashMap;
public class Os {
    private static final IdentityHashMap<FileDescriptor,FileChannel> directories=new IdentityHashMap<>();
    public static void chmod(String path,int mode)throws Exception{Files.setAttribute(Path.of(path),"unix:mode",mode);}
    public static void symlink(String target,String path)throws Exception{Files.createSymbolicLink(Path.of(path),Path.of(target));}
    public static synchronized FileDescriptor open(String path,int flags,int mode)throws Exception{
        FileDescriptor fd=new FileDescriptor();directories.put(fd,FileChannel.open(Path.of(path),StandardOpenOption.READ));return fd;
    }
    public static synchronized void fsync(FileDescriptor fd)throws Exception{directories.get(fd).force(true);}
    public static synchronized void close(FileDescriptor fd)throws Exception{directories.remove(fd).close();}
    public static void kill(int pid,int signal){throw new AssertionError("Tests must not signal real processes");}
}
