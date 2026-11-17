#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import json, os, pathlib, subprocess, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
toolchain = json.loads((ROOT / 'tools/toolchain.json').read_text())
decoder = '--decoder' in sys.argv
OUT = ROOT / ('artifacts/test-runs/media-shape/decoder-art' if decoder else 'artifacts/test-runs/media-shape/shape-art')
OUT.mkdir(parents=True, exist_ok=True)
classes = OUT / 'classes'
classes.mkdir(exist_ok=True)
dex = OUT / 'dex'
dex.mkdir(exist_ok=True)
compiled = ROOT / 'apps/gui/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
production = OUT / 'production-shape.jar'
with zipfile.ZipFile(production, 'w') as jar:
    entries = ['io/github/modao7067/luma/gui/ui/MediaDecoderKt.class', 'io/github/modao7067/luma/gui/ui/MediaFrame.class', 'io/github/modao7067/luma/gui/session/ResolvedMedia.class'] if decoder else ['io/github/modao7067/luma/gui/ui/LumaCardShape.class', 'io/github/modao7067/luma/gui/ui/shapes/MiuixSquirclePathKt.class', 'io/github/modao7067/luma/gui/ui/shapes/SquircleDefaults.class']
    for rel in entries:
        jar.write(compiled / rel, rel)
modules = [('ui', 'ui-android', toolchain['composeRuntime']), ('ui', 'ui-graphics-android', toolchain['composeRuntime']), ('ui', 'ui-geometry-android', toolchain['composeRuntime']), ('ui', 'ui-unit-android', toolchain['composeRuntime']), ('ui', 'ui-util-android', toolchain['composeRuntime']), ('foundation', 'foundation-android', toolchain['composeRuntime']), ('foundation', 'foundation-layout-android', toolchain['composeRuntime']), ('runtime', 'runtime-android', toolchain['composeRuntime'])]
jars = [production]
if decoder:
    with zipfile.ZipFile(ROOT / 'artifacts/maven/com/caverock/androidsvg-aar/1.4/androidsvg-aar-1.4.aar') as z:
        target = OUT / 'androidsvg.jar'
        target.write_bytes(z.read('classes.jar'))
        jars.append(target)
gradle_home = pathlib.Path(os.environ.get('GRADLE_USER_HOME') or (pathlib.Path.home() / '.gradle'))
gradle_cache = gradle_home / 'caches/modules-2/files-2.1'
for group, artifact, version in modules:
    aar = ROOT / f'artifacts/maven/androidx/compose/{group}/{artifact}/{version}/{artifact}-{version}.aar'
    if not aar.exists():
        aar = next((gradle_cache / f'androidx.compose.{group}' / artifact / version).glob('*/*.aar'))
    with zipfile.ZipFile(aar) as z:
        target = OUT / (artifact + '.jar')
        target.write_bytes(z.read('classes.jar'))
        jars.append(target)
stdlib_candidates = list((ROOT / 'artifacts/maven/org/jetbrains/kotlin/kotlin-stdlib' / toolchain['kotlin']).glob('*.jar'))
stdlib = stdlib_candidates[0] if stdlib_candidates else next((gradle_cache / 'org.jetbrains.kotlin/kotlin-stdlib' / toolchain['kotlin']).glob('*/*.jar'))
jars.append(stdlib)
collection = list((ROOT / 'artifacts/maven/androidx/collection/collection-jvm').glob('*/collection-jvm-*.jar'))
if collection:
    jars.append(sorted(collection)[-1])
sdk = toolenv.ANDROID_JAR
subprocess.run(['javac', '--release', '17', '-cp', ':'.join([sdk] + [str(p) for p in jars]), '-d', str(classes), str(ROOT / ('tools/tests/ui/MediaDecoderCheck.java' if decoder else 'tools/tests/ui/MediaShapeCheck.java'))], check=True)
check = OUT / 'check.jar'
subprocess.run(['jar', 'cf', str(check), '-C', str(classes), '.'], check=True)
with (OUT / 'dex.log').open('w') as log:
    subprocess.run([toolenv.JAVA, '-cp', toolenv.D8_JAR, 'com.android.tools.r8.D8', '--lib', sdk, '--min-api', '31', '--output', str(dex), str(check), *[str(j) for j in jars]], stdout=log, stderr=subprocess.STDOUT, check=True)
def host(p):
    return toolenv.DEVICE_REPO + '/' + str(p.relative_to(ROOT))
subprocess.run(['android-sh', 'sh', host(ROOT / 'tools/tests/terminal/run-android.sh'), host(dex), host(check), 'MediaDecoderCheck' if decoder else 'MediaShapeCheck', host(OUT)], check=True, timeout=45)
print((OUT / ('decoder-art.txt' if decoder else 'shape-art.txt')).read_text())
