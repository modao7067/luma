#!/usr/bin/env python3
import argparse
import asyncio
import fcntl
import http.server
import json
import os
import pathlib
import pty
import select
import shutil
import struct
import subprocess
import tempfile
import termios
import threading
import time
import websockets

ROOT = pathlib.Path(__file__).resolve().parents[3]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--codex', default=os.environ.get('LUMA_TEST_CODEX') or shutil.which('codex') or 'codex')
    parser.add_argument('--tui-codex', default=shutil.which('codex') or 'codex')
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'artifacts/test-runs/external-codex')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    run = pathlib.Path(tempfile.mkdtemp(prefix='tui-', dir=args.output)); home = run / 'home'; home.mkdir(mode=0o700)
    work = run / 'work'; work.mkdir()
    socket_dir = pathlib.Path(tempfile.mkdtemp(prefix='luma-tui-')); sock = socket_dir / 's'
    calls = []
    class NoInput(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            calls.append(self.path); self.send_error(500, 'No inference authorized in TUI attachment fixture')
    model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), NoInput)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    (home / 'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
[model_providers.fixture]
name="No-input local fixture"
base_url="http://127.0.0.1:{model.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
    env = {key: value for key, value in os.environ.items() if 'TOKEN' not in key and 'API_KEY' not in key}
    env.update(HOME=str(home), CODEX_HOME=str(home), TERM='xterm-256color', COLORTERM='truecolor')
    async def exercise(owner):
        async with websockets.unix_connect(str(sock), open_timeout=5, close_timeout=1) as ws:
            seq = 0
            async def rpc(method, params):
                nonlocal seq
                seq += 1; mid = seq
                await ws.send(json.dumps({'id': mid, 'method': method, 'params': params}))
                while True:
                    response = json.loads(await asyncio.wait_for(ws.recv(), 10))
                    if response.get('id') == mid and 'method' not in response:
                        if 'error' in response: raise RuntimeError(response['error'])
                        return response.get('result', {})
            await rpc('initialize', {'clientInfo': {'name': 'luma_tui_fixture', 'title': 'Luma isolated TUI attachment', 'version': '1'}, 'capabilities': {'experimentalApi': True}})
            await ws.send(json.dumps({'method': 'initialized'}))
            started = await rpc('thread/start', {'cwd': str(work), 'approvalPolicy': 'never', 'sandbox': 'read-only', 'historyMode': 'paginated'})
            native = started['thread']['id']
            await rpc('thread/name/set', {'threadId': native, 'name': 'External owner TUI fixture'})
            master, slave = pty.openpty(); fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 120, 0, 0))
            command = [args.tui_codex, 'resume', native, '--remote', 'unix://' + str(sock)]
            tui = subprocess.Popen(command, cwd=work, env=env, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
            os.close(slave); screen = bytearray(); attached = False
            def drain(timeout=.1):
                if select.select([master], [], [], timeout)[0]:
                    try: data = os.read(master, 65536)
                    except OSError: return
                    screen.extend(data)
                    if b'\x1b[6n' in data: os.write(master, b'\x1b[1;1R')
                    if b'\x1b[?u' in data: os.write(master, b'\x1b[?0u')
                    if b'\x1b[c' in data: os.write(master, b'\x1b[?1;2c')
            try:
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline and tui.poll() is None:
                    drain()
                    if b'fixture' in screen and b']0;External owner TUI fixture |' in screen:
                        attached = True; break
                if not attached: raise AssertionError('TUI did not display the isolated remote model; see screen.txt')
                os.write(master, b'\x03'); await asyncio.sleep(.2); drain(); os.write(master, b'\x03')
                deadline = time.monotonic() + 5
                while tui.poll() is None and time.monotonic() < deadline: drain()
                if tui.poll() is None: tui.terminate(); tui.wait(timeout=3)
                drain(0)
                loaded = await rpc('thread/loaded/list', {})
                turns = await rpc('thread/turns/list', {'threadId': native, 'limit': 1})
                assert set(loaded['data']) == {native} and owner.poll() is None
                assert not turns['data'] and not calls
                (run / 'screen.txt').write_text(screen.decode('utf-8', errors='replace'))
                return {'nativeId': native, 'serverPid': owner.pid, 'command': command, 'tuiAttachedToExistingUds': attached,
                        'nativeThreadTitleRendered': True, 'ownerSurvivedTuiExit': True, 'loadedIdUnchanged': True,
                        'modelRequests': len(calls), 'nativeTurns': len(turns['data']), 'tuiExitCode': tui.returncode}
            finally:
                (run / 'screen.txt').write_text(screen.decode('utf-8', errors='replace'))
                if tui.poll() is None: tui.terminate(); tui.wait(timeout=3)
                os.close(master)
    try:
        with (run / 'server.log').open('w') as log:
            owner = subprocess.Popen([args.codex, 'app-server', '--listen', 'unix://' + str(sock)], cwd=work, env=env, stdout=log, stderr=log)
            try:
                for _ in range(100):
                    if sock.exists(): break
                    time.sleep(.05)
                result = asyncio.run(exercise(owner))
            finally:
                owner.terminate()
                try: owner.wait(timeout=5)
                except subprocess.TimeoutExpired: owner.kill(); owner.wait()
    finally:
        model.shutdown(); model.server_close(); sock.unlink(missing_ok=True); socket_dir.rmdir()
    (run / 'result.json').write_text(json.dumps(result, indent=2) + '\n'); print(run / 'result.json'); print(json.dumps(result))

if __name__ == '__main__': main()
