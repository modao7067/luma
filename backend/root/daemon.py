#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import argparse
import array
import collections
import errno
import fcntl
import hmac
import json
import os
from pathlib import Path
import pty
import re
import selectors
import signal
import socket
import struct
import sys
sys.path.insert(0,str(Path(__file__).absolute().parent))
from mode_state import ModeState
import termios
import time

VERSION = 3
MODE_STATE_CAPS = 3
MAX_WINDOWS = 16
MAX_REQUEST = 128 * 1024
MAX_CLIENT_BUFFER = 1024 * 1024
_LANG = os.environ.get('LUMA_LANG', 'zh')
if _LANG not in ('zh', 'en'):
    _LANG = 'en' if str(_LANG).lower().startswith('en') else 'zh'

def _tr(zh, en):
    return zh if _LANG == 'zh' else en

def frame(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()

def proc_identity(pid):
    try:
        text = Path(f'/proc/{pid}/stat').read_text()
        fields = text[text.rfind(')') + 2:].split()
        return int(fields[3]), int(fields[19])
    except (OSError, ValueError, IndexError):
        return None

class Client:
    def __init__(self, sock):
        self.sock, self.auth, self.role = sock, False, 'control'
        self.input, self.output = bytearray(), collections.deque()
        self.queued = 0
        self.lease = None
        self.rights = []

class Window:
    def __init__(self, ident, name, pid, fd, size):
        self.id, self.name, self.pid, self.fd, self.size = ident, name, pid, fd, size
        self.exit_code, self.closing, self.owner = None, None, None
        self.leased_before = False
        self.drained_bytes = 0
        self.mode_state = None

    def info(self):
        return dict(id=self.id, name=self.name, pid=self.pid, running=self.exit_code is None,
                    exit=self.exit_code, size=self.size, drainedBytes=self.drained_bytes)

class Server:
    def __init__(self, address, uid, token):
        self.uid, self.token = uid, token
        self.selector = selectors.DefaultSelector()
        self.windows, self.clients = {}, {}
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind('\0' + address)
        self.sock.listen(8)
        self.sock.setblocking(False)
        self.selector.register(self.sock, selectors.EVENT_READ, 'listen')
        self.wake_r, self.wake_w = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
        signal.set_wakeup_fd(self.wake_w)
        signal.signal(signal.SIGCHLD, lambda *_: None)
        self.selector.register(self.wake_r, selectors.EVENT_READ, 'signal')
        self.idle_since = time.monotonic()

    def send(self, client, value, fd=None):
        if client.sock.fileno() < 0:
            return
        data = frame(value)
        if client.queued + len(data) > MAX_CLIENT_BUFFER:
            self.disconnect(client)
            return
        duplicate = os.dup(fd) if fd is not None else None
        client.output.append([data, duplicate])
        client.queued += len(data)
        self.selector.modify(client.sock, selectors.EVENT_READ | selectors.EVENT_WRITE, client)

    def stop_drain(self, window):
        if window.fd >= 0:
            try:
                self.selector.unregister(window.fd)
            except KeyError:
                pass

    def disconnect(self, client):
        if client.sock.fileno() < 0:
            return
        self.clients.pop(client.sock.fileno(), None)
        self.selector.unregister(client.sock)
        client.sock.close()
        for _, fd in client.output:
            if fd is not None:
                os.close(fd)
        client.output.clear()
        for fd in client.rights:os.close(fd)
        client.rights.clear()
        window = self.windows.get(client.lease)
        if window is not None and window.owner is client:
            window.owner = None
            if window.mode_state is not None:window.mode_state.load()
            if window.fd >= 0:
                self.selector.register(window.fd, selectors.EVENT_READ, window)

    def publish(self, value):
        for client in list(self.clients.values()):
            if client.auth and client.role == 'control':
                self.send(client, value)

    @staticmethod
    def dimensions(value):
        values = [int(x) for x in value]
        if len(values) != 4 or not (4 <= values[0] <= 1000 and 2 <= values[1] <= 1000):
            raise ValueError('Invalid terminal dimensions')
        if not all(0 <= x <= 4096 for x in values[2:]):
            raise ValueError('Invalid cell dimensions')
        if values[0]*values[2] > 65535 or values[1]*values[3] > 65535:
            raise ValueError('PTY pixel dimensions overflow')
        return values

    def create(self, request):
        ident = request['id']
        if not isinstance(ident, str) or not re.fullmatch(r'[a-f0-9]{32}', ident):
            raise ValueError('Invalid window ID')
        if ident in self.windows or len(self.windows) >= MAX_WINDOWS:
            raise ValueError('Window exists or window limit reached')
        size = self.dimensions(request.get('size', [80, 24, 0, 0]))
        script = request['script']
        if not isinstance(script, str) or len(script.encode()) > 32 * 1024:
            raise ValueError('Invalid startup script')
        pid, fd = pty.fork()
        if pid == 0:
            try:
                signal.set_wakeup_fd(-1)
                signal.pthread_sigmask(signal.SIG_SETMASK, [])
                for sig in signal.valid_signals() - {signal.SIGKILL, signal.SIGSTOP}:
                    signal.signal(sig, signal.SIG_DFL)
                os.closerange(3, 65536)
                cols, rows, cw, ch = size
                fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, cols*cw, rows*ch))
                env = dict(os.environ, TERM='xterm-256color', COLORTERM='truecolor', LUMA_ROOT_WINDOW=ident, LUMA_LANG=str(_LANG or 'zh'))
                env.pop('TMUX', None)
                os.execve('/bin/bash', ['bash', '-c', script], env)
            except BaseException:
                os._exit(127)
        os.set_inheritable(fd, False)
        os.set_blocking(fd, False)
        window = Window(ident, str(request.get('name', _tr('窗口', 'Window')))[:128], pid, fd, size)
        self.windows[ident] = window
        return window

    @staticmethod
    def redraw(window):
        if window.fd >= 0:
            try:
                foreground = os.tcgetpgrp(window.fd)
                if foreground > 1 and os.getsid(foreground) == window.pid:
                    os.killpg(foreground, signal.SIGWINCH)
            except OSError:
                pass

    def request(self, client, request):
        serial = request.get('request', 0)
        if not client.auth:
            if request.get('op') != 'hello' or not hmac.compare_digest(str(request.get('token', '')), self.token):
                self.disconnect(client)
                return
            client.auth = True
            lang = request.get('lang')
            if lang in ('zh', 'en'):
                global _LANG
                _LANG = lang
            client.role = 'lease' if request.get('role') == 'lease' else 'control'
            self.send(client, dict(event='list', windows=[w.info() for w in self.windows.values()], version=VERSION,
                                   serverPid=os.getpid(),modeMetadataFd=True,modeStateCaps=MODE_STATE_CAPS))
            return
        op, ident = request.get('op'), request.get('id')
        if client.lease is not None:
            raise ValueError('PTY lease connection only tracks frontend lifetime')
        if op == 'create' and client.role == 'control':
            window = self.create(request)
            self.send(client, dict(event='created', id=window.id, window=window.info(), request=serial))
            return
        window = self.windows.get(ident)
        if window is None:
            raise ValueError('Window no longer exists (backend restarted or window closed)')
        if op == 'lease' and client.role == 'lease':
            if window.owner is not None:
                raise ValueError('Window is already connected; wait for the previous frontend to disconnect')
            if request.get('modeMetadataFd'):
                if len(client.rights)!=1:raise ValueError('Mode metadata FD missing')
                replacement=ModeState(client.rights.pop(),self.uid,window.size)
                if window.mode_state is not None:window.mode_state.close()
                window.mode_state=replacement
            elif client.rights:
                for fd in client.rights:
                    try:os.close(fd)
                    except OSError:pass
                client.rights.clear()
                raise ValueError('Unexpected file descriptors')
            self.stop_drain(window)
            window.owner, client.lease = client, ident
            self.send(client, dict(event='leased', id=ident, window=window.info(),
                                   reattached=window.leased_before, hasFd=window.fd >= 0),
                      window.fd if window.fd >= 0 else None)
            window.leased_before = True
            return
        if client.role != 'control':
            raise ValueError('Not a control connection')
        if op == 'resize':
            size = self.dimensions(request['size'])
            if size != window.size and window.fd >= 0:
                cols, rows, cw, ch = size
                fcntl.ioctl(window.fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, cols*cw, rows*ch))
                window.size = size
        elif op == 'redraw':
            self.redraw(window)
        elif op == 'rename':
            window.name = str(request['name'])[:128]
        elif op == 'inspect':
            self.send(client, dict(event='info', id=ident, window=window.info()))
        elif op == 'close':
            if window.closing is not None:
                raise ValueError('Window is already closing')
            owned = self.owned(window)
            if self.signal_owned(owned, signal.SIGTERM):
                raise PermissionError(_tr('无法终止窗口中的进程，窗口已保留', 'Unable to terminate processes in window; window kept'))
            window.closing = (time.monotonic() + .5, owned, client, serial, False, 0)
        else:
            raise ValueError('Unknown operation; terminal bytes must use the PTY FD')

    @staticmethod
    def owned(window):
        owned = {}
        for name in os.listdir('/proc'):
            if not name.isdecimal() or int(name) <= 1 or int(name) == os.getpid():
                continue
            pid = int(name)
            identity = proc_identity(pid)
            if identity is None:
                continue
            if identity[0] == window.pid:
                owned[pid] = identity
        return owned

    @staticmethod
    def signal_owned(owned, sig):
        refused = []
        for pid, identity in owned.items():
            if pid > 1 and proc_identity(pid) == identity:
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    refused.append(pid)
        return refused

    @staticmethod
    def still_running(owned):
        for pid, identity in owned.items():
            if proc_identity(pid) != identity:
                continue
            try:
                stat = Path(f'/proc/{pid}/stat').read_text()
                if stat[stat.rfind(')') + 2] not in ('Z', 'X'):
                    return True
            except OSError:
                continue
        return False

    def reap(self):
        while True:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                return
            if pid == 0:
                return
            for window in self.windows.values():
                if window.pid == pid:
                    window.exit_code = os.waitstatus_to_exitcode(status)
                    self.publish(dict(event='exit', id=window.id, exit=window.exit_code))
                    break

    def close_due(self):
        for ident, window in list(self.windows.items()):
            if window.closing is None or window.closing[0] > time.monotonic():
                continue
            _, known, client, serial, killed, finish_deadline = window.closing
            known.update(self.owned(window))
            if not killed:
                if self.signal_owned(known, signal.SIGKILL):
                    window.closing = None
                    self.send(client, dict(event='error', id=ident, request=serial,
                                           message=_tr('无法终止窗口中的进程，窗口已保留', 'Unable to terminate processes in window; window kept')))
                else:
                    window.closing = (time.monotonic()+.05, known, client, serial, True, time.monotonic()+2)
                continue
            if self.still_running(known):
                if time.monotonic() < finish_deadline:
                    window.closing = (time.monotonic()+.05, known, client, serial, True, finish_deadline)
                else:
                    window.closing = None
                    self.send(client, dict(event='error', id=ident, request=serial,
                                           message=_tr('进程尚未退出，窗口保留，请稍后重试', 'Processes still exiting; window kept, retry shortly')))
                continue
            if window.fd >= 0:
                self.stop_drain(window)
                os.close(window.fd)
                window.fd = -1
            if window.mode_state is not None:window.mode_state.close()
            del self.windows[ident]
            self.send(client, dict(event='closed', id=ident, request=serial))

    def run(self):
        while True:
            self.reap()
            self.close_due()
            if self.windows or self.clients:
                self.idle_since = time.monotonic()
            elif time.monotonic() - self.idle_since > 5:
                return
            deadlines = [w.closing[0] for w in self.windows.values() if w.closing]
            timeout = max(0, min(deadlines)-time.monotonic()) if deadlines else None
            if not self.windows and not self.clients:
                timeout = 5
            for key, events in self.selector.select(timeout):
                obj = key.data
                if obj == 'listen':
                    conn, _ = self.sock.accept()
                    uid = struct.unpack('3i', conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1]
                    if uid not in (0, self.uid):
                        conn.close()
                        continue
                    conn.setblocking(False)
                    client = Client(conn)
                    self.clients[conn.fileno()] = client
                    self.selector.register(conn, selectors.EVENT_READ, client)
                elif obj == 'signal':
                    try:
                        os.read(self.wake_r, 4096)
                    except BlockingIOError:
                        pass
                elif isinstance(obj, Window):
                    if obj.owner is not None or obj.fd < 0:
                        continue
                    try:
                        data = os.read(obj.fd, 65536)
                    except BlockingIOError:
                        continue
                    except OSError as failure:
                        if failure.errno != errno.EIO:
                            raise
                        data = b''
                    if data:
                        obj.drained_bytes += len(data)
                        if obj.mode_state is not None:obj.mode_state.feed(data)
                    else:
                        self.stop_drain(obj)
                        os.close(obj.fd)
                        obj.fd = -1
                else:
                    client = obj
                    if events & selectors.EVENT_READ:
                        try:
                            data,rights,flags,_ = client.sock.recvmsg(65536,socket.CMSG_SPACE(8*4),socket.MSG_CMSG_CLOEXEC)
                            for level,kind,value in rights:
                                if level==socket.SOL_SOCKET and kind==socket.SCM_RIGHTS:
                                    fds=array.array('i');fds.frombytes(value[:len(value)//fds.itemsize*fds.itemsize]);client.rights.extend(fds)
                            if flags&socket.MSG_CTRUNC or len(client.rights)>1:
                                self.disconnect(client);continue
                        except BlockingIOError:
                            continue
                        except ConnectionResetError:
                            self.disconnect(client)
                            continue
                        if not data:
                            self.disconnect(client)
                            continue
                        client.input.extend(data)
                        if len(client.input) > MAX_REQUEST:
                            self.disconnect(client)
                            continue
                        while b'\n' in client.input and client.sock.fileno() >= 0:
                            line, _, rest = client.input.partition(b'\n')
                            client.input = bytearray(rest)
                            request = {}
                            try:
                                parsed = json.loads(line)
                                if not isinstance(parsed, dict):
                                    raise ValueError('Expected a request object')
                                request = parsed
                                self.request(client, request)
                            except (ValueError, KeyError, TypeError, OSError) as failure:
                                for fd in client.rights:
                                    try:os.close(fd)
                                    except OSError:pass
                                client.rights.clear()
                                self.send(client, dict(event='error', id=request.get('id'),
                                           request=request.get('request', 0), message=str(failure)))
                    if client.sock.fileno() >= 0 and events & selectors.EVENT_WRITE and client.output:
                        packet = client.output[0]
                        try:
                            rights = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [packet[1]]))] if packet[1] is not None else []
                            count = client.sock.sendmsg([packet[0]], rights)
                            if count:
                                if packet[1] is not None:
                                    os.close(packet[1]);packet[1] = None
                                packet[0] = packet[0][count:]
                                client.queued -= count
                                if not packet[0]:
                                    client.output.popleft()
                            if not client.output:
                                self.selector.modify(client.sock, selectors.EVENT_READ, client)
                        except (BrokenPipeError, ConnectionResetError):
                            self.disconnect(client)
                        except BlockingIOError:
                            pass

def main():
    signal.pthread_sigmask(signal.SIG_SETMASK, [])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--socket', required=True)
    parser.add_argument('--uid', required=True, type=int)
    parser.add_argument('--token-file', required=True, type=Path)
    parser.add_argument('--daemonize', action='store_true')
    args = parser.parse_args()
    token = args.token_file.read_text().strip()
    if len(token) < 32:
        parser.error('Invalid authentication token')
    try:
        server = Server(args.socket, args.uid, token)
    except OSError as failure:
        if failure.errno == errno.EADDRINUSE:
            return
        raise
    if args.daemonize:
        if os.fork():
            return
        os.setsid()
        if os.fork():
            os._exit(0)
        null = os.open('/dev/null', os.O_RDWR)
        for fd in (0, 1, 2):
            os.dup2(null, fd)
        if null > 2:
            os.close(null)
    server.run()

if __name__ == '__main__':
    main()
