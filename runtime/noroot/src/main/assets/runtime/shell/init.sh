[[ $- == *i* && -t 0 && -t 1 && ${TERM:-dumb} != dumb && ${LUMA_PLAIN_SHELL:-0} != 1 ]] || return 0
[[ ${__luma_readline_tools_loaded:-0} != 1 ]] || return 0
__luma_readline_tools_loaded=1
if [[ ! ${BASH_COMPLETION_VERSINFO[*]-} && -r /etc/profile.d/bash_completion.sh ]]; then
    source /etc/profile.d/bash_completion.sh
fi
if [[ -r /usr/share/doc/fzf/examples/key-bindings.bash ]]; then
    source /usr/share/doc/fzf/examples/key-bindings.bash
fi
