#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse,shutil,subprocess
repo=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk',type=Path,default=repo/'apps/gui/app/build/outputs/apk/release/gui-release.apk')
p.add_argument('--work',type=Path,required=True,help='New fixture directory under /workspace')
a=p.parse_args();work=a.work.resolve();work.mkdir(parents=True,exist_ok=False)
def host(path):return toolenv.DEVICE_ROOT+'/'+str(path.resolve().relative_to('/workspace'))
generated=work/'src';generated.mkdir();classes=work/'classes';classes.mkdir()
source=repo/'runtime/noroot/src/main/java/io/github/modao7067/luma'
linux=generated/'LinuxEnvironment.java';linux.write_text((source/'LinuxEnvironment.java').read_text().replace('static boolean storageGranted(){return Environment.isExternalStorageManager();}','static boolean storageGranted(){return false;}'))
sources=list((repo/'shared/terminal-ghostty/java').rglob('*.java'))+list((repo/'shared/terminal-emulator/java').rglob('*.java'))+[source/n for n in ['RuntimeAssets.java','EnvironmentInstaller.java']]+[linux,repo/'tools/tests/noroot-files/EnvironmentInstallProbe.java']
sdk=toolenv.ANDROID_JAR;annotation=repo/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar';xz=repo/'artifacts/maven/org/tukaani/xz/1.12/xz-1.12.jar'
subprocess.run(['javac','--release','17','-cp',':'.join(map(str,[sdk,annotation,xz])),'-d',str(classes),*map(str,sources)],check=True)
jar=work/'checks.jar';subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
subprocess.run([toolenv.JAVA,'-Xmx256m','-cp',toolenv.D8_JAR,'com.android.tools.r8.D8','--lib',sdk,'--min-api','31','--output',str(work),str(jar),str(xz)],check=True)
r=subprocess.run(['android-sh','sh',host(repo/'tools/tests/terminal/run-android.sh'),host(work),host(a.apk),'io.github.modao7067.luma.EnvironmentInstallProbe',host(work)],timeout=240)
log=(work/'install-check.txt').read_text();print(log)
assert r.returncode==0 and 'ALL PASS' in log
