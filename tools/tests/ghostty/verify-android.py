#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse,json,subprocess,time,zipfile,shutil
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--case',choices=['core','view','host','modes','keyboard','store','input','images','browser','files','background','stream','security','preview','root','compression','report','paste','events','runtime','all'],nargs='+',default=['all'])
parser.add_argument('--oracle-dir',type=Path,help='Reuse unchanged frozen oracle inputs from a prior run of this candidate')
parser.add_argument('--apk',type=Path,default=REPO/'apps/gui/app/build/outputs/apk/release/gui-release.apk')
parser.add_argument('--native-lib',type=Path,help='Use an explicitly built candidate JNI library instead of the APK copy')
args=parser.parse_args();APK=args.apk.resolve();OUT=HERE/'build'/str(time.time_ns());OUT.mkdir(parents=True)
if 'all' in args.case or set(args.case)&{'view','background','root'}:
 if args.oracle_dir:
  for p in args.oracle_dir.iterdir():
   if p.name.startswith('oracle-') or p.name.startswith('legacy-'):shutil.copy2(p,OUT/p.name)
  (OUT/'oracle-source.txt').write_text(str(args.oracle_dir.resolve())+'\n')
 else:
  from reference_pixels import produce
  produce(REPO,APK,OUT)
CLASSES=OUT/'classes';DEX=OUT/'dex';CLASSES.mkdir();DEX.mkdir()
android=toolenv.ANDROID_JAR
annotation=REPO/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar'
r=REPO/'runtime/root/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar'
viewR=REPO/'shared/terminal-view/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar'
sources=[]
for name in ['shared/terminal-emulator/java','shared/terminal-view/java','shared/terminal-ghostty/java']:sources+=list((REPO/name).rglob('*.java'))
(OUT/'MainActivity.java').write_text('package io.github.modao7067.luma.root; public class MainActivity extends android.app.Activity {}')
for name in ['TerminalTheme','LogCallbacks','TerminalEngines', 'ManagedTerminalRequest','TerminalDocuments','TerminalLinkOpener','TerminalSystemLinks','TerminalFileProvider','WallpaperImages','LumaUi','TerminalPaste']:(OUT/(name+'.java')).write_text((REPO/'shared/ui/java/io/github/modao7067/luma/shared'/(name+'.java')).read_text().replace('io.github.modao7067.luma.shared','io.github.modao7067.luma.root'))
sources+=list(OUT.glob('*.java'))+[p for p in HERE.glob('*.java') if p.name not in ['NoRootEngineCheck.java','OffscreenBenchCheck.java']]+[REPO/'tools/tests/root-backend/AgentWindowProbe.java']
sources+=[REPO/'runtime/root/src/main/java/io/github/modao7067/luma/root'/(name+'.java') for name in ['ShellService','RootBackend','RootShellIntegration','DirectPty','TerminalModeStore','ColdStartRepaint','AgentWindowRequest']]
lib=OUT/'libluma_vt_jni.so'
if args.native_lib:shutil.copy2(args.native_lib,lib)
else:
 with zipfile.ZipFile(APK) as z:lib.write_bytes(z.read('lib/arm64-v8a/libluma_vt_jni.so'))
def host(path):return toolenv.DEVICE_ROOT+'/'+str(Path(path).resolve().relative_to('/workspace'))
commands=[['javac','--release','17','-nowarn','-cp',':'.join(map(str,[android,annotation,r,viewR])),'-d',str(CLASSES),*map(str,sources)],['jar','cf',str(OUT/'check.jar'),'-C',str(CLASSES),'.'],[toolenv.D8,'--lib',android,'--min-api','31','--output',str(DEX),str(OUT/'check.jar'),str(r),str(viewR)]]
cases=['core','view','host','modes','keyboard','store','input','images','browser','files','background','stream','security','preview','root','compression','report','paste','events','runtime'] if 'all' in args.case else args.case
for case in cases:
 cls={'security':'io.github.modao7067.luma.ghostty.NativeScrollClearCheck','preview':'com.termux.view.NativePreviewLifecycleCheck','core':'com.termux.terminal.SessionCoreCheck','view':'com.termux.view.ViewViewportCheck','images':'com.termux.view.NativeImageCheck','browser':'com.termux.view.NativeBrowserFrameCheck','files':'io.github.modao7067.luma.ghostty.NativeFrameFileCheck','background':'com.termux.view.NativeBackgroundCheck','stream':'com.termux.view.NativeStreamCheck','host':'com.termux.terminal.NativeHostCheck','modes':'com.termux.terminal.NativeModeCheck','keyboard':'com.termux.terminal.NativeKeyboardCheck','store':'io.github.modao7067.luma.root.NativeModeStoreCheck','input':'com.termux.view.NativeKeyInputCheck','root':'io.github.modao7067.luma.root.NativeRootServiceCheck','compression':'io.github.modao7067.luma.ghostty.NativeCompressionCheck','report':'com.termux.terminal.NativeReportCheck','paste':'com.termux.terminal.NativePasteCheck','events':'com.termux.terminal.NativeEventQueueCheck','runtime':'io.github.modao7067.luma.root.RuntimeLifecycleCheck'}[case]
 params=[host(lib)]
 if case in ['view','images','browser','files','background','stream','security','preview']:params+=[host(APK),host(OUT),host(APK)]
 if case=='root':
  fixture=OUT/'root-fixture';fixture.mkdir();params+=[host(fixture),host(OUT)]
 if case=='runtime':
  fixture=OUT/'runtime-fixture';fixture.mkdir();params+=[host(fixture)]
 if case=='store':
  fixture=OUT/'store-fixture';fixture.mkdir();params+=[host(fixture)]
 if case=='input':params+=[host(APK)]
 commands.append(['android-sh','sh',host(HERE/'run-android-luma-boot.sh'),host(DEX),cls,*params])
(OUT/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
for i,cmd in enumerate(commands):
 log=OUT/('step-'+str(i)+'.log')
 with log.open('w') as stream:r=subprocess.run(cmd,stdout=stream,stderr=subprocess.STDOUT,timeout=120)
 if r.returncode:
  print(log.read_text())
  if i>=3 and cases[i-3]=='view' and (OUT/'view-viewport-check.txt').exists():print((OUT/'view-viewport-check.txt').read_text())
  raise SystemExit(r.returncode)
 if i>=3:
  text=log.read_text()
  if cases[i-3]=='view':text=(OUT/'view-viewport-check.txt').read_text()
  print(text)
print('Evidence:',OUT)
