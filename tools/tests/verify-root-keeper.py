#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import subprocess
import tempfile
import time
import uuid
from root_keeper_client import Connection,Lease

REPO=Path(__file__).resolve().parents[2];SOURCE=REPO/'backend/root/daemon.py'
with tempfile.TemporaryDirectory(prefix='luma-keeper-') as directory:
    folder=Path(directory);token=uuid.uuid4().hex+uuid.uuid4().hex;path=folder/'token';path.write_text(token);path.chmod(0o600)
    address='luma-keeper-'+uuid.uuid4().hex
    process=subprocess.Popen(['python3','-I',str(SOURCE),'--socket',address,'--uid',str(os.getuid()),'--token-file',str(path)])
    control=None;leases=[];ids=[]
    try:
        for _ in range(100):
            try:control=Connection(address,token);break
            except (ConnectionRefusedError,FileNotFoundError):time.sleep(.02)
        assert control is not None
        bad=socket.socket(socket.AF_UNIX);bad.settimeout(3);bad.connect('\0'+address)
        bad.sendall(b'{"op":"hello","token":"wrong"}\n');assert bad.recv(1)==b'';bad.close()
        for number in range(2):
            ident=uuid.uuid4().hex;ids.append(ident)
            script='cd '+shlex.quote(directory)+'; export HOME='+shlex.quote(directory)+" HISTFILE=/dev/null PS1='PROMPT> '; exec /bin/bash --noprofile --norc -i"
            control.send(op='create',id=ident,name='test '+str(number),script=script,size=[80,24,12,24]);entry=control.until('created',ident)
            lease=Lease(address,token,ident);leases.append(lease);lease.output_until(b'PROMPT> ')
            lease.input("stty -echo; printf '\\nREADY\\n'\n");lease.output_until(b'\r\nREADY\r\n')
        first,second=ids;one,two=leases;pid=one.packet['window']['pid']
        os.kill(process.pid,signal.SIGSTOP)
        try:
            one.input("export KEEP=original; printf '\\nDIRECT_WITH_KEEPER_STOPPED\\n'\n")
            one.output_until(b'\r\nDIRECT_WITH_KEEPER_STOPPED\r\n')
        finally:os.kill(process.pid,signal.SIGCONT)
        print('PASS real direct input/output works while the isolated Python keeper is SIGSTOPPED',flush=True)
        one.input("python3 -c 'import os;os.write(1,b\"LIVE\"*262144)'; printf '\\nLIVE_END\\n'\n")
        raw=one.output_until(b'\r\nLIVE_END\r\n');assert raw.count(b'LIVE')>=262144
        control.send(op='inspect',id=first);assert control.until('info',first)['window']['drainedBytes']==0
        print('PASS 1 MiB live output bypasses keeper reads; no JSON/Base64 terminal stream or history journal',flush=True)
        duplicate=Connection(address,token,'lease');duplicate.send(op='lease',id=first);assert duplicate.read()['event']=='error';duplicate.close()
        one.close();leases.remove(one);time.sleep(.05)
        child_code='''import sys,os,time
sys.path.insert(0,sys.argv[1]);from root_keeper_client import Lease
lease=Lease(sys.argv[2],sys.argv[3],sys.argv[4]);lease.input(sys.argv[5]);os._exit(0)
'''
        marker=folder/'generated'
        command="python3 -c "+shlex.quote("import time,os;time.sleep(.2);os.write(1,b'Z'*(2*1024*1024));open("+repr(str(marker))+",'w').write('done')")+"\n"
        subprocess.run(['python3','-c',child_code,str(REPO/'tools/tests'),address,token,first,command],check=True)
        deadline=time.monotonic()+6
        while not marker.exists() and time.monotonic()<deadline:time.sleep(.05)
        assert marker.read_text()=='done','Offline generation blocked on terminal output'
        control.send(op='inspect',id=first);info=control.until('info',first)['window']
        assert info['pid']==pid and info['drainedBytes']>=2*1024*1024,info
        one=Lease(address,token,first);leases.append(one)
        assert one.packet['reattached'] and one.packet['window']['pid']==pid
        one.input("printf '\\nRESTORED:%s:%s:%s\\n' \"$KEEP\" \"$$\" \"$PWD\"\n")
        one.output_until(('\r\nRESTORED:original:'+str(pid)+':'+directory+'\r\n').encode())
        print('PASS abrupt frontend process exit: generation drains >2 MiB offline, same shell PID/variables/cwd reconnect without replay',flush=True)
        control.send(op='rename',id=first,name='persistent')
        control.send(op='resize',id=first,size=[40,18,12,24]);control.send(op='inspect',id=first)
        info=control.until('info',first)['window'];assert info['name']=='persistent' and info['size']==[40,18,12,24]
        one.input("printf '\\033]1337;File=inline=1:AAAA\\007\\033[31mRAW\\033[0m\\rREFRESH'; printf '\\nRAW_END\\n'\n")
        raw=one.output_until(b'RAW_END\r\n');assert b'\x1b]1337;File=inline=1:AAAA\x07\x1b[31mRAW\x1b[0m\rREFRESH' in raw
        one.input("sleep 120; printf 'SHOULD_NOT_PRINT'\n");time.sleep(.1);one.input(b'\x03');one.output_until(b'PROMPT> ')
        one.input("sleep 120 & printf '\\nJOB:%s\\n' $!\n");job=int(re.search(rb'JOB:(\d+)',one.output_until(b'JOB:'))[1])
        control.send(op='close',id=first);control.until('closed',first);one.close();leases.remove(one)
        deadline=time.monotonic()+2
        while Path('/proc/'+str(pid)).exists() and time.monotonic()<deadline:time.sleep(.02)
        assert not Path('/proc/'+str(pid)).exists()
        stat=Path('/proc/'+str(job)+'/stat')
        if stat.exists():assert stat.read_text().split(') ',1)[1].startswith('Z')
        two.input("printf '\\nOTHER_WINDOW_OK\\n'\n");two.output_until(b'\r\nOTHER_WINDOW_OK\r\n')
        print('PASS raw image/SGR/CR bytes, dimensions, Ctrl-C, safe per-window close and another live window',flush=True)
        control.send(op='input',id=second,data='forbidden');assert control.read()['event']=='error'
        control.send(op='close',id=uuid.uuid4().hex,pid=0);assert control.read()['event']=='error'
        control.send(op='close',id=second);control.until('closed',second);two.close();leases.remove(two)
        control.close();control=None;assert process.wait(timeout=8)==0
        print('PASS authentication, duplicate lease, relay/PID-0 rejection and idle exit',flush=True)
    finally:
        if process.poll() is None:os.kill(process.pid,signal.SIGCONT)
        for lease in leases:lease.close()
        if control is not None:
            for ident in ids:
                try:control.send(op='close',id=ident)
                except OSError:pass
            time.sleep(.7);control.close()
        if process.poll() is None:
            process.terminate();process.wait(timeout=3)

spec=importlib.util.spec_from_file_location('keeper',SOURCE);daemon=importlib.util.module_from_spec(spec);spec.loader.exec_module(daemon)
from unittest.mock import patch
with patch.object(daemon,'proc_identity',return_value=(123,999)),patch.object(daemon.os,'kill') as kill:
    daemon.Server.signal_owned({0:(123,999),456:(123,111)},signal.SIGKILL);kill.assert_not_called()
window=daemon.Window('a'*32,'permission test',123,-1,[80,24,12,24]);server=object.__new__(daemon.Server)
server.windows={window.id:window};messages=[];server.send=lambda client,value:messages.append(value)
window.closing=(0,{123:(123,1)},object(),0,False,0)
with patch.object(daemon.Server,'owned',return_value={123:(123,1)}),patch.object(daemon.Server,'signal_owned',return_value=[123]):server.close_due()
assert window.id in server.windows and window.closing is None and messages[-1]['event']=='error'
print('PASS PID reuse/PID-0 signal guards and failed close preserves the window',flush=True)
