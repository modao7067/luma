import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import androidx.compose.ui.graphics.Outline;
import androidx.compose.ui.graphics.AndroidPath;
import androidx.compose.ui.platform.ShapeContainingUtilKt;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import java.io.*;
import java.lang.reflect.*;

public final class MediaShapeCheck {
    static Outline outline(float width, float height, float radius) throws Exception {
        Class<?> shape = Class.forName("io.github.modao7067.luma.gui.ui.LumaCardShape");
        Constructor<?> ctor = shape.getConstructor(float.class, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"));
        Object instance = ctor.newInstance(radius, null);
        Method method = shape.getMethod("createOutline-Pq9zytI", long.class, LayoutDirection.class, androidx.compose.ui.unit.Density.class);
        long size = ((long)Float.floatToRawIntBits(width) << 32) | (Float.floatToRawIntBits(height) & 0xffffffffL);
        return (Outline) method.invoke(instance, size, LayoutDirection.Ltr, DensityKt.Density(1f,1f));
    }
    static boolean hit(Outline shape, float x,float y) { return ShapeContainingUtilKt.isInOutline(shape,x,y,null,null); }
    static int touch(Outline shape, float x,float y) {
        int clicks=0;
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(1,2,action,x,y,0);
            if(hit(shape,event.getX(),event.getY()) && event.getAction()==MotionEvent.ACTION_UP) clicks++;
            event.recycle();
        }
        return clicks;
    }
    public static void main(String[] args) throws Exception {
        File out=new File(args[1]);out.mkdirs();System.setOut(new PrintStream(new File(out,"shape-art.txt")));
        try {
            for(int[] bounds:new int[][]{{84,84,18},{180,360,18},{320,76,18},{44,44,22},{393,852,0}}) {
                int w=bounds[0],h=bounds[1],r=bounds[2]; Outline shape=outline(w,h,r);
                if(touch(shape,w/2f,h/2f)!=1 || hit(shape,-2,-2) || hit(shape,w+2,h+2)) throw new AssertionError("center/outside hit test");
                if(r>0 && hit(shape,0,0)) throw new AssertionError("rounded corner hit leaked");
                if(!hit(shape,w/2f,2) || !hit(shape,2,h/2f)) throw new AssertionError("straight edges not hit-testable");
                if(shape instanceof Outline.Generic) {
                    Path path=((AndroidPath)((Outline.Generic)shape).getPath()).getInternalPath();
                    for(float press:new float[]{1f,.97f}) {
                        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap);
                        canvas.drawColor(Color.WHITE);canvas.save();canvas.scale(press,press,w/2f,h/2f);canvas.clipPath(path);
                        canvas.drawColor(0xff71588a);Paint feedback=new Paint();feedback.setColor(0xffd8c9e8);canvas.drawCircle(w/2f,h/2f,w,feedback);canvas.restore();
                        if(bitmap.getPixel(0,0)!=Color.WHITE || bitmap.getPixel(w/2,h/2)!=0xffd8c9e8) throw new AssertionError("fill/press clip escaped");
                        try(FileOutputStream f=new FileOutputStream(new File(out,"shape-"+w+"x"+h+"-"+press+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);} bitmap.recycle();
                    }
                }
                System.out.println("PASS production shape "+w+"x"+h+" r="+r+" "+shape.getClass().getSimpleName()+": real Compose hit tester center/edge/corner/outside and motion events");
            }
            System.out.println("PASS: production generic card outlines preserve Android hit testing and fill/press clipping; circles/capsules and zero-radius pages retain their proper outlines");
        } catch(Throwable failure) { failure.printStackTrace(System.out);System.exit(1); }
    }
}
