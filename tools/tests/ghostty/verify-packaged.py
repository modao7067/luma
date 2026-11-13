#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse,json,subprocess,time,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=Path,required=True);a=p.parse_args();apk=a.apk.resolve()
out=HERE/'build'/('packaged-'+str(time.time_ns()));out.mkdir(parents=True);classes=out/'classes';classes.mkdir()
sdk=toolenv.ANDROID_JAR;lib=out/'libluma_vt_jni.so'
with zipfile.ZipFile(apk) as z:lib.write_bytes(z.read('lib/arm64-v8a/libluma_vt_jni.so'))
def host(p):return toolenv.DEVICE_ROOT+'/'+str(Path(p).resolve().relative_to('/workspace'))
commands=[['javac','--release','17','-cp',sdk,'-d',str(classes),str(HERE/'PackagedGhosttyCheck.java'),str(HERE/'PackagedPreviewCheck.java')],['jar','cf',str(out/'probe.jar'),'-C',str(classes),'.'],[toolenv.D8,'--lib',sdk,'--min-api','31','--output',str(out),str(out/'probe.jar')],['android-sh','sh',host(REPO/'tools/tests/terminal/run-android.sh'),host(out),host(apk),'PackagedGhosttyCheck',host(lib)]]
commands.append(['android-sh','sh',host(REPO/'tools/tests/terminal/run-android.sh'),host(out),host(apk),'PackagedPreviewCheck',host(lib),host(out)])
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
for i,cmd in enumerate(commands):
 log=out/('step-'+str(i)+'.log')
 with log.open('w') as f:r=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=90)
 if r.returncode or i>=3:print(log.read_text())
 if r.returncode:raise SystemExit(r.returncode)
print('Evidence:',out)
