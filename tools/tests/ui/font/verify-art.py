#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[4]
work = root / 'verification/gui-snapshot-font-20261103/font-art'
classes, dex = work / 'classes', work / 'dex'
classes.mkdir(parents=True, exist_ok=True); dex.mkdir(exist_ok=True)
sdk = toolenv.ANDROID_JAR
subprocess.run(['javac', '--release', '17', '-cp', sdk, '-d', str(classes), str(Path(__file__).with_name('GuiFontArtCheck.java'))], check=True)
jar = work / 'check.jar'
subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
subprocess.run(['java', '-Xmx256m', '-cp', toolenv.D8_JAR, 'com.android.tools.r8.D8', '--lib', sdk, '--min-api', '31', '--output', str(dex), str(jar)], check=True)
def host(p): return toolenv.DEVICE_REPO+'/' + str(p.relative_to(root))
result = subprocess.run(['android-sh', 'sh', host(root/'tools/tests/terminal/run-android.sh'), host(dex), host(jar), 'io.github.modao7067.luma.gui.ui.GuiFontArtCheck', toolenv.DEVICE_REPO], text=True, capture_output=True, timeout=60)
(work/'result.log').write_text(result.stdout+result.stderr)
print(result.stdout+result.stderr)
assert result.returncode == 0 and 'PASS Android:' in result.stdout
