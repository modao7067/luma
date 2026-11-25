#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
REPO = Path(__file__).resolve().parents[3]
import subprocess

here=Path(__file__).resolve().parent
source=Path(REPO / 'shared/terminal-view/java/com/termux/view/TerminalView.java').read_text()
start=source.index('    public InputConnection onCreateInputConnection(')
end=source.index('\n    @Override\n    protected int computeVerticalScrollRange()',start)
method=source[start:end]
adapter=r'''
import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
public final class ImeChecks {
 static final boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED=false;
 static final String LOG_TAG="test";
 static final int KEY_EVENT_SOURCE_SOFT_KEYBOARD=0;
 static class SurroundingText {
  final CharSequence text; final int selStart,selEnd,offset;
  SurroundingText(CharSequence text,int selStart,int selEnd,int offset){
   this.text=text;this.selStart=selStart;this.selEnd=selEnd;this.offset=offset;
  }
 }
 static class EditorInfo {
  static final int IME_FLAG_NO_FULLSCREEN=android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;
  static final int IME_FLAG_NO_EXTRACT_UI=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
  static final int IME_FLAG_NO_PERSONALIZED_LEARNING=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
  int inputType,imeOptions,initialSelStart,initialSelEnd;
  CharSequence initialSubText; int initialSubTextStart;
  void setInitialSurroundingSubText(CharSequence s,int start){initialSubText=s;initialSubTextStart=start;}
 }
 static class TerminalEmulator {static final int UNICODE_REPLACEMENT_CHAR=0xfffd;}
 static class Preview {boolean key(int code){return true;}}
 Preview mFilePreview;
 Object mEmulator=new Object(); Client mClient=new Client();StringBuilder sent=new StringBuilder();
 boolean protocolActive;
 int lastVirtualSelection=-1;
 int selectionUpdates=0;
 void notifyImeVirtualSelection(int pos){lastVirtualSelection=pos;selectionUpdates++;}
 static class Client {
  boolean isTerminalViewSelected(){return true;} int getInputMode(){return 0;}
  boolean ctrlLatched,altLatched,shiftLatched;
  int ctrlReads,altReads,shiftReads;
  boolean readControlKey(){ctrlReads++;boolean v=ctrlLatched;ctrlLatched=false;return v;}
  boolean readAltKey(){altReads++;boolean v=altLatched;altLatched=false;return v;}
  boolean readShiftKey(){shiftReads++;boolean v=shiftLatched;shiftLatched=false;return v;}
  void logInfo(String tag,String message){}
 }
 void stopTextSelectionMode(){}
 void sendCommittedEnterOrTab(boolean isEnter,boolean ctrl,boolean alt,boolean shift){
  if(protocolActive){sent.append(isEnter?"<ENTER":"<TAB").append(ctrl?"+C":"").append(alt?"+A":"").append(shift?"+S":"").append(">");}
  else if(isEnter){if(alt)sent.append('\033');sent.append('\r');}
  else if(shift&&!ctrl&&!alt){sent.append("\033[Z");}
  else{if(alt)sent.append('\033');sent.append('\t');}
 }
 void inputCodePointResolved(int source,int code,boolean ctrl,boolean alt,boolean shift){
  if(protocolActive&&(ctrl||alt||shift)){sent.append("<CP:").append(code).append(ctrl?"+C":"").append(alt?"+A":"").append(shift?"+S":"").append(">");return;}
  if(alt)sent.append('\033');
  sent.appendCodePoint(ctrl?code&31:code);
 }
 static class Editable implements CharSequence {
  StringBuilder text=new StringBuilder();int cursor=0;
  public int length(){return text.length();}public char charAt(int i){return text.charAt(i);}
  public CharSequence subSequence(int a,int b){return text.subSequence(a,b);}
  public String toString(){return text.toString();}
  void clear(){text.setLength(0);cursor=0;}
  void delete(int a,int b){text.delete(a,b);cursor=Math.min(cursor,text.length());}
 }
 static class Selection {static int getSelectionEnd(Editable text){return text.cursor;}}
 static class KeyEvent {
  static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DEL=67,KEYCODE_FORWARD_DEL=112;
  final int action,key;KeyEvent(int action,int key){this.action=action;this.key=key;}
  int getKeyCode(){return key;}int getAction(){return action;}
 }
 interface InputConnection {
  boolean finishComposingText();boolean commitText(CharSequence s,int cursor);
  boolean deleteSurroundingText(int a,int b);boolean deleteSurroundingTextInCodePoints(int a,int b);
  boolean sendKeyEvent(KeyEvent e);boolean setComposingText(CharSequence text,int cursor);
  CharSequence getTextBeforeCursor(int n,int flags);
  CharSequence getTextAfterCursor(int n,int flags);
  SurroundingText getSurroundingText(int before,int after,int flags);
 }
 class BaseInputConnection implements InputConnection {
  Editable content=new Editable(); BaseInputConnection(Object target,boolean full){}
  Editable getEditable(){return content;}
  public boolean finishComposingText(){return true;}
  public boolean commitText(CharSequence s,int cursor){setComposingText(s.toString(),cursor);return true;}
  public boolean sendKeyEvent(KeyEvent e){if(e.action==0)sent.append(e.key==67?"<DEL>":"<FDEL>");return true;}
  public boolean deleteSurroundingText(int a,int b){return true;}
  public boolean deleteSurroundingTextInCodePoints(int a,int b){return true;}
  public boolean setComposingText(CharSequence text,int cursor){content.clear();content.text.append(text);content.cursor=text.length();return true;}
  public CharSequence getTextBeforeCursor(int n,int flags){return content.toString();}
  public CharSequence getTextAfterCursor(int n,int flags){return "";}
  public SurroundingText getSurroundingText(int before,int after,int flags){return new SurroundingText(content.toString(),content.cursor,content.cursor,0);}
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS: "+message);}
 void run(){
  EditorInfo info=new EditorInfo();InputConnection ic=onCreateInputConnection(info);
  check((info.inputType&InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)!=0,"no dictionary suggestions requested");
  check((info.inputType&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_FILTER,"filter variation retains text composition");
  check((info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0,"no personalized learning requested");
  check(info.initialSelStart>0&&info.initialSelEnd==info.initialSelStart,"non-zero initial selection enables IME long-press backspace");
  check(" ".contentEquals(ic.getTextBeforeCursor(1,0)),"idle terminal exposes single-space sentinel before cursor");
  check(" ".contentEquals(ic.getSurroundingText(1,0,0).text),"surrounding text exposes single-space sentinel before cursor");
  ic.setComposingText("nihao",1);check(sent.length()==0,"pinyin composition sends no PTY text");
  ic.finishComposingText();check(sent.length()==0,"finish without explicit commit discards pending pinyin");
  ic.setComposingText("nihao",1);ic.commitText("你好",1);
  check(sent.toString().equals("你好"),"Chinese selection commits only selected text once");
  sent.setLength(0);ic.setComposingText("zhong",1);ic.deleteSurroundingText(1,0);
  check(sent.length()==0,"IME deletion while composing does not backspace terminal contents");
  ic.deleteSurroundingTextInCodePoints(1,0);check(sent.length()==0,"code-point deletion also stays local");
  ic.sendKeyEvent(new KeyEvent(0,67));ic.sendKeyEvent(new KeyEvent(1,67));
  check(sent.length()==0,"raw DEL key pair while composing stays local");
  ic.finishComposingText();int prevSel=lastVirtualSelection;ic.deleteSurroundingText(2,1);
  check(sent.toString().equals("<DEL><DEL><FDEL>"),"committed terminal text supports backward and forward deletion");
  check(lastVirtualSelection!=prevSel&&lastVirtualSelection>0,"repeated delete updates non-zero virtual selection for OEM IMEs");
  check(" ".contentEquals(ic.getTextBeforeCursor(1,0)),"sentinel remains non-empty after repeated backspace");
  sent.setLength(0);ic.deleteSurroundingText(500,500);
  check(sent.toString().equals("<DEL>".repeat(128)+"<FDEL>".repeat(128)),"deleteSurroundingText clamps large counts to 128");
  sent.setLength(0);ic.deleteSurroundingText(-10,-5);
  check(sent.length()==0,"deleteSurroundingText clamps negative counts to 0");
  sent.setLength(0);ic.commitText("git status 😀\n",1);
  check(sent.toString().equals("git status 😀\r"),"ASCII, spaces, supplementary Unicode and terminal Enter preserved");
  ic.finishComposingText();check(sent.toString().equals("git status 😀\r"),"finish after commit does not duplicate text");
  sent.setLength(0);protocolActive=true;ic.commitText("\n\r\t",1);
  check(sent.toString().equals("<ENTER><ENTER><TAB>"),"protocol-active newline, CR and tab route directly as Enter/Tab");
  sent.setLength(0);mClient.ctrlReads=mClient.altReads=mClient.shiftReads=0;
  mClient.ctrlLatched=true;mClient.altLatched=true;mClient.shiftLatched=true;
  ic.commitText("",1);
  check(mClient.ctrlLatched&&mClient.altLatched&&mClient.shiftLatched,"empty commit preserves latched modifiers");
  ic.commitText("ab",1);
  check(mClient.ctrlReads==1&&mClient.altReads==1&&mClient.shiftReads==1,"latched modifiers read once before loop without short-circuit");
  check(sent.toString().equals("<CP:65+C+A+S><CP:66+C+A+S>"),"latched modifiers apply consistently across multi-char commit");
  protocolActive=false;
  sent.setLength(0);mFilePreview=new Preview();ic.setComposingText("pinyin",1);ic.commitText("输入",1);ic.deleteSurroundingText(1,0);ic.sendKeyEvent(new KeyEvent(0,67));
  check(sent.length()==0,"preview blocks IME commits, composition, deletion and raw key forwarding into main PTY");
  mFilePreview=null;ic.commitText("继续",1);check(sent.toString().equals("继续"),"IME resumes on main terminal after preview closes");
 }
 public static void main(String[] args){new ImeChecks().run();}
'''
work=here/'input-check';work.mkdir(exist_ok=True)
(work/'ImeChecks.java').write_text(adapter+method+'\n}\n')
sdk=toolenv.ANDROID_JAR
subprocess.run(['javac','--release','17','-cp',sdk,'-d',str(work),str(work/'ImeChecks.java')],check=True)
result=subprocess.run(['java','-cp',f'{work}:{sdk}','ImeChecks'],text=True,capture_output=True,check=True)
(here/'input-check.txt').write_text(result.stdout)
print(result.stdout)
