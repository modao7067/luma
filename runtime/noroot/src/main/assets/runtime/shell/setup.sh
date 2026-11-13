#!/bin/bash
set -euo pipefail
prefix=/opt/luma/shell
mkdir -p /root/.config/bat/themes /root/.config/glow
[[ -e /root/.config/bat/themes/Luma-Morandi.tmTheme ]] || cp "$prefix/Luma-Morandi.tmTheme" /root/.config/bat/themes/
if [[ ! -e /root/.config/bat/config ]]; then printf '%s\n' '# Luma default; user configurations are preserved.' '--theme="Luma-Morandi"' >/root/.config/bat/config; fi
if [[ ! -e /root/.config/glow/glow.yml ]]; then printf '%s\n' "style: '/opt/luma/shell/glow.json'" 'pager: false' 'width: 80' >/root/.config/glow/glow.yml; fi
if command -v batcat >/dev/null 2>&1 && [[ ! -e /usr/local/bin/bat ]]; then printf '%s\n' '#!/bin/sh' 'exec /usr/bin/batcat "$@"' >/usr/local/bin/bat; chmod 755 /usr/local/bin/bat; fi
if command -v fdfind >/dev/null 2>&1 && [[ ! -e /usr/local/bin/fd ]]; then ln -s /usr/bin/fdfind /usr/local/bin/fd; fi
if command -v bat >/dev/null 2>&1; then bat cache --build >/dev/null; fi
