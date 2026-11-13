package io.github.modao7067.luma.shared;

import android.util.Log;

class LogCallbacks {
    public void logError(String t,String m){Log.e(t,m);}
    public void logWarn(String t,String m){Log.w(t,m);}
    public void logInfo(String t,String m){Log.i(t,m);}
    public void logDebug(String t,String m){Log.d(t,m);}
    public void logVerbose(String t,String m){Log.v(t,m);}
    public void logStackTraceWithMessage(String t,String m,Exception e){Log.e(t,m,e);}
    public void logStackTrace(String t,Exception e){Log.e(t,"Terminal error",e);}
}
