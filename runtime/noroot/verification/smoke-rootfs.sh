#!/bin/bash
set -euo pipefail
export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8
cat /etc/os-release
test -z "$(dpkg --audit)"
bash --version | head -1
git --version
curl --version | head -1
nano --version | head -1
node --version
npm --version
node - <<'JS'
const fs = require('fs');
const cp = require('child_process');
fs.mkdirSync('/workspace/.luma-check', {recursive:true});
fs.writeFileSync('/workspace/.luma-check/file','ok');
fs.symlinkSync('file','/workspace/.luma-check/link');
if(fs.readFileSync('/workspace/.luma-check/link','utf8')!=='ok')throw Error('symlink failure');
if(cp.execFileSync('/bin/sh',['-c','printf child-ok'],{encoding:'utf8'})!=='child-ok')throw Error('child process failure');
fs.rmSync('/workspace/.luma-check',{recursive:true});
console.log('Node file, symlink and child-process checks passed');
JS
curl -fsSI --max-time 25 https://deb.debian.org/debian/ > /tmp/luma-curl-check
npm view is-number version --cache=/tmp/luma-npm-check --fetch-retries=0 --fetch-timeout=25000
echo 'Rootfs package audit, CLI, filesystem, child process, HTTPS and npm registry checks passed'
