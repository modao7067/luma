#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse,json,subprocess,time,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=Path,default=REPO/'apps/gui/app/build/outputs/apk/release/gui-release.apk');p.add_argument('--check-jni',action='store_true');p.add_argument('--native-lib',type=Path);p.add_argument('--termux-lib',type=Path);a=p.parse_args();apk=a.apk.resolve()
out=HERE/'build'/('noroot-'+str(time.time_ns()));out.mkdir(parents=True);classes=out/'classes';classes.mkdir()
sdk=toolenv.ANDROID_JAR;annotation=REPO/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar';xz=REPO/'artifacts/maven/org/tukaani/xz/1.12/xz-1.12.jar'
source=REPO/'runtime/noroot/src/main/java/io/github/modao7067/luma'
(out/'MainActivity.java').write_text('package io.github.modao7067.luma; public class MainActivity extends android.app.Activity {}')
(out/'R.java').write_text('package io.github.modao7067.luma; final class R { static class drawable { static final int ic_notification=1; }}')
(out/'LinuxEnvironment.java').write_text((source/'LinuxEnvironment.java').read_text().replace('static boolean storageGranted(){return Environment.isExternalStorageManager();}','static boolean storageGranted(){return false;}'))
for name in ['TerminalTheme','LogCallbacks','TerminalEngines', 'ManagedTerminalRequest']:(out/(name+'.java')).write_text((REPO/'shared/ui/java/io/github/modao7067/luma/shared'/(name+'.java')).read_text().replace('io.github.modao7067.luma.shared','io.github.modao7067.luma'))
sources=list(out.glob('*.java'))+[HERE/'NoRootEngineCheck.java',HERE/'JniCwdCheck.java']
for name in ['ShellService','EnvironmentInstaller','RuntimeAssets','NativeBridge','TaskRegistry','ServiceCleanup','TerminalWorkspace']:sources.append(source/(name+'.java'))
for name in ['shared/terminal-emulator/java','shared/terminal-ghostty/java']:sources+=list((REPO/name).rglob('*.java'))
lib=out/'libluma_vt_jni.so'
with zipfile.ZipFile(apk) as z:
 lib.write_bytes(a.native_lib.read_bytes() if a.native_lib else z.read('lib/arm64-v8a/libluma_vt_jni.so'))
 (out/'libtermux.so').write_bytes(a.termux_lib.read_bytes() if a.termux_lib else z.read('lib/arm64-v8a/libtermux.so'))
def host(p):return toolenv.DEVICE_ROOT+'/'+str(Path(p).resolve().relative_to('/workspace'))
runner=out/'run.sh'
runner.write_text((HERE/'run-android-luma-boot.sh').read_text().replace('exec /system/bin/app_process64 /system/bin', 'exec /system/bin/app_process64 '+('-Xcheck:jni ' if a.check_jni else '')+'-Djava.library.path='+host(out)+' /system/bin'))
commands=[['javac','--release','17','-cp',':'.join(map(str,[sdk,annotation,xz])),'-d',str(classes),*map(str,sources)],['jar','cf',str(out/'check.jar'),'-C',str(classes),'.'],[toolenv.D8,'--lib',sdk,'--min-api','31','--output',str(out),str(out/'check.jar'),str(xz)],['android-sh','sh',host(runner),host(out),'io.github.modao7067.luma.NoRootEngineCheck',host(lib),host(out/'fixture')]]
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
for i,cmd in enumerate(commands):
 log=out/('step-'+str(i)+'.log')
 with log.open('w') as f:r=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=90)
 if r.returncode or i==3:print(log.read_text())
 if r.returncode:raise SystemExit(r.returncode)
print('Evidence:',out)
