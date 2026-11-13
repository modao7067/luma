package android.content;
import java.io.File;
import android.content.res.AssetManager;
import android.content.pm.ApplicationInfo;
public class Context {
    private final File files,cache;
    private final AssetManager assets;
    public Context(File base,File assetDirectory){files=new File(base,"files");cache=new File(base,"cache");files.mkdirs();cache.mkdirs();assets=new AssetManager(assetDirectory);}
    public File getFilesDir(){return files;}
    public File getCacheDir(){return cache;}
    public AssetManager getAssets(){return assets;}
    public ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}
    public <T> T getSystemService(Class<T> type){return type.cast(new android.net.ConnectivityManager());}
}
