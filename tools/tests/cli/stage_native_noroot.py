#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--fixture-dir', type=Path, default=os.environ.get('LUMA_NOROOT_STAGE_DIR'))
parser.add_argument('--guest-deps-dir', default='/opt/cli-deps')
parser.add_argument('--copy-packages', action='store_true')
args = parser.parse_args()
if not args.fixture_dir:
    parser.error('Provide --fixture-dir or LUMA_NOROOT_STAGE_DIR pointing to the host App cache fixture directory')

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = args.fixture_dir.resolve()
(FIXTURE / 'workspace').mkdir(parents=True, exist_ok=True)
TARGET = FIXTURE / 'workspace/luma-stage'
UID = (FIXTURE / 'workspace').stat().st_uid

def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)

for name in ['live_mimo_native.py', 'probe_native_atomic_link.mjs']:
    copy(ROOT / 'tools/tests/cli' / name, TARGET / 'tools/tests/cli' / name)
source = ROOT / 'apps/gui/app/src/main/assets/cli_bridge'
target = TARGET / 'apps/gui/app/src/main/assets/cli_bridge'
shutil.copytree(source, target, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
bin_dir = TARGET / 'tooling'
bin_dir.mkdir(parents=True, exist_ok=True)
entries = {
    'pi': f'/usr/local/bin/node {args.guest_deps_dir}/pi-coding-agent/dist/bundle/cli.js',
    'dsh': f'/usr/local/bin/node {args.guest_deps_dir}/dsh/lib/bin.js',
}
for name, command in entries.items():
    path = bin_dir / name
    path.write_text('#!/bin/sh\nexec ' + command + ' "$@"\n')
    path.chmod(0o755)
deps_host_dir = FIXTURE / 'workspace/cli-deps'
if args.copy_packages:
    for package, target_name in [('@earendil-works/pi-coding-agent', 'pi-coding-agent'), ('@deepseek-ai/dsh', 'dsh')]:
        source = Path('/usr/lib/node_modules') / package
        destination = deps_host_dir / target_name
        shutil.copytree(source, destination, dirs_exist_ok=True, symlinks=True)
(FIXTURE / 'home/native').mkdir(parents=True, exist_ok=True)
owned = [TARGET, FIXTURE / 'home/native']
if args.copy_packages:
    owned.append(deps_host_dir)
for root in owned:
    for path in [root, *root.rglob('*')]:
        os.chown(path, UID, UID, follow_symlinks=False)
print('Staged source, wrappers and scratch HOME for App UID', UID, 'at', TARGET)
