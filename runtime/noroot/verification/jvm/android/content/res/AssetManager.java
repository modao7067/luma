package android.content.res;
import java.io.*;
public class AssetManager {
    private final File root;
    public AssetManager(File root){this.root=root;}
    public InputStream open(String name)throws IOException{return new FileInputStream(new File(root,name));}
    public String[] list(String name){String[] result=new File(root,name).list();return result==null?new String[0]:result;}
}
