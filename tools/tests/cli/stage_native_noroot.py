#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--copy-packages', action='store_true')
args = parser.parse_args()

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = Path('/proc/1/root/data/user/0/io.github.modao7067.luma/cache/cli-live-20261103')
TARGET = FIXTURE / 'workspace/native/luma'
UID = (FIXTURE / 'workspace').stat().st_uid

def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)

for name in ['live_mimo_native.py', 'probe_native_atomic_link.mjs']:
    copy(ROOT / 'tools/tests/cli' / name, TARGET / 'tools/tests/cli' / name)
source = ROOT / 'apps/gui/app/src/main/assets/cli_bridge'
target = TARGET / 'apps/gui/app/src/main/assets/cli_bridge'
shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
bin_dir = TARGET / 'verification/gui-cli-adapters-20261102/tooling'
bin_dir.mkdir(parents=True, exist_ok=True)
entries = {
    'pi': '/usr/local/bin/node /workspace/native/deps/pi-coding-agent/dist/bundle/cli.js',
    'dsh': '/usr/local/bin/node /workspace/native/deps/dsh/lib/bin.js',
}
for name, command in entries.items():
    path = bin_dir / name
    path.write_text('#!/bin/sh\nexec ' + command + ' "$@"\n')
    path.chmod(0o755)
if args.copy_packages:
    for package, target_name in [('@earendil-works/pi-coding-agent', 'pi-coding-agent'), ('@deepseek-ai/dsh', 'dsh')]:
        source = Path('/usr/lib/node_modules') / package
        destination = FIXTURE / 'workspace/native/deps' / target_name
        shutil.copytree(source, destination, dirs_exist_ok=True, symlinks=True)
(FIXTURE / 'home/native').mkdir(parents=True, exist_ok=True)
owned = [TARGET, FIXTURE / 'home/native']
if args.copy_packages: owned.append(FIXTURE / 'workspace/native/deps')
for root in owned:
    for path in [root, *root.rglob('*')]: os.chown(path, UID, UID, follow_symlinks=False)
print('Staged source, wrappers and scratch HOME for App UID', UID, 'at /workspace/native/luma')
