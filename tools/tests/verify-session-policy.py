#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import os
import re
import subprocess
import sys
REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(Path(__file__).resolve().parent))
from state_fixtures import stubs
stubs=dict(stubs)
stubs['android/os/Handler.java']=stubs['android/os/Handler.java'].replace(
    'public class Handler {','public class Handler { public boolean postAtTime(Runnable r,long when){return postDelayed(r,when);}')
stubs['android/os/Looper.java']=stubs['android/os/Looper.java'].replace(
    'public class Looper {','public class Looper { public MessageQueue getQueue(){throw new AssertionError("Native timers are not initialized by this policy test");}')
work=REPO/'tools/tests/terminal/build/session-policy';work.mkdir(parents=True,exist_ok=True)
files=[]
for relative in ['android/os/Handler.java','android/os/Message.java','android/os/Looper.java']:
 p=work/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(stubs[relative]);files.append(p)
for name,source in {
'android/system/Os.java':'''package android.system; public class Os {public static int pid=-99,signal=-99;public static void kill(int p,int s)throws ErrnoException{pid=p;signal=s;}}''',
'android/system/OsConstants.java':'''package android.system;public class OsConstants {public static final int SIGQUIT=3,SIGKILL=9;}''',
'com/termux/terminal/SessionPolicyCheck.java':'''package com.termux.terminal;
import android.system.*;
public class SessionPolicyCheck {
 public static void main(String[] args){
  TerminalSessionClient client=(TerminalSessionClient)java.lang.reflect.Proxy.newProxyInstance(SessionPolicyCheck.class.getClassLoader(),new Class[]{TerminalSessionClient.class},(p,m,v)->null);
  TerminalSession proot=new TerminalSession("unused","/",new String[0],new String[0],100,client);
  proot.mShellPid=123;proot.finishIfRunning();
  if(Os.pid!=123||Os.signal!=OsConstants.SIGQUIT)throw new AssertionError("PRoot policy changed");
  TerminalSession root=new TerminalSession("unused","/",new String[0],new String[0],100,client,pid->Os.kill(pid,OsConstants.SIGKILL));
  root.mShellPid=456;root.finishIfRunning();
  if(Os.pid!=456||Os.signal!=OsConstants.SIGKILL)throw new AssertionError("Root policy changed");
  root.mShellPid=-1;Os.pid=-99;root.finishIfRunning();
  if(Os.pid!=-99)throw new AssertionError("Exited session signalled");
  root.mShellPid=0;Os.pid=-99;root.finishIfRunning();
  if(Os.pid!=-99)throw new AssertionError("PID 0 signalled");
  System.out.println("PASS local session policies: PRoot SIGQUIT, legacy injected SIGKILL, PID-0/exited guards; no real signals");
 }
}'''
}.items():
 p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source);files.append(p)
def _annotation_version(path: Path):
    match = re.fullmatch(r'annotation-jvm-(\d+(?:\.\d+)*)\.jar', path.name)
    if not match:
        return None
    return tuple(int(part) for part in match.group(1).split('.'))


def resolve_annotations_jar():
    gradle_home = Path(os.environ.get('GRADLE_USER_HOME') or (Path.home() / '.gradle'))
    roots = (
        REPO / 'artifacts/maven/androidx/annotation/annotation-jvm',
        gradle_home / 'caches/modules-2/files-2.1/androidx.annotation/annotation-jvm',
    )
    candidates = []
    for root in roots:
        if not root.is_dir():
            continue
        for candidate in root.rglob('annotation-jvm-*.jar'):
            if not candidate.is_file():
                continue
            version = _annotation_version(candidate)
            if version is not None:
                candidates.append((version, candidate))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    return candidates[-1][1]


files+=list((REPO/'shared/terminal-emulator/java').rglob('*.java'))+list((REPO/'shared/terminal-ghostty/java').rglob('*.java'))
sdk=toolenv.ANDROID_JAR
annotations_jar=resolve_annotations_jar()
if annotations_jar is not None:
    compile_cp=os.pathsep.join([sdk,str(annotations_jar)])
else:
    for ann_name in ('NonNull','Nullable'):
        ann_path=work/'androidx/annotation'/(ann_name+'.java')
        ann_path.parent.mkdir(parents=True,exist_ok=True)
        ann_path.write_text(
            'package androidx.annotation;\n'
            'import java.lang.annotation.Retention;\n'
            'import java.lang.annotation.RetentionPolicy;\n'
            f'@Retention(RetentionPolicy.CLASS) public @interface {ann_name} {{}}\n'
        )
        files.append(ann_path)
    compile_cp=sdk
classes=work/'classes'
classes.mkdir(parents=True,exist_ok=True)
subprocess.run(['javac','--release','17','-cp',compile_cp,'-d',str(classes),*map(str,files)],check=True)
subprocess.run(['java','-cp',os.pathsep.join([str(classes),compile_cp]),'com.termux.terminal.SessionPolicyCheck'],check=True)
