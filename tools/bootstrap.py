#!/usr/bin/env python3
import argparse
import fnmatch
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
LOCK = REPO / 'tools' / 'artifacts.lock.json'
STORE = REPO / 'artifacts' / 'store' / 'blobs'
TOOLS = REPO / 'artifacts' / 'toolchain'

ARTIFACT_GLOBS = [
    'runtime/*/src/main/assets/*', 'runtime/*/src/main/jniLibs', 'shared/*/jniLibs', 'shared/*/src/main/jniLibs',
    'apps/gui/app/src/main/assets/LumaGuiHan-Regular.ttf',
]
ORIGIN_RULES = [
    ('*/MapleMonoNL-NF-CN-Regular.ttf', 'fetch:fonts', 'Maple Mono NF CN release zip'),
    ('*/JetBrainsMonoNLNerdFontMono-Regular.ttf', 'fetch:fonts', 'Nerd Fonts JetBrainsMono release zip'),
    ('*/NotoSansMono-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansSymbols-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansMath-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoMusic-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansSymbols2-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoColorEmoji.ttf', 'fetch:fonts', 'googlefonts/noto-emoji (pinned commit)'),
    ('*/NotoSansArabic-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansThai-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansKannada-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansCanadianAboriginal-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/NotoSansGeorgian-Regular.ttf', 'fetch:fonts', 'notofonts/noto-fonts (pinned commit)'),
    ('*/LumaCjkForms-Regular.otf', 'build:fonts-derived', 'tools/build-terminal-fonts.py (needs Noto Sans CJK SC 2.004)'),
    ('apps/gui/app/src/main/assets/LumaGuiHan-Regular.ttf', 'build:fonts-derived', 'tools/build-gui-font.py'),
    ('runtime/noroot/src/main/assets/debian-arm64.zip.xz', 'build:rootfs', 'tools/build-basic-rootfs.py --xz <builder> <base> <assets>'),
    ('runtime/noroot/src/main/assets/rootfs-manifest.json', 'build:rootfs', 'written together with debian-arm64.zip.xz'),
    ('runtime/noroot/src/main/assets/native-sources/proot-sources.tgz', 'build:native-src', 'tools/build-native-proot.py --package-sources-only'),
    ('runtime/noroot/src/main/assets/native-sources/*.tgz', 'fetch:native-src', 'tools/fetch-native-sources.py (unmodified upstream talloc-2.4.3 source)'),
    ('runtime/noroot/src/main/assets/pnpm/*', 'fetch:pnpm', 'tools/fetch-pnpm.py (pnpm + @pnpm/exe.linux-arm64 from the npm registry)'),
    ('*/libluma_vt_jni.so', 'build:ghostty', 'shared/terminal-ghostty/build-native.py'),
    ('*/libproot_exec.so', 'build:proot', 'tools/build-native-proot.py'),
    ('*/libproot_loader.so', 'build:proot', 'tools/build-native-proot.py'),
    ('*/libluma_compute.so', 'build:compute', 'runtime/noroot/scripts/build-compute-check.sh'),
    ('*/libluma_exec.so', 'build:compute', 'runtime/noroot/scripts/build-compute-check.sh'),
    ('*/libtermux.so', 'build:termux', 'shared/terminal-emulator/build-native.sh'),
]


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for block in iter(lambda: handle.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def elf_digest(path):
    data = Path(path).read_bytes()
    if len(data) < 0x40 or data[:4] != b'\x7fELF':
        raise ValueError('Not an ELF64 file')
    shoff = struct.unpack_from('<Q', data, 0x28)[0]
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 0x3A)
    headers = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * shentsize) for i in range(shnum)]
    names_at = headers[shstrndx][4]
    digest = hashlib.sha256()
    for header in headers:
        name = data[names_at + header[0]:data.index(b'\0', names_at + header[0])].decode()
        if not name or name.startswith('.debug') or name in ('.symtab', '.strtab', '.shstrtab') or header[1] == 8:
            continue
        digest.update(name.encode() + b'\0' + data[header[4]:header[4] + header[5]])
    return digest.hexdigest()


def load_lock():
    return json.loads(LOCK.read_text())


def entry_matches(path, entry):
    if not path.is_file():
        return False
    if entry.get('hash') == 'elf-sections':
        try:
            return elf_digest(path) == entry['sha256']
        except (OSError, struct.error, ValueError, IndexError):
            return False
    return path.stat().st_size == entry['bytes'] and sha256_file(path) == entry['sha256']


def status_of(rel, entry):
    path = REPO / rel
    if not path.is_file():
        return 'missing'
    return 'ok' if entry_matches(path, entry) else 'changed'


def link_or_copy(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def cmd_status(args, quiet=False):
    lock = load_lock()
    counts = {}
    bad = []
    for rel, entry in sorted(lock['artifacts'].items()):
        state = status_of(rel, entry)
        counts.setdefault((entry['origin'], state), 0)
        counts[(entry['origin'], state)] += 1
        if state != 'ok':
            bad.append((rel, state, entry))
    if not quiet:
        for (origin, state), count in sorted(counts.items()):
            print(f'{state:8} {origin:20} {count}')
    for rel, state, entry in bad[:40]:
        print(f'  {state}: {rel}  [{entry["origin"]}] {entry.get("note", "")}')
    if len(bad) > 40:
        print(f'  ... {len(bad) - 40} more')
    total = len(lock['artifacts'])
    print(f'{total - len(bad)}/{total} artifacts verified')
    return 1 if bad else 0


def cmd_restore(args):
    lock = load_lock()
    restored = 0
    for rel, entry in lock['artifacts'].items():
        if status_of(rel, entry) == 'ok':
            continue
        blob = STORE / entry.get('fileSha256', entry['sha256'])
        if blob.is_file() and sha256_file(blob) == entry.get('fileSha256', entry['sha256']):
            link_or_copy(blob, REPO / rel)
            restored += 1
    print(f'restored {restored} file(s) from {STORE.relative_to(REPO)}')
    return 0


def cmd_stash(args):
    lock = load_lock()
    stored = 0
    for rel, entry in lock['artifacts'].items():
        if status_of(rel, entry) != 'ok':
            continue
        blob = STORE / sha256_file(REPO / rel)
        if not blob.is_file():
            link_or_copy(REPO / rel, blob)
            stored += 1
    print(f'stored {stored} new blob(s) in {STORE.relative_to(REPO)}')
    return 0


def download(url, target, sha256):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not (target.is_file() and sha256_file(target) == sha256):
        print('download', url)
        with urllib.request.urlopen(url, timeout=300) as response, open(target, 'wb') as out:
            shutil.copyfileobj(response, out)
    if sha256_file(target) != sha256:
        raise SystemExit(f'sha256 mismatch for {url}')


def ensure_toolchain(name):
    spec = load_lock()['toolchains'][name]
    archive = REPO / 'artifacts' / 'download-cache' / spec['file']
    download(spec['url'], archive, spec['sha256'])
    target = REPO / spec['extractTo']
    if not (target / spec['probe']).exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        print('extract', archive.name)
        if archive.suffix == '.zip':
            with zipfile.ZipFile(archive) as z:
                z.extractall(target.parent)
                if spec.get('unzippedAs'):
                    (target.parent / spec['unzippedAs']).rename(target)
        else:
            subprocess.run(['tar', '-xf', str(archive), '-C', str(target.parent)], check=True)
            if spec.get('unzippedAs'):
                (target.parent / spec['unzippedAs']).rename(target)
    return target


def missing(origin_prefix):
    lock = load_lock()
    return [rel for rel, e in lock['artifacts'].items()
            if e['origin'].startswith(origin_prefix) and status_of(rel, e) != 'ok']


def resolve_ndk():
    ndk_env = os.environ.get('ANDROID_NDK_HOME')
    return Path(ndk_env) if ndk_env else ensure_toolchain('ndk-r27c')


def resolve_zig():
    zig_env = os.environ.get('LUMA_ZIG') or os.environ.get('ZIG')
    if zig_env:
        candidate = Path(zig_env)
        return candidate / 'zig' if candidate.is_dir() else candidate
    return ensure_toolchain('zig-0.16.0') / 'zig'


def run_step(command, env=None):
    result = subprocess.run(command, env=env, check=False)
    if result.returncode:
        print(f'step failed ({result.returncode}): {" ".join(command)}', file=sys.stderr)
    return result.returncode


def cmd_fetch(args):
    if missing('fetch:fonts'):
        rc = run_step([sys.executable, str(REPO / 'tools/fetch-fonts.py')])
        if rc:
            return rc
    if missing('fetch:pnpm'):
        rc = run_step([sys.executable, str(REPO / 'tools/fetch-pnpm.py')])
        if rc:
            return rc
    if missing('fetch:native-src'):
        rc = run_step([sys.executable, str(REPO / 'tools/fetch-native-sources.py')])
        if rc:
            return rc
    if missing('build:native-src'):
        rc = run_step([sys.executable, str(REPO / 'tools/build-native-proot.py'), '--package-sources-only'])
        if rc:
            return rc
    if missing('build:proot') and not args.no_native:
        ndk = resolve_ndk()
        rc = run_step([sys.executable, str(REPO / 'tools/build-native-proot.py'), '--ndk', str(ndk), '--no-package-sources'])
        if rc:
            return rc
    if missing('build:termux') and not args.no_native:
        ndk = resolve_ndk()
        env = dict(os.environ, ANDROID_NDK_HOME=str(ndk))
        rc = run_step(['bash', str(REPO / 'shared/terminal-emulator/build-native.sh')], env=env)
        if rc:
            return rc
    if missing('build:ghostty') and not args.no_native:
        ndk = resolve_ndk()
        zig = resolve_zig()
        src = ensure_toolchain('ghostty-0e6afb2600d4')
        rc = run_step([sys.executable, str(REPO / 'shared/terminal-ghostty/build-native.py'),
                       '--source', str(src), '--zig', str(zig), '--ndk', str(ndk),
                       '--build-dir', str(REPO / 'artifacts/native-build/ghostty')])
        if rc:
            return rc
    return 0


def verify_imported_digests(lock):
    toolchain_file = REPO / 'tools' / 'toolchain.json'
    if toolchain_file.is_file():
        artifact_hashes = json.loads(toolchain_file.read_text()).get('artifactSha256', {})
        for key, digest in artifact_hashes.items():
            if not key.startswith('$LUMA_ROOT/artifacts/'):
                continue
            target = REPO / key[len('$LUMA_ROOT/'):]
            if not target.is_file():
                continue
            accepted = digest if isinstance(digest, list) else [digest]
            if sha256_file(target) not in accepted:
                print(f'error: imported file digest mismatch: {target.relative_to(REPO)}', file=sys.stderr)
                return 1
    for spec in lock.get('toolchains', {}).values():
        archive_name = spec.get('file')
        expected = spec.get('sha256')
        if not archive_name or not expected:
            continue
        for directory in (REPO / 'artifacts' / 'download-cache', REPO / 'artifacts' / 'toolchain'):
            candidate = directory / archive_name
            if candidate.is_file() and sha256_file(candidate) != expected:
                print(f'error: imported toolchain archive digest mismatch: {candidate.relative_to(REPO)}', file=sys.stderr)
                return 1
    return 0


def cmd_import(args):
    src = Path(args.source).resolve()
    lock = load_lock()
    copied = skipped = 0
    for rel, entry in lock['artifacts'].items():
        if status_of(rel, entry) == 'ok':
            continue
        candidate = src / rel
        if entry_matches(candidate, entry):
            dest = REPO / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(candidate, dest)
            if not entry_matches(dest, entry):
                print(f'error: copied artifact failed verification: {rel}', file=sys.stderr)
                return 1
            copied += 1
        else:
            skipped += 1
    mapping = [('build-tools/maven', 'artifacts/maven'), ('build-tools/compute-check', 'artifacts/compute-check'),
               ('rootfs', 'artifacts/rootfs')]
    for old, new in mapping:
        if (src / old).is_dir() and not (REPO / new).exists():
            try:
                shutil.copytree(src / old, REPO / new, symlinks=True, copy_function=shutil.copy2)
            except shutil.Error as exc:
                print(f'error: failed to copy {old}: {len(exc.args[0])} error(s)', file=sys.stderr)
                return 1
    legacy = src / 'build-tools'
    if legacy.is_dir() and not (REPO / 'artifacts/toolchain').exists():
        try:
            shutil.copytree(legacy, REPO / 'artifacts/toolchain', symlinks=True,
                            ignore=shutil.ignore_patterns('maven', 'compute-check'))
        except shutil.Error as exc:
            print(f'error: failed to copy build-tools: {len(exc.args[0])} error(s)', file=sys.stderr)
            return 1
    if verify_imported_digests(lock):
        return 1
    print(f'imported {copied} artifact(s) from {src}; {skipped} not available there (run `bootstrap.py fetch` / build them)')
    return 0


def cmd_record(args):
    old = load_lock() if LOCK.is_file() else {'schema': 1, 'toolchains': {}, 'artifacts': {}}
    ignored = subprocess.check_output(['git', '-C', str(REPO), 'ls-files', '-o', '-i', '--exclude-standard'],
                                      text=True).split('\n')
    artifacts = {}
    for rel in sorted(p for p in ignored if p):
        if '/build/' in rel or rel.startswith(('artifacts/', 'verification/')) or '/signing/' in rel:
            continue
        if not any(fnmatch.fnmatch(rel, g) or fnmatch.fnmatch(rel, g + '/*') for g in ARTIFACT_GLOBS):
            continue
        path = REPO / rel
        if not path.is_file():
            continue
        origin, note = 'manual', ''
        for glob, o, n in ORIGIN_RULES:
            if fnmatch.fnmatch(rel, glob):
                origin, note = o, n
                break
        entry = {'sha256': sha256_file(path), 'bytes': path.stat().st_size, 'origin': origin}
        if origin == 'build:ghostty':
            entry.update(hash='elf-sections', fileSha256=entry['sha256'], sha256=elf_digest(path))
        if note:
            entry['note'] = note
        artifacts[rel] = entry
    old['artifacts'] = artifacts
    LOCK.write_text(json.dumps(old, indent=1, sort_keys=True) + '\n')
    print(f'recorded {len(artifacts)} artifacts in {LOCK.relative_to(REPO)}')
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', nargs='?', default='all',
                        choices=['all', 'status', 'restore', 'stash', 'fetch', 'record', 'import'])
    parser.add_argument('source', nargs='?', help='import: path of the older checkout')
    parser.add_argument('--no-native', action='store_true', help='fetch: skip native builds')
    args = parser.parse_args()
    if args.command == 'status':
        return cmd_status(args)
    if args.command == 'restore':
        return cmd_restore(args)
    if args.command == 'stash':
        return cmd_stash(args)
    if args.command == 'fetch':
        return cmd_fetch(args)
    if args.command == 'record':
        return cmd_record(args)
    if args.command == 'import':
        if not args.source:
            parser.error('import needs the path of the older checkout')
        return cmd_import(args)
    cmd_restore(args)
    rc = cmd_fetch(args)
    if rc:
        return rc
    return cmd_status(args)


if __name__ == '__main__':
    sys.exit(main())
