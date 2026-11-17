#!/usr/bin/env python3
import ast
import io
from pathlib import Path
import re
import subprocess
import sys
import tokenize

REPO = Path(__file__).resolve().parents[2]
PROVENANCE = re.compile(r'copyright\b|SPDX-License-Identifier:|\b(?:adapted|derived|ported|copied) from\b', re.IGNORECASE)


def scan_comments(label: str, source: str) -> list[str]:
    findings = []
    try:
        tree = ast.parse(source)
        for token in tokenize.generate_tokens(io.StringIO(source).readline):
            if token.type != tokenize.COMMENT:
                continue
            if token.start == (1, 0) and token.string.startswith('#!'):
                continue
            if not PROVENANCE.search(token.string):
                findings.append(f'{label}:{token.start[0]}: explanatory Python comment: {token.string[:120]}')
        for node in ast.walk(tree):
            if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                documentation = ast.get_docstring(node)
                if documentation is not None and not PROVENANCE.search(documentation):
                    findings.append(f'{label}:{node.body[0].lineno}: Python docstring')
    except (SyntaxError, tokenize.TokenError) as error:
        findings.append(f'{label}: cannot parse Python source: {error}')
    return findings


def main() -> int:
    paths = subprocess.check_output(
        [str(REPO / 'tools/luma'), 'git', 'ls-files', '--cached', '--others', '--exclude-standard'], text=True,
    ).splitlines()
    findings = []
    scanned = 0
    for rel in sorted(set(paths)):
        path = REPO / rel
        if path.suffix != '.py' or 'third-party' in path.parts or not path.is_file():
            continue
        scanned += 1
        findings.extend(scan_comments(rel, path.read_text('utf-8')))
    if findings:
        for finding in findings:
            print('FAIL source comment:', finding, file=sys.stderr)
        print(f'Python comment policy failed ({len(findings)} violation(s))', file=sys.stderr)
        return 1
    print(f'PASS Python comment policy ({scanned} first-party files; CLI help strings and provenance retained)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
