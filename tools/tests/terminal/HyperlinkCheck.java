import com.termux.terminal.*;
import java.io.*;
import java.lang.reflect.Field;

public final class HyperlinkCheck {
    static int checks;
    static final String URI="file:///workspace/图%20sample.png";
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static void feed(TerminalEmulator e,String s){RenderComparison.feed(e,s);}
    static TerminalEmulator fresh(int columns){TerminalEmulator e=RenderComparison.emulator();e.resize(columns,8,12,24);return e;}
    static String link(String uri,String label){return "\033]8;id=artifact;"+uri+"\033\\"+label+"\033]8;;\033\\";}
    public static void main(String[]args)throws Exception {
        System.setOut(new PrintStream(args[1]+"/hyperlink-check.txt"));
        try{
            TerminalEmulator e=fresh(40);byte[] bytes=link(URI,"预览图片ABC").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(byte b:bytes)e.append(new byte[]{b},1);
            for(int col=0;col<11;col++)check(URI.equals(e.getScreen().getHyperlinkAt(col,0)),"fragmented OSC 8 label/wide halves "+col);
            check(e.getScreen().getSelectedText(0,0,10,0).equals("预览图片ABC"),"copy contains label, no hidden URI/escape sequences");
            feed(e,"Z");check(e.getScreen().getHyperlinkAt(11,0)==null,"OSC 8 close ends target");
            feed(e,"\rX");check(e.getScreen().getHyperlinkAt(0,0)==null&&e.getScreen().getHyperlinkAt(1,0)==null,"overwrite first wide half clears both target cells");
            e=fresh(40);feed(e,link(URI,"中")+"\033[1;2HX");check(e.getScreen().getHyperlinkAt(0,0)==null&&e.getScreen().getHyperlinkAt(1,0)==null,"overwrite second wide half clears both target cells");
            e=fresh(40);feed(e,link(URI,"ABCDE"));e.getScreen().blockCopy(0,0,5,1,1,0);
            for(int col=1;col<=5;col++)check(URI.equals(e.getScreen().getHyperlinkAt(col,0)),"overlapping DECCRA preserves target "+col);
            feed(e,"\rplain");for(int col=0;col<5;col++)check(e.getScreen().getHyperlinkAt(col,0)==null,"ASCII fast path clears stale target "+col);
            feed(e,"\r\033[2K");for(int col=0;col<40;col++)check(e.getScreen().getHyperlinkAt(col,0)==null,"erase clears target "+col);
            e=fresh(8);feed(e,link(URI,"ABC中文DEF"));e.resize(5,8,12,24);e.resize(20,8,12,24);
            int linked=0;for(int row=-e.getScreen().getActiveTranscriptRows();row<8;row++)for(int col=0;col<20;col++)if(URI.equals(e.getScreen().getHyperlinkAt(col,row)))linked++;
            check(linked==10,"wide/ASCII links survive shrink and expand reflow, linked cells="+linked);
            e=fresh(20);feed(e,link(URI,"ABC")+"\r\n");for(int i=0;i<12;i++)feed(e,"line\r\n");
            check(URI.equals(e.getScreen().getHyperlinkAt(0,-e.getScreen().getActiveTranscriptRows())),"links follow rows into scrollback");
            e=fresh(20);feed(e,link(URI,"A"));for(int i=0;i<256;i++)feed(e,link("https://example.test/"+i,""));
            check(e.getScreen().getHyperlinkAt(0,0)==null,"bounded pool expires old destinations");
            feed(e,link("https://example.test/latest","B"));check("https://example.test/latest".equals(e.getScreen().getHyperlinkAt(1,0)),"pool retains recent links");
            e=fresh(20);feed(e,link("x".repeat(4097),"A"));check(e.getScreen().getHyperlinkAt(0,0)==null,"overlong destination rejected");
            feed(e,"\033]8;;"+URI+"\007B\033[0mC\033]8;;\007D");check(URI.equals(e.getScreen().getHyperlinkAt(1,0))&&URI.equals(e.getScreen().getHyperlinkAt(2,0))&&e.getScreen().getHyperlinkAt(3,0)==null,"BEL terminator and SGR do not corrupt OSC 8 state");
            e=fresh(12);feed(e,link(URI,"跨行图片预览ABCDEFGHIJKL"));
            for(int row=0;row<2;row++)for(int col=0;col<12;col++)check(URI.equals(TerminalLinks.at(e.getScreen(),col,row)),"OSC8 natural wrap retains its explicit destination cell="+col+":"+row);
            e=fresh(40);feed(e,"\033]8;;"+URI+"\033\\第一行\r\n第二行\033]8;;\033\\");
            for(int row=0;row<2;row++)for(int col=0;col<6;col++)check(URI.equals(TerminalLinks.at(e.getScreen(),col,row)),"OSC8 hard CR/LF retains its destination across wide characters="+col+":"+row);
            check(TerminalLinks.at(e.getScreen(),6,1)==null,"outside explicit label does not inherit target");
            String other="https://example.test/second";e=fresh(80);feed(e,link(URI,"图片")+" 正文 "+link(other,"网页"));
            check(URI.equals(TerminalLinks.at(e.getScreen(),2,0))&&other.equals(TerminalLinks.at(e.getScreen(),11,0)),"adjacent OSC8 labels retain their independent destinations");
            for(int col=4;col<10;col++)check(TerminalLinks.at(e.getScreen(),col,0)==null,"body between explicit labels stays inert "+col);
            for(String style:new String[]{"0","34","34;4","36;1","38;2;128;200;210","90"}){
                e=fresh(40);feed(e,"\033["+style+"mbench-kernel-r4/REVIEW-GROK-4.7.md，模型是 Grok\r\n+ Show details /workspace/a.png\r\n查看更多 https://example.test/next\r\n图片 (/workspace/long-\r\npath/sample.png)\033[0m");
                for(int row=0;row<8;row++)for(int col=0;col<40;col++)check(TerminalLinks.at(e.getScreen(),col,row)==null,"colored filenames, URLs, labels, controls and hard wraps never create links style="+style+" cell="+col+":"+row);
            }
            e=fresh(80);feed(e,link(URI,"CLICK HERE")+"\033[1;1H\033[34mDashboard \033[0m");
            for(int col=0;col<10;col++)check(TerminalLinks.at(e.getScreen(),col,0)==null,"dashboard overwrites old OSC8 label at cell "+col);
            e=fresh(80);feed(e,link(URI,"bench-kernel-r4/REVIEW-GROK-4.7.md")+"，模型是 Grok");
            check(URI.equals(TerminalLinks.at(e.getScreen(),5,0)),"explicit target does not depend on displayed filename");
            for(int col="bench-kernel-r4/REVIEW-GROK-4.7.md".length();col<45;col++)check(TerminalLinks.at(e.getScreen(),col,0)==null,"Chinese punctuation and following body cannot join OSC8 target "+col);
            e=fresh(80);feed(e,"\033[8m"+link(URI,"hidden"));
            check(TerminalLinks.at(e.getScreen(),0,0)==null,"invisible OSC8 label is not a visible target");
            e=fresh(80);feed(e,link(URI,"/workspace/image.png"));e.getScreen().setChar(0,0,'/',TextStyle.encodeTerminalBitmap(0,0,0));
            check(TerminalLinks.at(e.getScreen(),0,0)==null,"bitmap cell is not a hidden text link");
            check(!TerminalLinks.isOpenable("javascript:fake.py")&&!TerminalLinks.isOpenable("data:text/example.md"),"unsafe URI is not reinterpreted as a filename");
            check(!TerminalLinks.isOpenable("javascript:alert(1)")&&!TerminalLinks.isOpenable("data:text/html,x"),"executable/data link schemes rejected");
            feed(e,"\033cX");check(e.getScreen().getHyperlinkAt(0,0)==null,"terminal reset clears active target");
            e=fresh(40);feed(e,"ordinary ASCII output");TerminalRow row=e.getScreen().allocateFullLineIfNecessary(e.getScreen().externalToInternalRow(0));Field links=TerminalRow.class.getDeclaredField("mHyperlinks");links.setAccessible(true);check(links.get(row)==null,"ordinary rows allocate no hyperlink array");
            check(TerminalLinks.at(e.getScreen(),-1,0)==null&&TerminalLinks.at(e.getScreen(),0,-10000)==null,"invalid taps are inert");
            System.out.println("ALL PASS "+checks+" OSC 8, overwrite/copy/reflow/scrollback, bounded metadata, explicit wrapped links; ordinary colored text inert");
        }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
