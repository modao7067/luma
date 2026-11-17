#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, default=ROOT/'artifacts/test-runs/terminal-modes')
parser.add_argument('--check', choices=['all','routing','header'], default='all')
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
source = ROOT/'runtime/noroot/src/main/java/io/github/modao7067/luma'
activity = (source/'MainActivity.java').read_text()

def method(text, name):
    start = text.index('{', text.index(name))
    depth, end = 1, start + 1
    while depth:
        if text[end] == '{': depth += 1
        if text[end] == '}': depth -= 1
        end += 1
    return text[start+1:end-1]

assert 'isSessionTerminal() ||' in method(activity, 'private void showWindows()')
assert 'getOrdinarySessions()' in method(activity, 'private void showWindows()')
assert 'getOrdinarySessions().size()' in method(activity, 'private void updateTitle()')
close = method(activity, 'private void confirmCloseWindow(')
assert 'service.stopAfterLastWindow(managedTerminalRequest)' in close
assert 'allowsSelection(managedTerminalRequest,session)' in method(activity, 'private void selectWindow(')
assert 'managedTerminalRequest=null' not in method(activity, 'private void openTerminalIfReady()')
assert 'lumaManagedTerminal' in method(activity, 'protected void onSaveInstanceState(')
assert 'managedTerminalRequest=null' in method(activity, 'protected void onNewIntent(')
assert 'openTerminalIfReady()' in method(activity, 'protected void onResume()')
back = method(activity, 'private void handleTerminalBack()')
assert back.index('linkOpener.dismiss()') < back.index('activeSheet.dialog.isShowing()') < back.index('WindowInsets.Type.ime()') < back.index('leaveTerminal()')
assert 'moveTaskToBack(true)' in back
assert 'handleTerminalBack()' in method(activity, 'public void onBackPressed()')
assert 'this::handleTerminalBack' in activity and 'registerOnBackInvokedCallback' in activity and 'unregisterOnBackInvokedCallback' in activity
leave = method(activity, 'private void leaveTerminal()')
assert 'io.github.modao7067.luma.gui.MainActivity' in leave and 'FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP' in leave
assert all(token not in leave for token in ['stopService', 'closeSession', 'finishIfRunning'])
assert 'android:enableOnBackInvokedCallback="true"' in (ROOT/'runtime/noroot/src/main/AndroidManifest.xml').read_text()
assert 'terminal.putExtra(ManagedTerminalRequest.EXTRA_TICKET,ticket)' in (source/'SetupActivity.java').read_text()
detail = (ROOT/'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/SessionDetail.kt').read_text()
assert 'onTerminal = { actions.openShell(actions.environment, "/workspace") }' in detail and 'tuiNotice' not in detail
print('PASS production Activity wiring: domain guards, retained launch identity, restore/new-intent/resume, ordinary list/count, dual Back callbacks/overlay/IME priority, GUI return target, Setup ticket forwarding')

work = output/'fixture'
classes, dex, generated = work/'classes', work/'dex', work/'src'
for folder in [classes, dex, generated]: folder.mkdir(parents=True, exist_ok=True)
stubs = {
    'MainActivity': 'public final class MainActivity extends android.app.Activity {}',
    'R': 'final class R { static final class drawable { static final int ic_notification=1; }}',
    'EnvironmentInstaller': 'final class EnvironmentInstaller { interface Progress { void update(String value); } static void install(android.content.Context c,Progress p) {} }',
    'RuntimeAssets': 'final class RuntimeAssets { static void ensure(android.content.Context c) {} }',
    'TaskRegistry': 'final class TaskRegistry { org.json.JSONObject listTasks(){return new org.json.JSONObject();} }',
    'NativeBridge': 'final class NativeBridge { final TaskRegistry taskRegistry=new TaskRegistry(); NativeBridge(android.content.Context c) {} void close() {} void checkToFile() {} }',
    'LinuxEnvironment': '''final class LinuxEnvironment {
        static void detectAcceleration(android.content.Context c) {} static void updateDns(android.content.Context c) {}
        static String[] command(android.content.Context c,java.util.List<String> command,String cwd) { return command.toArray(new String[0]); }
        static String[] environment(android.content.Context c) { return new String[0]; }
        static com.termux.terminal.TerminalSession session(android.content.Context c,com.termux.terminal.TerminalSessionClient client,com.termux.terminal.TerminalEmulatorFactory factory) {
            return new com.termux.terminal.TerminalSession("/fixture-no-exec","/fixture",new String[0],new String[0],10000,client,pid->{throw new AssertionError("fixture process must never start");},factory);
        }
    }''',
}
for name, body in stubs.items(): (generated/(name+'.java')).write_text('package io.github.modao7067.luma;\n'+body+'\n')
for name in ['ManagedTerminalRequest', 'LogCallbacks', 'TerminalEngines', 'TerminalTheme', 'ToolbarIcon']:
    (generated/(name+'.java')).write_text((ROOT/'shared/ui/java/io/github/modao7067/luma/shared'/(name+'.java')).read_text().replace('io.github.modao7067.luma.shared', 'io.github.modao7067.luma'))
sources = list((ROOT/'shared/terminal-emulator/java').rglob('*.java')) + list((ROOT/'shared/terminal-ghostty/java').rglob('*.java')) + list(generated.glob('*.java'))
sources += [source/(name+'.java') for name in ['ShellService', 'ServiceCleanup', 'TerminalWorkspace', 'TerminalHeader']]
sources += [ROOT/'tools/tests/terminal'/(name+'.java') for name in ['TerminalModesCheck', 'TerminalHeaderCheck']]
sdk = toolenv.ANDROID_JAR
annotation = ROOT/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar'
subprocess.run(['javac', '--release', '17', '-cp', sdk+':'+str(annotation), '-d', str(classes), *map(str, sources)], check=True)
jar = work/'checks.jar'
subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
subprocess.run([toolenv.JAVA, '-Xmx256m', '-cp', toolenv.D8_JAR,
    'com.android.tools.r8.D8', '--lib', sdk, '--min-api', '31', '--output', str(dex), str(jar)], check=True)
resources = work/'resources.zip'
with zipfile.ZipFile(resources, 'w') as archive: archive.writestr('fixture', 'No app resources, user state or runtime assets')

def host(path):
    return toolenv.DEVICE_ROOT+'/'+str(path.resolve().relative_to('/workspace'))

for main, log in [('TerminalModesCheck', 'terminal-modes-result.txt'), ('TerminalHeaderCheck', 'terminal-header-result.txt')]:
    if args.check=='routing' and main!='TerminalModesCheck': continue
    if args.check=='header' and main!='TerminalHeaderCheck': continue
    result = subprocess.run(['android-sh', 'sh', host(ROOT/'tools/tests/terminal/run-android.sh'), host(dex), host(resources),
        'io.github.modao7067.luma.'+main, host(output)], capture_output=True, text=True, timeout=90)
    content = (output/log).read_text() if (output/log).is_file() else ''
    print(content, end='')
    if result.returncode != 0 or 'ALL PASS' not in content:
        raise SystemExit(f'{main} failed ({result.returncode})\n{result.stdout}\n{result.stderr}')
