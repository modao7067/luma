#!/usr/bin/env python3
import gc
import json
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import weakref
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.base import Session, item
from luma_cli.server import Manager
from luma_cli.transport import Conflict
from luma_cli.pi import PiSession
from luma_cli.pi_native import PiNativeSession
from luma_cli.claude import ClaudeSession
from luma_cli.dsh import DshSession
from luma_cli.kimi import KimiSession
from luma_cli.qoder import QoderSession
from luma_cli.grok import GrokSession
from luma_cli.antigravity import AntigravitySession
from luma_cli.antigravity_cli import AntigravityCliSession
from luma_cli.codebuddy import CodeBuddySession
from luma_cli.acp import AcpSession
from luma_cli.i18n import is_zh, lang_code, set_lang
from luma_cli.native_owner import NativeOwner, SocketPeer, _verified_tmux_pane_pid, control_socket_settled, foreground_terminal_protects, non_mcp_background_child_alive, pi_process_has_live_subprocesses, process_start, reap_recorded_owner, recorded_owner_alive, stop_recorded_owner, tcp_port_has_established_client, tmux_pi_has_live_subprocesses, unix_socket_has_connected_client

def wait_for(predicate):
    end = time.monotonic() + 5
    while not predicate():
        if time.monotonic() > end: raise AssertionError('resource did not settle')
        time.sleep(.005)

def thread_counts():
    names = ('LumaDelivery', 'LumaCliRead', 'LumaCliDiagnostic')
    return {name: sum(t.name == name for t in threading.enumerate()) for name in names}

class LocalPeer:
    closed = False
    reconnectable = False
    pending = {}
    def __init__(self): self.closes = 0
    def close(self): self.closed = True; self.closes += 1

class Ready(Session):
    def __init__(self):
        super().__init__({'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'native'}, lambda: None)
        self.peer = LocalPeer()
        self.update(connected=True)
    def release_mode(self): return 'process'
    def start_delivery_worker(self): pass

class NativeFixture(Session):
    def release_mode(self): return 'process' if self.data.get('historyReady') else None
    def connect(self):
        native = self.config.get('nativeId') or 'fixture-' + self.config['id']
        history = pathlib.Path(self.config['cwd']) / (native + '.native.json')
        script = pathlib.Path(__file__).with_name('fixtures') / 'idle_peer.py'
        self.start([sys.executable, '-B', '-u', str(script), str(history), native])
        state = self.call('read')
        if state['nativeId'] != native: raise AssertionError('foreign fixture identity')
        for row in state['messages']: self.row(row)
        self.update(nativeId=native, connected=True, historyReady=True)
    def prompt(self, payload):
        self.update(running=True)
        try:
            row = self.call('append', {'id': payload['submissionId'], 'text': payload['text']})
            self.row(row); self.consumed(payload['submissionId'])
            return True
        finally: self.update(running=False)

class ReclaimTests(unittest.TestCase):
    def test_idle_connect_does_not_allocate_a_waiting_delivery_thread(self):
        before = thread_counts()['LumaDelivery']
        sessions = [Session({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None) for _ in range(80)]
        for session in sessions: session.start_delivery_worker()
        self.assertTrue(all(s.delivery_worker is None for s in sessions))
        self.assertEqual(thread_counts()['LumaDelivery'], before)

    def test_worker_drains_then_restarts_without_lost_enqueue_or_two_executors(self):
        session = Session({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
        session.update(connected=True)
        sent = []; active = 0; maximum = 0
        def prompt(payload):
            nonlocal active, maximum
            active += 1; maximum = max(maximum, active)
            time.sleep(.0002)
            sent.append(payload['submissionId']); active -= 1
            return True
        session.prompt = prompt
        for group in range(30):
            threads = [threading.Thread(target=lambda ident=f'{group}-{n}': session.admit({'submissionId': ident, 'text': ident})) for n in range(4)]
            for thread in threads: thread.start()
            for thread in threads: thread.join()
            wait_for(lambda: session.delivery_worker is None)
        self.assertEqual(len(sent), 120); self.assertEqual(len(set(sent)), 120)
        self.assertEqual(maximum, 1)

    def test_blocked_queue_waits_without_polling_and_cancellation_retires_worker(self):
        session = Session({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
        session.update(connected=True, running=True)
        session.prompt = lambda _: self.fail('busy queued message was sent')
        session.admit({'submissionId': 'a', 'text': 'a'})
        worker = session.delivery_worker
        time.sleep(.03)
        self.assertTrue(worker.is_alive())
        self.assertFalse(session.delivery_wake.is_set())
        session.cancel_pending('a'); wait_for(lambda: session.delivery_worker is None)

    def test_every_uncertain_or_active_state_rejects_release_without_changes(self):
        changes = [
            lambda s: s.update(running=True), lambda s: s.update(connected=False),
            lambda s: s.update(connecting=True), lambda s: s.update(requests=[{'id': 'approval'}]),
            lambda s: s.pending_requests.update(approval={}), lambda s: s.update(queue=[{'id': 'native'}]),
            lambda s: s.update(nativeQueuePending=True),
            lambda s: s.config.update(deliveries={'a': {'state': 'queued', 'payload': {'text': 'queued'}}}),
            lambda s: s.config.update(deliveries={'a': {'state': 'dispatching'}}),
            lambda s: s.config.update(deliveries={'a': {'state': 'unknown'}}),
            lambda s: s.config.update(receipts={'legacy': 'unknown'}),
            lambda s: s.config.update(receipts={'legacy': 'pending'}),
            lambda s: s.subagent('child', status='running'), lambda s: s.subagent('child', status='unknown'),
            lambda s: s.row(item('tool', 'tool', status='unknown')),
            lambda s: setattr(s, 'delivery_storage_error', 'failed checkpoint'),
            lambda s: setattr(s, 'action_leases', 1), lambda s: setattr(s, 'current_submission', 'prompt'),
            lambda s: setattr(s.peer, 'pending', {'rpc': object()}),
        ]
        for change in changes:
            with self.subTest(change=change):
                session = Ready(); change(session)
                before = json.dumps(session.config, sort_keys=True)
                self.assertFalse(session.snapshot()['releasable'])
                with self.assertRaises(Conflict): session.release_resources('native')
                self.assertEqual(json.dumps(session.config, sort_keys=True), before)
                self.assertFalse(session.released); self.assertEqual(session.peer.closes, 0)

    def test_identity_and_save_failure_do_not_release(self):
        session = Ready(); session.config['nativeProtocol'] = 'original'
        for native, protocol in [('other', 'original'), ('native', 'other'), (None, None)]:
            with self.assertRaises(Conflict): session.release_resources(native, protocol)
        session.save = lambda: (_ for _ in ()).throw(OSError('fixture disk failure'))
        with self.assertRaises(OSError): session.release_resources('native', 'original')
        self.assertFalse(session.released); self.assertEqual(session.peer.closes, 0)

    def test_admission_and_release_have_exactly_one_winner(self):
        for _ in range(100):
            session = Ready(); barrier = threading.Barrier(2); outcomes = []
            def run(name, fn):
                barrier.wait()
                try: fn(); outcomes.append(name)
                except Conflict: pass
            a = threading.Thread(target=run, args=('admit', lambda: session.admit({'submissionId': 'a', 'text': 'a'})))
            b = threading.Thread(target=run, args=('release', lambda: session.release_resources('native')))
            a.start(); b.start(); a.join(); b.join()
            self.assertEqual(len(outcomes), 1)
            if outcomes == ['release']:
                with self.assertRaises(Conflict): session.admit({'submissionId': 'b', 'text': 'b'})
                with self.assertRaises(Conflict): session.action('send', {'submissionId': 'b', 'text': 'b'})
            else: self.assertEqual(session.config['deliveries']['a']['state'], 'queued')

    def test_multisession_native_history_and_threads_return_to_baseline(self):
        baseline = thread_counts(); samples = {'baseline': baseline}; weak = []
        with tempfile.TemporaryDirectory(prefix='luma-idle-reclaim-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager = Manager(pathlib.Path(directory) / 'records')
            for round_number in range(2):
                sessions = [manager.connect({'id': f's{n}', 'cli': 'fixture', 'cwd': directory}) for n in range(12)]
                self.assertEqual(len(manager.sessions), 12)
                self.assertEqual(thread_counts()['LumaDelivery'], baseline['LumaDelivery'])
                samples[f'round{round_number}-connected'] = thread_counts()
                for n, session in enumerate(sessions):
                    if round_number:
                        self.assertEqual([r['text'] for r in session.rows.values()], [f'original-{n}'])
                    else:
                        session.admit({'submissionId': f'message-{n}', 'text': f'original-{n}'})
                for session in sessions:
                    wait_for(lambda: session.delivery_worker is None)
                    self.assertTrue(session.snapshot()['releasable'])
                samples[f'round{round_number}-settled'] = thread_counts()
                for n, session in enumerate(sessions):
                    native = session.config['nativeId']; peer = session.peer
                    weak.append(weakref.ref(session))
                    result = manager.release(f's{n}', {'nativeId': native})
                    self.assertEqual(result, {'released': True, 'nativeId': native})
                    self.assertIsNotNone(peer.proc.poll())
                    self.assertTrue((manager.directory / f's{n}.json').is_file())
                    native_rows = json.loads((pathlib.Path(directory) / (native + '.native.json')).read_text())
                    self.assertEqual(len(native_rows), 1)
                    if n + 1 < len(sessions): self.assertIsNone(sessions[n + 1].peer.proc.poll())
                self.assertEqual(manager.sessions, {})
                with self.assertRaises(Conflict): manager.get('s0')
                del sessions, session, peer
                wait_for(lambda: thread_counts() == baseline)
                gc.collect(); self.assertTrue(all(ref() is None for ref in weak))
                samples[f'round{round_number}-released'] = thread_counts()
        print('RESOURCE_EVIDENCE ' + json.dumps(samples, sort_keys=True))

    def test_detached_live_owner_blocks_duplicate_but_history_and_dead_owner_do_not(self):
        with tempfile.TemporaryDirectory(prefix='luma-idle-owner-index-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager = Manager(pathlib.Path(directory) / 'records')
            session = manager.connect({'id': 'old', 'cli': 'fixture', 'cwd': directory})
            native = session.config['nativeId']
            session.config['nativeOwner'] = {'kind': 'tmux', 'instanceId': 'fixture-owner'}
            manager.release('old', {'nativeId': native})
            attach = {'id': 'reattached', 'cli': 'fixture', 'cwd': directory, 'nativeId': native}
            with patch('luma_cli.server.recorded_owner_alive', return_value=True):
                with self.assertRaisesRegex(Exception, '不能启动第二个执行进程' if is_zh() else 'instead of starting a second process'): manager.connect(attach)
                self.assertFalse(manager.sessions)
            with patch('luma_cli.server.recorded_owner_alive', return_value=False):
                restored = manager.connect(attach)
                self.assertEqual(restored.config['nativeId'], native)
                manager.release('reattached', {'nativeId': native})
            old_path = manager.directory / 'old.json'
            config = json.loads(old_path.read_text()); del config['nativeOwner']; old_path.write_text(json.dumps(config))
            with patch('luma_cli.server.recorded_owner_alive', side_effect=AssertionError('history is not an owner lease')):
                restored = manager.connect({'id': 'after-archive', 'cli': 'fixture', 'cwd': directory, 'nativeId': native})
                manager.release('after-archive', {'nativeId': native})

    def test_recorded_owner_probe_is_read_only_and_checks_process_identity(self):
        import os
        self.assertFalse(recorded_owner_alive({'nativeId': 'historical'}))
        self.assertTrue(recorded_owner_alive({'nativeOwner': {'kind': 'grok-leader', 'pid': os.getpid(), 'processStart': process_start(os.getpid())}}))
        self.assertFalse(recorded_owner_alive({'nativeOwner': {'kind': 'grok-leader', 'pid': os.getpid(), 'processStart': 'wrong-start'}}))
        self.assertFalse(recorded_owner_alive({'nativeOwner': {'kind': 'tmux', 'tmuxCommand': ['/bin/false'], 'terminalSocket': '/unused-fixture'}}))
        self.assertIsNone(recorded_owner_alive({'nativeOwner': {'kind': 'tmux'}}))

    def test_http_release_conflict_and_original_identity_reconnect(self):
        with tempfile.TemporaryDirectory(prefix='luma-idle-http-') as directory:
            token = 'fixture-' + 'x' * 48
            token_path = pathlib.Path(directory) / 'token'; token_path.write_text(token)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            bootstrap = "import sys;sys.path[:0]=" + repr([str(ROOT / 'tools/tests/cli'), str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge')]) + ";from test_idle_reclaim import NativeFixture;from luma_cli import server;server.ADAPTERS={'fixture':NativeFixture};server.serve(*sys.argv[1:])"
            proc = subprocess.Popen([sys.executable, '-B', '-c', bootstrap, str(port), str(token_path), str(pathlib.Path(directory) / 'records')],
                cwd=directory, env={'HOME': directory, 'PATH': '/usr/bin', 'LANG': 'C.UTF-8'}, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def call(path, body=None):
                request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Connection': 'close'})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response: return response.status, json.load(response)
                except urllib.error.HTTPError as error:
                    with error: return error.code, json.load(error)
            try:
                def available():
                    try: return call('/health')[0] == 200
                    except OSError: return False
                wait_for(available)
                connect = {'id': 'http', 'cli': 'fixture', 'cwd': directory}
                self.assertEqual(call('/sessions/connect', connect)[0], 200)
                wait_for(lambda: call('/sessions/http')[1].get('connected'))
                native = call('/sessions/http')[1]['nativeId']
                self.assertEqual(call('/sessions/http/release', {'nativeId': 'foreign'})[0], 409)
                self.assertTrue(call('/sessions/http')[1]['connected'])
                self.assertEqual(call('/sessions/http/submit', {'submissionId': 'once', 'text': 'history'})[0], 200)
                wait_for(lambda: call('/sessions/http')[1].get('releasable'))
                code, result = call('/sessions/http/release', {'nativeId': native})
                self.assertEqual(code, 200); self.assertEqual(result, {'released': True, 'nativeId': native})
                self.assertEqual(call('/sessions/http')[0], 409)
                self.assertEqual(call('/sessions/http/submit', {'submissionId': 'late', 'text': 'late'})[0], 409)
                connect['nativeId'] = native
                self.assertEqual(call('/sessions/connect', connect)[0], 200)
                wait_for(lambda: call('/sessions/http')[1].get('connected'))
                restored = call('/sessions/http')[1]
                self.assertEqual(restored['nativeId'], native)
                self.assertEqual([r['text'] for r in restored['messages']], ['history'])
                call('/sessions/http/release', {'nativeId': native})
            finally:
                proc.terminate(); proc.communicate(timeout=8)

    def test_adapter_release_scope_requires_actual_recovery_evidence(self):
        with tempfile.TemporaryDirectory(prefix='luma-reclaim-capability-') as directory:
            persisted = pathlib.Path(directory) / 'native-history'; persisted.write_text('fixture')
            for cls in (PiSession, PiNativeSession):
                session = cls({'cli': 'pi', 'cwd': directory}, lambda: None)
                session.peer = LocalPeer(); session.queue_readable = True
                self.assertIsNone(session.release_mode())
                session.config['sessionFile'] = str(persisted)
                self.assertEqual(session.release_mode(), 'process')
                session.peer.reconnectable = True
                self.assertEqual(session.release_mode(), 'observer')
            session = ClaudeSession({'cli': 'Claude Code', 'cwd': directory, 'nativeId': 'native'}, lambda: None)
            session.history_path = lambda: persisted
            self.assertEqual(session.release_mode(), 'process')
            persisted.unlink(); self.assertIsNone(session.release_mode())
        for cls in (KimiSession, QoderSession, GrokSession):
            session = cls({'cli': 'fixture', 'cwd': '/tmp'}, lambda: None)
            session.native_caps = {'loadSession': True}; session.update(historyReady=True)
            self.assertEqual(bool(session.release_mode()), cls is QoderSession)
            session.row(item('native-user', 'user', 'native', origin='native'))
            self.assertEqual(session.release_mode(), 'process')
            session.native_caps = {'loadSession': False}; self.assertIsNone(session.release_mode())
            if cls is GrokSession:
                session.native_caps = {'loadSession': True}
                session.config['receipts'] = {'interjection': 'accepted'}
                self.assertIsNone(session.release_mode())
                session.config['consumedSubmissions'] = {'interjection': True}
                self.assertEqual(session.release_mode(), 'process')
        cb = CodeBuddySession({'cli': 'CodeBuddy Code', 'cwd': '/tmp'}, lambda: None)
        self.assertIsNone(cb.release_mode())
        cb.native_caps = {'loadSession': True}; cb.update(historyReady=True)
        self.assertEqual(cb.release_mode(), 'process')
        session = DshSession({'cli': 'DeepSeek Harness', 'cwd': '/tmp'}, lambda: None)
        session.update(historyReady=True); session.row(item('user', 'user'))
        self.assertIsNone(session.release_mode())
        session.history_protocol = True; self.assertEqual(session.release_mode(), 'process')
        session.update(historyReady=False); self.assertIsNone(session.release_mode())
        for cls in (AntigravitySession, AntigravityCliSession):
            self.assertIsNone(cls({'cli': 'Antigravity', 'cwd': '/tmp'}, lambda: None).release_mode())
        agy_acp = AntigravitySession({'cli': 'Antigravity', 'cwd': '/tmp'}, lambda: None)
        agy_acp.native_caps = {'loadSession': True}; agy_acp.update(historyReady=True)
        self.assertEqual(agy_acp.release_mode(), 'process')
        agy_cli = AntigravityCliSession({'cli': 'Antigravity', 'cwd': '/tmp', 'nativeId': 'agy-1', 'localProjection': {'nativeId': 'agy-1'}}, lambda: None)
        agy_cli.update(historyReady=True)
        self.assertEqual(agy_cli.release_mode(), 'process')
        attached_pi = PiSession({'cli': 'pi', 'cwd': '/tmp', 'nativeId': 'external-pi'}, lambda: None)
        attached_pi.peer = LocalPeer(); attached_pi.queue_readable = True; attached_pi.update(connected=True)
        self.assertIsNone(attached_pi.release_mode())
        managed_pi = PiSession({'cli': 'pi', 'cwd': '/tmp', 'nativeId': 'managed-pi', 'managedIdentity': True}, lambda: None)
        managed_pi.peer = LocalPeer(); managed_pi.queue_readable = True; managed_pi.update(connected=True)
        self.assertEqual(managed_pi.release_mode(), 'process')

    def test_socket_close_detaches_without_stopping_native_owner(self):
        import socket
        a, b = socket.socketpair()
        class Owner:
            def alive(self): return True
        peer = SocketPeer.__new__(SocketPeer)
        peer.socket = a; peer.reader = a.makefile('rb'); peer.closed = False
        peer.proc = type('Process', (), {'poll': lambda _: None})()
        peer.close()
        self.assertTrue(peer.reader.closed); self.assertIsNone(peer.proc.poll())
        b.close()

    def test_native_owner_tmux_stops_when_unattached_preserves_attached_tui_and_destroy_cleans_state(self):
        import fcntl, os, pty, struct, termios
        with tempfile.TemporaryDirectory(prefix='luma-tmux-reclaim-') as directory:
            state_dir = pathlib.Path(directory) / 'records'
            manager = Manager(state_dir)
            owner_dir = state_dir / 's1.native'
            owner_dir.mkdir(parents=True, exist_ok=True)
            config = {'id': 's1', 'cli': 'pi', 'cwd': directory, 'nativeId': 'pi-empty-1',
                      'nativeProtocol': 'pi-native-sdk-v1', 'ownerDirectory': str(owner_dir)}
            record_path = state_dir / 's1.json'
            def save(): record_path.write_text(json.dumps(config))
            save()
            session = PiNativeSession(config, save)
            session.owner = NativeOwner(session)
            session.owner.launch_tui(['/bin/sh', '-c', 'trap "exit 0" TERM; while :; do sleep 0.2; done'])
            session.peer = LocalPeer(); session.peer.reconnectable = True
            session.update(connected=True)
            self.assertEqual(session.release_mode(), 'observer')
            self.assertTrue(session.owner.alive())

            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 110, 0, 0))
            attached = subprocess.Popen(session.owner.terminal()['argv'], cwd=directory,
                env={**os.environ, 'TERM': 'xterm-256color'},
                stdin=slave, stdout=slave, stderr=slave, close_fds=True)
            os.close(slave)
            try:
                wait_for(lambda: session.owner.has_attached_client())
                session.release_resources('pi-empty-1', 'pi-native-sdk-v1')
                self.assertTrue(session.owner.alive())
                self.assertIn('nativeOwner', config)
            finally:
                try: os.close(master)
                except OSError: pass
                attached.terminate()
                try: attached.wait(timeout=3)
                except subprocess.TimeoutExpired: attached.kill(); attached.wait(timeout=3)

            wait_for(lambda: not session.owner.has_attached_client())
            session2 = PiNativeSession(config, save)
            session2.owner = NativeOwner(session2)
            session2.peer = LocalPeer(); session2.peer.reconnectable = True
            session2.update(connected=True)
            session2.release_resources('pi-empty-1', 'pi-native-sdk-v1')
            self.assertFalse(session2.owner.alive())
            self.assertNotIn('nativeOwner', config)
            self.assertFalse(session2.owner.directory.exists())

            session3 = PiNativeSession(config, save)
            session3.owner = NativeOwner(session3)
            session3.owner.launch_tui(['/bin/sh', '-c', 'while :; do sleep 0.2; done'])
            session3.peer = LocalPeer(); session3.peer.reconnectable = True
            session3.update(connected=True)
            manager.sessions['s1'] = session3
            old_dir = session3.owner.directory
            manager.destroy('s1', {'deleteState': True})
            self.assertFalse(old_dir.exists())
            self.assertFalse(record_path.exists())
            self.assertFalse(owner_dir.exists())

    def test_bridge_idle_reaper_releases_unpolled_idle_sessions_and_exits_cleanly(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-reaper-http-') as directory:
            token = 'fixture-' + 'x' * 48
            token_path = pathlib.Path(directory) / 'token'; token_path.write_text(token)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            bootstrap = "import sys;sys.path[:0]=" + repr([str(ROOT / 'tools/tests/cli'), str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge')]) + ";from test_idle_reclaim import NativeFixture;from luma_cli import server;server.ADAPTERS={'fixture':NativeFixture};server.serve(*sys.argv[1:])"
            proc = subprocess.Popen([sys.executable, '-B', '-c', bootstrap, str(port), str(token_path), str(pathlib.Path(directory) / 'records')],
                cwd=directory, env={**os.environ, 'HOME': directory, 'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LUMA_BRIDGE_IDLE_EXIT_SEC': '2'},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def call(path, body=None):
                request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Connection': 'close'})
                with urllib.request.urlopen(request, timeout=5) as response: return response.status, json.load(response)
            try:
                wait_for(lambda: (lambda: call('/health')[0] == 200)() if True else False)
            except Exception:
                pass
            def available():
                try: return call('/health')[0] == 200
                except OSError: return False
            try:
                wait_for(available)
                self.assertEqual(call('/sessions/connect', {'id': 'orphan', 'cli': 'fixture', 'cwd': directory})[0], 200)
                wait_for(lambda: call('/sessions/orphan')[1].get('releasable'))
                code = proc.wait(timeout=8)
                self.assertEqual(code, 0)
            finally:
                if proc.poll() is None:
                    proc.terminate()
                proc.communicate(timeout=5)

    def test_bridge_idle_reaper_unloads_idle_session_while_sibling_session_keeps_polling(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-reaper-sibling-') as directory:
            token = 'fixture-' + 'y' * 48
            token_path = pathlib.Path(directory) / 'token'; token_path.write_text(token)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            bootstrap = "import sys;sys.path[:0]=" + repr([str(ROOT / 'tools/tests/cli'), str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge')]) + ";from test_idle_reclaim import NativeFixture;from luma_cli import server;server.ADAPTERS={'fixture':NativeFixture};server.serve(*sys.argv[1:])"
            proc = subprocess.Popen([sys.executable, '-B', '-c', bootstrap, str(port), str(token_path), str(pathlib.Path(directory) / 'records')],
                cwd=directory, env={**os.environ, 'HOME': directory, 'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LUMA_BRIDGE_IDLE_EXIT_SEC': '2'},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def call(path, body=None):
                request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Connection': 'close'})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response: return response.status, json.load(response)
                except urllib.error.HTTPError as err:
                    try: return err.code, json.loads(err.read().decode())
                    finally: err.close()
            def available():
                try: return call('/health')[0] == 200
                except OSError: return False
            try:
                wait_for(available)
                self.assertEqual(call('/sessions/connect', {'id': 'active', 'cli': 'fixture', 'cwd': directory})[0], 200)
                self.assertEqual(call('/sessions/connect', {'id': 'idle', 'cli': 'fixture', 'cwd': directory})[0], 200)
                wait_for(lambda: call('/sessions/active')[1].get('releasable') and call('/sessions/idle')[1].get('releasable'))
                for _ in range(10):
                    self.assertEqual(call('/sessions/active')[0], 200)
                    time.sleep(0.4)
                idle_code, _ = call('/sessions/idle')
                self.assertEqual(idle_code, 409)
                active_code, active_snap = call('/sessions/active')
                self.assertEqual(active_code, 200)
                self.assertTrue(active_snap.get('connected'))
            finally:
                if proc.poll() is None:
                    proc.terminate()
                proc.communicate(timeout=5)

    def test_reap_detached_owners_preserves_fresh_tui_and_stops_stale_attached_tui(self):
        import fcntl, os, pty, struct, termios
        with tempfile.TemporaryDirectory(prefix='luma-tmux-stale-') as directory:
            state_dir = pathlib.Path(directory) / 'records'
            manager = Manager(state_dir)
            owner_dir = state_dir / 's1.native'
            owner_dir.mkdir(parents=True, exist_ok=True)
            config = {'id': 's1', 'cli': 'pi', 'cwd': directory, 'nativeId': 'pi-empty-1',
                      'nativeProtocol': 'pi-native-sdk-v1', 'ownerDirectory': str(owner_dir)}
            record_path = state_dir / 's1.json'
            def save(): record_path.write_text(json.dumps(config))
            save()
            session = PiNativeSession(config, save)
            session.owner = NativeOwner(session)
            session.owner.launch_tui(['/bin/sh', '-c', 'trap "exit 0" TERM; while :; do sleep 0.2; done'])
            session.peer = LocalPeer(); session.peer.reconnectable = True
            session.update(connected=True)
            manager.sessions['s1'] = session

            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 110, 0, 0))
            attached = subprocess.Popen(session.owner.terminal()['argv'], cwd=directory,
                env={**os.environ, 'TERM': 'xterm-256color'},
                stdin=slave, stdout=slave, stderr=slave, close_fds=True)
            os.close(slave)
            try:
                wait_for(lambda: session.owner.has_attached_client())
                manager.release('s1', {'nativeId': 'pi-empty-1', 'nativeProtocol': 'pi-native-sdk-v1'})
                self.assertNotIn('s1', manager.sessions)
                self.assertTrue(session.owner.alive())
                self.assertIn('nativeOwner', json.loads(record_path.read_text()))
                manager.reap_detached_owners(60)
                self.assertTrue(session.owner.alive())
                self.assertIn('nativeOwner', json.loads(record_path.read_text()))
                time.sleep(2.1)
                manager.reap_detached_owners(1)
                self.assertFalse(session.owner.alive())
                self.assertFalse(session.owner.directory.exists())
                self.assertNotIn('nativeOwner', json.loads(record_path.read_text()))
            finally:
                try: os.close(master)
                except OSError: pass
                if attached.poll() is None:
                    attached.terminate()
                    try: attached.wait(timeout=3)
                    except subprocess.TimeoutExpired: attached.kill(); attached.wait(timeout=3)
                stop_recorded_owner(config)

    def test_watch_poll_header_does_not_keep_idle_session_or_bridge_alive(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-reaper-watch-') as directory:
            token = 'fixture-' + 'w' * 48
            token_path = pathlib.Path(directory) / 'token'; token_path.write_text(token)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            bootstrap = "import sys;sys.path[:0]=" + repr([str(ROOT / 'tools/tests/cli'), str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge')]) + ";from test_idle_reclaim import NativeFixture;from luma_cli import server;server.ADAPTERS={'fixture':NativeFixture};server.serve(*sys.argv[1:])"
            proc = subprocess.Popen([sys.executable, '-B', '-c', bootstrap, str(port), str(token_path), str(pathlib.Path(directory) / 'records')],
                cwd=directory, env={**os.environ, 'HOME': directory, 'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LUMA_BRIDGE_IDLE_EXIT_SEC': '2'},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def call(path, body=None, watch=False):
                headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Connection': 'close'}
                if watch:
                    headers['X-Luma-Watch'] = '1'
                request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                    data=None if body is None else json.dumps(body).encode(), headers=headers)
                try:
                    with urllib.request.urlopen(request, timeout=5) as response: return response.status, json.load(response)
                except urllib.error.HTTPError as err:
                    try: return err.code, json.loads(err.read().decode())
                    finally: err.close()
            def available():
                try: return call('/health')[0] == 200
                except OSError: return False
            try:
                wait_for(lambda: proc.poll() is None and available())
                code, snap = call('/sessions/connect', {'id': 'idle', 'cli': 'fixture', 'cwd': directory})
                self.assertEqual(code, 200)
                wait_for(lambda: call('/sessions/idle', watch=True)[1].get('releasable') is True)
                deadline = time.monotonic() + 5.0
                unloaded = False
                while time.monotonic() < deadline:
                    try:
                        status, _ = call('/sessions/idle', watch=True)
                    except OSError:
                        unloaded = True
                        break
                    if status == 409:
                        unloaded = True
                        break
                    time.sleep(0.2)
                self.assertTrue(unloaded, 'X-Luma-Watch poll kept idle session alive past reaper window')
                self.assertEqual(proc.wait(timeout=4), 0)
            finally:
                if proc.poll() is None:
                    proc.terminate()
                proc.communicate(timeout=5)

    def test_pi_host_empty_queue_dict_reaps_while_nonempty_queue_or_background_subprocess_blocks(self):
        with tempfile.TemporaryDirectory(prefix='luma-pi-reap-') as directory:
            sock_path = pathlib.Path(directory) / 'c.sock'
            state = {'isSettled': True, 'isStreaming': False, 'isCompacting': False,
                     'pendingMessageCount': 0, 'queuedMessages': {'steering': [], 'followUp': []},
                     'requests': [], 'receipts': {'r1': {'state': 'accepted'}}}
            stop_server = threading.Event()
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            srv.bind(str(sock_path))
            srv.listen(8)
            srv.settimeout(0.1)
            def serve_sock():
                while not stop_server.is_set():
                    try:
                        conn, _ = srv.accept()
                    except socket.timeout:
                        continue
                    except OSError:
                        break
                    with conn:
                        buf = b''
                        while b'\n' not in buf:
                            chunk = conn.recv(4096)
                            if not chunk:
                                break
                            buf += chunk
                        if buf.strip():
                            req = json.loads(buf.splitlines()[0].decode('utf-8'))
                            resp = {'id': req.get('id'), 'type': 'response', 'command': req.get('type'),
                                    'success': True, 'data': json.loads(json.dumps(state))}
                            conn.sendall((json.dumps(resp) + '\n').encode('utf-8'))
            server_thread = threading.Thread(target=serve_sock, daemon=True)
            server_thread.start()

            state_dir = pathlib.Path(directory) / 'records'
            owner_dir = state_dir / 's1.native'
            owner_dir.mkdir(parents=True, exist_ok=True)
            bg_flag = pathlib.Path(directory) / 'bg.on'
            bg_flag.write_text('1')
            sup_ready = pathlib.Path(directory) / 'sup.ready'
            bg_ready = pathlib.Path(directory) / 'bg.ready'
            sup_code = '\n'.join([
                'import pathlib, signal, subprocess, sys, time',
                'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
                'p = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "agy acp"])',
                'pathlib.Path(sys.argv[1]).write_text("1")',
                'try:',
                '    while True: time.sleep(0.05)',
                'finally:',
                '    p.terminate(); p.wait()',
            ])
            host_code = '\n'.join([
                'import pathlib, signal, subprocess, sys, time',
                'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
                'bg_flag, bg_ready, sup_code, sup_ready = sys.argv[1:5]',
                'sup = subprocess.Popen([sys.executable, "-c", sup_code, sup_ready, "supervisor.mjs"])',
                'bg = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "bg-script"]) if pathlib.Path(bg_flag).exists() else None',
                'if bg is not None: pathlib.Path(bg_ready).write_text("1")',
                'try:',
                '    while True:',
                '        if bg is not None and not pathlib.Path(bg_flag).exists():',
                '            bg.terminate(); bg.wait(); bg = None',
                '        time.sleep(0.05)',
                'finally:',
                '    if bg is not None: bg.terminate(); bg.wait()',
                '    sup.terminate(); sup.wait()',
            ])
            config = {'id': 's1', 'cli': 'pi', 'cwd': directory, 'nativeId': 'pi-empty-dict',
                      'nativeProtocol': 'pi-native-sdk-v1', 'ownerDirectory': str(owner_dir)}
            record_path = state_dir / 's1.json'
            def save(): record_path.write_text(json.dumps(config))
            save()
            session = PiNativeSession(config, save)
            session.owner = NativeOwner(session)
            try:
                self.assertTrue(control_socket_settled(str(sock_path)))
                state['queuedMessages'] = {'steering': [{'id': 'q1'}], 'followUp': []}
                self.assertFalse(control_socket_settled(str(sock_path)))
                state['queuedMessages'] = {'steering': [], 'followUp': []}
                wait_for(lambda: not unix_socket_has_connected_client(str(sock_path)))
                self.assertTrue(control_socket_settled(str(sock_path)))

                session.owner.launch_tui([sys.executable, '-c', host_code, str(bg_flag), str(bg_ready), sup_code, str(sup_ready), 'pi-host.mjs'])
                config['nativeOwner']['socket'] = str(sock_path)
                save()
                session.peer = LocalPeer(); session.peer.reconnectable = True
                session.update(connected=True)
                wait_for(lambda: sup_ready.exists() and bg_ready.exists())

                self.assertIsNotNone(session.release_blocker())
                self.assertFalse(reap_recorded_owner(config, 0))
                self.assertTrue(session.owner.alive())

                bg_flag.unlink()
                wait_for(lambda: session.release_blocker() is None)
                self.assertTrue(reap_recorded_owner(config, 0))
                self.assertFalse(session.owner.alive())
            finally:
                stop_server.set()
                try: srv.close()
                except OSError: pass
                server_thread.join(timeout=2)
                stop_recorded_owner(config)

    def test_cross_daemon_observer_and_foreground_terminal_protect_from_reap(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-fg-protect-') as directory:
            runtime_dir = pathlib.Path(directory) / 'runtime'
            state_dir = runtime_dir / 'agent/root/cli-sessions'
            owner_dir = state_dir / 's1.native'
            owner_dir.mkdir(parents=True, exist_ok=True)
            sock_path = pathlib.Path(directory) / 'ctrl.sock'
            state = {'isSettled': True, 'isStreaming': False, 'isCompacting': False,
                     'pendingMessageCount': 0, 'queuedMessages': {'steering': [], 'followUp': []},
                     'requests': [], 'receipts': {}, 'observerCount': 1}
            stop_server = threading.Event()
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            srv.bind(str(sock_path))
            srv.listen(8)
            srv.settimeout(0.1)
            conns = []
            def handle_conn(conn):
                with conn:
                    conn.settimeout(0.2)
                    buf = b''
                    while not stop_server.is_set():
                        try:
                            chunk = conn.recv(4096)
                        except socket.timeout:
                            continue
                        except OSError:
                            break
                        if not chunk:
                            break
                        buf += chunk
                        while b'\n' in buf:
                            line, buf = buf.split(b'\n', 1)
                            if not line.strip():
                                continue
                            req = json.loads(line.decode('utf-8'))
                            resp = {'id': req.get('id'), 'type': 'response', 'command': req.get('type'),
                                    'success': True, 'data': json.loads(json.dumps(state))}
                            conn.sendall((json.dumps(resp) + '\n').encode('utf-8'))
            def accept_loop():
                while not stop_server.is_set():
                    try:
                        conn, _ = srv.accept()
                    except socket.timeout:
                        continue
                    except OSError:
                        break
                    conns.append(conn)
                    threading.Thread(target=handle_conn, args=(conn,), daemon=True).start()
            server_thread = threading.Thread(target=accept_loop, daemon=True)
            server_thread.start()

            config = {'id': 's1', 'cli': 'pi', 'cwd': directory, 'nativeId': 'pi-fg-1',
                      'nativeProtocol': 'pi-native-sdk-v1', 'ownerDirectory': str(owner_dir)}
            record_path = state_dir / 's1.json'
            def save(): record_path.write_text(json.dumps(config))
            save()
            manager1 = Manager(state_dir)
            manager2 = Manager(state_dir)
            session = PiNativeSession(config, save)
            session.owner = NativeOwner(session)
            session.owner.launch_tui(['/bin/sh', '-c', 'trap "exit 0" TERM; while :; do sleep 0.2; done'])
            config['nativeOwner']['socket'] = str(sock_path)
            save()
            manager1._acquire_observer_lock('s1')
            client_sock = None
            marker = runtime_dir / 'foreground-terminal.json'
            try:
                client_sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                client_sock.connect(str(sock_path))
                wait_for(lambda: unix_socket_has_connected_client(str(sock_path)))
                self.assertTrue(manager2._has_remote_observer('s1'))
                manager2.reap_detached_owners(0)
                self.assertTrue(session.owner.alive())

                manager1._release_observer_lock('s1')
                self.assertFalse(manager2._has_remote_observer('s1'))
                self.assertFalse(reap_recorded_owner(config, 0))
                self.assertTrue(session.owner.alive())

                with patch('luma_cli.native_owner.unix_socket_has_connected_client', return_value=False):
                    state['observerCount'] = 2
                    self.assertFalse(control_socket_settled(str(sock_path)))
                    state['observerCount'] = 1
                    self.assertTrue(control_socket_settled(str(sock_path)))

                client_sock.close()
                client_sock = None
                wait_for(lambda: not unix_socket_has_connected_client(str(sock_path)))

                marker.write_text(json.dumps({
                    'token': 'fg-token-1',
                    'pid': os.getpid(),
                    'processStart': process_start(os.getpid()),
                    'windowId': 'a' * 32,
                    'sessionId': 's1',
                    'instanceId': session.owner.handle['instanceId'],
                    'updatedAt': int(time.time() * 1000),
                }))
                self.assertTrue(foreground_terminal_protects(config))
                self.assertFalse(reap_recorded_owner(config, 0))
                self.assertTrue(session.owner.alive())

                marker.unlink()
                self.assertFalse(foreground_terminal_protects(config))
                self.assertTrue(reap_recorded_owner(config, 0))
                self.assertFalse(session.owner.alive())
            finally:
                if client_sock is not None:
                    try: client_sock.close()
                    except OSError: pass
                manager1._release_observer_lock('s1')
                stop_server.set()
                for c in conns:
                    try: c.close()
                    except OSError: pass
                try: srv.close()
                except OSError: pass
                server_thread.join(timeout=2)
                stop_recorded_owner(config)

    def test_codebuddy_owner_reap_respects_tcp_client_and_background_child(self):
        with tempfile.TemporaryDirectory(prefix='luma-cb-reap-') as directory:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.bind(('127.0.0.1', 0))
            srv.listen(4)
            port = srv.getsockname()[1]
            bg_flag = pathlib.Path(directory) / 'bg.on'
            bg_ready = pathlib.Path(directory) / 'bg.ready'
            bg_flag.write_text('1')
            owner_code = '\n'.join([
                'import pathlib, signal, subprocess, sys, time',
                'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
                'flag, ready = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])',
                'bg = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], stdin=subprocess.DEVNULL)',
                'ready.write_text("1")',
                'try:',
                '    while True:',
                '        if bg is not None and not flag.exists():',
                '            bg.terminate(); bg.wait(); bg = None',
                '        time.sleep(0.05)',
                'finally:',
                '    if bg is not None: bg.terminate(); bg.wait()',
            ])
            proc = subprocess.Popen([sys.executable, '-c', owner_code, str(bg_flag), str(bg_ready)])
            client = None
            accepted = None
            config = {}
            try:
                wait_for(bg_ready.exists)
                config = {'id': 'cb1', 'cli': 'CodeBuddy Code', 'cwd': directory, 'nativeOwner': {
                    'kind': 'codebuddy-server', 'instanceId': 'cb-inst-1',
                    'pid': proc.pid, 'processStart': process_start(proc.pid), 'port': port,
                }}
                self.assertTrue(non_mcp_background_child_alive(proc.pid))
                self.assertFalse(reap_recorded_owner(config, 0))
                self.assertEqual(recorded_owner_alive(config), True)

                bg_flag.unlink()
                wait_for(lambda: not non_mcp_background_child_alive(proc.pid))

                client = socket.create_connection(('127.0.0.1', port), timeout=2)
                accepted, _ = srv.accept()
                self.assertFalse(reap_recorded_owner(config, 0))
                self.assertEqual(recorded_owner_alive(config), True)

                client.close(); client = None
                accepted.close(); accepted = None
                wait_for(lambda: reap_recorded_owner(config, 0))
                self.assertEqual(recorded_owner_alive(config), False)
            finally:
                for sock_obj in (client, accepted, srv):
                    if sock_obj is not None:
                        try: sock_obj.close()
                        except OSError: pass
                stop_recorded_owner(config)
                try: proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    proc.kill(); proc.wait()

    def test_session_release_blocker_protects_background_script_but_ignores_stdio_mcp(self):
        mcp_code = '\n'.join([
            'import pathlib, signal, subprocess, sys, time',
            'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
            'ready = pathlib.Path(sys.argv[1])',
            'mcp = subprocess.Popen([sys.executable, "-c", "import sys, time; time.sleep(60)", "--mcp"], stdin=subprocess.PIPE)',
            'ready.write_text("1")',
            'try:',
            '    while True: time.sleep(0.05)',
            'finally:',
            '    mcp.terminate(); mcp.wait()',
        ])
        bg_code = '\n'.join([
            'import pathlib, signal, subprocess, sys, time',
            'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
            'ready = pathlib.Path(sys.argv[1])',
            'bg = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "test-runner"], stdin=subprocess.DEVNULL)',
            'ready.write_text("1")',
            'try:',
            '    while True: time.sleep(0.05)',
            'finally:',
            '    bg.terminate(); bg.wait()',
        ])
        with tempfile.TemporaryDirectory(prefix='luma-child-blocker-') as directory:
            mcp_ready = pathlib.Path(directory) / 'mcp.ready'
            bg_ready = pathlib.Path(directory) / 'bg.ready'
            mcp_parent = subprocess.Popen([sys.executable, '-c', mcp_code, str(mcp_ready)])
            bg_parent = subprocess.Popen([sys.executable, '-c', bg_code, str(bg_ready)])
            try:
                wait_for(lambda: mcp_ready.exists() and bg_ready.exists())
                s_mcp = Ready()
                s_mcp.peer = type('ProcPeer', (), {'closed': False, 'pending': {}, 'proc': mcp_parent})()
                self.assertFalse(non_mcp_background_child_alive(mcp_parent.pid))
                self.assertIsNone(s_mcp.release_blocker())

                s_bg = Ready()
                s_bg.peer = type('ProcPeer', (), {'closed': False, 'pending': {}, 'proc': bg_parent})()
                self.assertTrue(non_mcp_background_child_alive(bg_parent.pid))
                self.assertIsNotNone(s_bg.release_blocker())
            finally:
                for p in (mcp_parent, bg_parent):
                    p.terminate()
                    p.wait(timeout=3)

    def test_grok_replay_reconciles_settled_accepted_receipts_only_after_settlement(self):
        cfg = {'id': 'g1', 'cli': 'Grok Build', 'cwd': '/tmp', 'nativeId': 'grok-1',
               'receipts': {'r1': 'accepted'}, 'consumedSubmissions': {}}
        session = GrokSession(cfg, lambda: None)
        session.native_caps = {'loadSession': True}
        session.update(historyReady=True)
        session.row(item('r1', 'user', 'hello', origin='native'))
        self.assertIsNone(session.release_mode())
        with patch.object(session, 'flush_replay'), \
             patch.object(sys.modules['luma_cli.acp'].AcpSession, 'connect', side_effect=lambda *a, **k: session.update(catalogState='ready', models=[{'id': 'grok', 'label': 'grok'}])), \
             patch.object(session, 'refresh_metrics'), \
             patch.object(session, 'finish_chunk'):
            session.peer = LocalPeer()
            session.is_grok = False
            session.connect()
        self.assertEqual(cfg['consumedSubmissions'], {'r1': True})
        self.assertEqual(session.release_mode(), 'process')

    def test_idle_reaper_skips_session_protected_by_foreground_terminal(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-reaper-fg-') as directory:
            runtime_dir = pathlib.Path(directory) / 'runtime'
            state_dir = runtime_dir / 'agent/root/cli-sessions'
            owner_dir = state_dir / 's1.native'
            owner_dir.mkdir(parents=True, exist_ok=True)
            marker = runtime_dir / 'foreground-terminal.json'
            marker.write_text(json.dumps({
                'token': 'fg-loop-token',
                'pid': os.getpid(),
                'processStart': process_start(os.getpid()),
                'windowId': 'b' * 32,
                'sessionId': 's1',
                'instanceId': 'inst-fg',
                'updatedAt': int(time.time() * 1000),
            }))
            token = 'fixture-' + 'y' * 48
            token_path = pathlib.Path(directory) / 'token'; token_path.write_text(token)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            bootstrap = "import sys;sys.path[:0]=" + repr([str(ROOT / 'tools/tests/cli'), str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge')]) + ";from test_idle_reclaim import NativeFixture;from luma_cli import server;server.ADAPTERS={'fixture':NativeFixture};server.serve(*sys.argv[1:])"
            env = {'HOME': directory, 'PATH': '/usr/bin', 'LANG': 'C.UTF-8', 'LUMA_BRIDGE_IDLE_EXIT_SEC': '1'}
            proc = subprocess.Popen([sys.executable, '-B', '-c', bootstrap, str(port), str(token_path), str(state_dir)],
                cwd=directory, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            def call(path, body=None):
                request = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Connection': 'close'})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response: return response.status, json.load(response)
                except urllib.error.HTTPError as error:
                    with error: return error.code, json.load(error)
            try:
                def available():
                    try: return call('/health')[0] == 200
                    except OSError: return False
                wait_for(available)
                self.assertEqual(call('/sessions/connect', {'id': 's1', 'cli': 'fixture', 'cwd': directory})[0], 200)
                wait_for(lambda: call('/sessions/s1')[1].get('connected'))
                native = call('/sessions/s1')[1]['nativeId']
                time.sleep(1.5)
                self.assertEqual(call('/sessions/s1')[0], 200)
                self.assertTrue(call('/sessions/s1')[1]['connected'])
                marker.unlink()
                wait_for(lambda: proc.poll() is not None)
                self.assertTrue((state_dir / 's1.json').is_file())
            finally:
                if proc.poll() is None:
                    proc.terminate()
                proc.communicate(timeout=8)

    def test_connect_and_reap_detached_owners_do_not_deadlock(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-deadlock-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager = Manager(pathlib.Path(directory) / 'records')
            detached_path = manager.directory / 'detached.json'
            errors = []
            stop = threading.Event()
            def connect_loop():
                try:
                    for _ in range(15):
                        sess = manager.connect({'id': 's1', 'cli': 'fixture', 'cwd': directory})
                        manager.release('s1', {'nativeId': sess.config['nativeId']})
                except Exception as exc:
                    errors.append(exc)
                finally:
                    stop.set()
            def reap_loop():
                try:
                    while not stop.is_set():
                        detached_path.write_text(json.dumps({
                            'id': 'detached', 'cli': 'fixture', 'cwd': directory,
                            'nativeOwner': {'kind': 'grok-leader', 'instanceId': 'g-dead', 'pid': 99999999, 'processStart': 'dead'},
                        }))
                        manager.reap_detached_owners(0)
                except Exception as exc:
                    errors.append(exc)
            t1 = threading.Thread(target=connect_loop, daemon=True)
            t2 = threading.Thread(target=reap_loop, daemon=True)
            t1.start(); t2.start()
            t1.join(timeout=8); t2.join(timeout=8)
            try:
                self.assertFalse(t1.is_alive() or t2.is_alive())
                self.assertEqual(errors, [])
            finally:
                manager.close()

    def test_cross_daemon_observer_lock_sh_ex_destroy_and_close(self):
        import os
        from luma_cli.transport import Rejected
        class BoomFixture(NativeFixture):
            def connect(self):
                raise RuntimeError('connect boom')
        with tempfile.TemporaryDirectory(prefix='luma-lock-sh-ex-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture, 'boom': BoomFixture}):
            records = pathlib.Path(directory) / 'records'
            m1 = Manager(records)
            m2 = Manager(records)
            try:
                s1 = m1.connect({'id': 'obs1', 'cli': 'fixture', 'cwd': directory})
                self.assertTrue(m2._has_remote_observer('obs1'))
                m2.reap_detached_owners(0)
                self.assertTrue((records / 'obs1.json').is_file())
                with self.assertRaises(Conflict):
                    m2.destroy('obs1', {'deleteState': True})

                s2 = m1.connect({'id': 'obs2', 'cli': 'fixture', 'cwd': directory})
                s2.peer.close()
                s2.update(connected=False)
                with patch.object(s2, 'retire_observer', side_effect=AssertionError('retired before validation')):
                    with self.assertRaises(Rejected):
                        m1.connect({'id': 'obs2', 'cli': 'fixture', 'cwd': directory, 'nativeProtocol': 'bad-proto'})

                with self.assertRaises(RuntimeError):
                    m1.connect({'id': 'boom1', 'cli': 'boom', 'cwd': directory}, background=False)
                self.assertNotIn('boom1', m1.sessions)
                self.assertNotIn('boom1', m1.observer_locks)
            finally:
                m1.close()
            self.assertFalse(m2._has_remote_observer('obs1'))
            (records / 'obs1.json').write_text(json.dumps({
                'id': 'obs1', 'cli': 'fixture', 'cwd': directory,
                'nativeOwner': {'kind': 'tmux', 'instanceId': 'i1'},
            }))
            seen_lock_busy = []
            def probe_lock(cfg):
                seen_lock_busy.append(m1._open_and_flock('obs1', 2, retries=1) is None)
            with patch('luma_cli.server.stop_recorded_owner', side_effect=probe_lock):
                self.assertEqual(m2.destroy('obs1', {'deleteState': True}), {'destroyed': True, 'id': 'obs1'})
            self.assertEqual(seen_lock_busy, [True])
            self.assertFalse((records / 'obs1.json').exists())
            m2.close()

    def test_non_mcp_and_pi_child_detection_hardening(self):
        import os
        with tempfile.TemporaryDirectory(prefix='luma-child-hard-') as directory:
            d = pathlib.Path(directory)
            procs = []
            fds = []
            try:
                parent_code = '\n'.join([
                    'import pathlib, signal, subprocess, sys, time',
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))',
                    'ready = pathlib.Path(sys.argv[1])',
                    'mode = sys.argv[2]',
                    'if mode == "pipe-worker":',
                    '    c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "worker-a"], stdin=subprocess.PIPE)',
                    'elif mode == "inline-mcp":',
                    '    c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60) # mcp", "bg-job"], stdin=subprocess.PIPE)',
                    'elif mode == "mcptest":',
                    '    c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "mcptest"], stdin=subprocess.PIPE)',
                    'elif mode == "inherit-fd0":',
                    '    c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "--mcp"])',
                    'elif mode == "real-mcp":',
                    '    c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "--mcp"], stdin=subprocess.PIPE)',
                    'ready.write_text("1")',
                    'try:',
                    '    while True: time.sleep(0.05)',
                    'finally:',
                    '    c.terminate(); c.wait()',
                ])
                r_fd, w_fd = os.pipe()
                fds.extend([r_fd, w_fd])
                for mode, expected in (('pipe-worker', True), ('inline-mcp', True), ('mcptest', True), ('inherit-fd0', True), ('real-mcp', False)):
                    flag = d / f'{mode}.ready'
                    p = subprocess.Popen([sys.executable, '-c', parent_code, str(flag), mode], stdin=r_fd if mode == 'inherit-fd0' else subprocess.DEVNULL)
                    procs.append(p)
                    wait_for(flag.exists)
                    self.assertEqual(non_mcp_background_child_alive(p.pid), expected, mode)

                helper_wrap = subprocess.Popen([
                    sys.executable, '-c',
                    'import pathlib, signal, socket, subprocess, sys, time\n'
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n'
                    'a, b = socket.socketpair()\n'
                    'c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", sys.argv[2]], stdin=b)\n'
                    'b.close()\n'
                    'pathlib.Path(sys.argv[1]).write_text("1")\n'
                    'try:\n'
                    '    while True: time.sleep(0.05)\n'
                    'finally:\n'
                    '    c.terminate(); c.wait(); a.close()',
                    str(d / 'sock-mcp.ready'), '--mcp',
                ])
                procs.append(helper_wrap)
                wait_for((d / 'sock-mcp.ready').exists)
                self.assertFalse(non_mcp_background_child_alive(helper_wrap.pid))

                helper_wrap_bg = subprocess.Popen([
                    sys.executable, '-c',
                    'import pathlib, signal, socket, subprocess, sys, time\n'
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n'
                    'a, b = socket.socketpair()\n'
                    'c = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", sys.argv[2]], stdin=b)\n'
                    'b.close()\n'
                    'pathlib.Path(sys.argv[1]).write_text("1")\n'
                    'try:\n'
                    '    while True: time.sleep(0.05)\n'
                    'finally:\n'
                    '    c.terminate(); c.wait(); a.close()',
                    str(d / 'sock-bg.ready'), 'bg-worker',
                ])
                procs.append(helper_wrap_bg)
                wait_for((d / 'sock-bg.ready').exists)
                self.assertTrue(non_mcp_background_child_alive(helper_wrap_bg.pid))

                fast_holder = subprocess.Popen([
                    sys.executable, '-c',
                    'import pathlib, signal, subprocess, sys, time\n'
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n'
                    'c = subprocess.Popen([sys.executable, "-c", "pass"])\n'
                    'c.wait()\n'
                    'pathlib.Path(sys.argv[1]).write_text("1")\n'
                    'while True: time.sleep(0.05)',
                    str(d / 'fast.ready'),
                ])
                procs.append(fast_holder)
                wait_for((d / 'fast.ready').exists)
                self.assertFalse(non_mcp_background_child_alive(fast_holder.pid))

                sup_1lvl = subprocess.Popen([
                    sys.executable, '-c',
                    'import pathlib, signal, subprocess, sys, time\n'
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n'
                    'sup = subprocess.Popen([sys.executable, "-c",\n'
                    '    "import pathlib, signal, subprocess, sys, time\\n"\n'
                    '    "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\\n"\n'
                    '    "w = subprocess.Popen([sys.executable, \\"-c\\", \\"import time; time.sleep(60)\\", \\"worker-under-sup\\"])\\n"\n'
                    '    "m = subprocess.Popen([sys.executable, \\"-c\\", \\"import time; time.sleep(60)\\", \\"--mcp\\"], stdin=subprocess.PIPE)\\n"\n'
                    '    "pathlib.Path(sys.argv[1]).write_text(\\"1\\")\\n"\n'
                    '    "try:\\n"\n'
                    '    "    while True: time.sleep(0.05)\\n"\n'
                    '    "finally:\\n"\n'
                    '    "    w.terminate(); w.wait(); m.terminate(); m.wait()", sys.argv[1], "supervisor.mjs"])\n'
                    'try:\n'
                    '    while True: time.sleep(0.05)\n'
                    'finally:\n'
                    '    sup.terminate(); sup.wait()',
                    str(d / 'sup1.ready'), 'pi-host.mjs',
                ])
                procs.append(sup_1lvl)
                wait_for((d / 'sup1.ready').exists)
                self.assertTrue(pi_process_has_live_subprocesses(sup_1lvl.pid))

                sup_acp_mcp = subprocess.Popen([
                    sys.executable, '-c',
                    'import pathlib, signal, subprocess, sys, time\n'
                    'signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n'
                    'sup = subprocess.Popen([sys.executable, "-c",\n'
                    '    "import pathlib, signal, subprocess, sys, time\\n"\n'
                    '    "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\\n"\n'
                    '    "a = subprocess.Popen([sys.executable, \\"-c\\", \\"import time; time.sleep(60)\\", \\"agy acp\\"])\\n"\n'
                    '    "m = subprocess.Popen([sys.executable, \\"-c\\", \\"import time; time.sleep(60)\\", \\"--mcp\\"], stdin=subprocess.PIPE)\\n"\n'
                    '    "pathlib.Path(sys.argv[1]).write_text(\\"1\\")\\n"\n'
                    '    "try:\\n"\n'
                    '    "    while True: time.sleep(0.05)\\n"\n'
                    '    "finally:\\n"\n'
                    '    "    a.terminate(); a.wait(); m.terminate(); m.wait()", sys.argv[1], "supervisor.mjs"])\n'
                    'm2 = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", "mcp-server"], stdin=subprocess.PIPE)\n'
                    'try:\n'
                    '    while True: time.sleep(0.05)\n'
                    'finally:\n'
                    '    m2.terminate(); m2.wait(); sup.terminate(); sup.wait()',
                    str(d / 'sup2.ready'), 'pi-host.mjs',
                ])
                procs.append(sup_acp_mcp)
                wait_for((d / 'sup2.ready').exists)
                self.assertFalse(pi_process_has_live_subprocesses(sup_acp_mcp.pid))
            finally:
                for p in procs:
                    if p.poll() is None:
                        p.terminate()
                        try: p.wait(timeout=3)
                        except subprocess.TimeoutExpired: p.kill(); p.wait()
                for fd in fds:
                    try: os.close(fd)
                    except OSError: pass

    def test_tmux_checks_and_tcp_port_validation(self):
        for bad in (True, False, 0, -1, 65536, '80'):
            self.assertFalse(tcp_port_has_established_client(bad))
        with patch('luma_cli.native_owner.subprocess.run', side_effect=subprocess.TimeoutExpired(['tmux'], 5)):
            self.assertTrue(tmux_pi_has_live_subprocesses(['tmux'], '/tmp/no.sock'))
        dead_pane = subprocess.CompletedProcess(['tmux'], 0, b'1\t99999\n', b'')
        with patch('luma_cli.native_owner.subprocess.run', return_value=dead_pane):
            self.assertFalse(tmux_pi_has_live_subprocesses(['tmux'], '/tmp/no.sock'))
            with patch('os.kill') as mock_kill, patch('luma_cli.native_owner.process_start', return_value='s1'):
                stop_recorded_owner({'nativeOwner': {
                    'kind': 'tmux', 'pid': 99999, 'processStart': 's1',
                    'tmuxCommand': ['tmux'], 'terminalSocket': '/tmp/no.sock',
                }})
                mock_kill.assert_not_called()
        alive_pane = subprocess.CompletedProcess(['tmux'], 0, b'0\t99999\n', b'')
        with patch('luma_cli.native_owner.subprocess.run', return_value=alive_pane), \
             patch('luma_cli.native_owner.process_start', return_value='s1'), \
             patch('luma_cli.native_owner._read_proc_cmdline_args', return_value=['/bin/sh']):
            self.assertEqual(_verified_tmux_pane_pid(['tmux'], '/tmp/no.sock', {'pid': 99999, 'processStart': 's1'}), (None, None))
            self.assertEqual(_verified_tmux_pane_pid(['tmux'], '/tmp/no.sock', {'pid': 99999, 'processStart': 'other'}), (None, None))
        with patch('luma_cli.native_owner.recorded_owner_alive', return_value=True), \
             patch('luma_cli.native_owner.foreground_terminal_protects', return_value=False), \
             patch('luma_cli.native_owner.tmux_client_idle_seconds', return_value=float('inf')):
            self.assertFalse(reap_recorded_owner({'nativeOwner': {
                'kind': 'tmux', 'instanceId': 'i1', 'tmuxCommand': ['tmux'], 'terminalSocket': '/tmp/s',
            }}, 60, idle_tracker=None))

    def test_base_snapshot_nonblocking_and_i18n_and_acp_lock(self):
        import os
        class SlowBlockerSession(Session):
            def release_mode(self):
                return 'process'
            def release_blocker(self):
                return self.cached_snapshot_probe(
                    ('slow-probe', self.config['id']),
                    lambda: (time.sleep(0.5), None)[1],
                    pending_value='正在确认慢探针',
                )
        slow = SlowBlockerSession({'id': 'slow', 'cli': 'fixture', 'cwd': '/tmp', 'nativeId': 'n1'}, lambda: None)
        slow.peer = LocalPeer(); slow.peer.reconnectable = True
        slow.update(connected=True)
        t0 = time.monotonic()
        snap = slow.snapshot()
        self.assertLess(time.monotonic() - t0, 0.45)
        self.assertFalse(snap['releasable'])
        wait_for(lambda: slow.snapshot()['releasable'] is True)

        prev_env = os.environ.get('LUMA_LANG')
        try:
            set_lang(None)
            os.environ['LUMA_LANG'] = ''
            self.assertEqual(lang_code(), 'zh')
            self.assertTrue(is_zh())
            os.environ['LUMA_LANG'] = 'en'
            self.assertEqual(lang_code(), 'en')
            self.assertFalse(is_zh())
            set_lang('zh')
            self.assertEqual(lang_code(), 'zh')
            set_lang(None)
            self.assertEqual(lang_code(), 'en')
        finally:
            set_lang(None)
            if prev_env is None:
                os.environ.pop('LUMA_LANG', None)
            else:
                os.environ['LUMA_LANG'] = prev_env

        class DummyAcp(AcpSession):
            pass
        acp = DummyAcp({'id': 'acp1', 'cli': 'dsh', 'cwd': '/tmp'}, lambda: None)
        acp.chunk_kind = None
        acp.chunk_id = None
        acp.current_submission = None
        acp.pending_echo_submissions = []
        acp_errors = []
        def mutate_echoes():
            try:
                for i in range(60):
                    with acp.lock:
                        acp.pending_echo_submissions.append(f'sub-{i}')
                        acp.row(item(f'sub-{i}', 'user', f'hi-{i}', origin='local'))
                    time.sleep(0.001)
            except Exception as exc:
                acp_errors.append(exc)
        def feed_chunks():
            try:
                for i in range(60):
                    kind = 'user_message_chunk' if i % 2 == 0 else 'agent_message_chunk'
                    acp.event({'method': 'session/update', 'params': {'update': {'sessionUpdate': kind, 'content': {'type': 'text', 'text': f'hi-{i // 2}'}}}})
            except Exception as exc:
                acp_errors.append(exc)
        ta = threading.Thread(target=mutate_echoes, daemon=True)
        tb = threading.Thread(target=feed_chunks, daemon=True)
        ta.start(); tb.start()
        ta.join(timeout=5); tb.join(timeout=5)
        self.assertEqual(acp_errors, [])

if __name__ == '__main__': unittest.main(verbosity=2)
