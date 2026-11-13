import android.graphics.Color;
import com.termux.terminal.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

public final class RenderComparison {
    static void feed(TerminalEmulator e,String s){byte[] b=s.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}
    static TerminalEmulator emulator(){
        TerminalSessionClient client=(TerminalSessionClient)Proxy.newProxyInstance(RenderComparison.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(p,m,v)->m.getName().equals("getTerminalCursorStyle")?1:null);
        TerminalOutput out=new TerminalOutput(){public void write(byte[]b,int o,int n){}public void titleChanged(String a,String b){}public void onCopyTextToClipboard(String s){}public void onPasteTextFromClipboard(){}public void onBell(){}public void onColorsChanged(){}};
        TerminalEmulator e=new TerminalEmulator(out,96,48,12,24,1000,client);feed(e,"\033[?25l");return e;
    }
    static String shimmer(int frame,int row){
        String text="Thinking... compiling terminal rendering and preserving animation";
        StringBuilder s=new StringBuilder("\033["+row+";1H");
        for(int i=0;i<text.length();i++){
            int c=Color.HSVToColor(new float[]{(frame*7+i*11)%360,0.7f,0.9f});
            s.append("\033[38;2;").append(Color.red(c)).append(';').append(Color.green(c)).append(';').append(Color.blue(c)).append('m').append(text.charAt(i));
        }return s.append("\033[0m").toString();
    }
}
