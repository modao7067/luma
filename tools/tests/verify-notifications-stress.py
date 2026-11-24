#!/usr/bin/env python3
import base64
import fcntl
import hashlib
import http.server
import json
import os
import pathlib
import re
import socket
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import time

REPO = pathlib.Path(__file__).resolve().parents[2]
KT_SRC = (REPO / 'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RootBackgroundNotifications.kt').read_text(encoding='utf-8')
m = re.search(r'internal val watcherScript = """\n(.*?)\n""".trimIndent\(\)', KT_SRC, re.S)
assert m, 'watcherScript not found in RootBackgroundNotifications.kt'
WATCHER_CODE = m.group(1)
assert '.resolve(' not in WATCHER_CODE, 'Forbidden Path.resolve() found in watcherScript!'

def ws_accept_key(sec_key: str) -> str:
    magic = (sec_key.strip() + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode('ascii')
    return base64.b64encode(hashlib.sha1(magic).digest()).decode('ascii')

def ws_encode_frame(payload: bytes, opcode: int = 1) -> bytes:
    header = bytearray([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header.extend(struct.pack('!H', n))
    else:
        header.append(127)
        header.extend(struct.pack('!Q', n))
    return bytes(header) + payload

def ws_read_frame(conn: socket.socket, buf: bytearray):
    while True:
        if len(buf) >= 2:
            b0, b1 = buf[0], buf[1]
            opcode = b0 & 0x0F
            masked = (b1 & 0x80) != 0
            length = b1 & 0x7F
            idx = 2
            if length == 126 and len(buf) >= 4:
                length = struct.unpack('!H', buf[2:4])[0]
                idx = 4
            elif length == 126:
                idx = -1
            elif length == 127 and len(buf) >= 10:
                length = struct.unpack('!Q', buf[2:10])[0]
                idx = 10
            elif length == 127:
                idx = -1
            if idx != -1:
                if masked:
                    idx += 4
                if len(buf) >= idx + length:
                    payload = bytes(buf[idx:idx + length])
                    if masked:
                        mask = buf[idx - 4:idx]
                        payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
                    del buf[:idx + length]
                    return opcode, payload
        chunk = conn.recv(16384)
        if not chunk:
            return None, b''
        buf.extend(chunk)

class MockCodexWsServer:
    def __init__(self, unix_path: str = '', expected_token: str = ''):
        self.unix_path = unix_path
        self.expected_token = expected_token
        self.clients = []
        self.lock = threading.Lock()
        self.running = True
        self.pongs_received = 0
        if unix_path:
            self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.srv.bind(unix_path)
            self.port = 0
        else:
            self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.srv.bind(('127.0.0.1', 0))
            self.port = self.srv.getsockname()[1]
        self.srv.listen(16)
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                conn, _ = self.srv.accept()
            except OSError:
                break
            threading.Thread(target=self._client_loop, args=(conn,), daemon=True).start()

    def _client_loop(self, conn: socket.socket):
        try:
            raw = b''
            while b'\r\n\r\n' not in raw:
                c = conn.recv(4096)
                if not c:
                    conn.close()
                    return
                raw += c
            head, rest = raw.split(b'\r\n\r\n', 1)
            headers = {}
            for line in head.decode('latin1').splitlines()[1:]:
                if ':' in line:
                    k, v = line.split(':', 1)
                    headers[k.strip().lower()] = v.strip()
            if self.expected_token:
                assert headers.get('authorization') == f'Bearer {self.expected_token}', headers
            accept = ws_accept_key(headers['sec-websocket-key'])
            resp = (
                'HTTP/1.1 101 Switching Protocols\r\n'
                'Upgrade: websocket\r\n'
                'Connection: Upgrade\r\n'
                f'Sec-WebSocket-Accept: {accept}\r\n\r\n'
            )
            conn.sendall(resp.encode('ascii'))
            with self.lock:
                self.clients.append(conn)
            buf = bytearray(rest)
            while self.running:
                opcode, payload = ws_read_frame(conn, buf)
                if opcode is None or opcode == 8:
                    break
                if opcode == 10:
                    with self.lock:
                        self.pongs_received += 1
                    continue
                if opcode == 1:
                    msg = json.loads(payload.decode('utf-8'))
                    method = msg.get('method')
                    if method == 'initialize':
                        conn.sendall(ws_encode_frame(b'ping-check', opcode=9))
                        reply = json.dumps({'id': msg['id'], 'result': {'serverInfo': {'name': 'mock'}}}).encode('utf-8')
                        frame = ws_encode_frame(reply)
                        for i in range(0, len(frame), 7):
                            conn.sendall(frame[i:i + 7])
                    elif method == 'thread/resume':
                        tid = msg['params']['threadId']
                        reply = json.dumps({
                            'id': msg['id'],
                            'result': {'thread': {'id': tid, 'status': {'type': 'running'}}},
                        }).encode('utf-8')
                        conn.sendall(ws_encode_frame(reply))
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def broadcast_json(self, obj: dict):
        frame = ws_encode_frame(json.dumps(obj).encode('utf-8'))
        with self.lock:
            targets = list(self.clients)
        for c in targets:
            try:
                mid = max(1, len(frame) // 2)
                c.sendall(frame[:mid])
                c.sendall(frame[mid:])
            except OSError:
                pass

    def close(self):
        self.running = False
        try:
            self.srv.close()
        except OSError:
            pass
        with self.lock:
            for c in self.clients:
                try:
                    c.close()
                except OSError:
                    pass

class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

def main():
    with tempfile.TemporaryDirectory(prefix='luma_bg_stress_') as tmp:
        base = pathlib.Path(tmp)
        script_path = base / 'bg-notify-watch.py'
        script_path.write_text(WATCHER_CODE, encoding='utf-8')

        wake_token = 'a1' * 32
        bridge_token = 'b2' * 32
        oc_password = 'c3' * 32
        codex_token = 'd4' * 32
        (base / 'bg-watch-token').write_text(wake_token, encoding='utf-8')
        (base / 'cli-bridge-token').write_text(bridge_token, encoding='utf-8')
        (base / 'opencode-password').write_text(oc_password, encoding='utf-8')
        (base / 'capability-token').write_text(codex_token, encoding='utf-8')

        log_file = base / 'bg-watch.log'
        log_file.write_bytes(b'X' * 102400)
        initial_inode = log_file.stat().st_ino

        wakes_log = base / 'wakes.jsonl'
        mock_android_sh = base / 'mock-android-sh'
        mock_android_sh.write_text(
            f"""#!{sys.executable}
import json, pathlib, sys, fcntl
p = pathlib.Path({str(wakes_log)!r})
with open(p, 'a', encoding='utf-8') as f:
    fcntl.flock(f.fileno(), fcntl.LOCK_EX)
    f.write(json.dumps(sys.argv[1:]) + '\\n')
""",
            encoding='utf-8',
        )
        mock_android_sh.chmod(0o755)

        state_lock = threading.Lock()
        bridged_state = {f'br-{i}': {'running': True, 'requests': []} for i in range(8)}
        watch_headers = []
        oc_active = {f'ses_oc_{i}': True for i in range(6)}
        oc_perms = {f'ses_oc_{i}': [] for i in range(6)}
        oc_forms = {f'ses_oc_{i}': [] for i in range(6)}

        class MockHttpHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_GET(self):
                with state_lock:
                    if self.path.startswith('/sessions/'):
                        watch_headers.append(self.headers.get('X-Luma-Watch'))
                        sid = self.path.split('/sessions/', 1)[1]
                        payload = bridged_state.get(sid, {'running': False, 'requests': []})
                    elif self.path == '/api/session/active':
                        payload = {'data': dict(oc_active)}
                    elif self.path.startswith('/api/session/') and self.path.endswith('/permission'):
                        tid = self.path.split('/')[3]
                        payload = {'data': list(oc_perms.get(tid, []))}
                    elif self.path.startswith('/api/session/') and self.path.endswith('/form'):
                        tid = self.path.split('/')[3]
                        payload = {'data': list(oc_forms.get(tid, []))}
                    else:
                        payload = {}
                body = json.dumps(payload).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        http_srv = ThreadedHTTPServer(('127.0.0.1', 0), MockHttpHandler)
        http_port = http_srv.server_address[1]
        threading.Thread(target=http_srv.serve_forever, daemon=True).start()

        ws_tcp = MockCodexWsServer(expected_token=codex_token)
        unix_sock_path = str(base / 'ext-codex.sock')
        ws_unix = MockCodexWsServer(unix_path=unix_sock_path)

        sessions = []
        for i in range(8):
            sessions.append({
                'id': f'br-{i}', 'epoch': 1, 'protocol': 'bridged', 'task': 'luma-native-cli-gui-v3',
                'endpointUrl': f'http://127.0.0.1:{http_port}', 'running': True,
                'userStopped': (i == 7), 'requestIds': [],
            })
        for i in range(6):
            sessions.append({
                'id': f'oc-{i}', 'epoch': 1, 'protocol': 'opencode', 'threadId': f'ses_oc_{i}',
                'endpointUrl': f'http://127.0.0.1:{http_port}', 'running': True,
                'userStopped': False, 'requestIds': [],
            })
        for i in range(5):
            sessions.append({
                'id': f'cx-tcp-{i}', 'epoch': 1, 'protocol': 'codex', 'threadId': f'tid_tcp_{i}',
                'endpointUrl': f'ws://127.0.0.1:{ws_tcp.port}', 'running': True,
                'userStopped': (i == 4), 'requestIds': [],
            })
        for i in range(5):
            sessions.append({
                'id': f'cx-uds-{i}', 'epoch': 1, 'protocol': 'codex', 'threadId': f'tid_uds_{i}',
                'externalSocket': unix_sock_path, 'running': True,
                'userStopped': False, 'requestIds': [],
            })

        spec = {
            'active': True,
            'packageName': 'io.github.modao7067.luma',
            'updatedAt': int(time.time() * 1000),
            'sessions': sessions,
        }
        (base / 'bg-watch-spec.json').write_text(json.dumps(spec), encoding='utf-8')

        env = dict(os.environ)
        env['LUMA_BG_ANDROID_SH'] = str(mock_android_sh)
        env['LUMA_BG_TICK_SEC'] = '0.15'

        procs = [
            subprocess.Popen([sys.executable, '-I', '-B', str(script_path), str(base)], env=env)
            for _ in range(8)
        ]
        for p in procs:
            assert p.wait(timeout=5) == 0

        time.sleep(0.8)
        assert log_file.stat().st_ino == initial_inode, 'Log inode changed (must truncate in-place)!'
        assert log_file.stat().st_size < 16384, f'Log file was not truncated: {log_file.stat().st_size}'
        assert ws_tcp.pongs_received >= 1 and ws_unix.pongs_received >= 1, 'WebSocket Ping/Pong failed!'

        check_fd = os.open(str(base / 'bg-watch.lock'), os.O_RDWR)
        locked = False
        try:
            fcntl.flock(check_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            locked = True
        finally:
            os.close(check_fd)
        assert locked, 'Singleton watcher daemon should hold flock!'

        with state_lock:
            for i in range(4):
                bridged_state[f'br-{i}']['running'] = False
            for i in (4, 5):
                bridged_state[f'br-{i}']['requests'] = [{'id': f'r-{i}'}]
            bridged_state['br-6']['requests'] = [{'id': 'rq-6', 'questions': [{'id': 'q1'}]}]
            bridged_state['br-7']['running'] = False

            for i in range(3):
                oc_active.pop(f'ses_oc_{i}', None)
            for i in (3, 4):
                oc_perms[f'ses_oc_{i}'] = [{'id': f'p-{i}'}]
            oc_forms['ses_oc_5'] = [{'id': 'f-5'}]

        ws_tcp.broadcast_json({'method': 'turn/completed', 'params': {'threadId': 'tid_tcp_0'}})
        ws_tcp.broadcast_json({'method': 'turn/completed', 'params': {'threadId': 'tid_tcp_1'}})
        ws_tcp.broadcast_json({'id': 'srv-2', 'method': 'execCommandApproval', 'params': {'threadId': 'tid_tcp_2'}})
        ws_tcp.broadcast_json({'id': 'srv-3', 'method': 'item/tool/requestUserInput', 'params': {'threadId': 'tid_tcp_3'}})
        ws_tcp.broadcast_json({'method': 'turn/completed', 'params': {'threadId': 'tid_tcp_4'}})

        for i in range(3):
            ws_unix.broadcast_json({'method': 'turn/completed', 'params': {'threadId': f'tid_uds_{i}'}})
        ws_unix.broadcast_json({'id': 'uds-3', 'method': 'applyPatchApproval', 'params': {'threadId': 'tid_uds_3'}})
        ws_unix.broadcast_json({
            'method': 'item/started',
            'params': {'threadId': 'tid_uds_4', 'item': {'id': 'item-q4', 'delivery': 'async', 'questions': [{'id': 'q'}]}},
        })

        time.sleep(0.75)

        assert wakes_log.is_file(), 'No wake broadcasts emitted!'
        lines = [json.loads(line) for line in wakes_log.read_text(encoding='utf-8').splitlines() if line.strip()]
        assert 1 <= len(lines) <= 3, f'Expected batched broadcasts (1..3), got {len(lines)}'

        delivered = {}
        for argv in lines:
            assert '--es' in argv
            tok_idx = argv.index('token') + 1
            assert argv[tok_idx] == wake_token
            ev_idx = argv.index('events') + 1
            events = json.loads(argv[ev_idx])
            for item in events:
                sid = item['sessionId']
                assert sid not in delivered, f'Duplicate wake fired for {sid}!'
                delivered[sid] = item['kind']

        expected = {
            'br-0': 'completed', 'br-1': 'completed', 'br-2': 'completed', 'br-3': 'completed',
            'br-4': 'approval', 'br-5': 'approval', 'br-6': 'question',
            'oc-0': 'completed', 'oc-1': 'completed', 'oc-2': 'completed',
            'oc-3': 'approval', 'oc-4': 'approval', 'oc-5': 'question',
            'cx-tcp-0': 'completed', 'cx-tcp-1': 'completed', 'cx-tcp-2': 'approval', 'cx-tcp-3': 'question',
            'cx-uds-0': 'completed', 'cx-uds-1': 'completed', 'cx-uds-2': 'completed',
            'cx-uds-3': 'approval', 'cx-uds-4': 'question',
        }
        assert 'br-7' not in delivered, 'userStopped bridged session must NOT fire wake!'
        assert 'cx-tcp-4' not in delivered, 'userStopped codex session must NOT fire wake!'
        assert delivered == expected, f'Mismatch:\nGot:      {sorted(delivered.items())}\nExpected: {sorted(expected.items())}'

        (base / 'bg-watch-spec.json').write_text(json.dumps({'active': False}), encoding='utf-8')
        deadline = time.monotonic() + 1.5
        released = False
        while time.monotonic() < deadline:
            fd = os.open(str(base / 'bg-watch.lock'), os.O_RDWR)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(fd, fcntl.LOCK_UN)
                released = True
                os.close(fd)
                break
            except BlockingIOError:
                os.close(fd)
                time.sleep(0.05)
        assert released, 'Daemon did not exit promptly after disarm!'
        with state_lock:
            assert watch_headers and all(h == '1' for h in watch_headers), f'Missing X-Luma-Watch header: {watch_headers[:5]}'
            bridged_state['bg-hold'] = {'running': False, 'releasable': False, 'capabilities': {'release': True}, 'requests': []}
        wakes_log.unlink(missing_ok=True)
        (base / 'bg-watch-spec.json').write_text(json.dumps({
            'active': True, 'packageName': 'io.github.modao7067.luma', 'updatedAt': int(time.time() * 1000),
            'sessions': [{
                'id': 'bg-hold', 'epoch': 1, 'protocol': 'bridged', 'task': 'luma-native-cli-gui-v3',
                'endpointUrl': f'http://127.0.0.1:{http_port}', 'running': True, 'userStopped': False, 'requestIds': [],
            }],
        }), encoding='utf-8')
        env2 = {**env, 'LUMA_BG_TICK_SEC': '0.1', 'LUMA_BG_IDLE_EXIT_SEC': '0.5'}
        assert subprocess.run([sys.executable, '-I', '-B', str(script_path), str(base)], env=env2, check=False).returncode == 0
        time.sleep(0.8)
        hold_fd = os.open(str(base / 'bg-watch.lock'), os.O_RDWR)
        hold_locked = False
        try:
            fcntl.flock(hold_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            hold_locked = True
        finally:
            os.close(hold_fd)
        assert hold_locked, 'Watcher exited early while releasable was False!'
        with state_lock:
            bridged_state['bg-hold']['running'] = True
        time.sleep(0.25)
        with state_lock:
            bridged_state['bg-hold'] = {'running': False, 'releasable': True, 'capabilities': {'release': True}, 'requests': []}
        deadline2 = time.monotonic() + 2.5
        released2 = False
        while time.monotonic() < deadline2:
            fd2 = os.open(str(base / 'bg-watch.lock'), os.O_RDWR)
            try:
                fcntl.flock(fd2, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(fd2, fcntl.LOCK_UN)
                released2 = True
                os.close(fd2)
                break
            except BlockingIOError:
                os.close(fd2)
                time.sleep(0.05)
        assert released2, 'Watcher did not exit after becoming idle and releasable!'
        wakes2 = [json.loads(line) for line in wakes_log.read_text(encoding='utf-8').splitlines() if line.strip()]
        events2 = [ev for argv in wakes2 for ev in json.loads(argv[argv.index('events') + 1])]
        assert any(ev.get('sessionId') == 'bg-hold' and ev.get('kind') == 'completed' for ev in events2), f'Missing hold completed wake: {events2}'

        sse_cond = threading.Condition()
        sse_seq = [0]
        sse_shutdown = [False]
        sse_state = {'sse-1': {'running': True, 'releasable': False, 'capabilities': {'release': True}, 'requests': []}}
        sse_polls = []

        class MockSseHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_GET(self):
                if self.path == '/watch/events':
                    self.close_connection = True
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.send_header('Cache-Control', 'no-cache')
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    try:
                        self.wfile.write(b': ok\n\n')
                        self.wfile.flush()
                        last = sse_seq[0]
                        while not sse_shutdown[0]:
                            with sse_cond:
                                if sse_seq[0] == last and not sse_shutdown[0]:
                                    sse_cond.wait(timeout=1.0)
                                cur = sse_seq[0]
                            if sse_shutdown[0]:
                                break
                            if cur != last:
                                last = cur
                                self.wfile.write(f'data: {cur}\n\n'.encode('ascii'))
                                self.wfile.flush()
                    except OSError:
                        pass
                    return
                if self.path.startswith('/sessions/'):
                    sid = self.path.split('/sessions/', 1)[1]
                    with sse_cond:
                        sse_polls.append(time.monotonic())
                        payload = dict(sse_state.get(sid, {'running': False, 'requests': []}))
                    body = json.dumps(payload).encode('utf-8')
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

        sse_srv = ThreadedHTTPServer(('127.0.0.1', 0), MockSseHandler)
        sse_port = sse_srv.server_address[1]
        threading.Thread(target=sse_srv.serve_forever, daemon=True).start()

        wakes_log.unlink(missing_ok=True)
        (base / 'bg-watch-spec.json').write_text(json.dumps({
            'active': True, 'watchGen': 7, 'packageName': 'io.github.modao7067.luma', 'updatedAt': int(time.time() * 1000),
            'sessions': [{
                'id': 'sse-1', 'epoch': 1, 'protocol': 'bridged', 'task': 'luma-native-cli-gui-v3',
                'endpointUrl': f'http://127.0.0.1:{sse_port}', 'running': True, 'userStopped': False, 'requestIds': [],
            }],
        }), encoding='utf-8')
        env_sse = {**env, 'LUMA_BG_TICK_SEC': '5.0', 'LUMA_BG_EVENT_WAIT_SEC': '15.0', 'LUMA_BG_IDLE_EXIT_SEC': '0.4'}
        assert subprocess.run([sys.executable, '-I', '-B', str(script_path), str(base)], env=env_sse, check=False).returncode == 0
        time.sleep(0.9)
        with sse_cond:
            initial_poll_count = len(sse_polls)
        assert initial_poll_count == 1, f'Expected exactly 1 initial snapshot under SSE (zero polling), got {initial_poll_count}'

        push_t0 = time.monotonic()
        with sse_cond:
            sse_state['sse-1'] = {'running': True, 'releasable': False, 'capabilities': {'release': True}, 'requests': [{'id': 'req-idle-1'}], 'subAgents': []}
            sse_seq[0] += 1
            sse_cond.notify_all()

        wake_deadline = time.monotonic() + 1.0
        while time.monotonic() < wake_deadline and not wakes_log.is_file():
            time.sleep(0.02)
        push_latency = time.monotonic() - push_t0
        assert wakes_log.is_file(), 'SSE push did not trigger wake broadcast!'
        assert push_latency < 0.8, f'SSE push took too long ({push_latency:.3f}s) with 5s fallback tick!'
        wakes3 = [json.loads(line) for line in wakes_log.read_text(encoding='utf-8').splitlines() if line.strip()]
        events3 = [ev for argv in wakes3 for ev in json.loads(argv[argv.index('events') + 1])]
        assert any(ev.get('sessionId') == 'sse-1' and ev.get('kind') == 'approval' for ev in events3), f'Missing SSE approval wake: {events3}'
        assert any('--es' in argv and 'watchGen' in argv and argv[argv.index('watchGen') + 1] == '7' for argv in wakes3), f'Missing watchGen=7: {wakes3}'

        idle_deadline = time.monotonic() + 1.5
        released3 = False
        while time.monotonic() < idle_deadline:
            fd3 = os.open(str(base / 'bg-watch.lock'), os.O_RDWR)
            try:
                fcntl.flock(fd3, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(fd3, fcntl.LOCK_UN)
                released3 = True
                os.close(fd3)
                break
            except BlockingIOError:
                os.close(fd3)
                time.sleep(0.03)
        assert released3, 'Watcher stayed alive on running+approval-blocked session instead of exiting!'

        with sse_cond:
            sse_shutdown[0] = True
            sse_cond.notify_all()
        sse_srv.shutdown()
        ws_tcp.close()
        ws_unix.close()
        http_srv.shutdown()
        print(f'PASS notifications stress: 8x flock spawn, 24 sessions / 4 protocols, {len(lines)} batched broadcast(s), 0 duplicates, instant disarm, X-Luma-Watch + releasable hold + SSE push ({push_latency*1000:.0f}ms, 0 polls) + idle approval exit')

if __name__ == '__main__':
    main()
