package com.termux.terminal;
import android.os.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import io.github.modao7067.luma.ghostty.*;
public final class NativeHostCheck {
 static int checks, screenUpdates, titleUpdates, colorUpdates, bells; static String copied;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static void feed(GhosttyTerminalEmulator e,String text){byte[]b=text.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}
 static TerminalSession session(){TerminalSessionClient c=(TerminalSessionClient)Proxy.newProxyInstance(NativeHostCheck.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(p,m,a)->{
  switch(m.getName()){case "getTerminalCursorStyle":return 0;case "onTextChanged":screenUpdates++;break;case "onTitleChanged":titleUpdates++;break;case "onColorsChanged":colorUpdates++;break;case "onBell":bells++;break;case "onCopyTextToClipboard":copied=(String)a[1];break;}return null;});
  TerminalSession s=new TerminalSession(new SessionCoreCheck.Provider(),1000,c,GhosttyTerminalEmulator::new);s.mShellPid=1;s.initializeEmulator(40,8,12,24);return s;}
 static String replies(TerminalSession s){byte[]b=new byte[4096];int n=s.mTerminalToProcessIOQueue.read(b,false);return n>0?new String(b,0,n,StandardCharsets.UTF_8):"";}
 static String text(GhosttyTerminalEmulator e){return e.getScreen().getSelectedText(0,0,39,7).trim();}
 public static void main(String[]args)throws Exception{
  Looper.prepare();GhosttyTerminalEmulator.loadLibrary(args[0]);
  TerminalSession s=session();GhosttyTerminalEmulator e=(GhosttyTerminalEmulator)s.getEmulator();
  feed(e,"\033]2;任务 中文\007\033]7;file://localhost/tmp/work\007\033]133;C\007\033]133;D;7\007\007");
  check("任务 中文".equals(s.getTitle())&&titleUpdates==1,"native UTF-8 title reaches the session callback");
  check("/tmp/work".equals(s.getCwd()),"OSC7 working directory reaches the session");
  check(s.getCommandState()==TerminalSession.COMMAND_FAILED,"same-chunk OSC133 start/end preserves order");
  check(bells==1,"native BEL reaches client");
  feed(e,"\033]52;c;aGVsbG8=\007");check("hello".equals(copied),"OSC52 decoded text reaches Android clipboard sink");
  feed(e,"\033[?1000;1004;1006;2004h");replies(s);
  e.sendMouseEvent(0,3,2,true);e.sendMouseEvent(0,3,2,false);e.sendFocusEvent(true);e.sendFocusEvent(false);e.paste("a\nb");
  check("\033[<0;3;2M\033[<0;3;2m\033[I\033[O\033[200~a\nb\033[201~".equals(replies(s)),"SGR mouse, focus and native bracketed paste preserve multi-line text");
  feed(e,"\033cA");e.getRenderFrame();e.setCursorBlinkingEnabled(true);e.setCursorBlinkState(false);
  check(!e.getRenderFrame().cursorVisible,"View blink hides native cursor");e.setCursorBlinkState(true);check(e.getRenderFrame().cursorVisible,"View blink restores native cursor");
  feed(e,"\033[?2026hBC");check("A".equals(text(e)),"hold keeps committed text A");
  check(!e.getViewportText(0).contains("BC"),"accessibility does not expose unfinished held text");
  long deadline=GhosttyNative.holdState(e.adapter().handle())[1];feed(e,"\033[?2026h");
  check(GhosttyNative.holdState(e.adapter().handle())[1]==deadline,"repeated enable keeps the original deadline");
  feed(e,"\033[?2026lD");check("ABCD".equals(text(e)),"release commits ABCD");
  feed(e,"\033[?2026hE\033[?2026l\033[?2026hF");check("ABCDE".equals(text(e)),"same-chunk groups capture the latest committed boundary");
  e.onUserInteraction();check("ABCDEF".equals(text(e)),"user interaction reveals live text without jumping viewport");
  check(e.adapter().queryMode(2026)==1,"user interaction preserves protocol hold mode");
  feed(e,"\033[?2026l\033]10;#123456\007");e.getRenderFrame();check(colorUpdates>0&&e.mColors.mCurrentColors[256]==0xff123456,"native palette changes notify Android");
  feed(e,"\033[?2026hT");e.resize(40,8,12,24);check(e.adapter().queryMode(2026)!=1,"same-size resize cancels hold");
  feed(e,"\033[?2026hZ");int before=screenUpdates;
  new Handler().postDelayed(()->{try{
   check(e.adapter().queryMode(2026)!=1,"real Handler clears mode 2026 after one second");
   check(text(e).endsWith("TZ"),"timeout exposes the complete native frame");
   check(screenUpdates>before,"timeout publishes a frame without further PTY output");
   s.disposeEmulator();check(e.adapter()==null,"dispose releases native and its timer");
   System.out.println("NATIVE HOST OK checks="+checks);
  }catch(Throwable t){t.printStackTrace();System.exit(1);}finally{Looper.myLooper().quitSafely();}},1150);
  Looper.loop();
 }
}