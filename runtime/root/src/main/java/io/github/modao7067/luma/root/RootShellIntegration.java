package io.github.modao7067.luma.root;

final class RootShellIntegration {
    static String startupScript() {
        return "cd /workspace || exit\n"+markers()+"\nexec /bin/bash --login";
    }
    private static String markers() {
        return String.join("\n",
            "export COLORTERM=truecolor",
            "__luma_root_command_finished() {",
            "  local result=$?",
            "  export -n -f __luma_root_command_finished",
            "  export -n PROMPT_COMMAND PS0",
            "  if [ \"${__luma_root_seen_prompt:-0}\" = 1 ]; then printf '\\033]133;D;%s\\007' \"$result\"; fi",
            "  __luma_root_seen_prompt=1",
            "  return \"$result\"",
            "}",
            "export -f __luma_root_command_finished",
            "export PROMPT_COMMAND=__luma_root_command_finished",
            "export PS0=$'\\e]133;C\\a'\"${PS0-}\"");
    }

    static String startupScript(java.io.File cache, int uid) {return frameEnvironment(cache,uid)+startupScript();}
    static String managedScript(java.io.File cache,int uid,String script,boolean ordinary){
        return frameEnvironment(cache,uid)+(ordinary?markers()+"\n":"")+script;
    }
    private static String frameEnvironment(java.io.File cache, int uid) {
        String host = new java.io.File(cache,"kitty-frames").getAbsolutePath();
        String writer = "/proc/1/root" + host;
        String env = "export TERMINAL_BROWSER_FRAME_DIR="+quote(writer)+"\n"+
            "export TERMINAL_BROWSER_FRAME_HOST_DIR="+quote(host)+"\n"+
            "export TERMINAL_BROWSER_FRAME_UID="+uid+"\n";
        return env;
    }
    private static String quote(String value) { return "'"+value.replace("'","'\"'\"'")+"'"; }

    static String command() {
        String quoted = "'" + startupScript().replace("'", "'\"'\"'") + "'";
        return "exec /data/local/linux/bin/ubuntu /bin/bash -c " + quoted;
    }
}
