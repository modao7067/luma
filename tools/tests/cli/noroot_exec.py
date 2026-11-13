#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse,json,os,pathlib,subprocess
REPO=pathlib.Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--native-links',action='store_true',help='Diagnostic only: disable PRoot hardlink emulation')
parser.add_argument('--native-dir',help='Android host directory containing a fixture-only candidate executor/loader')
parser.add_argument('--accelerated',action='store_true',help='Allow the normal PRoot seccomp probe instead of forcing compatibility mode')
parser.add_argument('command',nargs=argparse.REMAINDER)
a=parser.parse_args();command=a.command
if command and command[0]=='--':command=command[1:]
if not command:parser.error('guest command is required')
package='io.github.modao7067.luma';data='/data/user/0/'+package
base=data+'/cache/cli-live-20261103';uid=os.stat('/proc/1/root'+data).st_uid
apk=subprocess.check_output(['android-sh','pm','path',package],text=True).strip().removeprefix('package:')
if '\n' in apk:raise SystemExit('expected a single installed APK')
native=a.native_dir or str(pathlib.PurePosixPath(apk).parent/'lib/arm64')
if a.native_dir and not pathlib.PurePosixPath(native).is_relative_to(pathlib.PurePosixPath(base)):
    raise SystemExit('candidate native directory must stay inside the dedicated fixture')
launcher=toolenv.DEVICE_REPO+'/verification/gui-cli-live-20261103/noroot/drop-uid'
proot=[native+'/libproot_exec.so','--root-id',*([] if a.native_links else ['--link2symlink']),'--kill-on-exit','--sysvipc','-r',data+'/files/linux/debian','-w','/workspace']
for source,target in [('/dev','/dev'),('/proc','/proc'),('/sys','/sys'),(base+'/home','/root'),(base+'/workspace','/workspace'),(base+'/tmp','/tmp'),(base+'/tmp','/dev/shm')]:proot+=['-b',source+':'+target]
proot+=['/usr/bin/env','-i','HOME=/root','USER=root','LOGNAME=root','PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin','LANG=C.UTF-8','LC_ALL=C.UTF-8','TMPDIR=/tmp','SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt','NO_PROXY=127.0.0.1,localhost,::1','LUMA_ENV=debian-proot',*command]
host=['/system/bin/env','-i','PATH=/system/bin','HOME='+base+'/home','TMPDIR='+base+'/tmp','PROOT_TMP_DIR='+base+'/tmp','PROOT_LOADER='+native+'/libproot_loader.so',*([] if a.accelerated else ['PROOT_NO_SECCOMP=1']),'/system/bin/sh','-c','exec "$@"','luma-fixture',*proot]
raise SystemExit(subprocess.call(['android-sh',launcher,str(uid),str(uid),*host]))
