#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def file_sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download_atomic(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=target.name + '.', suffix='.tmp', dir=target.parent)
    tmp_path = pathlib.Path(tmp_name)
    try:
        with os.fdopen(fd, 'wb') as out, urllib.request.urlopen(url, timeout=120) as response:
            shutil.copyfileobj(response, out)
        os.replace(tmp_path, target)
    except BaseException:
        tmp_path.unlink(missing_ok=True)
        raise


def ensure_deb(package, work):
    deb = work / package['url'].rsplit('/', 1)[1]
    for attempt in range(2):
        from_cache = deb.is_file()
        if not from_cache:
            download_atomic(package['url'], deb)
        if file_sha256(deb) == package['sha256']:
            return deb
        deb.unlink(missing_ok=True)
        if from_cache and attempt == 0:
            continue
        raise ValueError('Debian package integrity mismatch: ' + package['name'])
    raise ValueError('Debian package integrity mismatch: ' + package['name'])


def main():
    work = ROOT / 'build/native-sdk/tmux'
    work.mkdir(parents=True, exist_ok=True)
    root_dir = work / 'root'
    if root_dir.exists():
        shutil.rmtree(root_dir)
    root_dir.mkdir(parents=True, exist_ok=True)
    packages = json.loads((ROOT / 'tools/native/tmux/packages.json').read_text())
    licenses = []
    for package in packages:
        deb = ensure_deb(package, work)
        subprocess.run(['dpkg-deb', '-x', str(deb), str(root_dir)], check=True)
        licenses.append(
            '\n===== ' + package['name'] + ' ' + package['version'] + ' =====\n'
            + (root_dir / 'usr/share/doc' / package['name'] / 'copyright').read_text()
        )
    out = ROOT / 'apps/gui/app/src/main/assets/cli_bridge/native/tmux-debian-arm64.zip'
    out.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=out.name + '.', suffix='.tmp', dir=out.parent)
    os.close(fd)
    tmp_out = pathlib.Path(tmp_name)
    try:
        with zipfile.ZipFile(tmp_out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            z.write(root_dir / 'usr/bin/tmux', 'tmux')
            for pattern, target in [('libevent_core-2.1.so.*', 'libevent_core-2.1.so.7'), ('libjemalloc.so.*', 'libjemalloc.so.2')]:
                f = next(f for f in (root_dir / 'usr/lib/aarch64-linux-gnu').glob(pattern) if f.is_file())
                z.write(f, 'lib/' + target)
            z.writestr('LICENSES.txt', ''.join(licenses))
            z.writestr('packages.json', json.dumps(packages, indent=2))
            unpacked_bytes = sum(i.file_size for i in z.infolist())
        os.replace(tmp_out, out)
    except BaseException:
        tmp_out.unlink(missing_ok=True)
        raise
    print(json.dumps({'asset': str(out.relative_to(ROOT)), 'compressedBytes': out.stat().st_size, 'unpackedBytes': unpacked_bytes}))


if __name__ == '__main__':
    main()
