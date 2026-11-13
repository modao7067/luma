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
from luma_cli.native_owner import NativeOwner, SocketPeer, recorded_owner_alive, process_start

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
                with self.assertRaisesRegex(Exception, '不能启动第二个执行进程'): manager.connect(attach)
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
        session = DshSession({'cli': 'DeepSeek Harness', 'cwd': '/tmp'}, lambda: None)
        session.update(historyReady=True); session.row(item('user', 'user'))
        self.assertIsNone(session.release_mode())
        session.history_protocol = True; self.assertEqual(session.release_mode(), 'process')
        session.update(historyReady=False); self.assertIsNone(session.release_mode())
        for cls in (AntigravitySession, AntigravityCliSession):
            self.assertIsNone(cls({'cli': 'Antigravity', 'cwd': '/tmp'}, lambda: None).release_mode())

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

if __name__ == '__main__': unittest.main(verbosity=2)
