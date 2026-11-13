#!/usr/bin/env python3
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import uuid
from root_keeper_client import Connection,Lease

with tempfile.TemporaryDirectory(prefix='luma-entry-') as directory:
    token=uuid.uuid4().hex+uuid.uuid4().hex;path=Path(directory)/'token';path.write_text(token);path.chmod(0o600)
    address='luma-entry-'+uuid.uuid4().hex;ident=uuid.uuid4().hex
    subprocess.run(['android-sh','/data/local/linux/bin/ubuntu','/usr/bin/python3','-I','/opt/luma-root/daemon.py',
        '--daemonize','--socket',address,'--uid',str(os.getuid()),'--token-file',str(path)],check=True,capture_output=True,timeout=20)
    control=None;lease=None;pid=None
    try:
        control=Connection(address,token);pid=control.initial['serverPid']
        script='cd '+shlex.quote(directory)+'; export HOME='+shlex.quote(directory)+" HISTFILE=/dev/null PS1='ENTRY> '; exec /bin/bash --noprofile --norc -i"
        control.send(op='create',id=ident,name='isolated entry check',script=script,size=[49,20,24,40]);created=control.until('created',ident)
        lease=Lease(address,token,ident);lease.output_until(b'ENTRY> ')
        lease.input("stty -echo; export ENTRY_STATE=kept; printf '\\nSET_OK\\n'\n");lease.output_until(b'\r\nSET_OK\r\n')
        lease.close();lease=None;control.close();control=None;time.sleep(.1)
        control=Connection(address,token);assert control.initial['serverPid']==pid
        lease=Lease(address,token,ident);assert lease.packet['window']['pid']==created['window']['pid']
        lease.input("printf '\\nENTRY:%s\\n' \"$ENTRY_STATE\"\n");lease.output_until(b'\r\nENTRY:kept\r\n')
        control.send(op='close',id=ident);control.until('closed',ident);lease.close();lease=None;control.close();control=None
        deadline=time.monotonic()+8
        while Path('/proc/'+str(pid)).exists() and time.monotonic()<deadline:time.sleep(.05)
        assert not Path('/proc/'+str(pid)).exists()
        print('PASS installed keeper through real Android Ubuntu entry: launcher exit, direct FD handoff, same PID/state reconnect, close and idle exit')
    finally:
        if lease is not None:lease.close()
        if control is not None:
            try:control.send(op='close',id=ident);control.until('closed',ident)
            except (OSError,AssertionError):pass
            control.close()
        if pid and Path('/proc/'+str(pid)).exists():os.kill(pid,15)
