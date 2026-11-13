package io.github.modao7067.luma;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Looper;
import android.view.View;
import java.io.*;
import java.lang.reflect.Method;

public final class TerminalHeaderCheck {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static float center(View view){return view.getTop()+view.getHeight()/2f;}
    static Rect ink(Bitmap image,int left,int right){
        Rect bounds=new Rect(right,image.getHeight(),left,0);
        for(int y=0;y<image.getHeight();y++)for(int x=left;x<right;x++){
            int color=image.getPixel(x,y);
            if(Color.red(color)>110&&Color.green(color)>110&&Color.blue(color)>110){
                bounds.left=Math.min(bounds.left,x);bounds.right=Math.max(bounds.right,x+1);
                bounds.top=Math.min(bounds.top,y);bounds.bottom=Math.max(bounds.bottom,y+1);
            }
        }
        return bounds;
    }
    static void render(Context system,File output,float density,float fontScale,boolean locked)throws Exception {
        Configuration config=new Configuration(system.getResources().getConfiguration());
        config.densityDpi=Math.round(density*160);config.fontScale=fontScale;
        Context context=system.createConfigurationContext(config);
        View dot=new View(context);dot.setVisibility(View.GONE);
        TerminalHeader header=new TerminalHeader(context,dot);
        header.title.setText("Codex · 1234abcd");
        header.title.setOnClickListener(v -> {});header.title.setOnLongClickListener(v -> true);
        header.setSessionLocked(locked);
        int width=Math.round(412*density),height=Math.round(48*density);
        for(int pass=0;pass<2;pass++){
            header.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            header.layout(0,0,width,height);
        }
        check(Math.abs(center(header.back)-height/2f)<=.5f,"back target centered at density="+density+" font="+fontScale);
        View group=(View)header.title.getParent();
        check(Math.abs(group.getTop()+center(header.title)-height/2f)<=.5f,"window-name container shares arrow vertical center");
        check(header.windows.isEnabled()!=locked&&(header.windows.getVisibility()==View.GONE)==locked,"window button visibility/enabled follows terminal domain");
        check(header.title.isClickable()!=locked&&header.title.isLongClickable()!=locked,"dedicated title has no window/rename affordance");
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        header.draw(new Canvas(bitmap));
        Rect arrow=ink(bitmap,header.back.getLeft(),header.back.getRight());
        int titleLeft=group.getLeft()+header.title.getLeft();
        Rect text=ink(bitmap,titleLeft,titleLeft+header.title.getWidth());
        check(!arrow.isEmpty()&&!text.isEmpty(),"actual vector arrow and title produce visible ink");
        check(Math.abs(arrow.exactCenterY()-height/2f)<=density,"vector arrow ink is centered, not font-baseline shifted");
        check(Math.abs(arrow.exactCenterY()-text.exactCenterY())<=2*density,"arrow and title ink align within 2dp: "+arrow+" / "+text);
        String name="header-"+density+"-font-"+fontScale+(locked?"-session":"-ordinary")+".png";
        try(FileOutputStream stream=new FileOutputStream(new File(output,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}
        bitmap.recycle();
    }
    public static void main(String[] args)throws Exception {
        File output=new File(args[1]);System.setOut(new PrintStream(new File(output,"terminal-header-result.txt")));
        try{
            Looper.prepareMainLooper();
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            Class<?> sharedMemory=Class.forName("com.android.internal.os.ApplicationSharedMemory");
            Object memory=sharedMemory.getMethod("create").invoke(null);
            sharedMemory.getMethod("setInstance",sharedMemory).invoke(null,memory);
            Class<?> activityThread=Class.forName("android.app.ActivityThread");
            Method main=activityThread.getDeclaredMethod("systemMain");main.setAccessible(true);
            Object thread=main.invoke(null);
            Method getContext=activityThread.getDeclaredMethod("getSystemContext");getContext.setAccessible(true);
            Context system=(Context)getContext.invoke(thread);
            for(float density:new float[]{1f,2.75f})for(float font:new float[]{1f,1.3f})for(boolean locked:new boolean[]{false,true})render(system,output,density,font,locked);
            System.out.println("ALL PASS checks="+checks+"; production toolbar views/geometry/pixels, no App Activity or system gesture");
            System.exit(0);
        }catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}
    }
}
