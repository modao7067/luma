import android.graphics.Bitmap;
import android.os.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PackagedGhosttyCheck {
    static Class<?> coreClass,nativeClass;static Object core;static int checks;static long handle;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static Object call(String name,Class<?>[]types,Object...args)throws Exception{return coreClass.getMethod(name,types).invoke(core,args);}
    static Object nativeCall(String name,Class<?>[]types,Object...args)throws Exception{return nativeClass.getMethod(name,types).invoke(null,args);}
    static Object proxy(Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        Class<?> ret=m.getReturnType();if(ret==Integer.class||ret==int.class)return 1;if(ret==boolean.class)return false;if(ret==long.class)return 0L;return null;
    });}
    static Object placeholder(Class<?> type)throws Exception{
        if(type==String.class)return "unused";if(type==String[].class)return new String[0];if(type==Integer.class)return 1000;
        if(type.isInterface())return proxy(type);
        for(Field f:type.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&type.isAssignableFrom(f.getType())){f.setAccessible(true);Object singleton=f.get(null);if(singleton!=null)return singleton;}
        for(Constructor<?> c:type.getDeclaredConstructors()){
            Class<?>[]p=c.getParameterTypes();if(p.length==1&&p[0]==int.class){c.setAccessible(true);return c.newInstance(0);}
            if(p.length==0){c.setAccessible(true);return c.newInstance();}
        }
        if(type.getDeclaredConstructors().length==0)return null;
        throw new AssertionError("Cannot construct inactive session argument: "+type);
    }
    static Object inactiveSession(Class<?> type)throws Exception{
        for(Constructor<?> c:type.getDeclaredConstructors()){
            Class<?>[]p=c.getParameterTypes();
            if(p.length>=6&&p[0]==String.class&&p[1]==String.class&&p[2]==String[].class&&p[3]==String[].class){
                Object[]a=new Object[p.length];for(int i=0;i<p.length;i++)a[i]=placeholder(p[i]);c.setAccessible(true);return c.newInstance(a);
            }
        }
        for(Constructor<?> c:type.getDeclaredConstructors()){
            Class<?>[]p=c.getParameterTypes();
            if(p.length==3&&p[1].isInterface()){
                for(Constructor<?> provider:p[0].getDeclaredConstructors()){
                    Class<?>[]owner=provider.getParameterTypes();
                    if(owner.length==2&&android.app.Service.class.isAssignableFrom(owner[0])&&owner[1]==String.class){
                        provider.setAccessible(true);Object inactive=provider.newInstance(null,"unused-probe");
                        c.setAccessible(true);return c.newInstance(inactive,proxy(p[1]),placeholder(p[2]));
                    }
                }
            }
        }
        throw new AssertionError("Missing supported inactive session constructor in packaged APK");
    }
    static void feed(String text)throws Exception{byte[]b=text.getBytes(StandardCharsets.UTF_8);call("append",new Class[]{byte[].class,int.class},b,b.length);}
    static String text()throws Exception{return((String)call("getViewportText",new Class[]{int.class},0)).trim();}
    static long[] hold()throws Exception{return(long[])nativeCall("holdState",new Class[]{long.class},handle);}
    static void fail(Throwable t){t.printStackTrace();System.exit(1);}
    public static void main(String[]args){try{
        Looper.prepareMainLooper();coreClass=Class.forName("io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator");nativeClass=Class.forName("io.github.modao7067.luma.ghostty.GhosttyNative");
        coreClass.getMethod("loadLibrary",String.class).invoke(null,args[1]);
        check(((Integer)nativeCall("ping",new Class[]{}))!=0,"actual APK JNI loads and resolves GhosttyImageDecoder");
        Class<?> contract=coreClass.getSuperclass();
        check(Modifier.isAbstract(contract.getModifiers()),"packaged TerminalEmulator is an abstract host, not a VT parser");
        boolean noShadow=true,noParser=true;for(Field f:contract.getDeclaredFields())if(f.getType().getName().contains("TerminalBuffer"))noShadow=false;for(Method m:contract.getDeclaredMethods())if(m.getName().equals("processCodePoint"))noParser=false;
        check(noShadow&&noParser,"actual native superclass has neither shadow screens nor a Java VT dispatcher");
        Constructor<?> ctor=coreClass.getConstructors()[0];Class<?>[]types=ctor.getParameterTypes();
        Object session=inactiveSession(types[0]);Object client=proxy(types[6]);
        core=ctor.newInstance(session,40,8,12,24,1000,client);
        Field adapterField=coreClass.getDeclaredField("mAdapter");adapterField.setAccessible(true);Object adapter=adapterField.get(core);
        for(Field f:adapter.getClass().getDeclaredFields())if(f.getType()==long.class){f.setAccessible(true);handle=f.getLong(adapter);break;}
        check(handle!=0,"packaged per-window emulator creates native terminal and binds private onRenderHold callback");
        Method pasteStatus=coreClass.getMethod("pasteStatus",String.class,boolean.class);
        check(((Integer)pasteStatus.invoke(core,"safe",false))==1,"actual R8 APK resolves the native safe-paste path");
        check(((Integer)pasteStatus.invoke(core,"a\nb",false))==0,"actual R8 APK classifies an unbracketed newline as needing confirmation");
        check(((Integer)pasteStatus.invoke(core,"a\nb",true))==1,"actual R8 APK writes the confirmed captured text");
        char[]over=new char[256*1024+1];Arrays.fill(over,'a');
        check(((Integer)pasteStatus.invoke(core,new String(over),false))==-2,"actual R8 APK refuses an over-budget paste before output");
        feed("A\033[?2026hB");check(text().equals("A"),"packaged hold freezes the exact committed boundary");
        long deadline=hold()[1];feed("C");check(text().equals("A")&&hold()[1]==deadline,"ordinary output does not cancel or extend hold");
        feed("\033[?2026lD");check(text().equals("ABCD"),"packaged hold release commits complete frame");
        Bitmap bitmap=Bitmap.createBitmap(new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffffff},2,2,Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,png);bitmap.recycle();
        feed("\033_Ga=T,f=100,i=91,p=1,c=2,r=2,C=1;"+Base64.getEncoder().encodeToString(png.toByteArray())+"\033\\");
        long[]placements=(long[])nativeCall("imagePlacements",new Class[]{long.class},handle);
        check(placements!=null&&placements[1]==1,"actual packaged C FindClass/GetStaticMethodID decodes Kitty PNG");
        int[]pixels=(int[])nativeCall("imagePixels",new Class[]{long.class,int.class},handle,91);
        check(pixels!=null&&pixels.length>=6&&pixels[0]==2&&pixels[1]==2&&pixels[2]==0xffff0000&&pixels[5]==0xffffffff,"packaged PNG retains exact dimensions and ARGB pixels");
        feed("\033]1337;File=inline=1:"+Base64.getEncoder().encodeToString(png.toByteArray())+"\007");
        placements=(long[])nativeCall("imagePlacements",new Class[]{long.class},handle);check(placements[1]==2,"actual packaged iTerm2 image decoder bridge resolves after R8");
        final int[]notified={0};call("setModeChangeListener",new Class[]{Runnable.class},(Runnable)()->notified[0]++);
        byte[]lead={(byte)0xf0,(byte)0x9f},tail={(byte)0x98,(byte)0x80};call("append",new Class[]{byte[].class,int.class},lead,2);
        int[]checkpoint=(int[])call("exportModeState",new Class[]{});check(checkpoint[76]!=0&&notified[0]==1,"packaged split UTF8 lead checkpoints automatically");
        call("append",new Class[]{byte[].class,int.class},tail,2);checkpoint=(int[])call("exportModeState",new Class[]{});check(checkpoint[76]==0&&notified[0]==2,"packaged split UTF8 completion clears persisted prefix");
        int[]packedKeyboard=(int[])call("exportKeyboardState",new Class[]{});check(packedKeyboard!=null&&packedKeyboard.length==21&&packedKeyboard[0]==1,"actual signed R8 APK exports the 21-word keyboard annex");
        feed("\033[>1u");packedKeyboard=(int[])call("exportKeyboardState",new Class[]{});check(packedKeyboard[2]==1&&packedKeyboard[4]==1,"packaged kitty push reaches the keyboard annex");
        byte[]packedEncoded=(byte[])call("encodeKey",new Class[]{int.class,int.class,int.class,int.class,byte[].class,int.class,boolean.class},
            20,1,2,0,"a".getBytes(StandardCharsets.UTF_8),'a',false);
        check(new String(packedEncoded,StandardCharsets.UTF_8).equals("\033[97;5u"),"actual signed R8 APK encodes a kitty ctrl key");
        int packedRestore=(Integer)call("restoreKeyboardState",new Class[]{int[].class},packedKeyboard);
        check(packedRestore==0,"actual signed R8 APK restores the keyboard annex atomically");
        feed("\033[?2026hZ");new Handler().postDelayed(()->{try{
            check(hold()[1]==0&&text().endsWith("Z"),"packaged Handler watchdog commits after one second without new input");
            call("dispose",new Class[]{});check(adapterField.get(core)==null,"packaged dispose releases native handle and timers");
            System.out.println("PACKAGED GHOSTTY OK checks="+checks+"; actual APK classes only, no Activity/Service/PTY");System.exit(0);
        }catch(Throwable t){fail(t);}},1150);Looper.loop();
    }catch(Throwable t){fail(t);}}
}
