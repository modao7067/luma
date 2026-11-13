#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import zipfile

def package(root: Path, assets: Path):
    if not (root / 'etc/debian_version').is_file():
        raise SystemExit('Not a prepared Debian rootfs')
    if not (root / 'usr/local/bin/node').is_file():
        raise SystemExit('Node.js is missing')
    assets.mkdir(parents=True, exist_ok=True)
    destination = assets / 'debian-arm64.zip'
    temp = destination.with_suffix('.zip.partial')
    modes, links, total, count = [], [], 0, 0
    excluded = {'dev', 'proc', 'sys', 'debootstrap', '.luma-ready'}
    with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED,
                         compresslevel=6, allowZip64=True) as archive:
        for directory, dirs, files in os.walk(root, followlinks=False):
            dirs.sort(); files.sort()
            if Path(directory) == root:
                dirs[:] = [n for n in dirs if n not in excluded]
                files = [n for n in files if n not in excluded]
            for name in dirs + files:
                path = Path(directory) / name
                relative = path.relative_to(root).as_posix()
                if name.startswith(('.l2s.', '.proot.l2s.')) or relative == 'var/lib/dpkg/status-old':
                    continue
                mode = path.lstat().st_mode
                if stat.S_ISLNK(mode):
                    target = os.readlink(path)
                    if target.startswith(('/data/', '/proc/1/root', str(root))) or '.l2s.' in target:
                        raise SystemExit(f'Host or link2symlink path in symlink: {relative} -> {target}')
                    links.append([relative, target])
                elif stat.S_ISDIR(mode):
                    archive.writestr(relative + '/', b'')
                    modes.append([relative, stat.S_IMODE(mode) & 0o1777])
                elif stat.S_ISREG(mode):
                    archive.write(path, relative)
                    modes.append([relative, stat.S_IMODE(mode) & 0o1777])
                    total += path.stat().st_size; count += 1
                else:
                    raise SystemExit(f'Unexpected special file: {relative}')
        for name in ('dev', 'dev/shm', 'proc', 'sys'):
            archive.writestr(name + '/', b''); modes.append([name, 0o755])
    temp.replace(destination)
    digest = hashlib.file_digest(destination.open('rb'), 'sha256').hexdigest()
    manifest = dict(format=1, distro='Debian 13 (trixie)', arch='arm64',
                    sha256=digest, unpackedBytes=total, regularFiles=count,
                    modes=modes, symlinks=links)
    (assets / 'rootfs-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({k: v for k, v in manifest.items() if k not in ('modes', 'symlinks')}, indent=2))
    print(f'Compressed archive: {destination.stat().st_size / 1024 / 1024:.1f} MiB')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rootfs', type=Path)
    parser.add_argument('assets', type=Path)
    args = parser.parse_args()
    package(args.rootfs.resolve(), args.assets.resolve())
