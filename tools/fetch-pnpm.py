#!/usr/bin/env python3
COMMAND_DESCRIPTION = """Download the pnpm tree that Luma bundles into the Debian runtime.

The tree in runtime/noroot/src/main/assets/pnpm is exactly two packages from the public npm
registry, unpacked as npm would install them:

    pnpm@VERSION                      -> pnpm/
    @pnpm/exe.linux-arm64@VERSION     -> pnpm/node_modules/@pnpm/exe.linux-arm64/

Both tarballs are checked against the sha512 integrity values pinned below, and every unpacked
file is checked again by tools/bootstrap.py against tools/artifacts.lock.json.

Usage: python3 tools/fetch-pnpm.py [--cache DIR]
"""
import argparse
import base64
import hashlib
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'runtime/noroot/src/main/assets/pnpm'
REGISTRY = 'https://registry.npmjs.org/'
VERSION = '12.15.0'

PACKAGES = [
    (f'pnpm/-/pnpm-{VERSION}.tgz', '.',
     'sha512-s5UdbW/0qfE9x89WzSjSuOs328ycaOUuHhGRhz6p+CoBcoerULqtxczElPA2xnwOR+sTYIwvo29HcRth6hKOYQ=='),
    (f'@pnpm/exe.linux-arm64/-/exe.linux-arm64-{VERSION}.tgz', 'node_modules/@pnpm/exe.linux-arm64',
     'sha512-qML/8aCXslh8td/w4U+HJNqUcZ2+m6EfTbWScIzZRmyPsRFN+xB4LP1U/hymxc1iSJiKtG56HJx2fvUErWFIGw=='),
]

def download(url, cache):
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit('/', 1)[-1]
    if not target.exists():
        print('download', url)
        with urllib.request.urlopen(url, timeout=300) as response:
            target.write_bytes(response.read())
    return target

def integrity(path):
    return 'sha512-' + base64.b64encode(hashlib.sha512(path.read_bytes()).digest()).decode()

def unpack(tarball, dest):
    with tarfile.open(tarball, 'r:gz') as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            parts = PurePosixPath(member.name).parts
            if len(parts) < 2 or parts[0] != 'package' or '..' in parts or member.name.startswith('/'):
                raise SystemExit(f'FAIL unexpected entry {member.name!r} in {tarball.name}')
            out = dest.joinpath(*parts[1:])
            out.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as src, open(out, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            out.chmod(0o755 if member.mode & 0o111 else 0o644)

def main():
    parser = argparse.ArgumentParser(description=COMMAND_DESCRIPTION, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cache', type=Path, default=ROOT / 'artifacts' / 'download-cache')
    args = parser.parse_args()
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=TARGET.parent, prefix='.pnpm-') as tmp:
        staging = Path(tmp) / 'pnpm'
        for path, subdir, pinned in PACKAGES:
            tarball = download(REGISTRY + path, args.cache)
            if integrity(tarball) != pinned:
                tarball.unlink()
                print(f'FAIL {tarball.name}: integrity mismatch (cached copy removed)', file=sys.stderr)
                return 1
            unpack(tarball, staging / subdir)
            print('ok  ', tarball.name)
        if TARGET.exists():
            shutil.rmtree(TARGET)
        staging.rename(TARGET)
    print('pnpm', VERSION, '->', TARGET.relative_to(ROOT))
    return 0

if __name__ == '__main__':
    sys.exit(main())
