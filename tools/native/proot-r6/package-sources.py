#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Deterministically package the tracked PRoot R5/R6 build recipes and patches
into runtime/noroot/src/main/assets/native-sources/proot-recipes.tgz."""
import argparse
import gzip
import io
import json
from pathlib import Path, PurePosixPath
import tarfile

SOURCE = Path(__file__).resolve().parent
REPO = SOURCE.parents[2]
R5_SOURCE = REPO / 'tools/native/proot-r5'
DEFAULT_BUNDLE = REPO / 'runtime/noroot/src/main/assets/native-sources/proot-recipes.tgz'


def collect_dir(base: Path, prefix: str) -> dict[str, tuple[bytes, int]]:
    entries: dict[str, tuple[bytes, int]] = {}
    spec = json.loads((base / 'provenance.json').read_text())
    for rel in spec['distribution_files']:
        parts = PurePosixPath(rel)
        if parts.is_absolute() or '..' in parts.parts:
            raise ValueError('unsafe distribution path: ' + rel)
        src = base / rel
        if not src.is_file():
            continue
        mode = 0o755 if rel.endswith(('.py', '.sh')) else 0o644
        entries[prefix + rel] = (src.read_bytes(), mode)
    return entries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, default=DEFAULT_BUNDLE)
    args = parser.parse_args()

    files: dict[str, tuple[bytes, int]] = {}
    files.update(collect_dir(R5_SOURCE, 'r5-fchmodat2/'))
    files.update(collect_dir(SOURCE, 'r6-native-links/'))

    args.bundle.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.bundle.with_suffix(args.bundle.suffix + '.partial')
    with temporary.open('wb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w') as archive:
                for name, (data, mode) in sorted(files.items()):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = mode
                    info.mtime = 0
                    info.uid = 0
                    info.gid = 0
                    info.uname = 'root'
                    info.gname = 'root'
                    archive.addfile(info, io.BytesIO(data))
    temporary.replace(args.bundle)
    print(f'Packaged PRoot recipes ({len(files)} files): {args.bundle}')


if __name__ == '__main__':
    main()
