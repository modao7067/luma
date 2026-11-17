#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[1]
BASE = REPO / 'tools'


def verify_git_scope():
    paths = subprocess.check_output(
        [str(BASE / 'luma'), 'git', 'ls-files', '--cached', '--others', '--exclude-standard'], text=True,
    ).splitlines()
    paths = sorted({path for path in paths if (REPO / path).is_file()})
    for path in paths:
        if not (path.startswith(('apps/', 'shared/', 'runtime/', 'gradle/', 'tools/', 'backend/', '.github/')) or path in (
            'README.md', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md', '.gitignore', 'luma', 'settings.gradle', 'build.gradle',
            'gradle.properties', 'gradlew', 'gradlew.bat',
        )):
            raise ValueError('Source path outside allowed scope: ' + path)
        if any(part in Path(path).parts for part in ('signing', 'build', '.gradle', 'jniLibs')):
            raise ValueError('Private/generated source path: ' + path)
        if path.endswith(('.apk', '.jks', '.keystore', '.key', '.pem', 'local.properties')):
            raise ValueError('Private/generated source file: ' + path)
    print('PASS Git scope:', len(paths), 'allowed source files', flush=True)


def static_checks():
    failed = []
    for name in (
        'test_source_hygiene', 'verify-layout', 'verify-build-entry', 'test_native_builds',
        'verify-production-gates', 'verify-no-host-paths', 'verify-jvm-comments', 'verify-python-comments',
    ):
        print('RUN static:', name, flush=True)
        if subprocess.run([sys.executable, str(BASE / 'tests' / (name + '.py'))]).returncode:
            failed.append(name)
    try:
        verify_git_scope()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        failed.append('Git scope')
    for name in ('artifacts.lock.json', 'assets.json'):
        try:
            json.loads((BASE / name).read_text())
        except (OSError, ValueError) as error:
            print(f'Invalid {name}: {error}', file=sys.stderr)
            failed.append(name)
    if failed:
        raise SystemExit('Static gates failed: ' + ', '.join(failed))
    print('PASS static gates', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--static', action='store_true', help='Run source gates without SDK or downloaded artifacts')
    args = parser.parse_args()
    static_checks()
    if args.static:
        return 0

    sys.path.insert(0, str(BASE))
    import os
    import toolenv

    def resolve(name):
        return Path(name.replace('$ANDROID_HOME', str(toolenv.ANDROID_HOME)).replace('$LUMA_ROOT', str(REPO)).replace('$AAPT2', toolenv.AAPT2))

    for name, digest in json.loads((BASE / 'toolchain.json').read_text())['artifactSha256'].items():
        path = resolve(name)
        if not path.is_file():
            if os.environ.get('LUMA_STRICT_TOOLCHAIN'):
                raise SystemExit('Pinned tool missing: ' + str(path))
            print('WARN pinned tool not present, skipped:', path)
            continue
        accepted = digest if isinstance(digest, list) else [digest]
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() not in accepted:
                raise SystemExit('Pinned tool changed: ' + str(path))
    print('PASS pinned SDK/aapt2/D8/apksigner hashes')
    for name in ('verify-row-projection',):
        subprocess.run([sys.executable, str(BASE / 'tests' / (name + '.py'))], check=True)
    for kind in ('status', 'input'):
        subprocess.run([sys.executable, str(BASE / 'tests/luma' / ('verify-' + kind + '.py'))], check=True)
    if not list((REPO / 'shared/terminal-emulator/java').rglob('*Image*.java')):
        raise SystemExit('Shared image-capable engine is missing')
    print('PASS image-capable terminal engine present')
    for name in (
        'verify-root-hook', 'verify-session-policy', 'verify-font-coverage', 'verify-font-cache',
        'verify-terminal-interaction', 'verify-terminal-scroll', 'verify-root-keeper', 'verify-root-mode-state',
    ):
        subprocess.run([sys.executable, str(BASE / 'tests' / (name + '.py'))], check=True)
    subprocess.run([sys.executable, str(BASE / 'assets.py'), 'check'], check=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
