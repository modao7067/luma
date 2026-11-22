#!/usr/bin/env python3
COMMAND_DESCRIPTION = """Download the freely redistributable terminal fonts that Luma bundles.

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
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRS = [ROOT / 'runtime/noroot/src/main/assets', ROOT / 'runtime/root/src/main/assets']
MANIFEST = DIRS[0] / 'terminal-fonts-manifest.json'

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
    ('NotoSansArabic-Regular.ttf', NOTO_FONTS + 'NotoSansArabic/NotoSansArabic-Regular.ttf', None, None),
    ('NotoSansThai-Regular.ttf', NOTO_FONTS + 'NotoSansThai/NotoSansThai-Regular.ttf', None, None),
    ('NotoSansKannada-Regular.ttf', NOTO_FONTS + 'NotoSansKannada/NotoSansKannada-Regular.ttf', None, None),
    ('NotoSansCanadianAboriginal-Regular.ttf', NOTO_FONTS + 'NotoSansCanadianAboriginal/NotoSansCanadianAboriginal-Regular.ttf', None, None),
    ('NotoSansGeorgian-Regular.ttf', NOTO_FONTS + 'NotoSansGeorgian/NotoSansGeorgian-Regular.ttf', None, None),
]

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def fetch_to_cache(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    print('download', url)
    fd, tmp_name = tempfile.mkstemp(prefix=target.name + '.', suffix='.tmp', dir=target.parent)
    tmp_path = Path(tmp_name)
    try:
        with os.fdopen(fd, 'wb') as out, urllib.request.urlopen(url, timeout=120) as response:
            shutil.copyfileobj(response, out)
        os.replace(tmp_path, target)
    except BaseException:
        tmp_path.unlink(missing_ok=True)
        raise


def load_verified_font(name, url, member, archive_hash, expected, cache):
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit('/', 1)[-1]
    for attempt in range(2):
        from_cache = target.is_file()
        if not from_cache:
            fetch_to_cache(url, target)
        raw = target.read_bytes()
        if archive_hash and sha256(raw) != archive_hash:
            target.unlink(missing_ok=True)
            if from_cache and attempt == 0:
                continue
            print(f'FAIL archive hash mismatch for {url}', file=sys.stderr)
            return None
        try:
            if member:
                with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                    blob = archive.read(member)
            else:
                blob = raw
        except (zipfile.BadZipFile, KeyError) as exc:
            target.unlink(missing_ok=True)
            if from_cache and attempt == 0:
                continue
            print(f'FAIL {name}: {exc}', file=sys.stderr)
            return None
        if sha256(blob) != expected:
            target.unlink(missing_ok=True)
            if from_cache and attempt == 0:
                continue
            print(f'FAIL {name}: sha256 {sha256(blob)} != manifest {expected}', file=sys.stderr)
            return None
        return blob
    return None


def main():
    parser = argparse.ArgumentParser(description=COMMAND_DESCRIPTION, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cache', type=Path, default=ROOT / 'artifacts' / 'download-cache')
    args = parser.parse_args()
    pinned = {row['file']: row['sha256'] for row in json.loads(MANIFEST.read_text())}
    failed = False
    for name, url, member, archive_hash in SOURCES:
        expected = pinned.get(name)
        if expected and all((d / name).is_file() and sha256((d / name).read_bytes()) == expected for d in DIRS):
            print('ok  ', name)
            continue
        blob = load_verified_font(name, url, member, archive_hash, expected, args.cache)
        if blob is None:
            failed = True
            continue
        for directory in DIRS:
            directory.mkdir(parents=True, exist_ok=True)
            (directory / name).write_bytes(blob)
        print('ok  ', name)
    if failed:
        sys.exit(1)
    print('\nStill to build: LumaCjkForms (tools/build-terminal-fonts.py), '
          'LumaGuiHan (tools/build-gui-font.py).')

if __name__ == '__main__':
    main()
