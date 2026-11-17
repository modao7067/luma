#!/usr/bin/env python3
import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'runtime/noroot/src/main/assets/native-sources'

DOWNLOADS = [
    ('talloc.tgz', 'https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz',
     'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd'),
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch_url(name: str, url: str, pinned: str, cache: Path) -> bytes:
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, default=ROOT / 'artifacts' / 'download-cache')
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, url, pinned in DOWNLOADS:
        blob = fetch_url(name, url, pinned, args.cache)
        (TARGET / name).write_bytes(blob)
        print('ok  ', name)
    return 0


if __name__ == '__main__':
    sys.exit(main())
