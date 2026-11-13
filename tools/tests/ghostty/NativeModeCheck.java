package com.termux.terminal;
import android.os.Looper;import java.nio.charset.StandardCharsets;import java.util.Arrays;
import io.github.modao7067.luma.ghostty.*;
public final class NativeModeCheck {
 static int checks;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static GhosttyTerminalEmulator make(){TerminalSession s=new TerminalSession(new SessionCoreCheck.Provider(),1000,SessionCoreCheck.client(),GhosttyTerminalEmulator::new);s.initializeEmulator(80,24,12,24);return(GhosttyTerminalEmulator)s.getEmulator();}
 static void feed(GhosttyTerminalEmulator e,String s){byte[]b=s.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}
 static void same(int[]a,int[]b,int...fields){for(int i:fields)check(a[i]==b[i],"checkpoint field "+i+" restored: "+a[i]+" / "+b[i]);}
 public static void main(String[]args){try{
  Looper.prepare();GhosttyTerminalEmulator.loadLibrary(args[0]);
  GhosttyTerminalEmulator a=make();feed(a,"\033[?1049h\033[?1;1000;1006;2004h\033[?25l\033[2;20r\033[5 q");int[]v=a.exportModeState();
  check(v.length==80&&v[0]==1,"unchanged Root checkpoint layout");
  GhosttyTerminalEmulator b=make();b.restoreModeState(v);check(b.isAlternateBufferActive()&&b.isCursorKeysApplicationMode()&&b.isMouseTrackingActive()&&!b.isCursorEnabled(),"TUI screen and keyboard/mouse/cursor modes restored before PTY input");
  same(v,b.exportModeState(),3,4,5,6,7,8,9,10,11,12,13,14,71,72,73,74,75);
  feed(a,"\033[?1049l");feed(b,"\033[?1049l");check(!b.isAlternateBufferActive(),"TUI leaves restored alternate screen normally");same(a.exportModeState(),b.exportModeState(),71,72,73,74,75);
  GhosttyTerminalEmulator split=make();feed(split,"\033[?10");int[]partial=split.exportModeState();check(partial[34]==7&&partial[38]==10,"pending CSI stored in keeper-compatible words");
  GhosttyTerminalEmulator resumed=make();resumed.restoreModeState(partial);feed(resumed,"49h");check(resumed.isAlternateBufferActive(),"partial CSI setup resumes after frontend replacement");
  final int[]changed={0};resumed.setModeChangeListener(()->changed[0]++);feed(resumed,"\033[?2004h");check(changed[0]==1,"mode change checkpoints once");changed[0]=0;
  for(int i=0;i<100;i++)feed(resumed,"ordinary 中文 output\033[31m red\033[0m\r\n");check(changed[0]==0,"normal text and SGR do not write mode checkpoints");
  GhosttyTerminalEmulator margin=make();feed(margin,"\033[?69h\033[3;50s\033[2;20r\033[?6h\033[5;7H\033[1;3;38;2;12;34;56m");int[]m=margin.exportModeState();GhosttyTerminalEmulator restored=make();restored.restoreModeState(m);same(m,restored.exportModeState(),3,8,9,10,11,71,72,73,74,75);
  GhosttyTerminalEmulator utf=make();final int[][] utfSaved={null};final int[] utfCalls={0};
  utf.setModeChangeListener(()->{utfSaved[0]=utf.exportModeState();utfCalls[0]++;});
  byte[]lead={(byte)0xf0,(byte)0x9f};utf.append(lead,2);int[]u=utf.exportModeState();
  check(u[76]!=0&&utfCalls[0]==1&&utfSaved[0]!=null&&utfSaved[0][76]==u[76],"split UTF-8 lead bytes without ESC notify and persist mode checkpoint");
  GhosttyTerminalEmulator next=make();next.restoreModeState(u);byte[]tail={(byte)0x98,(byte)0x80};next.append(tail,2);check(next.getSelectedText(0,0,1,0).equals("😀"),"split UTF-8 scalar resumes when no offline bytes intervened");
  GhosttyTerminalEmulator utfClear=make();final int[][] clearSaved={null};final int[] clearCalls={0};
  utfClear.setModeChangeListener(()->{clearSaved[0]=utfClear.exportModeState();clearCalls[0]++;});
  byte[] escLead={27,'[','0','m',(byte)0xf0,(byte)0x9f};utfClear.append(escLead,escLead.length);int callsAfterLead=clearCalls[0];
  check(clearSaved[0]!=null&&clearSaved[0][76]!=0,"ESC plus split UTF-8 lead persists pending prefix");
  utfClear.append(tail,2);
  check(clearCalls[0]>callsAfterLead&&clearSaved[0]!=null&&clearSaved[0][76]==0&&utfClear.exportModeState()[76]==0,"completing split UTF-8 scalar without ESC notifies and clears persisted prefix");
  GhosttyTerminalEmulator osc=make();feed(osc,"\033]133;C");int[]o=osc.exportModeState();check(o[34]==10,"partial OSC shell marker retained");GhosttyTerminalEmulator om=make();om.restoreModeState(o);feed(om,"\007");check(om.exportModeState()[34]==0,"partial OSC completes without rendering its tail");
  feed(osc,"\007\033[?2026h");GhosttyTerminalEmulator clean=make();clean.restoreModeState(osc.exportModeState());check(clean.adapter().queryMode(2026)!=1,"2026 transient hold never crosses Root reconnect");
  GhosttyTerminalEmulator sgr=make();feed(sgr,"\033[?1000;1016h");int[] sgrOn=sgr.exportModeState();
  check((sgrOn[3]&16384)!=0,"1016 current mode exported in existing checkpoint");
  feed(sgr,"\033[?1016s");int[] sgrSaved=sgr.exportModeState();check((sgrSaved[4]&16384)!=0,"1016 saved mode exported");
  feed(sgr,"\033[?1016l");int[] sgrOff=sgr.exportModeState();check((sgrOff[3]&16384)==0&&(sgrOff[4]&16384)!=0,"1016 disable keeps the saved value");
  feed(sgr,"\033[?1016r");check((sgr.exportModeState()[3]&16384)!=0,"1016 DECRST restore returns the saved mode");
  GhosttyTerminalEmulator sgrRestored=make();sgrRestored.restoreModeState(sgrOn);
  check(sgrRestored.adapter().queryMode(1016)==1,"1016 restored on a replacement frontend before new PTY bytes");
  for(GhosttyTerminalEmulator e:new GhosttyTerminalEmulator[]{a,b,split,resumed,margin,restored,utf,next,utfClear,osc,om,clean,sgr,sgrRestored})e.dispose();
  System.out.println("NATIVE MODE OK checks="+checks);
 }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}