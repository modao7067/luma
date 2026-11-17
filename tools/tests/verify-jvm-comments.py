#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
SUFFIXES = {'.kt', '.kts', '.java', '.gradle'}
UPSTREAM_ROOTS = (
    'shared/terminal-emulator/java/com/termux/',
    'shared/terminal-view/java/com/termux/',
    'shared/ui/java/io/github/modao7067/luma/shared/keys/',
    'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/color/mcu/',
    'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/thirdparty/',
)
PROVENANCE = re.compile(
    r'copyright\b|SPDX-License-Identifier:|\b(?:adapted|derived|ported|copied) from\b'
    r'|source details and notices:|\blicensed? under\b|\bApache-\d+\.\d+\b'
    r'|\bsee\b[^\n]*third-party/',
    re.IGNORECASE,
)


def comment_spans(text: str, kotlin: bool, groovy: bool = False) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    length = len(text)

    def string(start: int, delimiter: str) -> int:
        pos = start + len(delimiter)
        raw = kotlin and len(delimiter) == 3
        while pos < length:
            if text.startswith(delimiter, pos):
                return pos + len(delimiter)
            if text[pos] == '\\' and not raw:
                pos += 2
            elif (kotlin or groovy) and delimiter in ('"', '"""') and text.startswith('${', pos):
                pos = code(pos + 2, nested=True)
            else:
                pos += 1
        return length

    def code(pos: int, nested: bool = False) -> int:
        depth = 0
        while pos < length:
            if nested and text[pos] == '}' and depth == 0:
                return pos + 1
            if text.startswith('//', pos):
                end = text.find('\n', pos)
                end = length if end < 0 else end
                spans.append((pos, end))
                pos = end
            elif text.startswith('/*', pos):
                start = pos
                pos += 2
                nesting = 1
                while pos < length and nesting:
                    if kotlin and text.startswith('/*', pos):
                        nesting += 1
                        pos += 2
                    elif text.startswith('*/', pos):
                        nesting -= 1
                        pos += 2
                    else:
                        pos += 1
                spans.append((start, pos))
            elif text.startswith('"""', pos) or text.startswith("'''", pos):
                pos = string(pos, text[pos:pos + 3])
            elif text[pos] in ('"', "'", '`'):
                pos = string(pos, text[pos])
            else:
                if nested and text[pos] == '{':
                    depth += 1
                elif nested and text[pos] == '}':
                    depth -= 1
                pos += 1
        return pos

    code(0)
    return sorted(spans)


def scan_comments(label: str, text: str) -> list[str]:
    suffix = Path(label).suffix
    spans = comment_spans(text, suffix in {'.kt', '.kts'}, suffix == '.gradle')
    findings: list[str] = []
    for start, end in spans:
        if PROVENANCE.search(text[start:end]):
            continue
        line = text.count('\n', 0, start) + 1
        excerpt = text[start:end].splitlines()[0][:120]
        findings.append(f'{label}:{line}: explanatory JVM comment: {excerpt}')
    return findings


def is_first_party_jvm(path: str) -> bool:
    return (
        Path(path).suffix in SUFFIXES
        and 'third-party' not in Path(path).parts
        and not path.startswith(UPSTREAM_ROOTS)
    )


def main() -> int:
    paths = subprocess.check_output(
        [str(REPO / 'tools/luma'), 'git', 'ls-files', '--cached', '--others', '--exclude-standard'],
        text=True,
    ).splitlines()
    findings: list[str] = []
    scanned = 0
    for rel in sorted(set(paths)):
        path = REPO / rel
        if not is_first_party_jvm(rel) or not path.is_file():
            continue
        scanned += 1
        findings.extend(scan_comments(rel, path.read_text('utf-8')))
    if findings:
        for finding in findings:
            print('FAIL source comment:', finding, file=sys.stderr)
        print(f'JVM comment policy failed ({len(findings)} violation(s))', file=sys.stderr)
        return 1
    print(f'PASS JVM comment policy ({scanned} first-party files; license/provenance retained)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
