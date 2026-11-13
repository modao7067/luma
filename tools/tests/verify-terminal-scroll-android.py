#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import subprocess
import argparse
import shutil

repo = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tap-negative-control',action='store_true',help='Require old delayed link lookup to fail the overlay race gate')
parser.add_argument('--negative-control', action='store_true', help='Require the installed 1.17 MOVE callback to fail the acceleration gate')
args = parser.parse_args()
work = repo/'tools/tests/terminal-scroll/build'/('gesture-tap-negative' if args.tap_negative_control else 'gesture-negative' if args.negative_control else 'gesture')
classes = work/'classes'
dex = work/'dex'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)
source = (repo/'shared/terminal-view/java/com/termux/view/TerminalView.java').read_text()
def method(signature, text=source):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        if text[end] == '{': depth += 1
        if text[end] == '}': depth -= 1
        end += 1
    return text[start:end]
methods = '\n'.join(method(signature) for signature in (
    'public boolean onSingleTapUp(MotionEvent', 'public boolean onScroll(MotionEvent', 'public boolean onFling(final MotionEvent',
    'public boolean onDown(float', 'public boolean onUp(MotionEvent',
    'private void resetScrollGesture()', 'void doScroll(MotionEvent',
    'public boolean handleKeyCode(int keyCode, int keyMod)',
    'private boolean handleKeyCode(int keyCode, int keyMod, boolean fromScroll)',
    'public void scrollToBottom()',
))
if args.tap_negative_control:
    methods=methods.replace(method('public boolean onSingleTapUp(MotionEvent'),'''public boolean onSingleTapUp(MotionEvent event){
        String link=getLinkAt(event);if(link!=null&&mClient.onLinkTap(link))return true;
        return true;
    }''')
if args.negative_control:
    old_source = subprocess.check_output(['git', '-C', str(repo), 'show',
        'bd1d162:shared/terminal-view/java/com/termux/view/TerminalView.java'], text=True)
    methods = methods.replace(method('public boolean onScroll(MotionEvent'),
                              method('public boolean onScroll(MotionEvent', old_source), 1)
fixture = repo/'tools/tests/terminal-scroll/GestureScrollCheck.java'
generated = work/'GestureScrollCheck.java'
generated.write_text(fixture.read_text().replace('private static final int PRODUCTION_METHODS_ANCHOR = 0;', methods))
sdk = toolenv.ANDROID_JAR
subprocess.run(['javac', '--release', '17', '-cp', sdk, '-d', str(classes), str(generated),
                str(repo/'shared/terminal-view/java/com/termux/view/GestureAndScaleRecognizer.java'),
                str(repo/'shared/terminal-view/java/com/termux/view/TerminalScrollSpeed.java')], check=True)
jar = work/'checks.jar'
subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
subprocess.run([toolenv.JAVA, '-Xmx256m', '-cp',
                toolenv.D8_JAR, 'com.android.tools.r8.D8',
                '--lib', sdk, '--min-api', '31', '--output', str(dex), str(jar)], check=True)
def host(path): return toolenv.DEVICE_REPO+'/' + str(path.relative_to(repo))
log = work/'gesture-scroll.txt'
log.unlink(missing_ok=True)
result = subprocess.run(['android-sh', 'sh', host(repo/'tools/tests/terminal/run-android.sh'),
                         host(dex), host(jar), 'com.termux.view.GestureScrollCheck', host(work)], timeout=30)
output = log.read_text() if log.exists() else '(no replay log)'
print(output)
if args.tap_negative_control:
    assert result.returncode!=0 and 'confirmed tap cannot open background exposed by closing dashboard' in output,output
    print('PASS negative control: old delayed lookup opens the underlying link after panel dismissal')
elif args.negative_control:
    assert result.returncode != 0 and 'same finger distance: fast drag exceeds slow before UP' in output, 'Negative control did not catch missing MOVE acceleration'
    print('PASS negative control: installed 1.17 MOVE path fails the acceleration gate')
else:
    assert result.returncode == 0 and 'PASS' in output and 'AssertionError' not in output, 'Android gesture replay failed'
