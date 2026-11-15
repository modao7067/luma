package com.termux.view;

import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.function.IntConsumer;

public final class TerminalPreviewView extends View {
    private TerminalPreviewScreen screen;
    private final Runnable dismiss;
    private final View viewport;
    private final int[] ownerLocation=new int[2],thisLocation=new int[2];
    private final android.graphics.Rect area=new android.graphics.Rect();
    private final OnLayoutChangeListener viewportLayout=(v,l,t,r,b,ol,ot,or,ob)->invalidate();
    private final GestureDetector gestures;
    private IntConsumer page;
    private float remainder,downX,downY;
    private boolean outside;
    public TerminalPreviewView(android.content.Context context,View viewport,Runnable dismiss){
        super(context);this.dismiss=dismiss;this.viewport=viewport;viewport.addOnLayoutChangeListener(viewportLayout);setContentDescription(com.termux.terminal.LumaLang.tr("文件预览，点击框外退出","File preview, tap outside to dismiss"));
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){
                if(screen!=null&&!screen.isImage()){
                    remainder+=dy;int lines=(int)(remainder/screen.lineHeight());if(lines!=0){screen.scroll(lines);remainder-=lines*screen.lineHeight();invalidate();}
                }return true;
            }
            @Override public boolean onFling(MotionEvent a,MotionEvent b,float x,float y){if(page!=null&&Math.abs(b.getX()-a.getX())>Math.abs(b.getY()-a.getY())&&Math.abs(b.getX()-a.getX())>48)page.accept(b.getX()<a.getX()?1:-1);return true;}
            @Override public void onLongPress(MotionEvent e){if(screen!=null&&!screen.isImage())getContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(com.termux.terminal.LumaLang.tr("预览文本","Preview text"),screen.plainText()));}
        });
    }
    public void pageHandler(IntConsumer handler){page=handler;}
    public void show(TerminalPreviewScreen next){if(screen!=null)screen.close();screen=next;screen.setInvalidator(this::invalidate);remainder=0;updateArea();screen.layout(area.width(),area.height());invalidate();}
    public void clear(){viewport.removeOnLayoutChangeListener(viewportLayout);if(screen!=null){screen.close();screen=null;}}
    private void updateArea(){
        viewport.getLocationOnScreen(ownerLocation);getLocationOnScreen(thisLocation);
        area.set(ownerLocation[0]-thisLocation[0],ownerLocation[1]-thisLocation[1],ownerLocation[0]-thisLocation[0]+viewport.getWidth(),ownerLocation[1]-thisLocation[1]+viewport.getHeight());
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){updateArea();if(screen!=null)screen.layout(area.width(),area.height());}
    @Override protected void onDraw(Canvas canvas){if(screen!=null){updateArea();screen.layout(area.width(),area.height());canvas.save();canvas.clipRect(area);canvas.translate(area.left,area.top);screen.draw(canvas);canvas.restore();}}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();outside=screen!=null&&!screen.bounds().contains(downX-area.left,downY-area.top);}
        if(outside){if(e.getActionMasked()==MotionEvent.ACTION_UP)dismiss.run();return true;}
        gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
    }
    public boolean key(int code){
        if(code==KeyEvent.KEYCODE_ESCAPE||code==KeyEvent.KEYCODE_BACK){dismiss.run();return true;}
        if(page!=null&&(code==KeyEvent.KEYCODE_PAGE_DOWN||code==KeyEvent.KEYCODE_DPAD_RIGHT)){page.accept(1);return true;}
        if(page!=null&&(code==KeyEvent.KEYCODE_PAGE_UP||code==KeyEvent.KEYCODE_DPAD_LEFT)){page.accept(-1);return true;}
        if(screen!=null){int lines=code==KeyEvent.KEYCODE_DPAD_UP?-1:code==KeyEvent.KEYCODE_DPAD_DOWN?1:code==KeyEvent.KEYCODE_PAGE_UP?-10:code==KeyEvent.KEYCODE_PAGE_DOWN?10:0;if(lines!=0){screen.scroll(lines);invalidate();}}
        return true;
    }
}
