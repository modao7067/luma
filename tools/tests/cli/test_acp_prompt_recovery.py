import copy
import json
import pathlib
import queue
import sys
import tempfile
import threading
import time
import unittest

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.kimi import KimiSession
from luma_cli.server import Manager
from luma_cli.transport import Peer, Rejected

def wait_for(test):
    end = time.monotonic() + 2
    while not test():
        if time.monotonic() >= end: raise AssertionError('fixture timed out')
        time.sleep(.002)

class Input:
    def __init__(self): self.lines = queue.Queue()
    def readline(self, limit): return self.lines.get(timeout=3)
    def put(self, frame): self.lines.put((json.dumps(frame) + '\n').encode())

class Process:
    def __init__(self):
        self.stdout = Input(); self.stdin = self; self.code = None
        self.frames = []; self.flush_fault = False; self.lock = threading.Lock()
    def poll(self): return self.code
    def write(self, data):
        with self.lock: self.frames.append(json.loads(data))
    def flush(self):
        if self.flush_fault:
            self.flush_fault = False
            raise OSError('PRIVATE_FLUSH_SENTINEL')

class Harness:
    def __init__(self, cls=AcpSession):
        self.temp = tempfile.TemporaryDirectory(prefix='luma-acp-recovery-')
        self.fail_save = False; self.saves = 0; self.saved = None
        self.config = {'id':'fixture','cli':'Kimi Code','cwd':self.temp.name,'nativeId':'native','ownerDirectory':self.temp.name+'/owner'}
        self.session = cls(self.config, self.save)
        self.session.chunk_id = self.session.chunk_kind = self.session.current_submission = None
        self.session.commands = []; self.session.prompt_wait_timeout = .03
        self.session.update(connected=True, capabilities={})
        self.process = Process(); self.peer = Peer.__new__(Peer)
        self.peer.proc = self.process; self.peer.event = self.session.event; self.peer.exited = self.session.exited
        self.peer.waiting_for_user = lambda:bool(self.session.pending_requests)
        self.peer.lock = threading.RLock(); self.peer.write_lock = threading.Lock()
        self.peer.pending = {}; self.peer.chunks = None; self.peer.closed = False
        self.session.peer = self.peer; self.peer.start_read()
    def save(self):
        self.saves += 1
        if self.fail_save: raise OSError('PRIVATE_DISK_SENTINEL')
        self.saved = copy.deepcopy(self.config)
    def prompts(self): return [f for f in self.process.frames if f.get('method') == 'session/prompt']
    def send(self, ident='a'):
        self.session.submit({'submissionId':ident,'text':'synthetic input'})
        wait_for(lambda:len(self.prompts()) >= 1)
        return self.prompts()[-1]
    def update(self, kind, **fields):
        self.process.stdout.put({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':kind, **fields}}})
    def respond(self, frame, result=None, error=None):
        self.process.stdout.put({'jsonrpc':'2.0','id':frame['id'],**({'error':error} if error else {'result': result if result is not None else {'stopReason':'end_turn'}})})
    def soft_timeout(self): wait_for(lambda:'TimeoutError' in (self.session.data['error'] or ''))
    def ended(self): wait_for(lambda:not self.session.data['running'])
    def close(self):
        self.process.stdout.lines.put(b''); self.peer.reader_thread.join(2)
        wait_for(lambda:not self.peer.pending)
        self.session.update(connected=False)
        self.temp.cleanup()

class PromptRecovery(unittest.TestCase):
    def setUp(self): self.h = Harness()
    def tearDown(self): self.h.close()
    def test_soft_timeout_retains_original_waiter_and_late_result_is_the_only_idle_proof(self):
        h = self.h; frame = h.send(); h.soft_timeout()
        self.assertEqual([frame['id']], list(h.peer.pending)); self.assertTrue(h.session.data['running'])
        self.assertEqual('a', h.session.current_submission); self.assertEqual('unknown', h.config['receipts']['a'])
        before = len(h.process.frames); h.session.action('refresh',{})
        self.assertTrue(h.session.data['running']); self.assertEqual(before,len(h.process.frames))
        h.respond({'id':'foreign'}, {'stopReason':'end_turn'}); time.sleep(.02)
        self.assertTrue(h.session.data['running']); self.assertEqual(1,len(h.prompts()))
        h.respond(frame); h.ended()
        self.assertIsNone(h.session.current_submission); self.assertEqual('accepted', h.config['receipts']['a'])
        self.assertTrue(h.config['consumedSubmissions']['a']); self.assertIsNone(h.session.data['error'])
        wait_for(lambda:not h.peer.pending); self.assertEqual(1,len(h.prompts()))
    def test_settled_turn_failure_does_not_leak_into_the_next_turn(self):
        h=self.h; frame=h.send('a')
        h.respond(frame,error={'code':-32603,'message':'Insufficient Balance'})
        h.ended(); self.assertEqual('Insufficient Balance',h.session.data['error'])
        frame=h.send('b')
        self.assertIsNone(h.session.data['error'])
        h.respond(frame); h.ended(); self.assertIsNone(h.session.data['error'])
    def test_native_admission_before_timeout_is_not_downgraded_and_content_is_not_idle_proof(self):
        h=self.h; frame=h.send(); h.update('agent_message_chunk', content={'type':'text','text':'native output'})
        wait_for(lambda:h.config['receipts']['a']=='accepted'); h.soft_timeout()
        h.update('tool_call',toolCallId='native',title='tool',status='completed')
        wait_for(lambda:'tool-native' in h.session.rows)
        self.assertTrue(h.session.data['running']); self.assertEqual('accepted',h.config['receipts']['a'])
        h.respond(frame); h.ended(); self.assertEqual('completed',h.session.rows['tool-native']['status'])
    def test_flush_error_after_native_write_keeps_the_same_request_and_late_completion(self):
        h=self.h; h.process.flush_fault=True; frame=h.send()
        wait_for(lambda:'OSError' in (h.session.data['error'] or ''))
        self.assertEqual([frame['id']],list(h.peer.pending)); self.assertEqual('unknown',h.config['receipts']['a'])
        self.assertNotIn('PRIVATE_FLUSH_SENTINEL',json.dumps(h.session.snapshot()))
        self.assertNotIn('PRIVATE_FLUSH_SENTINEL',pathlib.Path(h.temp.name,'owner/native-owner.log').read_text())
        h.respond(frame); h.ended(); self.assertEqual(1,len(h.prompts())); self.assertIsNone(h.session.data['error'])
    def test_explicit_stop_cancels_pending_permission_but_does_not_settle_until_original_cancelled_result(self):
        h=self.h; frame=h.send(); h.soft_timeout()
        h.process.stdout.put({'jsonrpc':'2.0','id':7,'method':'session/request_permission','params':{
            'sessionId':'native','options':[{'optionId':'cancel','name':'A native option named cancel'},{'optionId':'once','name':'Allow once'}]}})
        wait_for(lambda:'7' in h.session.pending_requests)
        h.session.stop()
        self.assertTrue(h.session.data['running']); self.assertEqual('a',h.session.current_submission)
        self.assertEqual('session/cancel',h.process.frames[-2]['method'])
        self.assertEqual({'jsonrpc':'2.0','id':7,'result':{'outcome':{'outcome':'cancelled'}}},h.process.frames[-1])
        self.assertFalse(h.session.pending_requests)
        h.update('tool_call',toolCallId='last',title='late tool update',status='in_progress')
        wait_for(lambda:'tool-last' in h.session.rows); self.assertTrue(h.session.data['running'])
        h.respond(frame,{'stopReason':'cancelled'}); h.ended()
        self.assertEqual('cancelled',h.session.rows['tool-last']['status']); self.assertIsNone(h.session.current_submission)
        self.assertEqual('accepted',h.config['receipts']['a']); self.assertEqual(1,len(h.prompts()))
    def test_permission_dialog_still_pauses_soft_deadline_and_stop_uses_form_adapter_cancellation(self):
        self.h.close(); self.h=Harness(KimiSession); h=self.h
        frame=h.send(); h.process.stdout.put({'jsonrpc':'2.0','id':'form','method':'elicitation/create','params':{
            'sessionId':'native','mode':'form','requestedSchema':{'type':'object','required':['secret'],'properties':{'secret':{'type':'string'}}}}})
        wait_for(lambda:'form' in h.session.pending_requests); time.sleep(.09)
        self.assertIsNone(h.session.data['error']); self.assertTrue(h.session.data['running'])
        h.session.stop(); self.assertEqual({'action':'cancel'},h.process.frames[-1]['result'])
        self.assertTrue(h.session.data['running']); h.respond(frame,{'stopReason':'cancelled'}); h.ended()
    def test_late_native_error_ends_execution_but_is_not_a_fake_delivery_rejection(self):
        h=self.h; frame=h.send(); h.soft_timeout()
        h.respond(frame,error={'code':-32603,'message':'Native terminal failure'}); h.ended()
        self.assertIsNone(h.session.current_submission); self.assertEqual('unknown',h.config['receipts']['a'])
        self.assertEqual('Native terminal failure',h.session.data['error']); self.assertEqual(1,len(h.prompts()))
    def test_storage_fault_on_late_result_does_not_lose_terminal_evidence_or_abandon_reader(self):
        h=self.h; frame=h.send(); h.soft_timeout(); h.fail_save=True
        h.respond(frame); h.ended()
        self.assertIsNone(h.session.current_submission); self.assertEqual('accepted',h.config['receipts']['a'])
        self.assertTrue(h.config['consumedSubmissions']['a']); self.assertFalse(h.peer.closed)
        self.assertIn('保存失败',h.session.snapshot()['error']); self.assertEqual('unknown',h.saved['receipts']['a'])
        h.fail_save=False; h.session.action('refresh',{})
        self.assertIsNone(h.session.snapshot()['error']); self.assertTrue(h.saved['consumedSubmissions']['a'])
        self.assertEqual(1,len(h.prompts()))
    def test_unrelated_error_is_not_erased_by_late_prompt_completion(self):
        h=self.h; frame=h.send(); h.soft_timeout(); h.session.update(error='Unrelated native setting failure')
        h.respond(frame); h.ended(); self.assertEqual('Unrelated native setting failure',h.session.data['error'])
    def test_host_fifo_resumes_only_after_original_cancel_response_and_never_replays_timed_out_id(self):
        h=self.h; h.session.admit({'submissionId':'a','text':'first'})
        wait_for(lambda:len(h.prompts())==1); first=h.prompts()[0]; h.soft_timeout()
        self.assertEqual('unknown',h.saved['receipts']['a'])
        h.session.admit({'submissionId':'b','text':'second'})
        h.session.action('refresh',{}); time.sleep(.02); self.assertEqual(1,len(h.prompts()))
        h.session.stop(); self.assertTrue(h.session.data['running'])
        h.respond(first,{'stopReason':'cancelled'}); wait_for(lambda:len(h.prompts())==2)
        second=h.prompts()[1]; self.assertNotEqual(first['id'],second['id'])
        self.assertEqual('second',second['params']['prompt'][0]['text'])
        h.respond(second); h.ended()
        self.assertEqual({'a':'accepted','b':'accepted'},h.config['receipts'])
        self.assertEqual(2,len(h.prompts()))
    def test_true_reader_loss_with_live_owner_remains_unknown_and_refuses_second_executor(self):
        h=self.h; h.send(); h.process.stdout.lines.put(b''); wait_for(lambda:h.peer.closed)
        wait_for(lambda:not h.session.data['connected'])
        wait_for(lambda:'ConnectionError' in (getattr(h.session,'prompt_wait_error',None) or ''))
        self.assertTrue(h.session.data['error'])
        self.assertFalse(h.session.data['connected']); self.assertTrue(h.session.data['running'])
        self.assertEqual('a',h.session.current_submission); self.assertEqual('unknown',h.config['receipts']['a'])
        manager=Manager(pathlib.Path(h.temp.name,'manager')); manager.sessions['fixture']=h.session
        with self.assertRaisesRegex(Rejected,'不能启动第二个执行进程'):
            manager.connect({'id':'fixture','cli':'Kimi Code','cwd':h.temp.name})
        before=len(h.process.frames); h.session.action('refresh',{})
        self.assertEqual(before,len(h.process.frames)); self.assertTrue(h.session.data['running'])
    def test_invalid_prompt_response_does_not_invent_a_stop_reason_or_admission(self):
        h=self.h; frame=h.send(); h.respond(frame,{'unrecognized':'not a v1 prompt completion'})
        wait_for(lambda:'ValueError' in (h.session.data['error'] or ''))
        self.assertTrue(h.session.data['running']); self.assertEqual('a',h.session.current_submission)
        self.assertEqual('unknown',h.config['receipts']['a']); self.assertEqual(1,len(h.prompts()))
    def test_serialization_failure_is_not_treated_as_an_uncertain_native_write(self):
        h=self.h; uncertain=[]
        with self.assertRaises(TypeError):
            h.peer.call('read-only-example', {'not_json':set()}, on_uncertain=uncertain.append)
        self.assertFalse(h.peer.pending); self.assertFalse(uncertain); self.assertFalse(h.process.frames)
    def test_ordinary_rpc_timeout_contract_still_expires_and_discards_its_waiter(self):
        h=self.h
        with self.assertRaises(TimeoutError): h.peer.call('read-only-example',timeout=.01)
        self.assertFalse(h.peer.pending); self.assertEqual(1,len(h.process.frames))

if __name__=='__main__': unittest.main(verbosity=2)
