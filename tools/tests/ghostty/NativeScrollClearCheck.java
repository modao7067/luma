package io.github.modao7067.luma.ghostty;
import android.os.Looper;import com.termux.terminal.*;import java.nio.charset.StandardCharsets;
public final class NativeScrollClearCheck {
    static void feed(GhosttyFrameAdapter a,String s){byte[]b=s.getBytes(StandardCharsets.UTF_8);if(a.feed(b,0,b.length)!=0)throw new AssertionError("feed failed");}
    public static void main(String[]args){try{
        Looper.prepare();GhosttyTerminalEmulator.loadLibrary(args[0]);int[]palette=new int[259];palette[256]=0xffffffff;palette[257]=0xff000000;
        GhosttyFrameAdapter a=GhosttyFrameAdapter.create(10,10,1,palette,palette[256],palette[257]);if(a==null)throw new AssertionError("native create");
        for(int i=0;i<160;i++){
            feed(a,"\033[0m\033[H1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8\r\n9\r\n10\033[H\033[1;38;2;239;18;52m\033]8;;https://example.com/pruned\007\033[22JX\033]8;;\007");
            TerminalRenderFrame f=a.capture();long style=f.screen.getStyleAt(0,0);
            if(!"X".equals(a.copyText(0,0,0,0,true,false))||TextStyle.decodeForeColor(style)!=0xffef1234||(TextStyle.decodeEffect(style)&1)==0)throw new AssertionError("cursor style/text after CSI22J: "+i);
            if(!"https://example.com/pruned".equals(a.hyperlinkAtActive(0,0)))throw new AssertionError("cursor hyperlink after CSI22J: "+i);
        }
        a.close();System.out.println("NATIVE SCROLLCLEAR OK 160 CSI22J cycles; one-line history, styled/hyperlinked cursor, packaged JNI");
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
