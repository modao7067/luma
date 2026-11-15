#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
REPO = Path(__file__).resolve().parents[3]
import sys
import subprocess

here=Path(__file__).resolve().parent
root=Path(REPO / 'apps/gui')
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from state_fixtures import stubs
stubs={k.replace('io/github/modao7067/luma','io/github/modao7067/luma'):v.replace('io.github.modao7067.luma','io.github.modao7067.luma') for k,v in stubs.items()}
stubs['android/os/SystemClock.java']='package android.os;public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}'
driver=stubs['io/github/modao7067/luma/StateChecks.java']
start=driver.index('  send(emulator,"\\u001b]133;C') if '  send(emulator,"\\u001b]133;C' in driver else driver.index('  send(emulator,"\x1b]133;C')
end=driver.index('  send(emulator,',driver.index('dot.onWindowFocusChanged(false)',start))
driver=driver[:start]+r'''
  send(emulator,"\u001b]133;C\u0007");dot.showSession(session);
  check(dot.color==0xffffffff&&Handler.pending.isEmpty(),"Running without output is solid white");
  dot.onOutput(session);check(Handler.pending.size()==1,"Output starts one pulse callback");
  for(int i=0;i<1000;i++)dot.onOutput(session);
  check(Handler.pending.size()==1,"Frequent output never multiplies queued callbacks");
  android.os.SystemClock.now=600;Handler.tick();
  check(dot.text.equals("○")&&dot.alpha==1f&&Handler.pending.size()==1,"Recent output blinks");
  android.os.SystemClock.now=1201;Handler.tick();
  check(dot.text.equals("●")&&dot.alpha==1f&&Handler.pending.isEmpty(),"Silence returns to solid white with zero callbacks");
  android.os.SystemClock.now=2000;dot.onOutput(session);
  android.os.SystemClock.now=2600;Handler.tick();check(dot.text.equals("○"),"First pulse is hollow white");
  android.os.SystemClock.now=2601;dot.onOutput(session);
  android.os.SystemClock.now=3200;Handler.tick();check(dot.text.equals("●"),"Next pulse returns to filled white");
  android.os.SystemClock.now=3802;Handler.tick();check(Handler.pending.isEmpty()&&dot.text.equals("●"),"Pulse expires without idle timers");
  dot.onOutput(session);dot.window(8);check(Handler.pending.isEmpty(),"Background cancels pulse");
  dot.onOutput(session);Handler.tick();check(Handler.pending.isEmpty(),"Background output never starts animation");
  dot.window(0);check(Handler.pending.isEmpty(),"Foreground does not replay old activity");
  dot.onOutput(session);dot.setActivityActive(false);check(Handler.pending.isEmpty(),"Pause cancels pulse");
  dot.onOutput(session);check(Handler.pending.isEmpty(),"Paused but visible output stays static");
  dot.setActivityActive(true);check(Handler.pending.isEmpty(),"Resume waits for fresh output");
  dot.onOutput(session);dot.onWindowFocusChanged(false);check(Handler.pending.isEmpty(),"Focus loss cancels pulse");
  dot.onWindowFocusChanged(true);check(Handler.pending.isEmpty(),"Focus regain waits for fresh output");
  System.out.println("PASS: output-only pulse settles; idle/background/focus changes leave zero callbacks");
'''+driver[end:]
stubs['io/github/modao7067/luma/StateChecks.java']=driver
work=here/'status-check';work.mkdir(exist_ok=True)
files=[]
for relative,body in stubs.items():
    path=work/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body);files.append(path)
import sys
sys.path.insert(0,str(REPO/'tools/tests'))
from reference_java import materialize
REFERENCE=materialize(REPO)
files+=list((root/'terminal-emulator/src/main/java').rglob('*.java'))+list(Path(REFERENCE / 'shared/terminal-emulator/java').rglob('*.java'))+[REPO/'shared/terminal-emulator/java/com/termux/terminal/LumaLang.java']
for n in ['StatusDot.java','WallpaperImages.java','keys/SpecialButtonState.java']:
    src=Path(REPO / 'shared/ui/java/io/github/modao7067/luma/shared')/n
    dst=work/'production'/n;dst.parent.mkdir(parents=True,exist_ok=True)
    dst.write_text(src.read_text().replace('io.github.modao7067.luma.shared','io.github.modao7067.luma'))
    files.append(dst)
sdk=toolenv.ANDROID_JAR
annotations=str(REPO / 'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar')
classes=work/'classes'
subprocess.run(['javac','--release','17','-cp',f'{sdk}:{annotations}','-d',str(classes),*map(str,files)],check=True)
run=subprocess.run(['java','-cp',f'{classes}:{sdk}:{annotations}','io.github.modao7067.luma.StateChecks'],text=True,capture_output=True,check=True)
(here/'status-check.txt').write_text(run.stdout+run.stderr);print(run.stdout)
