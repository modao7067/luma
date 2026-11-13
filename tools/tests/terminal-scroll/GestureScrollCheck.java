package com.termux.view;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LongSparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class GestureScrollCheck implements GestureAndScaleRecognizer.Listener {
    static int checks;
    static long nextTime;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static class TerminalBuffer {
        int history = 10000;
        int getActiveTranscriptRows() { return history; }
    }
    static class TerminalEmulator {
        static final int MOUSE_LEFT_BUTTON = 0, MOUSE_LEFT_BUTTON_MOVED = 32;
        static final int MOUSE_WHEELUP_BUTTON = 64, MOUSE_WHEELDOWN_BUTTON = 65;
        boolean mouse, alt;
        TerminalBuffer screen = new TerminalBuffer();
        TerminalBuffer getScreen() { return screen; }
        boolean isMouseTrackingActive() { return mouse; }
        boolean isAlternateBufferActive() { return alt; }
        void setCursorBlinkState(boolean shown) {}
        boolean isCursorKeysApplicationMode() { return true; }
        boolean isKeypadApplicationMode() { return false; }
    }
    static class Renderer { int mFontLineSpacing; }
    static class KeyHandler {
        static String getCode(int code, int mods, boolean cursor, boolean keypad) {
            check(cursor && !keypad, "scroll arrows honor application cursor mode");
            return Integer.toString(code);
        }
    }
    class Session {
        TerminalEmulator getEmulator() { return mEmulator; }
        void write(String code) { keys++; lastKey = Integer.parseInt(code); }
    }
    final Context context;
    final GestureAndScaleRecognizer mGestureRecognizer;
    final TerminalEmulator mEmulator = new TerminalEmulator();
    final Renderer mRenderer = new Renderer();
    final Session mTermSession = new Session();
    final ArrayList<Runnable> queued = new ArrayList<>();
    int mTopRow, wheels, keys, mouseMoves, lastKey, invalidations,cliClicks,openedLinks;
    private String mLinkOnDown,visibleLink;
    private Session mLinkSession;
    private long mCliTapDownTime=Long.MIN_VALUE;
    final Client mClient=new Client();
    class Client {
        boolean onLinkTap(String link){openedLinks++;return true;}
        void onSingleTapUp(MotionEvent event){}
    }
    void requestFocus(){}
    void stopTextSelectionMode(){selecting=false;}
    boolean scrolledWithFinger, selecting;
    float mScrollRemainder;

    GestureScrollCheck(Context context, int mode) {
        this.context = context;
        mGestureRecognizer = new GestureAndScaleRecognizer(context, this);
        mRenderer.mFontLineSpacing = Math.max(1, Math.round(20f * density()));
        mEmulator.mouse = mode == 1;
        mEmulator.alt = mode == 2;
        if (mEmulator.alt) mEmulator.screen.history = 0;
    }
    Resources getResources() { return context.getResources(); }
    float density() { return getResources().getDisplayMetrics().density; }
    boolean isSelectingText() { return selecting; }
    boolean handleKeyCodeAction(int code, int mods) { return false; }
    boolean awakenScrollBars() { return false; }
    void invalidate() { invalidations++; }
    void postOnAnimation(Runnable task) { queued.add(task); }
    void post(Runnable task) { queued.add(task); }
    void sendMouseEventCode(MotionEvent event, int code, boolean pressed) {
        if(code==TerminalEmulator.MOUSE_LEFT_BUTTON&&pressed)cliClicks++;
        if (code == TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED) mouseMoves++;
        if (code == TerminalEmulator.MOUSE_WHEELUP_BUTTON || code == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON) wheels++;
    }
    public boolean onDoubleTap(MotionEvent event) { return false; }
    public boolean onScale(float x, float y, float scale) { return true; }
    public void onLongPress(MotionEvent event) {}
    private String getLinkAt(MotionEvent event){return visibleLink;}
    private String getLinkAt(float x,float y){return visibleLink;}
    private static final int PRODUCTION_METHODS_ANCHOR = 0;

    void event(long down, long time, int action, float y, int source) {
        MotionEvent event = MotionEvent.obtain(down, time, action, 100f * density(), y, 0);
        event.setSource(source);
        mGestureRecognizer.onTouchEvent(event);
        event.recycle();
    }
    int units() { return mEmulator.mouse ? wheels : mEmulator.alt ? keys : -mTopRow; }
    static long time() { long result = nextTime; nextTime += 10000; return result; }

    static int drag(Context context, int mode, boolean fast, boolean selecting, int source) {
        GestureScrollCheck replay = new GestureScrollCheck(context, mode);
        replay.selecting = selecting;
        long down = time();
        float start = 100f * replay.density();
        int interval = fast ? 2 : 50;
        replay.event(down, down, MotionEvent.ACTION_DOWN, start, source);
        for (int step = 1; step <= 40; step++) {
            replay.event(down, down + step * interval, MotionEvent.ACTION_MOVE,
                    start + step * 5f * replay.density(), source);
            check(replay.queued.isEmpty(), "move scrolls immediately without queued animation");
        }
        int beforeUp = replay.units();
        replay.event(down, down + 41L * interval, MotionEvent.ACTION_UP,
                start + 200f * replay.density(), source);
        check(replay.units() == beforeUp, "lifting adds no scroll distance");
        check(replay.queued.isEmpty(), "lifting schedules no further scroll");
        check(replay.mGestureRecognizer.getScrollVelocityY() == 0f, "lifting recycles velocity tracking");
        if (source == InputDevice.SOURCE_MOUSE && mode == 1) {
            check(replay.mouseMoves > 0 && replay.wheels == 0, "physical mouse drag remains pointer protocol");
        } else {
            check(beforeUp > 0, "drag reaches production history/wheel/arrow sink before release");
        }
        System.out.println("mode=" + mode + " fast=" + fast + " selection=" + selecting
                + " source=" + source + " unitsBeforeUp=" + beforeUp);
        return beforeUp;
    }

    static void pointerEvent(GestureScrollCheck replay, long down, long at, int action, int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = (100 + i * 50) * replay.density();
            coordinates[i].y = 300 * replay.density();
            coordinates[i].pressure = 1f; coordinates[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(down, at, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        replay.mGestureRecognizer.onTouchEvent(event); event.recycle();
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    static Context headlessContext() throws Exception {
        Context context = new ContextWrapper(null) {
            @Override public int getDeviceId() { return 0; }
            @Override public boolean isUiContext() { return false; }
            @Override public Resources getResources() { return Resources.getSystem(); }
            @Override public String getPackageName() { return "io.github.modao7067.luma.scroll.checks"; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(); info.targetSdkVersion = 35; return info;
            }
        };
        Method keyMethod = ViewConfiguration.class.getDeclaredMethod("createKey", Context.class);
        keyMethod.setAccessible(true);
        Field configurations = ViewConfiguration.class.getDeclaredField("sConfigurations");
        configurations.setAccessible(true);
        ViewConfiguration defaults = new ViewConfiguration();
        Field withContext = ViewConfiguration.class.getDeclaredField("mConstructedWithContext");
        withContext.setAccessible(true); withContext.setBoolean(defaults, true);
        ((LongSparseArray<ViewConfiguration>) configurations.get(null)).put(
                ((Number) keyMethod.invoke(null, context)).longValue(), defaults);
        return context;
    }
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(args[1] + "/gesture-scroll.txt"));
        try {
            Looper.prepareMainLooper();
            nextTime = SystemClock.uptimeMillis() + 1000;
            Context context = headlessContext();
            for (int mode = 0; mode <= 2; mode++) {
                int slow = drag(context, mode, false, false, InputDevice.SOURCE_TOUCHSCREEN);
                int fast = drag(context, mode, true, false, InputDevice.SOURCE_TOUCHSCREEN);
                check(fast > slow * 2, "same finger distance: fast drag exceeds slow before UP in mode " + mode);
            }
            int slowSelected = drag(context, 0, false, true, InputDevice.SOURCE_TOUCHSCREEN);
            int fastSelected = drag(context, 0, true, true, InputDevice.SOURCE_TOUCHSCREEN);
            check(slowSelected == fastSelected, "text selection never gains speed");
            drag(context, 1, true, false, InputDevice.SOURCE_MOUSE);

            GestureScrollCheck taps=new GestureScrollCheck(context,1);
            long tapTime=time();
            MotionEvent tap=MotionEvent.obtain(tapTime,tapTime+40,MotionEvent.ACTION_UP,100,100,0);tap.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            taps.onDown(100,100);taps.onUp(tap);
            check(taps.cliClicks==1,"dashboard gets exactly one CLI mouse click");
            taps.visibleLink="/workspace/under-dashboard.png";
            taps.onSingleTapUp(tap);
            check(taps.openedLinks==0,"confirmed tap cannot open background exposed by closing dashboard");tap.recycle();
            tapTime=time();tap=MotionEvent.obtain(tapTime,tapTime+40,MotionEvent.ACTION_UP,100,100,0);tap.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            taps.onDown(100,100);taps.onUp(tap);taps.onSingleTapUp(tap);
            check(taps.openedLinks==1&&taps.cliClicks==1,"real stable link opens once without also clicking the TUI");tap.recycle();
            tapTime=time();tap=MotionEvent.obtain(tapTime,tapTime+40,MotionEvent.ACTION_UP,100,100,0);tap.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            taps.onDown(100,100);taps.visibleLink="/workspace/different.png";taps.onUp(tap);taps.onSingleTapUp(tap);
            check(taps.openedLinks==1&&taps.cliClicks==2,"redraw changing the destination during a gesture does not launch it");tap.recycle();
            GestureScrollCheck replay = new GestureScrollCheck(context, 0);
            long down = time();
            replay.event(down, down, MotionEvent.ACTION_DOWN, 100f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 5, MotionEvent.ACTION_MOVE, 150f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 10, MotionEvent.ACTION_MOVE, 200f, InputDevice.SOURCE_TOUCHSCREEN);
            check(replay.mGestureRecognizer.getScrollVelocityY() > 0f, "native tracker sees actual MOVE speed");
            int before = replay.units();
            replay.event(down, down + 15, MotionEvent.ACTION_CANCEL, 200f, InputDevice.SOURCE_TOUCHSCREEN);
            check(replay.units() == before && replay.queued.isEmpty(), "cancel produces no extra movement");
            check(replay.mGestureRecognizer.getScrollVelocityY() == 0f, "cancel recycles tracker");

            down = time();
            replay.event(down, down, MotionEvent.ACTION_DOWN, 100f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 5, MotionEvent.ACTION_MOVE, 150f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 10, MotionEvent.ACTION_MOVE, 200f, InputDevice.SOURCE_TOUCHSCREEN);
            pointerEvent(replay, down, down + 12, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
            check(replay.mGestureRecognizer.getScrollVelocityY() == 0f, "second finger clears one-finger speed");
            pointerEvent(replay, down, down + 15, MotionEvent.ACTION_CANCEL, 2);
            check(replay.queued.isEmpty(), "pinch never schedules scroll continuation");

            down = time();
            replay.event(down, down, MotionEvent.ACTION_DOWN, 100f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 5, MotionEvent.ACTION_MOVE, 150f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.event(down, down + 10, MotionEvent.ACTION_MOVE, 200f, InputDevice.SOURCE_TOUCHSCREEN);
            replay.resetScrollGesture();
            check(replay.mGestureRecognizer.getScrollVelocityY() == 0f && replay.mScrollRemainder == 0f,
                    "view/session lifecycle reset clears speed and fractional scroll");
            replay.event(down, down + 15, MotionEvent.ACTION_CANCEL, 200f, InputDevice.SOURCE_TOUCHSCREEN);
            System.out.println("PASS Android gesture scroll: " + checks + " assertions; real detectors/tracker/MOVE/UP; no Activity/install");
            System.out.flush(); System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace(System.out); System.out.flush(); throw error;
        }
    }
}
