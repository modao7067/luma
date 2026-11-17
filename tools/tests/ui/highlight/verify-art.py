#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import subprocess
import sys
import json
repo = Path(__file__).resolve().parents[4]
work = repo / 'artifacts/test-runs/highlight-art'
work.mkdir(parents=True, exist_ok=True)
classes, dex = work / 'classes', work / 'dex'
classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
sdk = toolenv.ANDROID_JAR
toolchain = json.loads((repo / 'tools/toolchain.json').read_text())
stdlib = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / toolchain['kotlin']).glob('*/*.jar'))
sources = [repo / 'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/CodeTokens.java', *Path(__file__).parent.glob('*.java')]
subprocess.run(['javac', '--release', '17', '-cp', str(stdlib) + ':' + sdk, '-d', str(classes), *map(str, sources)], check=True)
jar = work / 'checks.jar'
subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
with (work / 'dex-build.log').open('w') as build_log:
    subprocess.run([toolenv.JAVA, '-Xmx256m', '-cp', toolenv.D8_JAR, 'com.android.tools.r8.D8', '--lib', sdk, '--min-api', '31', '--output', str(dex), str(jar), str(stdlib)], check=True, stdout=build_log, stderr=subprocess.STDOUT)
def host(p): return toolenv.DEVICE_REPO+'/' + str(p.relative_to(repo))
main = 'HighlightLayoutArtCheck' if '--layout' in sys.argv else 'HighlightArtCheck'
log = work / ('layout.log' if '--layout' in sys.argv else 'result.log')
with log.open('w') as output:
    result = subprocess.run(['android-sh', 'sh', host(repo / 'tools/tests/terminal/run-android.sh'), host(dex), host(jar), 'io.github.modao7067.luma.gui.ui.' + main], stdout=output, stderr=subprocess.STDOUT, timeout=120)
text = log.read_text()
print(text)
assert result.returncode == 0 and 'PASS Android' in text
