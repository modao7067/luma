#!/usr/bin/env python3
from pathlib import Path
import argparse
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
default_json = repo/'verification/json.jar'
if not default_json.is_file():
    default_json = repo/'artifacts/maven/org/json/json/20250517/json-20250517.jar'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', choices=['all', 'environment', 'tasks'], default='all')
parser.add_argument('--json-jar', type=Path, default=default_json)
parser.add_argument('--before', type=Path, help='Use saved pre-fix production sources for a negative control')
args = parser.parse_args()
assert args.json_jar.is_file(), 'Provide the build-only org.json JAR with --json-jar'
fixtures = repo/'runtime/noroot/verification/jvm'
production = Path('runtime/noroot/src/main/java/io/github/modao7067/luma')
sources = list((fixtures/'android').rglob('*.java')) + list((fixtures/'com').rglob('*.java'))
for name in ['EnvironmentInstaller', 'RuntimeAssets', 'LinuxEnvironment', 'TaskRegistry']:
    relative = production/(name+'.java')
    saved = args.before/relative if args.before else None
    sources.append(saved if saved and saved.is_file() else repo/relative)
sources += [repo/'shared/terminal-emulator/java/com/termux/terminal/LumaLang.java'] + [
    repo/'tools/tests/noroot-files'/(name+'.java') for name in [
    'NativeBridge', 'EnvironmentPreservationCheck', 'TaskRestartCheck']]
xz = repo/'artifacts/maven/org/tukaani/xz/1.12/xz-1.12.jar'
with tempfile.TemporaryDirectory(prefix='luma-preservation-') as directory:
    work = Path(directory)
    classes = work/'classes'
    classes.mkdir()
    dependencies = ':'.join(map(str, [args.json_jar, xz]))
    subprocess.run(['javac', '--release', '17', '-cp', dependencies, '-d', str(classes),
                    *map(str, sources)], check=True)
    for check in ['environment', 'tasks']:
        if args.check not in ['all', check]:
            continue
        main = 'EnvironmentPreservationCheck' if check == 'environment' else 'TaskRestartCheck'
        subprocess.run(['java', '-cp', str(classes)+':'+dependencies, 'io.github.modao7067.luma.'+main,
                        str(work/check)], check=True, timeout=45)
