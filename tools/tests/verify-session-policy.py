#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
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
files+=list((REPO/'shared/terminal-emulator/java').rglob('*.java'))+list((REPO/'shared/terminal-ghostty/java').rglob('*.java'))
sdk=toolenv.ANDROID_JAR
annotations=str(REPO/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar')
classes=work/'classes'
subprocess.run(['javac','--release','17','-cp',sdk+':'+annotations,'-d',str(classes),*map(str,files)],check=True)
subprocess.run(['java','-cp',str(classes)+':'+sdk+':'+annotations,'com.termux.terminal.SessionPolicyCheck'],check=True)
