#!/usr/bin/env python3
import array
import importlib.util
import json
import os
import select
import signal
import socket
import struct
import subprocess
import tempfile
import time
import uuid
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'backend/root/daemon.py'
spec = importlib.util.spec_from_file_location('mode_state', REPO / 'backend/root/mode_state.py')
mode_state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mode_state)

def check(condition, message):
    assert condition, message
    print('PASS ' + message)

class Lease:
    def __init__(self, socket_object, path, ident):
        self.socket = socket_object
        self.ident = ident
        self.fd = os.open(path, os.O_RDWR)
        self.pty = None
        request = json.dumps(dict(op='lease', id=self.ident, modeMetadataFd=True)).encode() + b'\n'
        self.socket.sendmsg([request], [(socket.SOL_SOCKET, socket.SCM_RIGHTS,
                                         array.array('i', [self.fd]))])
        os.close(self.fd)
        self.packet = self.read()

    def read(self):
        data = bytearray()
        while True:
            chunk, ancillary, _, _ = self.socket.recvmsg(1, socket.CMSG_SPACE(4 * 8))
            for level, kind, value in ancillary:
                if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                    fds = array.array('i')
                    fds.frombytes(value[:len(value) // fds.itemsize * fds.itemsize])
                    for fd in fds:
                        if self.pty is None: self.pty = fd
                        else: os.close(fd)
            assert chunk, 'keeper disconnected'
            if chunk == b'\n': return json.loads(data)
            data.extend(chunk)

    def write(self, data):
        data = data.encode() if isinstance(data, str) else data
        while data:
            count = os.write(self.pty, data)
            data = data[count:]

    def until(self, marker, timeout=5):
        data = bytearray(); deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            assert select.select([self.pty], [], [], max(0, deadline - time.monotonic()))[0], bytes(data[-200:])
            chunk = os.read(self.pty, 65536)
            assert chunk, 'PTY closed'
            data.extend(chunk)
            if marker in data: return bytes(data)
        raise AssertionError('missing ' + repr(marker) + ' in ' + repr(bytes(data[-200:])))

class Control:
    def __init__(self, address, token, role='control'):
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(5); self.socket.connect('\0' + address)
        self.socket.sendall((json.dumps(dict(op='hello', token=token, role=role)) + '\n').encode())
        self.initial = self.read()

    def read(self):
        data = bytearray()
        while True:
            chunk = self.socket.recv(1)
            assert chunk, 'control disconnected'
            if chunk == b'\n': return json.loads(data)
            data.extend(chunk)

    def send(self, **value):
        self.socket.sendall((json.dumps(value) + '\n').encode())

    def until(self, kind):
        for _ in range(100):
            event = self.read()
            if event.get('event') == kind: return event
            assert event.get('event') != 'error', event
        raise AssertionError(kind)

def seed(path):
    fd = os.open(path, os.O_RDWR)
    os.ftruncate(fd, mode_state.SIZE)
    state = mode_state.ModeState(fd, os.getuid(), [80, 24])
    state.feed(b'\033[>1u')
    state.kb_confident = True
    state.feed(b'\033[?2004h')
    state.close()

with tempfile.TemporaryDirectory(prefix='luma-kb-keeper-') as directory:
    folder = Path(directory)
    path = folder / 'window.modes'; path.write_bytes(b'\0' * mode_state.SIZE)
    seed(path)
    token = uuid.uuid4().hex + uuid.uuid4().hex
    token_file = folder / 'token'; token_file.write_text(token); token_file.chmod(0o600)
    address = 'luma-kb-' + uuid.uuid4().hex
    process = subprocess.Popen(['python3', '-I', str(SOURCE), '--socket', address,
                                '--uid', str(os.getuid()), '--token-file', str(token_file)])
    control = None; lease = None
    try:
        for _ in range(100):
            try:
                control = Control(address, token); break
            except (ConnectionRefusedError, FileNotFoundError):
                time.sleep(0.02)
        check(control is not None, 'private keeper accepted a private token')
        check(control.initial.get('modeStateCaps', 0) & 2, 'keeper advertises keyboard annex tracking')
        ident = uuid.uuid4().hex
        control.send(op='create', id=ident, name='keyboard', size=[80, 24, 12, 24],
                     script="exec /bin/bash --noprofile --norc -i")
        created = control.until('created'); check(created['window']['pid'] > 0, 'isolated PTY created')
        first = Control(address, token, 'lease')
        lease = Lease(first.socket, path, ident)
        check(lease.packet['event'] == 'leased' and lease.packet.get('reattached') is False,
              'first lease is a fresh attach')
        lease.write("printf '\\nLEASE_READY\\n'\n")
        lease.until(b'\r\nLEASE_READY\r\n')

        first.socket.close()
        lease.write("printf '\\033[>3u\\033[>4;2m\\nOFFLINE_DONE\\n'\n")
        deadline = time.monotonic() + 5
        drained = 0
        while time.monotonic() < deadline:
            control.send(op='inspect', id=ident)
            info = control.until('info')['window']
            drained = info.get('drainedBytes', 0)
            if drained > 0: break
            time.sleep(0.05)
        check(drained > 0, 'offline bytes observed by the private keeper')

        second = Control(address, token, 'lease')
        lease2 = Lease(second.socket, path, ident)
        check(lease2.packet.get('reattached') is True and lease2.packet['window']['pid'] == created['window']['pid'],
              'same window/PID reattached')
        lease2.write("printf '\\nREATTACH_OK\\n'\n")
        lease2.until(b'\r\nREATTACH_OK\r\n')

        probe = mode_state.ModeState(os.open(path, os.O_RDWR), os.getuid(), [80, 24])
        check(probe.kb_available and probe.kb_confident,
              'published v1 slot has a bound confident annex after offline tracking')
        check(probe.keyboard[2] == 2 and probe.keyboard[5] == 3,
              'offline kitty push updated the primary stack')
        check(probe.keyboard[1] & 1, 'offline modifyOtherKeys state updated the annex')
        probe.close()

        control.send(op='close', id=ident)
        control.until('closed')
        lease2.socket.close(); os.close(lease2.pty)
        control.socket.close(); control = None
        assert process.wait(timeout=8) == 0
        print('PASS close and idle keeper exit')
    finally:
        for item in (lease,):
            if item is not None:
                try: item.socket.close()
                except OSError: pass
                if item.pty is not None:
                    try: os.close(item.pty)
                    except OSError: pass
        if control is not None:
            try: control.socket.close()
            except OSError: pass
        if process.poll() is None:
            process.terminate(); process.wait(timeout=3)

print('ROOT KEYBOARD KEEPER OK')
