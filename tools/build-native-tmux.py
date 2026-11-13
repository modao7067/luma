#!/usr/bin/env python3
import hashlib,json,pathlib,subprocess,urllib.request,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1];work=ROOT/'build/native-sdk/tmux';work.mkdir(parents=True,exist_ok=True)
packages=json.loads((ROOT/'tools/native/tmux/packages.json').read_text());licenses=[]
for package in packages:
 deb=work/package['url'].rsplit('/',1)[1]
 if not deb.is_file():urllib.request.urlretrieve(package['url'],deb)
 if hashlib.file_digest(deb.open('rb'),'sha256').hexdigest()!=package['sha256']:raise ValueError('Debian package integrity mismatch: '+package['name'])
 subprocess.run(['dpkg-deb','-x',str(deb),str(work/'root')],check=True)
 licenses.append('\n===== '+package['name']+' '+package['version']+' =====\n'+(work/'root/usr/share/doc'/package['name']/'copyright').read_text())
out=ROOT/'apps/gui/app/src/main/assets/cli_bridge/native/tmux-debian-arm64.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 z.write(work/'root/usr/bin/tmux','tmux')
 for pattern,target in [('libevent_core-2.1.so.*','libevent_core-2.1.so.7'),('libjemalloc.so.*','libjemalloc.so.2')]:
  f=next(f for f in (work/'root/usr/lib/aarch64-linux-gnu').glob(pattern) if f.is_file());z.write(f,'lib/'+target)
 z.writestr('LICENSES.txt',''.join(licenses));z.writestr('packages.json',json.dumps(packages,indent=2))
 unpacked_bytes=sum(i.file_size for i in z.infolist())
print(json.dumps({'asset':str(out.relative_to(ROOT)),'compressedBytes':out.stat().st_size,'unpackedBytes':unpacked_bytes}))
