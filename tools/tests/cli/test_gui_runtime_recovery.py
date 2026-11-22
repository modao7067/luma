import contextlib
import io
import json
import os
from pathlib import Path
import re
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RuntimeScripts.kt'

def program(name):
    source = SOURCE.read_text().split('val ' + name + ' = """', 1)[1].split('""".trimIndent()', 1)[0]
    return source.replace("${'$'}", '$')

class Recovery(unittest.TestCase):
    def test_managed_dead_identity_restarts_once_but_live_owner_is_reused(self):
        with tempfile.TemporaryDirectory(prefix='luma-service-recovery-') as folder:
            base = Path(folder); state = base / 'codex.json'; state.write_text('{}')
            args = ['fixture', folder, 'codex', '/readyz', '["dummy","{url}"]']
            class Process:
                pid = os.getpid()
                def poll(self): return None
            class Response:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *args): pass
            opener = type('Opener', (), {'open': lambda *a, **k: Response()})()
            with patch.object(sys, 'argv', args), patch('subprocess.Popen', return_value=Process()) as spawn, patch('urllib.request.build_opener', return_value=opener):
                with self.assertRaises(SystemExit) as started, contextlib.redirect_stdout(io.StringIO()): exec(program('rootService'), {'__name__':'__main__'})
                self.assertEqual(started.exception.code, 0)
                first = json.loads(state.read_text())
                with self.assertRaises(SystemExit) as ended, contextlib.redirect_stdout(io.StringIO()): exec(program('rootService'), {'__name__':'__main__'})
                self.assertEqual(ended.exception.code, 0)
                self.assertEqual(spawn.call_count, 1)
                self.assertEqual(json.loads(state.read_text()), first)
                first['stamp'] = 'dead-owner'; state.write_text(json.dumps(first))
                with self.assertRaises(SystemExit) as started, contextlib.redirect_stdout(io.StringIO()): exec(program('rootService'), {'__name__':'__main__'})
                self.assertEqual(started.exception.code, 0)
                self.assertEqual(spawn.call_count, 2)
                self.assertNotEqual(json.loads(state.read_text())['stamp'], 'dead-owner')

    def test_wedged_opencode_bridge_is_terminated_and_replaced_while_healthy_is_reused(self):
        import signal, subprocess, time, urllib.error
        with tempfile.TemporaryDirectory(prefix='luma-opencode-wedge-') as folder:
            base = Path(folder) / 'runtime/agent/root'
            base.mkdir(parents=True)
            state = base / 'opencode.json'
            state.write_text('{}')
            wedged_sock = socket.socket()
            wedged_sock.bind(('127.0.0.1', 0))
            wedged_sock.listen(1)
            wedged_port = wedged_sock.getsockname()[1]
            wedged_code = (
                "import signal, time\n"
                "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                "time.sleep(60)\n"
            )
            wedged = subprocess.Popen(
                ['python3', '-c', wedged_code, 'opencode', 'serve', '--hostname', '127.0.0.1', '--port', str(wedged_port)],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                pass_fds=(wedged_sock.fileno(),), start_new_session=True,
            )
            wedged_sock.close()
            healthy_proc = None
            try:
                stat_fields = Path('/proc', str(wedged.pid), 'stat').read_text().rsplit(') ', 1)[1].split()
                state.write_text(json.dumps({
                    'pid': wedged.pid,
                    'stamp': stat_fields[19],
                    'url': f'http://127.0.0.1:{wedged_port}',
                    'template': ['/usr/bin/python3', str(base / 'opencode-launch.py'), '{port}', str(base / 'opencode-password')],
                }))
                launcher = base / 'opencode-launch.py'
                launcher.write_text(
                    "import http.server, sys\n"
                    "port = int(sys.argv[1])\n"
                    "class H(http.server.BaseHTTPRequestHandler):\n"
                    "    def do_GET(self):\n"
                    "        self.send_response(401); self.end_headers()\n"
                    "    def log_message(self, *a): pass\n"
                    "http.server.HTTPServer(('127.0.0.1', port), H).serve_forever()\n"
                )
                src = program('rootService').replace("['/bin/bash','--login','-c',shell]", "['/bin/bash','--noprofile','--norc','-c',shell]")
                src = src.replace("cwd='/workspace'", "cwd=" + repr(folder))
                src = src.replace("deadline=time.monotonic()+3.0", "deadline=time.monotonic()+0.5")
                src = src.replace("min(1.5,remaining)", "min(0.25,remaining)")
                src = src.replace("select.select([fd],[],[],1.5)", "select.select([fd],[],[],0.3)")
                args = ['fixture', str(base), 'opencode', '/api/info', json.dumps(['/usr/bin/python3', str(launcher), '{port}'])]
                out = io.StringIO()
                with patch.object(sys, 'argv', args), self.assertRaises(SystemExit) as first, contextlib.redirect_stdout(out):
                    exec(src, {'__name__': '__main__'})
                self.assertEqual(first.exception.code, 0)
                wedged.wait(timeout=2)
                self.assertIsNotNone(wedged.returncode)
                replaced = json.loads(state.read_text())
                healthy_proc = replaced['pid']
                self.assertNotEqual(replaced['pid'], wedged.pid)
                self.assertEqual(json.loads(out.getvalue())['url'], replaced['url'])

                out2 = io.StringIO()
                with patch.object(sys, 'argv', args), self.assertRaises(SystemExit) as second, contextlib.redirect_stdout(out2):
                    exec(src, {'__name__': '__main__'})
                self.assertEqual(second.exception.code, 0)
                self.assertEqual(json.loads(state.read_text())['pid'], healthy_proc)
                self.assertEqual(json.loads(out2.getvalue())['url'], replaced['url'])
            finally:
                if wedged.poll() is None:
                    wedged.kill(); wedged.wait()
                if healthy_proc is not None:
                    with contextlib.suppress(ProcessLookupError):
                        os.kill(healthy_proc, signal.SIGTERM)

    def test_wedged_opencode_with_foreign_cmdline_is_never_signalled(self):
        import subprocess
        with tempfile.TemporaryDirectory(prefix='luma-opencode-foreign-') as folder:
            base = Path(folder)
            state = base / 'opencode.json'
            foreign = subprocess.Popen(['python3', '-c', 'import time; time.sleep(30)'],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                with socket.socket() as dead_sock:
                    dead_sock.bind(('127.0.0.1', 0))
                    dead_port = dead_sock.getsockname()[1]
                stat_fields = Path('/proc', str(foreign.pid), 'stat').read_text().rsplit(') ', 1)[1].split()
                state.write_text(json.dumps({
                    'pid': foreign.pid, 'stamp': stat_fields[19], 'url': f'http://127.0.0.1:{dead_port}', 'template': ['dummy'],
                }))
                src = program('rootService').replace("deadline=time.monotonic()+3.0", "deadline=time.monotonic()+0.3")
                args = ['fixture', str(base), 'opencode', '/api/info', '["dummy"]']
                with patch.object(sys, 'argv', args), self.assertRaisesRegex(SystemExit, '身份已变化'):
                    exec(src, {'__name__': '__main__'})
                self.assertIsNone(foreign.poll())
            finally:
                foreign.kill(); foreign.wait()

    def external(self, live=False, official=True, failure=False):
        temp = tempfile.TemporaryDirectory(prefix='luma-external-recovery-')
        base = Path(temp.name); selected = base / 'native.sock'; home = base / 'home'
        control = home / 'app-server-control/app-server-control.sock'; control.parent.mkdir(parents=True)
        control.symlink_to(selected if official else base / 'other.sock')
        listeners = []
        def bind():
            listener = socket.socket(socket.AF_UNIX); listener.bind(str(selected)); listener.listen(10); listeners.append(listener)
        if live: bind()
        def start(*args, **kwargs):
            if not failure: bind()
            return type('Result', (), {'returncode': 1 if failure else 0, 'stdout': json.dumps({'socketPath':str(control)}).encode()})()
        try:
            with patch.dict(os.environ, {'CODEX_HOME':str(home)}), patch.object(sys, 'argv', ['fixture', str(selected)]), patch('subprocess.run', side_effect=start) as spawn:
                try: exec(program('externalCodexRecovery'), {'__name__':'__main__'})
                except SystemExit as end:
                    if end.code not in (0,None): raise
                return spawn.call_count
        finally:
            for listener in listeners: listener.close()
            temp.cleanup()

    def test_live_external_socket_never_starts_another_owner(self):
        self.assertEqual(self.external(live=True), 0)
    def test_original_official_socket_can_be_restarted_without_fallback(self):
        self.assertEqual(self.external(), 1)
    def test_arbitrary_external_socket_is_not_replaced_by_default_daemon(self):
        with self.assertRaisesRegex(SystemExit, '不是当前官方 daemon'): self.external(official=False)
    def test_failed_official_restart_stays_explicit_and_retains_original_identity(self):
        with self.assertRaisesRegex(SystemExit, '原会话 ID 和未确认消息已保留'): self.external(failure=True)

if __name__ == '__main__': unittest.main(verbosity=2)
