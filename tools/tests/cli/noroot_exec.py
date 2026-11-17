#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse, os, pathlib, subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--package', default='io.github.modao7067.luma')
parser.add_argument('--cache-subdir', default='noroot-cli-fixture')
parser.add_argument('--launcher', default=os.environ.get('LUMA_DROP_UID_LAUNCHER'))
parser.add_argument('--native-links', action='store_true')
parser.add_argument('--native-dir')
parser.add_argument('--accelerated', action='store_true')
parser.add_argument('command', nargs=argparse.REMAINDER)
a = parser.parse_args()
command = a.command
if command and command[0] == '--':
    command = command[1:]
if not command:
    parser.error('guest command is required')
if not a.launcher:
    parser.error('Provide --launcher or LUMA_DROP_UID_LAUNCHER pointing to the host drop-uid helper')

data = '/data/user/0/' + a.package
base = data + '/cache/' + a.cache_subdir
uid = os.stat('/proc/1/root' + data).st_uid
apk = subprocess.check_output(['android-sh', 'pm', 'path', a.package], text=True).strip().removeprefix('package:')
if '\n' in apk:
    raise SystemExit('expected a single installed APK')
native = a.native_dir or str(pathlib.PurePosixPath(apk).parent / 'lib/arm64')
if a.native_dir and not pathlib.PurePosixPath(native).is_relative_to(pathlib.PurePosixPath(base)):
    raise SystemExit('candidate native directory must stay inside the dedicated fixture')
proot = [native + '/libproot_exec.so', '--root-id', *([] if a.native_links else ['--link2symlink']), '--kill-on-exit', '--sysvipc', '-r', data + '/files/linux/debian', '-w', '/workspace']
for source, target in [('/dev', '/dev'), ('/proc', '/proc'), ('/sys', '/sys'), (base + '/home', '/root'), (base + '/workspace', '/workspace'), (base + '/tmp', '/tmp'), (base + '/tmp', '/dev/shm')]:
    proot += ['-b', source + ':' + target]
proot += ['/usr/bin/env', '-i', 'HOME=/root', 'USER=root', 'LOGNAME=root', 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'LANG=C.UTF-8', 'LC_ALL=C.UTF-8', 'TMPDIR=/tmp', 'SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt', 'NO_PROXY=127.0.0.1,localhost,::1', 'LUMA_ENV=debian-proot', *command]
host = ['/system/bin/env', '-i', 'PATH=/system/bin', 'HOME=' + base + '/home', 'TMPDIR=' + base + '/tmp', 'PROOT_TMP_DIR=' + base + '/tmp', 'PROOT_LOADER=' + native + '/libproot_loader.so', *([] if a.accelerated else ['PROOT_NO_SECCOMP=1']), '/system/bin/sh', '-c', 'exec "$@"', 'luma-fixture', *proot]
raise SystemExit(subprocess.call(['android-sh', a.launcher, str(uid), str(uid), *host]))
