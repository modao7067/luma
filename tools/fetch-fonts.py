#!/usr/bin/env python3
"""Download the freely redistributable terminal fonts that Luma bundles.

Fonts are not committed to the repository (they are build artifacts, see tools/bootstrap.py).
This script fetches the ones that
have a stable public download URL, verifies them against the SHA-256 values
pinned in `runtime/noroot/src/main/assets/terminal-fonts-manifest.json`, and
copies them into both runtime asset directories.

Fonts it does NOT fetch:
  * LumaCjkForms-Regular.otf: run tools/build-terminal-fonts.py (needs
    fontTools and NotoSansCJK-Regular.ttc, Noto Sans CJK SC 2.004).
  * LumaGuiHan-Regular.ttf: run tools/build-gui-font.py after this script.

Usage: python3 tools/fetch-fonts.py [--cache DIR]
"""
import argparse
import hashlib
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRS = [ROOT / 'runtime/noroot/src/main/assets', ROOT / 'runtime/root/src/main/assets']
MANIFEST = DIRS[0] / 'terminal-fonts-manifest.json'

# Noto upstream, pinned to exact commits so the bytes match the manifest hashes.
NOTO_FONTS = 'https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac/hinted/ttf/'
NOTO_EMOJI = 'https://raw.githubusercontent.com/googlefonts/noto-emoji/99701e07d3d1fe1bdbcdb14964547f0fd592966f/2D/fonts/'

SOURCES = [
    ('MapleMonoNL-NF-CN-Regular.ttf',
     'https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMonoNL-NF-CN.zip',
     'MapleMonoNL-NF-CN-Regular.ttf',
     '6103d3a73d0095de6b34e709f3ecaf1147e35ab7717c03068e07c0bb0a8efa2e'),
    ('JetBrainsMonoNLNerdFontMono-Regular.ttf',
     'https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.1/JetBrainsMono.zip',
     'JetBrainsMonoNLNerdFontMono-Regular.ttf', None),
    ('NotoSansMono-Regular.ttf', NOTO_FONTS + 'NotoSansMono/NotoSansMono-Regular.ttf', None, None),
    ('NotoSansSymbols-Regular.ttf', NOTO_FONTS + 'NotoSansSymbols/NotoSansSymbols-Regular.ttf', None, None),
    ('NotoSansMath-Regular.ttf', NOTO_FONTS + 'NotoSansMath/NotoSansMath-Regular.ttf', None, None),
    ('NotoMusic-Regular.ttf', NOTO_FONTS + 'NotoMusic/NotoMusic-Regular.ttf', None, None),
    ('NotoSansSymbols2-Regular.ttf', NOTO_FONTS + 'NotoSansSymbols2/NotoSansSymbols2-Regular.ttf', None, None),
    ('NotoColorEmoji.ttf', NOTO_EMOJI + 'NotoColorEmoji.ttf', None, None),
]

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def download(url, cache):
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit('/', 1)[-1]
    if not target.exists():
        print('download', url)
        with urllib.request.urlopen(url, timeout=120) as response:
            target.write_bytes(response.read())
    return target.read_bytes()

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cache', type=Path, default=ROOT / 'artifacts' / 'download-cache')
    args = parser.parse_args()
    pinned = {row['file']: row['sha256'] for row in json.loads(MANIFEST.read_text())}
    failed = False
    for name, url, member, archive_hash in SOURCES:
        blob = download(url, args.cache)
        if archive_hash and sha256(blob) != archive_hash:
            print(f'FAIL archive hash mismatch for {url}', file=sys.stderr)
            failed = True
            continue
        if member:
            with zipfile.ZipFile(io.BytesIO(blob)) as archive:
                blob = archive.read(member)
        if sha256(blob) != pinned.get(name):
            print(f'FAIL {name}: sha256 {sha256(blob)} != manifest {pinned.get(name)}', file=sys.stderr)
            failed = True
            continue
        for directory in DIRS:
            (directory / name).write_bytes(blob)
        print('ok  ', name)
    if failed:
        sys.exit(1)
    print('\nStill to build: LumaCjkForms (tools/build-terminal-fonts.py), '
          'LumaGuiHan (tools/build-gui-font.py).')

if __name__ == '__main__':
    main()
