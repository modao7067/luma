package io.github.modao7067.luma;

import android.content.*;
import android.os.Looper;
import com.termux.terminal.TerminalSession;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

public final class TerminalModesCheck {
    static int checks;
    static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static ManagedTerminalRequest request(String mode,String id,String script)throws Exception {
        JSONObject json=new JSONObject().put("environment","noroot").put("windowId",id).put("title","fixture")
            .put("cwd","/workspace").put("script",script);
        if(mode!=null)json.put("terminalMode",mode);
        return ManagedTerminalRequest.restore(json.toString(),"noroot");
    }
    static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void exited(TerminalSession session)throws Exception {Field f=TerminalSession.class.getDeclaredField("mShellPid");f.setAccessible(true);f.setInt(session,-1);}
    static ShellService ready(){ShellService service=new ShellService();service.environmentReady=true;return service;}
    static void attach(ShellService service,Context context)throws Exception {
        java.lang.reflect.Method m=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);m.setAccessible(true);m.invoke(service,context);
    }
    public static void main(String[] args)throws Exception {
        System.setOut(new PrintStream(new File(args[1],"terminal-modes-result.txt")));
        try {
            Looper.prepareMainLooper();
            File data=new File(args[1],"private-fixture");data.mkdirs();
            Context context=new ContextWrapper(null){public File getFilesDir(){return data;}};
            ManagedTerminalRequest tui=request("session",A,"fixture-tui-original");
            ManagedTerminalRequest ordinary=request("ordinary",B,"fixture-shell-first");
            ShellService service=new ShellService();attach(service,context);
            check(service.openManagedSession(tui)==null&&service.getSessions().isEmpty(),"not-ready launch stays pending without a shell fallback");
            service.environmentReady=true;
            TerminalSession normalA=service.openOrdinarySession(null);
            service.createSession();TerminalSession normalB=service.session;
            service.selectSession(normalA);
            TerminalSession dedicatedA=service.openManagedSession(tui);
            check(service.openManagedSession(tui)==dedicatedA&&service.getSessions().size()==3,"same-session TUI entry is stable/idempotent");
            check(service.getOrdinarySessions().equals(List.of(normalA,normalB)),"normal list/count excludes all dedicated TUI windows");
            check(service.allowsSelection(tui,dedicatedA)&&!service.allowsSelection(tui,normalA)&&!service.allowsSelection(null,dedicatedA),"dedicated and ordinary selection guards reject cross-domain targets");
            check(service.openManagedSession(ordinary)==normalA,"ordinary GUI entry restores last ordinary selection, not last TUI");
            service.selectSession(normalB);
            ManagedTerminalRequest updated=request("session",A,"fixture-tui-new-server");
            check(service.openManagedSession(updated)==dedicatedA,"new server command updates reconnect metadata without duplicating a live TUI");
            TerminalSession dedicatedB=service.openManagedSession(request("session","cccccccccccccccccccccccccccccccc","fixture-tui-other"));
            check(!service.allowsSelection(tui,dedicatedB),"one session cannot select another session's TUI");
            check(service.openManagedSession(request("ordinary","dddddddddddddddddddddddddddddddd","different-chat-cwd"))==normalB,"chat changes do not create/reset the shared normal terminal");
            check(!dedicatedA.isEmulatorDisposed()&&!dedicatedB.isEmulatorDisposed(),"switching domains never closes/disposes either dedicated session");
            service.closeSession(normalB);
            check(service.session==normalA&&service.openManagedSession(ordinary)==normalA,"closing selected normal window chooses only another normal window");
            service.closeSession(normalA);
            check(service.session==null&&service.getOrdinarySessions().isEmpty()&&service.getSessions().size()==2,"closing final normal window never selects/stops hidden dedicated windows");
            check(!service.stopAfterLastWindow(ordinary)&&!service.stopAfterLastWindow(null),"no entrance may stop the service while dedicated terminals still exist");
            check(!dedicatedA.isEmulatorDisposed()&&!dedicatedB.isEmulatorDisposed(),"final-normal-window close preserves dedicated terminal objects");
            TerminalSession normalC=service.openManagedSession(ordinary);
            check(normalC!=dedicatedA&&normalC!=dedicatedB&&service.getOrdinarySessions().equals(List.of(normalC)),"reentering empty normal domain creates only a normal window");
            exited(normalC);
            check(service.openManagedSession(ordinary)==normalC&&!normalC.isEmulatorDisposed(),"ordinary reentry preserves an ended normal selection instead of silently respawning");
            TerminalSession normalReplacement=service.reconnectSession(normalC);
            check(normalReplacement!=normalC&&service.getOrdinarySessions().equals(List.of(normalReplacement)),"explicit ordinary reconnect keeps its replacement in the normal domain");
            normalC=normalReplacement;
            exited(dedicatedA);
            TerminalSession replacement=service.openManagedSession(updated);
            check(replacement!=dedicatedA&&dedicatedA.isEmulatorDisposed(),"ended dedicated terminal reconnects within its stable identity");
            check(java.util.Arrays.asList((String[])field(replacement,"mArgs")).contains("fixture-tui-new-server"),"dedicated reconnect uses latest official TUI command, never a normal shell");
            check(service.getOrdinarySessions().equals(List.of(normalC))&&service.allowsSelection(tui,replacement),"replacement remains dedicated and does not alter normal selection");
            ManagedTerminalRequest restored=ManagedTerminalRequest.restore(updated.save(),"noroot");
            service.openManagedSession(ordinary);
            check(service.openManagedSession(restored)==replacement,"Activity save/restore or service rebind selects original dedicated target");
            ShellService restarted=ready();attach(restarted,context);
            TerminalSession afterRestart=restarted.openManagedSession(restored);
            check(restarted.getSessions().equals(List.of(afterRestart))&&restarted.getOrdinarySessions().isEmpty(),"fresh service restores dedicated request without creating an ordinary substitute");
            TerminalSession restartedNormal=restarted.openManagedSession(ordinary);
            check(restartedNormal!=afterRestart&&restarted.getSessions().size()==2,"ordinary entry after service restart stays separate");
            ManagedTerminalRequest legacy=request(null,"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","fixture-legacy");
            restarted.closeSession(restartedNormal);restarted.closeSession(afterRestart);
            check(!restarted.stopAfterLastWindow(ordinary),"GUI ordinary final-close retains service for hosted GUI CLI processes even when all PTYs are empty");
            check(restarted.stopAfterLastWindow(null)&&restarted.stopAfterLastWindow(legacy),"plain and legacy final-close retain the original service-shutdown contract");
            check("legacy".equals(legacy.terminalMode)&&!legacy.isSessionTerminal()&&!legacy.isOrdinaryTerminal(),"old ticket without mode keeps the legacy contract");
            TerminalSession legacyWindow=service.openManagedSession(legacy);
            check(service.getOrdinarySessions().contains(legacyWindow)&&service.openManagedSession(legacy)==legacyWindow,"old managed-window callers remain usable with normal multiwindow behavior");
            String root=legacy.save().replace("\"noroot\"","\"root\"");
            check("legacy".equals(ManagedTerminalRequest.restore(root,"root").terminalMode),"shared request still restores Root legacy payloads");
            try{request("invalid",A,"fixture");throw new AssertionError("unknown mode accepted");}catch(IOException expected){checks++;System.out.println("PASS invalid mode fails rather than opening another domain");}
            File tickets=new File(data,"agent-terminal-requests");tickets.mkdirs();String ticket="f".repeat(64);
            File file=new File(tickets,ticket+".json");Files.writeString(file.toPath(),tui.save());
            Intent intent=new Intent().putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket);
            check(ManagedTerminalRequest.take(context,intent,"noroot").isSessionTerminal()&&!file.exists()&&!intent.hasExtra(ManagedTerminalRequest.EXTRA_TICKET),"ticket consumption preserves domain and removes file/Intent capability");
            check(ManagedTerminalRequest.take(context,new Intent().putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket),"noroot")==null,"consumed launch capability never replays");
            Files.writeString(file.toPath(),tui.save());
            try{ManagedTerminalRequest.take(context,new Intent().putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket),"root");throw new AssertionError("environment mismatch accepted");}catch(IOException expected){check(file.isFile(),"environment mismatch refuses request without consuming it");}
            for(TerminalSession session:service.getSessions())check(session.getEmulator()==null&&session.getPid()<=0,"fixture never started/sized a PTY or engine");
            System.out.println("ALL PASS checks="+checks+"; real ShellService routing, synthetic lazy sessions, no app/PTY/CLI/keepalive operations");
        }catch(Throwable error){error.printStackTrace(System.out);System.exit(1);}
    }
}
