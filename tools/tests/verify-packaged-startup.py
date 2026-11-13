#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse
import json
import re
import shutil
import subprocess

repo = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target', choices=['gui', 'luma'], default='luma')
parser.add_argument('--allow-font-change',action='store_true',help='Validate upgrade even when font assets intentionally change')
parser.add_argument('--previous-apk', type=Path, help='Require cache invalidation across two APKs with identical fonts')
args = parser.parse_args()
policy = json.loads((repo/'tools/release-policy.json').read_text())['gui' if args.target == 'luma' else args.target]
apk = repo/policy['apk']
manifest = subprocess.check_output([toolenv.AAPT2, 'dump', 'xmltree', str(apk),
                                    '--file', 'AndroidManifest.xml'], text=True)
match = re.search(r'android:appComponentFactory[^\n]*?="([^"]+)"', manifest)
assert match, 'APK has no startup factory'
factory = match.group(1)
if factory.startswith('.'): factory = policy['package'] + factory
elif '.' not in factory: factory = policy['package'] + '.' + factory
work = repo/'tools/tests/packaged-startup/build'/args.target
classes = work/'classes'
dex = work/'dex'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True)
dex.mkdir(exist_ok=True)
(work/'startup-factory.txt').write_text(factory + '\n')
(work/'allow-font-change.txt').write_text('yes' if args.allow_font_change else 'no')
previous = args.previous_apk.resolve() if args.previous_apk else None
(work/'previous-apk.txt').write_text((toolenv.DEVICE_REPO+'/' + str(previous.relative_to(repo))) if previous else '')
sdk = toolenv.ANDROID_JAR
subprocess.run(['javac', '--release', '17', '-cp', sdk, '-d', str(classes),
                str(repo/'tools/tests/packaged-startup/PackagedStartupCheck.java')], check=True)
jar = work/'checks.jar'
subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
subprocess.run([toolenv.JAVA, '-Xmx256m', '-cp',
                toolenv.D8_JAR, 'com.android.tools.r8.D8',
                '--lib', sdk, '--min-api', '31', '--output', str(dex), str(jar)], check=True)
def host(path): return toolenv.DEVICE_REPO+'/' + str(path.relative_to(repo))
log = work/'packaged-startup.txt'
log.unlink(missing_ok=True)
result = subprocess.run(['android-sh', 'sh', host(repo/'tools/tests/terminal/run-android.sh'),
                         host(dex), host(apk), 'PackagedStartupCheck', host(work)], timeout=30)
output = log.read_text() if log.exists() else '(no startup log)'
print('Factory:', factory)
print(output)
assert result.returncode == 0 and 'ALL PASS' in output and 'AssertionError' not in output, 'Packaged startup failed'
