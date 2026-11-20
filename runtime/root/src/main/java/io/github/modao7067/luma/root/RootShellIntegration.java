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

    static String startupScript(java.io.File cache, int uid) {return startupScript();}
    static String managedScript(java.io.File cache,int uid,String script,boolean ordinary){
        return (ordinary?markers()+"\n":"")+script;
    }

    static String command() {
        String quoted = "'" + startupScript().replace("'", "'\"'\"'") + "'";
        return "exec /data/local/linux/bin/ubuntu /bin/bash -c " + quoted;
    }
}
