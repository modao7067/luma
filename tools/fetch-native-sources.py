#!/usr/bin/env python3
"""Download the unmodified PRoot / talloc / libandroid-shmem sources that Luma ships in its APK.

The archives in runtime/noroot/src/main/assets/native-sources are the GPL/LGPL source offer for the
bundled libproot_*.so. Each one is reproduced byte-for-byte from a public upstream:

    talloc.tgz  samba.org release tarball talloc-2.4.3.tar.gz (as published)
    shmem.tgz   GitHub tag archive termux/libandroid-shmem v0.7 (as published)
    proot.tgz   termux/proot commit 7266fb3e, packed as
                `git archive --format=tar --prefix=proot-5.1.107.92/ COMMIT | gzip -n`
                (needs git and GNU gzip; the tar stream is pinned separately so a different
                compressor is reported as such)

Every result is checked against the SHA-256 pinned below and again by tools/bootstrap.py.

Usage: python3 tools/fetch-native-sources.py [--cache DIR]
"""
import argparse
import hashlib
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'runtime/noroot/src/main/assets/native-sources'

DOWNLOADS = [
    ('talloc.tgz', 'https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz',
     'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd'),
    ('shmem.tgz', 'https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz',
     '1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867'),
]

PROOT_REPO = 'https://github.com/termux/proot.git'
PROOT_COMMIT = '7266fb3e8516535682f5a9c8f3a7e70f6506eddb'
PROOT_PREFIX = 'proot-5.1.107.92/'
PROOT_TAR_SHA256 = 'b1465af1cfe930f0296710a6604fb866316f401ce5184d2ab44e58bac5a9f9f9'
PROOT_TGZ_SHA256 = 'a1b070f55ec32b78e5033621476533d7230eb110275fe0cc3ee79c4fb334cfaa'

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def fetch_url(name, url, pinned, cache):
    cached = cache / url.rsplit('/', 1)[-1]
    if not cached.exists():
        print('download', url)
        with urllib.request.urlopen(url, timeout=300) as response:
            cached.write_bytes(response.read())
    blob = cached.read_bytes()
    if sha256(blob) != pinned:
        cached.unlink()
        raise SystemExit(f'FAIL {name}: sha256 {sha256(blob)} != pinned {pinned} (cached copy removed)')
    return blob

def fetch_proot(cache):
    cached = cache / f'proot-{PROOT_COMMIT[:12]}.tar'
    if not cached.exists():
        print('git fetch', PROOT_REPO, PROOT_COMMIT)
        with tempfile.TemporaryDirectory() as tmp:
            git = ['git', '-C', tmp]
            subprocess.run(git + ['init', '-q'], check=True)
            subprocess.run(git + ['fetch', '-q', '--depth', '1', PROOT_REPO, PROOT_COMMIT], check=True)
            tar = subprocess.run(git + ['archive', '--format=tar', '--prefix=' + PROOT_PREFIX, PROOT_COMMIT],
                                 check=True, capture_output=True).stdout
        cached.write_bytes(tar)
    tar = cached.read_bytes()
    if sha256(tar) != PROOT_TAR_SHA256:
        cached.unlink()
        raise SystemExit(f'FAIL proot.tgz: git archive sha256 {sha256(tar)} != pinned {PROOT_TAR_SHA256}')
    blob = subprocess.run(['gzip', '-n', '-6'], input=tar, check=True, capture_output=True).stdout
    if sha256(blob) != PROOT_TGZ_SHA256:
        raise SystemExit('FAIL proot.tgz: tar stream matches, but this gzip compresses differently '
                         '(GNU gzip expected); sha256 ' + sha256(blob))
    return blob

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cache', type=Path, default=ROOT / 'artifacts' / 'download-cache')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    TARGET.mkdir(parents=True, exist_ok=True)
    results = [(name, fetch_url(name, url, pinned, args.cache)) for name, url, pinned in DOWNLOADS]
    results.append(('proot.tgz', fetch_proot(args.cache)))
    for name, blob in results:
        (TARGET / name).write_bytes(blob)
        print('ok  ', name)
    return 0

if __name__ == '__main__':
    sys.exit(main())
