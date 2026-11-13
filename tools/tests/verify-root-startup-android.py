#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import subprocess
import tempfile
import zipfile

repo = Path(__file__).resolve().parents[2]
parent = repo/'tools/tests/root-backend/build'
parent.mkdir(parents=True, exist_ok=True)
sdk = toolenv.ANDROID_JAR
annotation = repo/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar'
def host(path):
    return toolenv.DEVICE_ROOT+'/'+str(path.resolve().relative_to('/workspace'))
with tempfile.TemporaryDirectory(prefix='startup-', dir=parent) as temporary:
    work = Path(temporary)
    (work/'MainActivity.java').write_text('package io.github.modao7067.luma.root; public class MainActivity extends android.app.Activity {}')
    (work/'R.java').write_text('package io.github.modao7067.luma.root; final class R { static class drawable { static final int ic_notification=1; } static class bool {static final int root_gui_managed=3;} static class integer { static final int root_notification_id=2; }}')
    for name in ['TerminalTheme', 'LogCallbacks', 'TerminalEngines', 'ManagedTerminalRequest']:
        (work/(name+'.java')).write_text((repo/'shared/ui/java/io/github/modao7067/luma/shared'/(name+'.java')).read_text().replace('io.github.modao7067.luma.shared', 'io.github.modao7067.luma.root'))
    sources = list((repo/'shared/terminal-emulator/java').rglob('*.java'))+list((repo/'shared/terminal-ghostty/java').rglob('*.java'))+list(work.glob('*.java'))
    sources += [repo/'runtime/root/src/main/java/io/github/modao7067/luma/root'/(name+'.java') for name in [
        'ShellService', 'RootBackend', 'RootShellIntegration', 'DirectPty', 'TerminalModeStore', 'ColdStartRepaint', 'AgentWindowRequest']]
    sources.append(repo/'tools/tests/root-backend/RootStartupProbe.java')
    classes = work/'classes'
    classes.mkdir()
    subprocess.run(['javac', '--release', '17', '-cp', sdk+':'+str(annotation), '-d', str(classes), *map(str, sources)], check=True)
    jar = work/'checks.jar'
    subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
    subprocess.run([toolenv.JAVA, '-Xmx256m', '-cp',
                    toolenv.D8_JAR, 'com.android.tools.r8.D8',
                    '--lib', sdk, '--min-api', '31', '--output', str(work), str(jar)], check=True)
    (work/'classes.dex').chmod(0o444)
    resources = work/'resources.zip'
    with zipfile.ZipFile(resources, 'w') as archive:
        archive.writestr('test-fixture', 'no app assets needed')
    subprocess.run(['android-sh', 'sh', host(repo/'tools/tests/terminal/run-android.sh'),
                    host(work), host(resources), 'io.github.modao7067.luma.root.RootStartupProbe', host(work), host(repo/'shared/terminal-ghostty/jniLibs/arm64-v8a/libluma_vt_jni.so')], check=True, timeout=20)
