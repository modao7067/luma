import com.termux.terminal.*;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

public final class ModeRestoreCheck {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);System.out.println("PASS "+text);}
    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        public void write(byte[] b,int o,int n){bytes.write(b,o,n);}
        public void titleChanged(String a,String b){}
        public void onCopyTextToClipboard(String s){}
        public void onPasteTextFromClipboard(){}
        public void onBell(){}
        public void onColorsChanged(){}
    }
    static TerminalSessionClient client(){return (TerminalSessionClient)Proxy.newProxyInstance(ModeRestoreCheck.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(p,m,v)->m.getName().equals("getTerminalCursorStyle")?1:null);}
    static TerminalEmulator emulator(Output out){return new TerminalEmulator(out,80,24,12,24,1000,client());}
    static void append(TerminalEmulator terminal,String text){byte[] data=text.getBytes(StandardCharsets.UTF_8);terminal.append(data,data.length);}
    public static void main(String[] args)throws Exception{
        System.setOut(new PrintStream(args[1]+"/mode-restore-check.txt"));
        try{
            if(Looper.myLooper()==null)Looper.prepare();
            TerminalEmulator original=emulator(new Output());
            append(original,"\033[?1049h\033[?1;1000;1006;2004h\033[?25l\033[2;20r\033[5 q");
            int[] state=original.exportModeState();
            TerminalEmulator broken=emulator(new Output());append(broken,"\033[2J\033[HREDRAW");
            check(!broken.isAlternateBufferActive(),"reproduced: SIGWINCH-style redraw alone leaves a new frontend on the main screen");
            Output out=new Output();TerminalEmulator restored=emulator(out);restored.restoreModeState(state);
            check(restored.isAlternateBufferActive()&&restored.isCursorKeysApplicationMode()&&restored.isMouseTrackingActive()&&!restored.isCursorEnabled(),"restored alternate screen, arrow/mouse modes and hidden cursor before any new PTY bytes");
            restored.paste("text");check(out.bytes.toString("UTF-8").equals("\033[200~text\033[201~"),"bracketed paste is preserved");
            append(restored,"\033[?1049$p");check(out.bytes.toString("UTF-8").endsWith("\033[?1049;1$y"),"mode query reports restored fullscreen state");
            append(restored,"\033[?1049l");check(!restored.isAlternateBufferActive(),"TUI can leave the restored alternate screen normally");
            TerminalEmulator split=emulator(new Output());append(split,"\033[?10");
            TerminalEmulator resumed=emulator(new Output());resumed.restoreModeState(split.exportModeState());append(resumed,"49h");
            check(resumed.isAlternateBufferActive(),"partial CSI mode setup survives frontend replacement");
            int[] count={0};resumed.setModeChangeListener(()->count[0]++);append(resumed,"\033[?2004h");count[0]=0;
            for(int i=0;i<200;i++)append(resumed,"ordinary 中文 output\033[31m red\033[0m\r\n");
            check(count[0]==0,"normal text/color output causes no mode checkpoint callbacks");
            System.out.println("ALL PASS mode restoration without output history or byte relaying");
        }catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
