#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import os
import shutil
import re
import shlex
import sys
import subprocess
import tempfile
import time
import uuid

offline_exit="--offline-exit" in sys.argv
legacy="--legacy" in sys.argv
cold="--cold-repaint" in sys.argv
negative="--negative-control" in sys.argv

REPO=Path(__file__).resolve().parents[2]
WORK=REPO/'tools/tests/root-backend/build/service';WORK.mkdir(parents=True,exist_ok=True)
SDK=toolenv.ANDROID_JAR
ANNOTATIONS=REPO/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar'
HOST=toolenv.DEVICE_REPO
ROOT_PACKAGE='io.github.modao7067.luma.root'
(WORK/'MainActivity.java').write_text('package '+ROOT_PACKAGE+'; public class MainActivity extends android.app.Activity {}')
(WORK/'R.java').write_text('package '+ROOT_PACKAGE+'; final class R {static class drawable {static final int ic_notification=1;} static class bool {static final int root_gui_managed=3;} static class integer {static final int root_notification_id=2;}}')
for name in ['TerminalTheme','LogCallbacks','TerminalEngines', 'ManagedTerminalRequest']:
 (WORK/(name+'.java')).write_text((REPO/'shared/ui/java/io/github/modao7067/luma/shared'/(name+'.java')).read_text().replace('package io.github.modao7067.luma.shared;','package '+ROOT_PACKAGE+';'))
sources=list((REPO/'shared/terminal-ghostty/java').rglob('*.java'))+list((REPO/'shared/terminal-emulator/java').rglob('*.java'))+list(WORK.glob('*.java'))
sources += [REPO/'runtime/root/src/main/java/io/github/modao7067/luma/root'/(name+'.java') for name in ['ShellService','RootBackend','RootShellIntegration','DirectPty','TerminalModeStore','ColdStartRepaint','AgentWindowRequest']]
if negative:
 assert cold and not offline_exit
 original=REPO/'runtime/root/src/main/java/io/github/modao7067/luma/root/ShellService.java'
 control=WORK/'ShellServiceNegative.java'
 control_dir=WORK/'negative';control_dir.mkdir(exist_ok=True);control=control_dir/'ShellService.java'
 control.write_text(original.read_text().replace('repaint.start(cols,rows,cw,ch);','send("redraw",id,null,null);'))
 sources=[control if p==original else p for p in sources]
sources += [REPO/'tools/tests/root-backend/RootServiceProbe.java']
subprocess.run(['javac','--release','17','-cp',SDK+':'+str(ANNOTATIONS),'-d',str(WORK/'classes'),*map(str,sources)],check=True)
subprocess.run(['jar','cf',str(WORK/'probe.jar'),'-C',str(WORK/'classes'),'.'],check=True)
subprocess.run([toolenv.JAVA,'-Xmx256m','-cp',toolenv.D8_JAR,'com.android.tools.r8.D8','--lib',SDK,'--min-api','31','--output',str(WORK),str(WORK/'probe.jar')],check=True)
if "--compile-only" in sys.argv:
 print("PASS Root FD/service fixture compiles with shared native sources; no backend started")
 raise SystemExit(0)
wrapper=(REPO/'tools/tests/terminal/run-android.sh').read_text().replace('export CLASSPATH="$1/classes.dex:$2"','export CLASSPATH="$1/classes.dex"').replace('"$3" "$2" "$4"','io.github.modao7067.luma.root.RootServiceProbe "$2" "$3" "$4" "$5"')
(WORK/'run.sh').write_text(wrapper)
from root_keeper_client import Connection

def run_as(*args,**kwargs):
 return subprocess.run(['android-sh','run-as','io.github.modao7067.luma',*args],check=True,**kwargs)

app_uid='--app-uid' in sys.argv
uid=int(run_as('/system/bin/id','-u',capture_output=True,text=True).stdout) if app_uid else 0
relative=None
try:
 with tempfile.TemporaryDirectory(prefix='luma-service-',dir=WORK) as temporary:
  directory=Path(temporary);shutil.copy2(REPO/'shared/terminal-ghostty/jniLibs/arm64-v8a/libluma_vt_jni.so',directory/'libluma_vt_jni.so');token=uuid.uuid4().hex+uuid.uuid4().hex
  token_file=directory/'root-backend.token';token_file.write_text(token);token_file.chmod(0o600)
  host_work=HOST+'/'+str(WORK.relative_to(REPO));host_directory=HOST+'/'+str(directory.relative_to(REPO))
  if app_uid:
   relative=run_as('/system/bin/mktemp','-d','cache/luma-direct-XXXXXX',capture_output=True,text=True).stdout.strip()
   assert re.fullmatch(r'cache/luma-direct-[A-Za-z0-9]+',relative),relative
   host_directory=run_as('/system/bin/pwd',capture_output=True,text=True).stdout.strip()+'/'+relative
   host_work=host_directory
   for name,data in [('classes.dex',(WORK/'classes.dex').read_bytes()),('run.sh',wrapper.encode()),('root-backend.token',token.encode()),('libluma_vt_jni.so',(directory/'libluma_vt_jni.so').read_bytes())]:
    run_as('/system/bin/sh','-c','cat > '+shlex.quote(relative+'/'+name),input=data,capture_output=True)
   run_as('/system/bin/chmod','400',relative+'/classes.dex')
   run_as('/system/bin/chmod','600',relative+'/root-backend.token')
   token_file=Path('/proc/1/root'+host_directory+'/root-backend.token')
  address='luma.root.direct.'+str(uid)+'.'+token[:12]
  source=REPO/'backend/root/daemon.py'
  if legacy:
   source=directory/'legacy.py';source.write_text(subprocess.check_output(['git','show','07f1253:backend/root/daemon.py'],text=True))
  process=subprocess.Popen(['python3','-I',str(source),'--socket',address,'--uid',str(uid),'--token-file',str(token_file)],env=dict(os.environ,LUMA_PLAIN_SHELL='1',HISTFILE='/dev/null'))
  try:
   for _ in range(100):
    try:connection=Connection(address,token);connection.close();break
    except (ConnectionRefusedError,FileNotFoundError):time.sleep(.02)
   else:raise AssertionError('Isolated service keeper did not start')
   for mode,expected in [('fresh','SERVICE_FRESH_OK'),('restore','SERVICE_RESTORE_CLOSE_OK')]:
    if mode=='restore' and offline_exit:time.sleep(1)
    command=['/system/bin/sh',host_work+'/run.sh',host_work,host_directory,mode,str(REPO/'tools/tests/root-backend/fake-tui.py'),'cold-repaint' if cold else 'offline-exit' if offline_exit else 'live']
    prefix=['android-sh','run-as','io.github.modao7067.luma'] if app_uid else ['android-sh']
    result=subprocess.run(prefix+command,capture_output=True,text=True,timeout=20)
    if negative and mode=='restore':
     assert result.returncode!=0 and 'Cold repaint missing' in result.stderr,result.stdout+result.stderr
     print('PASS negative control: same-size SIGWINCH leaves static TUI blank before any input')
     break
    assert result.returncode==0 and expected in result.stdout,result.stdout+result.stderr
    if cold and mode=='restore':
     assert 'COLD_REPAINT_OK' in result.stdout,result.stdout
     print(next(line for line in result.stdout.splitlines() if 'COLD_REPAINT_OK' in line))
   if not negative:assert process.wait(timeout=8)==0
   scope=('legacy keeper; ' if legacy else '')+('offline TUI exit; ' if offline_exit else '')+('App UID '+str(uid)+' / run-as SELinux domain' if app_uid else 'isolated Root frontend; keeper SIGSTOPPED during live I/O')
   if not negative:print('PASS actual Android ShellService/RootBackend/emulator ('+scope+'): SCM_RIGHTS direct I/O, create, rename, abrupt frontend exit, same PID, alternate screen/input modes/command state reconnect, explicit close; no APK/UI or user windows touched')
  finally:
   if process.poll() is None:
    import signal
    os.kill(process.pid,signal.SIGCONT)
    try:
     connection=Connection(address,token)
     for window in connection.initial['windows']:
      connection.send(op='close',id=window['id']);connection.until('closed',window['id'])
     connection.close();process.wait(timeout=8)
    except Exception:process.terminate();process.wait(timeout=3)
finally:
 if relative is not None:run_as('/system/bin/rm','-r',relative,capture_output=True)
