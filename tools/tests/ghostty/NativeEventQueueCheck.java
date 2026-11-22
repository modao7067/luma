package com.termux.terminal;

import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import io.github.modao7067.luma.ghostty.*;

public final class NativeEventQueueCheck {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void feed(GhosttyTerminalEmulator e,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);e.append(bytes,bytes.length);}
    static int rawFeed(long h,String text){byte[] b=text.getBytes(StandardCharsets.UTF_8);return GhosttyNative.feed(h,b,0,b.length);}
    static final class Output extends TerminalOutput {
        final ByteArrayOutputStream replies=new ByteArrayOutputStream();
        int bells,command=-99;String title,clipboard;
        public void write(byte[] b,int offset,int length){replies.write(b,offset,length);}
        public void titleChanged(String old,String value){title=value;}
        public void onCopyTextToClipboard(String value){clipboard=value;}
        public void onPasteTextFromClipboard(){}
        public void onBell(){bells++;}
        public void onColorsChanged(){}
        public void onCommandState(int state){command=state;}
    }
    public static void main(String[] args){try{
        Looper.prepare();GhosttyTerminalEmulator.loadLibrary(args[0]);
        Output sink=new Output();
        GhosttyTerminalEmulator e=GhosttyTerminalEmulator.forOutput(sink,80,24,12,24,12000,SessionCoreCheck.client(),null);
        String metadata="\007\033]2;title\007\033]7;file://localhost/tmp/test\007\033]133;C\007\033]133;D;9\007";
        feed(e,metadata.repeat(4000)+"\033]2;final-title\007\033]52;c;ZmluYWw=\007");
        check(sink.bells==1&&"final-title".equals(sink.title)&&"final".equals(sink.clipboard),"4000 metadata groups coalesce BEL/title/clipboard without throwing");
        check(sink.command==1&&e.getWorkingDirectory()!=null&&e.getWorkingDirectory().endsWith("/tmp/test"),"latest command completion and PWD survive metadata flood");
        int before=sink.bells;feed(e,"\007");check(sink.bells==before+1,"metadata drain permits the next BEL");
        feed(e,"\033]2;before\007\033c\033]2;after\007");check("after".equals(sink.title),"reset/title last-occurrence order is preserved");
        feed(e,"\033[18t".repeat(150000));
        String expected="\033[8;24;80t".repeat(150000);
        check(Arrays.equals(sink.replies.toByteArray(),expected.getBytes(StandardCharsets.UTF_8)),"over 1 MiB replies stream through bounded queue exactly once in request order");
        check(!GhosttyNative.replyOverflow(e.adapter().handle()),"bound production host never overflows on query flood");
        sink.replies.reset();feed(e,"\033[18t");
        check("\033[8;24;80t".equals(sink.replies.toString("UTF-8")),"reply drain is reusable after flood");
        long h=GhosttyNative.create(80,24,10,new int[256],0xffeeeeee,0xff111111);
        GhosttyNative.resize(h,80,24,12,24);rawFeed(h,"\033[18t".repeat(150000));
        check(GhosttyNative.replyOverflow(h),"unbound raw JNI capacity failure is visible");
        byte[] retained=GhosttyNative.takeReplies(h);
        check(retained!=null&&retained.length==1024*1024&&!GhosttyNative.replyOverflow(h),"raw bounded prefix remains consumable and successful drain clears diagnostic");
        rawFeed(h,"\033[18t");check(Arrays.equals(GhosttyNative.takeReplies(h),"\033[8;24;80t".getBytes(StandardCharsets.UTF_8)),"raw reply queue recovers after drain");
        rawFeed(h,"\033[18t".repeat(150000));GhosttyNative.reset(h);
        check(!GhosttyNative.replyOverflow(h)&&GhosttyNative.takeReplies(h).length==0,"explicit reset clears queued replies and overflow");
        rawFeed(h,"\007".repeat(10000));int[] events=GhosttyNative.takeEvents(h);
        check(events!=null&&events.length<=14&&GhosttyNative.takeEvents(h).length==0,"raw metadata storage stays bounded and drains after 10000 BELs");
        GhosttyNative.reset(h);check(GhosttyNative.takeEvents(h)!=null,"metadata reset leaves a consumable queue");GhosttyNative.destroy(h);
        TerminalSession session=NativeHostCheck.session();GhosttyTerminalEmulator host=(GhosttyTerminalEmulator)session.getEmulator();
        feed(host,"\033]133;C\007\033]133;D;7\007".repeat(3000));
        check(session.getCommandState()==TerminalSession.COMMAND_FAILED,"coalescing preserves start-before-end for real TerminalSession state guard");session.disposeEmulator();
        StringBuilder history=new StringBuilder();for(int row=0;row<6000;row++)history.append("aa word").append(String.format(java.util.Locale.ROOT,"%04d",row)).append(" zz\r\n");
        feed(e,history.toString());long handle=e.adapter().handle();
        long[] memory=GhosttyNative.memoryUsage(handle);check(memory[5]>3,"word-selection fixture really spans multiple native history pages");
        long[] bar=GhosttyNative.scrollbar(handle);int historyRows=(int)bar[3];
        for(int row:new int[]{0,250,1000,3000,5500,5999}){
            int[] bounds=GhosttyNative.selectWordAt(handle,row,5);
            check(Arrays.equals(bounds,new int[]{3,row,10,row}),"page pin converts to SCREEN row "+row+": "+Arrays.toString(bounds));
            int[] active=e.adapter().wordBounds(row-historyRows,5);
            check(Arrays.equals(active,new int[]{3,row-historyRows,10,row-historyRows}),"Java selection retains active-screen history contract at row "+row);
        }
        String maxClip = "B".repeat(262_144);
        String b64Max = java.util.Base64.getEncoder().encodeToString(maxClip.getBytes(StandardCharsets.UTF_8));
        sink.clipboard = null;
        feed(e, "\033]52;c;" + b64Max + "\007");
        check(maxClip.equals(sink.clipboard),
            "OSC 52 clipboard accepts exact 262144-byte boundary");
        String overClip = "A".repeat(262_145);
        String b64Over = java.util.Base64.getEncoder().encodeToString(overClip.getBytes(StandardCharsets.UTF_8));
        sink.clipboard = null;
        feed(e, "\033]52;c;" + b64Over + "\007");
        check(sink.clipboard == null,
            "OSC 52 clipboard rejects payload above 262144 bytes");
        feed(e, "\033[?2026h");
        long[] holdBeforeReset = GhosttyNative.holdState(e.adapter().handle());
        check(holdBeforeReset != null && holdBeforeReset[0] == 1, "DEC 2026 activates render hold before reset");
        e.reset();
        long[] holdAfterReset = GhosttyNative.holdState(e.adapter().handle());
        check(holdAfterReset != null && holdAfterReset[0] == 0, "reset cancels active render hold");
        e.dispose();System.out.println("NATIVE EVENT QUEUE OK checks="+checks);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
