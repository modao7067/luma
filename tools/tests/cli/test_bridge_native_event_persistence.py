import copy
import json
import pathlib
import queue
import sys
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.base import Session, DeliveryPersistenceError
from luma_cli.server import Manager
from luma_cli.transport import Peer, Rejected

def wait_for(predicate):
    end = time.monotonic() + 2
    while not predicate():
        if time.monotonic() > end: raise AssertionError('fixture timed out')
        time.sleep(.003)

class Input:
    def __init__(self): self.frames = queue.Queue()
    def readline(self, size): return self.frames.get(timeout=3)
    def put(self, frame): self.frames.put((json.dumps(frame) + '\n').encode())

class EventPersistence(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='luma-reader-store-')
        self.attempts = 0; self.fail = False; self.saved = None
        self.config = {'id':'fixture', 'cli':'Kimi Code', 'cwd':self.temp.name, 'nativeId':'native',
            'ownerDirectory':self.temp.name + '/owner', 'receipts':{'a':'unknown'},
            'deliveries':{'a':{'state':'unknown', 'payload':{'submissionId':'a','text':'fixture'}}}}
        self.session = AcpSession(self.config, self.save)
        self.session.chunk_kind = self.session.chunk_id = None
        self.session.current_submission = 'a'; self.session.commands = []
        self.session.update(connected=True, running=True)
        self.reader = Input(); self.exits = []
        self.peer = Peer.__new__(Peer)
        self.peer.proc = SimpleNamespace(stdout=self.reader, poll=lambda:None)
        self.peer.event = self.session.event
        self.peer.exited = lambda error:(self.exits.append(error), self.session.exited(error))
        self.peer.lock = threading.RLock(); self.peer.pending = {}; self.peer.chunks = None; self.peer.closed = False
        self.session.peer = self.peer
        self.peer.start_read()
    def save(self):
        self.attempts += 1
        if self.fail: raise OSError('PRIVATE_STORAGE_SENTINEL credential-like text must not leak')
        self.saved = copy.deepcopy(self.config)
    def frame(self, kind, **fields):
        self.reader.put({'method':'session/update', 'params':{'sessionId':'native', 'update':{'sessionUpdate':kind, **fields}}})
    def tearDown(self):
        self.reader.frames.put(b''); self.peer.reader_thread.join(2)
        self.assertFalse(self.peer.reader_thread.is_alive())
        self.temp.cleanup()
    def assert_reader_live(self):
        self.assertFalse(self.peer.closed); self.assertTrue(self.peer.reader_thread.is_alive())
        self.assertTrue(self.session.data['connected']); self.assertFalse(self.exits)
        self.assertIsNone(self.peer.proc.poll())
        self.assertNotIn('PRIVATE_STORAGE_SENTINEL', json.dumps(self.session.snapshot()))
        self.assertNotIn('PRIVATE_STORAGE_SENTINEL', pathlib.Path(self.temp.name, 'owner/native-owner.log').read_text())
    def test_receipt_save_failure_keeps_current_event_reader_rpc_and_single_native_owner(self):
        self.fail = True
        self.frame('agent_message_chunk', content={'type':'text','text':'first'})
        wait_for(lambda:self.config.get('consumedSubmissions',{}).get('a'))
        self.assert_reader_live(); self.assertEqual('accepted', self.config['receipts']['a'])
        self.assertEqual('accepted', self.config['deliveries']['a']['state'])
        self.assertIn('保存失败', self.session.snapshot()['error']); before = self.attempts
        self.frame('agent_message_chunk', content={'type':'text','text':' next'})
        self.frame('tool_call', toolCallId='tool', title='actual subsequent event', status='in_progress')
        wait_for(lambda:'tool-tool' in self.session.rows)
        self.assertEqual(before, self.attempts, 'stream is not a storage retry loop')
        waiter = queue.Queue(); self.peer.pending['rpc'] = waiter
        self.reader.put({'jsonrpc':'2.0','id':'rpc','result':{'ok':True}})
        self.assertTrue(waiter.get(timeout=2)['result']['ok'])
        self.assert_reader_live()
        manager = Manager(pathlib.Path(self.temp.name, 'manager')); manager.sessions['fixture'] = self.session
        self.assertIs(self.session, manager.connect({'id':'fixture','cli':'Kimi Code','cwd':self.temp.name,'nativeId':'native'}))
        self.fail = False; self.session.action('refresh',{})
        self.assertIsNone(self.session.delivery_storage_error); self.assertIsNone(self.session.snapshot()['error'])
        self.assertEqual('accepted', self.saved['receipts']['a']); self.assertTrue(self.saved['consumedSubmissions']['a'])
        self.assert_reader_live()
    def test_consumed_save_failure_does_not_truncate_user_chunk_or_later_events(self):
        self.fail = True
        self.frame('user_message_chunk', content={'type':'text','text':'full native input'})
        wait_for(lambda:self.config.get('receipts',{}).get('a') == 'accepted')
        self.assertEqual('full native input', self.session.rows['a']['text'])
        self.assertEqual('native', self.session.rows['a']['origin'])
        self.assertTrue(self.config['consumedSubmissions']['a']); self.assert_reader_live()
        self.frame('agent_message_chunk', content={'type':'text','text':'after consumed fault'})
        wait_for(lambda:any(r['text']=='after consumed fault' for r in self.session.rows.values()))
        self.assert_reader_live(); self.assertEqual(1, self.attempts)
    def test_async_prompt_result_still_settles_running_after_native_receipt_checkpoint_fails(self):
        ready = threading.Event(); release = threading.Event()
        def native_result(*args, **kwargs):
            ready.set(); self.assertTrue(release.wait(2)); return {'stopReason':'end_turn'}
        self.session.call = native_result
        self.session.current_submission = None; self.session.update(running=False)
        self.session.submit({'submissionId':'b','text':'synthetic new input'})
        self.assertTrue(ready.wait(2)); self.assertEqual('unknown', self.saved['receipts']['b'])
        self.fail = True; release.set()
        wait_for(lambda:not self.session.data['running'])
        self.assertEqual('accepted', self.config['receipts']['b']); self.assertTrue(self.config['consumedSubmissions']['b'])
        self.assertIsNone(self.session.current_submission); self.assert_reader_live()
        self.fail = False; self.session.action('refresh',{})
        self.assertEqual('accepted', self.saved['receipts']['b']); self.assertTrue(self.saved['consumedSubmissions']['b'])
    def test_legacy_unknown_fence_failure_still_prevents_any_native_call(self):
        calls = []; self.session.prompt = lambda payload:calls.append(payload)
        self.session.update(running=False); self.fail = True
        with self.assertRaises(DeliveryPersistenceError): self.session.submit({'submissionId':'b','text':'never cross'})
        self.assertFalse(calls); self.assertEqual('unknown', self.config['receipts']['b']); self.assert_reader_live()
    def test_real_bad_frame_still_disconnects_and_alive_executor_protection_is_not_relaxed(self):
        self.reader.frames.put(b'not-json\n'); wait_for(lambda:self.peer.closed)
        self.assertFalse(self.session.data['connected']); self.assertTrue(self.exits)
        manager = Manager(pathlib.Path(self.temp.name, 'manager')); manager.sessions['fixture'] = self.session
        with self.assertRaisesRegex(Rejected, '不能启动第二个执行进程'):
            manager.connect({'id':'fixture','cli':'Kimi Code','cwd':self.temp.name})

if __name__ == '__main__': unittest.main(verbosity=2)
