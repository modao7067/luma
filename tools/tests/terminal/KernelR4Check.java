import com.termux.terminal.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class KernelR4Check {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;}
    static final class Output extends TerminalOutput {
        public void write(byte[] b,int o,int n){}
        public void titleChanged(String a,String b){}
        public void onCopyTextToClipboard(String s){}
        public void onPasteTextFromClipboard(){}
        public void onBell(){}
        public void onColorsChanged(){}
    }
    static TerminalEmulator emulator(int cols,int rows){
        TerminalSessionClient client=(TerminalSessionClient)Proxy.newProxyInstance(KernelR4Check.class.getClassLoader(),new Class[]{TerminalSessionClient.class},
            (p,m,v)->m.getName().equals("getTerminalCursorStyle")?0:null);
        return new TerminalEmulator(new Output(),cols,rows,10,20,64,client);
    }
    static void append(TerminalEmulator e,byte[] bytes,int chunk){
        for(int off=0;off<bytes.length;off+=chunk){byte[] b=Arrays.copyOfRange(bytes,off,Math.min(bytes.length,off+chunk));e.append(b,b.length);}
    }
    static void append(TerminalEmulator e,String s){byte[] b=s.getBytes(StandardCharsets.UTF_8);e.append(b,b.length);}
    static TerminalRow row(TerminalEmulator e,int y){TerminalBuffer b=e.getScreen();return b.allocateFullLineIfNecessary(b.externalToInternalRow(y));}
    static byte[] snapshot(TerminalEmulator e)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);
        d.writeInt(e.getCursorCol());d.writeInt(e.getCursorRow());
        d.writeBoolean(e.isAlternateBufferActive());d.writeBoolean(e.isCursorKeysApplicationMode());d.writeBoolean(e.isMouseTrackingActive());
        TerminalBuffer b=e.getScreen();d.writeInt(b.getActiveTranscriptRows());
        for(int y=-b.getActiveTranscriptRows();y<e.mRows;y++){
            TerminalRow r=row(e,y);d.writeBoolean(b.getLineWrap(y));d.writeInt(r.getSpaceUsed());
            for(int k=0;k<r.getSpaceUsed();k++)d.writeChar(r.mText[k]);
            for(int c=0;c<e.mColumns;c++)d.writeLong(r.getStyle(c));
        }
        return bytes.toByteArray();
    }
    static void run()throws Exception {
        for(int lead=0xf4;lead<=0xf7;lead++)for(int chunk:new int[]{1,2,4}){
            TerminalEmulator e=emulator(8,3);
            byte[] b={(byte)lead,(byte)(lead==0xf4?0x90:0x80),(byte)0x80,(byte)0x80};append(e,b,chunk);append(e,"X");
            check(row(e,0).mText[0]=='\ufffd'&&row(e,0).mText[1]=='X'&&e.getCursorCol()==2,"invalid UTF-8 replacement and recovery lead="+lead+" chunk="+chunk);
        }
        TerminalBuffer b=new TerminalBuffer(8,16,3);
        b.setChar(0,0,0x6c49,0);b.blockCopy(0,0,1,1,0,1);b.blockCopy(1,1,1,1,0,2);
        check(b.getSelectedText(0,2,1,2).trim().isEmpty(),"half-wide copy from a fresh identity row");
        TerminalRow source=new TerminalRow(8,0);source.setChar(0,0x6c49,0);
        TerminalRow reused=new TerminalRow(8,0);reused.setChar(0,0x6c49,0);reused.clear(0);
        for(int c=0;c<8;c++)reused.setChar(c,'A'+c,0);
        reused.copyInterval(source,0,1,0);TerminalRow copied=new TerminalRow(8,0);copied.copyInterval(reused,1,4,0);
        check(new String(copied.mText,0,3).equals("BCD"),"clear must not reuse stale wide-character kinds");
        TerminalEmulator rect=emulator(8,3);append(rect,"汉\033[1;1;1;1;1;2;1;1$v\033[2;2;2;2;1;3;1;1$v");
        check(rect.getCursorCol()==2&&row(rect,0).mText[0]=='汉',"DECCRA half-wide copy leaves cursor and text intact");
        TerminalRow combining=new TerminalRow(8,0);combining.setChar(2,' ',0);combining.setChar(2,0x301,0);
        check(combining.findStartOfColumn(2)==2&&combining.findStartOfColumn(3)==4,"space fast path skips attached combining mark for next column");
        combining.setChar(3,'Z',0);TerminalRow copy=new TerminalRow(8,0);copy.copyInterval(combining,2,4,0);
        check(new String(copy.mText,0,3).equals(" \u0301Z"),"space plus combining mark survives overwrite and copy");
        String stream="ABCDEFGHabcdefgh0123456789\r\n汉A😀e\u0301 \u0301XYZ\r\n"
            +"\033[2;3H\033[4hINSERT\033[4l\033[?7lABCDEFGHIJKLMNOPQRSTUVWXYZ\033[?7h"
            +"\033(0lqkx\033(Bascii\033[3b\033[31mRED\033[0mplain\033[4;5H\033[2@\033[2P"
            +"\033[?1049h\033[2J\033[Halt 汉 text\033[?1049l"
            +"\033[?69h\033[3;12s\033[2;5r\033[HmarginABCDEFGHIJKLMNOPQRSTUVWXYZ\033[?69l\033[r";
        StringBuilder full=new StringBuilder(stream);for(int n=0;n<40;n++)full.append("\r\nrow ").append(n).append(" 汉 text\033[32m色\033[0m");
        byte[] data=full.toString().getBytes(StandardCharsets.UTF_8);byte[] expected=null;
        for(int chunk:new int[]{1,2,3,7,64,65536}){
            TerminalEmulator e=emulator(16,6);append(e,data,chunk);byte[] actual=snapshot(e);
            if(expected==null)expected=actual;
            check(Arrays.equals(expected,actual),"bulk ASCII gates and history match bytewise input, chunk="+chunk);
        }
        TerminalEmulator modes=emulator(16,6);int[] callbacks={0};modes.setModeChangeListener(()->callbacks[0]++);
        append(modes,"\033[?2004h");callbacks[0]=0;
        for(int i=0;i<20;i++)append(modes,"ASCII text 汉\033[31mred\033[0m\r\n");
        check(callbacks[0]==0,"ordinary output does not add mode checkpoint callbacks");
        System.out.println("ALL PASS "+checks+" kernel R4 checks: malformed UTF-8, identity maps, DECCRA, combining marks, ASCII chunks and mode callbacks");
    }
    public static void main(String[] args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/kernel-r4-check.txt"));
        try{run();}catch(Throwable failure){failure.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
