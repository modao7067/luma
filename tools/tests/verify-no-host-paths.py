#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys
import tarfile

REPO = Path(__file__).resolve().parents[2]

PRODUCT_INSTRUCTION_FILES = {
    'apps/gui/app/src/main/assets/browser/SKILL.md',
    'apps/gui/app/src/main/assets/sessions/SKILL.md',
    'runtime/noroot/src/main/assets/root-mode/AGENTS.md',
    'runtime/noroot/src/main/assets/root-mode/SKILL.md',
    'runtime/noroot/src/main/assets/runtime/SKILL.md',
    'runtime/noroot/src/main/assets/workspace/AGENTS.md',
    'runtime/noroot/src/main/assets/workspace/CLAUDE.md',
    'runtime/noroot/src/main/assets/workspace/.agents/skills/luma-environment/SKILL.md',
}
DEVELOPMENT_RULE_NAMES = {
    'agents.md', 'claude.md', 'gemini.md', 'codex.md', 'skill.md', 'handover.md',
    'project' + '_map.md', 'cli' + '_adapter_guide.md',
}
DEVELOPMENT_RULE_DIRS = {'.agents', '.claude', '.codex', '.cursor', '.kimi', '.gemini'}


def checkout_sample_pattern(checkout: Path) -> re.Pattern:
    prefixes = [r'/workspace/(?:[l]uma|docs/[l]uma)']
    neutral_roots = {'/', '/workspace', '/workspace/project', '/workspace/app', '/workspace/repo', '/root', '/tmp', '/sdcard'}
    if str(checkout) not in neutral_roots:
        prefixes.append(re.escape(str(checkout)))
    return re.compile(r'(?:' + '|'.join(prefixes) + r')(?=/|[^A-Za-z0-9._-]|$)')


FORBIDDEN_PATTERNS = [
    ('checkout-specific sample path', checkout_sample_pattern(REPO)),
    ('host workspace path', re.compile(r'/workspace/(?:[P]Root-[A-Za-z0-9._-]+|[a]ndroid-(?:ndk|libs)\b|[n]ative/|[m]aple-han|[b]ench-kernel|[j]atsy-glass)')),
    ('legacy host tool path', re.compile(r'/opt/[e]ta-aapt2|/data/local/tmp/[e]ta\b|\b[e]ta\.db\b')),
    ('private package or overlay identifier', re.compile(r'\blocal\.[l]uma\.[A-Za-z0-9_.]*|\blocal\.[u]buntu\.[A-Za-z0-9_.]*|\.local-[r]oot-gui\b|\b(?:[P]ROJECT_MAP|[C]LI_ADAPTER_GUIDE)\.md\b')),
    ('host-pinned CLI or cache path', re.compile(r'/root/\.[g]radle\b|/root/\.[c]odex/packages\b|/root/\.[l]ocal/bin/agy\b|/root/\.[g]rok/bin/grok\b|/root/\.[o]pencode/bin/opencode\b|/root/[n]ative/')),
    ('removed verification fixture path', re.compile(r'(?<!luma-)\bverification/[A-Za-z0-9._-]*202[0-9][A-Za-z0-9._/-]*|\bverification/[j]son\.jar\b')),
    ('private sample identifier', re.compile(r'\b(?:[m]aple-han-size|[b]ench-kernel-r4|[j]atsy-glass)\b')),
    ('legacy PRoot recipe reference', re.compile(r'\btools/native/[p]root-r[0-9]+\b|\b[p]root-recipes\.tgz\b')),
]

BINARY_SUFFIXES = (
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.pdf', '.ttf', '.otf', '.woff', '.woff2',
    '.zip', '.xz', '.gz', '.tgz', '.jar', '.aar', '.dex', '.so', '.a', '.o', '.spv', '.mp4', '.wav',
)


def scan_text(label: str, text: str) -> list[str]:
    findings: list[str] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for reason, pattern in FORBIDDEN_PATTERNS:
            match = pattern.search(line)
            if match:
                findings.append(f'{label}:{lineno}: {reason}: {match.group(0)}')
    return findings


def scan_source_path(label: str) -> list[str]:
    if label in PRODUCT_INSTRUCTION_FILES:
        return []
    path = Path(label)
    if path.name.lower() in DEVELOPMENT_RULE_NAMES or any(part.lower() in DEVELOPMENT_RULE_DIRS for part in path.parts):
        return [f'{label}: private development-agent rules/config file']
    return []


def main() -> int:
    luma_cmd = REPO / 'tools/luma'
    raw_paths = subprocess.check_output(
        [str(luma_cmd), 'git', 'ls-files', '--cached', '--others', '--exclude-standard'],
        text=True,
    ).splitlines()
    paths = sorted({rel for rel in raw_paths if rel and (REPO / rel).is_file()})

    violations: list[str] = []
    scanned_files = 0
    for rel in paths:
        violations.extend(scan_source_path(rel))
        if rel.lower().endswith(BINARY_SUFFIXES):
            continue
        data = (REPO / rel).read_bytes()
        if b'\x00' in data[:8192]:
            continue
        scanned_files += 1
        violations.extend(scan_text(rel, data.decode('utf-8', errors='replace')))

    bundle = REPO / 'runtime/noroot/src/main/assets/native-sources/proot-sources.tgz'
    scanned_members = 0
    if bundle.is_file():
        with tarfile.open(bundle, 'r:gz') as archive:
            for member in archive.getmembers():
                if not member.isfile() or member.name.lower().endswith(BINARY_SUFFIXES):
                    continue
                handle = archive.extractfile(member)
                if handle is None:
                    continue
                payload = handle.read()
                if b'\x00' in payload[:8192]:
                    continue
                scanned_members += 1
                violations.extend(scan_text(f'proot-sources.tgz!{member.name}', payload.decode('utf-8', errors='replace')))

    if violations:
        for item in violations[:50]:
            print('FAIL host/private path leak:', item, file=sys.stderr)
        if len(violations) > 50:
            print(f'... {len(violations) - 50} more violation(s)', file=sys.stderr)
        raise SystemExit(f'Host/private path leak check failed ({len(violations)} violation(s))')

    print(f'PASS configured host/private path rules ({scanned_files} source files, {scanned_members} source-bundle members)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
