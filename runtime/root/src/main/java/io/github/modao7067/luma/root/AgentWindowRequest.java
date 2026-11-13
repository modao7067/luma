package io.github.modao7067.luma.root;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class AgentWindowRequest {
    static final String ACTION_OPEN_CLAUDE="io.github.modao7067.luma.root.OPEN_CLAUDE";
    static final String ACTION_CLOSE_CLAUDE="io.github.modao7067.luma.root.CLOSE_CLAUDE";
    static final String EXTRA_SESSION="claude_session";
    static final String EXTRA_TITLE="window_title";
    static final int MAX_TITLE_LENGTH=128;
    private static final String SESSION_ID="[0-9a-fA-F]{8}";

    final String sessionId;
    final String title;
    final String windowId;

    private AgentWindowRequest(String sessionId,String title){
        this.sessionId=sessionId;
        this.title=title;
        this.windowId=windowId(sessionId);
    }

    static AgentWindowRequest parse(String rawSession,String rawTitle){
        if(rawSession==null||rawTitle==null)return null;
        if(!rawSession.matches(SESSION_ID))return null;
        if(rawTitle.isEmpty()||rawTitle.codePointCount(0,rawTitle.length())>MAX_TITLE_LENGTH)return null;
        for(int i=0;i<rawTitle.length();i++)if(Character.isISOControl(rawTitle.charAt(i)))return null;
        return new AgentWindowRequest(rawSession.toLowerCase(Locale.ROOT),rawTitle);
    }

    static AgentWindowRequest parseClose(String rawSession){
        return parse(rawSession,rawSession==null?"":"Claude "+rawSession);
    }

    static String windowId(String sessionId){
        return UUID.nameUUIDFromBytes(("luma.claude.agent:"+sessionId).getBytes(StandardCharsets.UTF_8))
            .toString().replace("-","");
    }

    String startupScript(){
        return "/bin/bash --login -c 'cd /workspace && exec claude attach "+sessionId+"'";
    }
}
