#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import sys
sys.dont_write_bytecode=True
import argparse,json,os,pathlib,shutil,subprocess
R=pathlib.Path(__file__).resolve().parents[3]; V=R/'verification/antigravity-adapter-20261103'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',nargs=argparse.REMAINDER);a=p.parse_args();command=a.command
if command and command[0]=='--':command=command[1:]
if not command:p.error('fixture guest command required')
package='io.github.modao7067.luma';data='/data/user/0/'+package;base=data+'/cache/antigravity-adapter-20261103';host=pathlib.Path('/proc/1/root'+base);uid=os.stat('/proc/1/root'+data).st_uid
for name in ['home','workspace','tmp']:
 path=host/name;path.mkdir(parents=True,exist_ok=True);os.chown(path,uid,uid)
apk=subprocess.check_output(['android-sh','pm','path',package],text=True).strip().removeprefix('package:')
if '\n' in apk:raise SystemExit('expected one APK')
native=str(pathlib.PurePosixPath(apk).parent/'lib/arm64')
fixture=pathlib.PurePosixPath(toolenv.DEVICE_REPO+'/verification/antigravity-adapter-20261103')
launcher=toolenv.DEVICE_REPO+'/verification/gui-cli-live-20261103/noroot/drop-uid'
proot=[native+'/libproot_exec.so','--root-id','--link2symlink','--kill-on-exit','--sysvipc','-r',data+'/files/linux/debian','-w','/workspace']
for source,target in [('/dev','/dev'),('/proc','/proc'),('/sys','/sys'),(base+'/home','/root'),(base+'/workspace','/workspace'),(base+'/tmp','/tmp'),(base+'/tmp','/dev/shm'),(str(fixture),'/verification')]:proot+=['-b',source+':'+target]
proot+=['/usr/bin/env','-i','HOME=/root','GEMINI_HOME=/root/.gemini','USER=root','LOGNAME=root','PATH=/usr/local/bin:/usr/bin:/bin','LANG=C.UTF-8','TMPDIR=/tmp','NO_PROXY=127.0.0.1,localhost,::1',*command]
args=['/system/bin/env','-i','PATH=/system/bin','HOME='+base+'/home','TMPDIR='+base+'/tmp','PROOT_TMP_DIR='+base+'/tmp','PROOT_LOADER='+native+'/libproot_loader.so','PROOT_NO_SECCOMP=1','/system/bin/sh','-c','ulimit -c 0; exec "$@"','luma-antigravity-fixture',*proot]
raise SystemExit(subprocess.call(['android-sh',launcher,str(uid),str(uid),*args]))
