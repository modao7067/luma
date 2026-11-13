package com.termux.terminal;
import android.os.Looper;
import android.os.Message;
import io.github.modao7067.luma.ghostty.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public final class SessionCoreCheck {
    static int checks;
    static void check(boolean ok,String t){if(!ok)throw new AssertionError(t);checks++;System.out.println("PASS "+t);}
    static byte[] u(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static TerminalSessionClient client(){return (TerminalSessionClient)Proxy.newProxyInstance(SessionCoreCheck.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(p,m,a)->m.getName().equals("getTerminalCursorStyle")?0:null);}
    static final class Provider implements TerminalSession.PtyProvider {
        public void start(TerminalSession s,int c,int r,int cw,int ch){}
        public void resize(int c,int r,int cw,int ch){}
        public void closeWindow(){}
        public void detach(){}
    }
    static void drain(TerminalSession s)throws Exception{
        s.mMainThreadHandler.handleMessage(Message.obtain(s.mMainThreadHandler,1));
    }
    static void send(TerminalSession s,byte[] b)throws Exception{
        check(s.mProcessToTerminalIOQueue.write(b,0,b.length),"queue accepts input");
        drain(s);
    }
    static String readReplies(TerminalSession s)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[4096];int n;
        while((n=s.mTerminalToProcessIOQueue.read(buf,false))>0) out.write(buf,0,n);
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception{
        try { run(args); } catch(Throwable t){ t.printStackTrace(System.out); System.out.flush(); System.exit(1); }
    }
    static void run(String[] args)throws Exception{
        if(Looper.myLooper()==null)Looper.prepare();
        GhosttyTerminalEmulator.loadLibrary(args[0]);
        boolean rejectedNull=false;try{new TerminalSession(new Provider(),1000,client(),null);}catch(NullPointerException expected){rejectedNull=true;}
        check(rejectedNull,"null factory is rejected, never a Java fallback");
        TerminalSession legacy=new TerminalSession(new Provider(),1000,client());
        legacy.initializeEmulator(20,5,12,24);
        check(legacy.getEmulator() instanceof GhosttyTerminalEmulator,"all TerminalSession constructors now initialize native");
        legacy.disposeEmulator();
        TerminalSession javaSession=new TerminalSession(new Provider(),1000,client(),
            GhosttyTerminalEmulator::new);
        javaSession.initializeEmulator(20,5,12,24);
        check(javaSession.getEmulator() instanceof GhosttyTerminalEmulator,"explicit factory creates the sole native implementation");
        javaSession.mShellPid=1;
        send(javaSession,u("java-kernel"));
        check(javaSession.getEmulator().getScreen().getSelectedText(0,0,20,1).contains("java-kernel"),"explicit native factory parses through the session queue");
        javaSession.disposeEmulator();
        TerminalSession session=new TerminalSession(new Provider(),1000,client(),
            (s,c,r,cw,ch,tr,cl)->new GhosttyTerminalEmulator(s,c,r,cw,ch,tr,cl));
        session.mShellPid=1;
        session.initializeEmulator(40,10,12,24);
        check(session.getEmulator() instanceof GhosttyTerminalEmulator,"native factory creates GhosttyTerminalEmulator");
        GhosttyTerminalEmulator nativeEmu=(GhosttyTerminalEmulator)session.getEmulator();
        check(nativeEmu.getScreen().mScreenRows==10&&nativeEmu.mColumns==40,"native frame dimensions");
        byte[] sample=u("abc中文é😀\033[31mRED\033[0m\033[3;5H");
        send(session,sample);
        check(nativeEmu.feedCount()==1,"one drain produces exactly one native feed");
        check(nativeEmu.getScreen().getSelectedText(0,0,40,2).contains("abc中文"),"native projection text via real session queue");
        check(nativeEmu.getCursorRow()==2&&nativeEmu.getCursorCol()==4,"native cursor via real session queue");
        int before=nativeEmu.feedCount();
        StringBuilder frag=new StringBuilder();for(int i=0;i<50;i++)frag.append(i%10);
        byte[] fb=u(frag.toString());
        for(int i=0;i<fb.length;i+=7){byte[] part=Arrays.copyOfRange(fb,i,Math.min(fb.length,i+7));
            session.mProcessToTerminalIOQueue.write(part,0,part.length);drain(session);}
        check(nativeEmu.feedCount()-before>1,"fragmented queue drains feed once per drain (no waiting for 64KiB)");
        readReplies(session);
        send(session,u("\033[c"));
        String r=readReplies(session);
        check(r.contains("\033[?"),"native device reply forwarded through TerminalOutput/queue ("+r.replace("\033","ESC")+")");
        check(readReplies(session).isEmpty(),"reply is forwarded exactly once (no Java shadow parser)");
        session.getEmulator().resize(30,8,12,24);
        check(nativeEmu.getScreen().mScreenRows==8&&nativeEmu.mColumns==30,"native resize updates projection geometry");
        send(session,u("\033[?1049hALT"));check(nativeEmu.isAlternateBufferActive(),"native alternate screen via facade");
        send(session,u("\033[?1049l"));
        int feedBeforeDispose=nativeEmu.feedCount();
        session.disposeEmulator();session.disposeEmulator();
        check(nativeEmu.feedCount()==feedBeforeDispose,"double dispose is safe");
        session.mProcessToTerminalIOQueue.write(u("late").clone(),0,4);drain(session);
        check(nativeEmu.feedCount()==feedBeforeDispose,"late queued input after dispose does not touch freed native state");
        int[] pal=new int[259];for(int i=0;i<256;i++)pal[i]=0x010101*i;pal[256]=0xffffff;
        GhosttyFrameAdapter wr=GhosttyFrameAdapter.create(8,300,1000,pal,pal[256],0);
        StringBuilder big=new StringBuilder();for(int i=0;i<300;i++)big.append("123456789\r\n");
        byte[] bb=u(big.toString());for(int i=0;i<bb.length;i+=4096)wr.feed(bb,i,Math.min(4096,bb.length-i));
        wr.capture();
        int wrappedMax=-1,wrappedCount=0;for(int y=0;y<300;y++)if(wr.buffer().getLineWrap(y)){wrappedMax=y;wrappedCount++;}
        System.out.println("WRAP count="+wrappedCount+" max="+wrappedMax);
        check(wrappedCount>0&&wrappedMax>=256,"wrap metadata exported beyond row 255 (count="+wrappedCount+" max="+wrappedMax+")");
        check(wr.copyText(0,-1,0,0,true,false)==null,"copy rejects negative column");
        check(wr.copyText(0,0,0,8,true,false)==null,"copy rejects column >= cols");
        check(GhosttyNative.create(2000,2000,100,pal,0xffffff,0)==0,"oversized frame rejected before allocation");
        final long h=wr.handle();final int[] rc={0};final GhosttyFrameAdapter wrf=wr;
        Thread t=new Thread(()->{wrf.close();rc[0]=wrf.handle()==h?1:0;});t.start();t.join();
        check(rc[0]==1&&wr.handle()==h&&wr.buffer()!=null,"wrong-thread adapter.close rejects and preserves owner state");
        wr.close();
        System.out.println("SESSION CORE OK checks="+checks);
    }
}
