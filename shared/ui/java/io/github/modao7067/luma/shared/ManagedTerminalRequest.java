package io.github.modao7067.luma.shared;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ManagedTerminalRequest {
    public static final String EXTRA_TICKET="io.github.modao7067.luma.gui.TERMINAL_TICKET";
    public final String windowId,title,cwd,script;
    public final String terminalMode;
    public final String sessionId,instanceId;
    private final String environment;
    private ManagedTerminalRequest(JSONObject value)throws Exception {
        environment=value.getString("environment");
        windowId=value.getString("windowId");title=value.getString("title");cwd=value.getString("cwd");script=value.getString("script");
        terminalMode=value.optString("terminalMode","legacy");
        sessionId=value.optString("sessionId","");
        instanceId=value.optString("instanceId","");
        if(!windowId.matches("[a-f0-9]{32}")||title.length()>128||!cwd.startsWith("/")||cwd.indexOf('\0')>=0||script.length()>24*1024||sessionId.length()>128||sessionId.indexOf('\0')>=0||instanceId.length()>128||instanceId.indexOf('\0')>=0)
            throw new IOException(com.termux.terminal.LumaLang.tr("终端接入请求无效","Invalid terminal attach request"));
        if(!"legacy".equals(terminalMode)&&!"ordinary".equals(terminalMode)&&!"session".equals(terminalMode))
            throw new IOException(com.termux.terminal.LumaLang.tr("终端接入模式无效","Invalid terminal attach mode"));
    }
    public boolean isSessionTerminal(){return "session".equals(terminalMode);}
    public boolean isOrdinaryTerminal(){return "ordinary".equals(terminalMode);}
    public String save(){
        try{
            JSONObject value=new JSONObject().put("environment",environment).put("windowId",windowId).put("title",title).put("cwd",cwd).put("script",script).put("terminalMode",terminalMode);
            if(!sessionId.isEmpty())value.put("sessionId",sessionId);
            if(!instanceId.isEmpty())value.put("instanceId",instanceId);
            return value.toString();
        }
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public static ManagedTerminalRequest restore(String saved,String expected)throws IOException {
        if(saved==null)return null;
        try {ManagedTerminalRequest request=new ManagedTerminalRequest(new JSONObject(saved));if(!expected.equals(request.environment))throw new IOException(com.termux.terminal.LumaLang.tr("终端运行环境不匹配","Terminal environment mismatch"));return request;}
        catch(IOException error){throw error;}catch(Exception error){throw new IOException(com.termux.terminal.LumaLang.tr("无法恢复终端接入请求","Failed to restore terminal attach request"),error);}
    }
    public static ManagedTerminalRequest take(Context context,Intent intent,String environment)throws IOException {
        if(intent==null)return null;
        String id=intent.getStringExtra(EXTRA_TICKET);
        if(id==null)return null;
        intent.removeExtra(EXTRA_TICKET);
        if(!id.matches("[a-f0-9]{64}"))throw new IOException(com.termux.terminal.LumaLang.tr("终端接入请求无效","Invalid terminal attach request"));
        File file=new File(new File(context.getFilesDir(),"agent-terminal-requests"),id+".json");
        if(!file.isFile())return null;
        try {
            if(file.length()>32*1024)throw new IOException(com.termux.terminal.LumaLang.tr("终端接入请求过长","Terminal attach request is too large"));
            JSONObject value=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(!environment.equals(value.getString("environment")))throw new IOException(com.termux.terminal.LumaLang.tr("终端运行环境不匹配","Terminal environment mismatch"));
            ManagedTerminalRequest request=new ManagedTerminalRequest(value);
            if(!file.delete())throw new IOException(com.termux.terminal.LumaLang.tr("无法消费终端接入请求","Failed to consume terminal attach request"));
            return request;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException(com.termux.terminal.LumaLang.tr("终端接入请求无效","Invalid terminal attach request"),e);}
    }
}
