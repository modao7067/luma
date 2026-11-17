#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import sys
sys.dont_write_bytecode = True
import argparse, os, pathlib, subprocess

p = argparse.ArgumentParser()
p.add_argument('--package', default='io.github.modao7067.luma')
p.add_argument('--cache-subdir', default='antigravity-fixture')
p.add_argument('--fixture-dir', default=os.environ.get('LUMA_NOROOT_FIXTURE_DIR'))
p.add_argument('--launcher', default=os.environ.get('LUMA_DROP_UID_LAUNCHER'))
p.add_argument('command', nargs=argparse.REMAINDER)
a = p.parse_args()
command = a.command
if command and command[0] == '--':
    command = command[1:]
if not command:
    p.error('fixture guest command required')
if not a.launcher:
    p.error('Provide --launcher or LUMA_DROP_UID_LAUNCHER pointing to the host drop-uid helper')

data = '/data/user/0/' + a.package
base = data + '/cache/' + a.cache_subdir
host = pathlib.Path('/proc/1/root' + base)
uid = os.stat('/proc/1/root' + data).st_uid
for name in ['home', 'workspace', 'tmp']:
    path = host / name
    path.mkdir(parents=True, exist_ok=True)
    os.chown(path, uid, uid)
apk = subprocess.check_output(['android-sh', 'pm', 'path', a.package], text=True).strip().removeprefix('package:')
if '\n' in apk:
    raise SystemExit('expected one APK')
native = str(pathlib.PurePosixPath(apk).parent / 'lib/arm64')
proot = [native + '/libproot_exec.so', '--root-id', '--link2symlink', '--kill-on-exit', '--sysvipc', '-r', data + '/files/linux/debian', '-w', '/workspace']
binds = [('/dev', '/dev'), ('/proc', '/proc'), ('/sys', '/sys'), (base + '/home', '/root'), (base + '/workspace', '/workspace'), (base + '/tmp', '/tmp'), (base + '/tmp', '/dev/shm')]
if a.fixture_dir:
    binds.append((str(a.fixture_dir), '/fixture'))
for source, target in binds:
    proot += ['-b', source + ':' + target]
proot += ['/usr/bin/env', '-i', 'HOME=/root', 'GEMINI_HOME=/root/.gemini', 'USER=root', 'LOGNAME=root', 'PATH=/usr/local/bin:/usr/bin:/bin', 'LANG=C.UTF-8', 'TMPDIR=/tmp', 'NO_PROXY=127.0.0.1,localhost,::1', *command]
args = ['/system/bin/env', '-i', 'PATH=/system/bin', 'HOME=' + base + '/home', 'TMPDIR=' + base + '/tmp', 'PROOT_TMP_DIR=' + base + '/tmp', 'PROOT_LOADER=' + native + '/libproot_loader.so', 'PROOT_NO_SECCOMP=1', '/system/bin/sh', '-c', 'ulimit -c 0; exec "$@"', 'luma-antigravity-fixture', *proot]
raise SystemExit(subprocess.call(['android-sh', a.launcher, str(uid), str(uid), *args]))
