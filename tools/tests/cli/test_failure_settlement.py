#!/usr/bin/env python3
import json,pathlib,sys,tempfile,time,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.claude import ClaudeSession
from luma_cli.transport import Peer,Rejected

class FailureSettlement(unittest.TestCase):
    def acp(self):
        s=AcpSession({'cli':'DeepSeek Harness','cwd':'/tmp','nativeId':'native','receipts':{}},lambda:None)
        s.chunk_kind=None;s.chunk_id=None;s.current_submission=None
        s.update(connected=True,capabilities={})
        return s
    def wait_settled(self,s):
        until=time.monotonic()+2
        while s.data['running'] and time.monotonic()<until:time.sleep(.01)
        self.assertFalse(s.data['running'])
    def test_acp_rpc_failure_does_not_leave_a_tool_or_expired_approval_running(self):
        s=self.acp()
        script='''import sys,json
r=json.loads(sys.stdin.readline())
assert r['method']=='session/prompt'
print(json.dumps({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'tool_call','toolCallId':'pending','title':'Native tool','status':'in_progress'}}}),flush=True)
print(json.dumps({'jsonrpc':'2.0','id':7,'method':'session/request_permission','params':{'sessionId':'native','options':[{'optionId':'once','name':'Allow once'}]}}),flush=True)
print(json.dumps({'jsonrpc':'2.0','id':r['id'],'error':{'code':-32603,'message':'Native prompt failed'}}),flush=True)
sys.stdin.readline()
'''
        s.peer=Peer([sys.executable,'-c',script],'/tmp',s.event,s.exited);s.peer.start_read()
        s.row({'id':'old-failure','role':'tool','status':'failed','text':'old'})
        try:
            s.submit({'submissionId':'submission','text':'native prompt'})
            self.wait_settled(s)
            self.assertEqual(s.rows['tool-pending']['status'],'unknown')
            self.assertEqual(s.rows['old-failure']['status'],'failed')
            self.assertEqual(s.data['requests'],[]);self.assertEqual(s.pending_requests,{})
            self.assertEqual(s.config['receipts']['submission'],'accepted')
            self.assertEqual(s.data['error'],'Native prompt failed')
        finally:s.peer.close()
    def test_transport_loss_keeps_execution_and_receipt_uncertain(self):
        s=self.acp()
        def lost(*a,**kw):
            s.row({'id':'pending','role':'tool','status':'in_progress','text':'tool'})
            raise ConnectionError('socket lost before native settlement')
        s.call=lost;s.submit({'submissionId':'submission','text':'native prompt'})
        until=time.monotonic()+2
        while s.data['error'] is None and time.monotonic()<until:time.sleep(.01)
        self.assertTrue(s.data['running']);self.assertEqual(s.rows['pending']['status'],'in_progress')
        self.assertEqual(s.config['receipts']['submission'],'unknown')
    def test_claude_failed_result_only_settles_unfinished_parent_rows_to_unknown(self):
        s=ClaudeSession({'cli':'Claude Code','cwd':'/tmp','nativeId':'native'},lambda:None)
        s.current_submission=None;s.load_history=lambda:None
        s.row({'id':'tool','role':'tool','kind':'tool','status':'running','text':'Bash'})
        s.row({'id':'done','role':'tool','status':'completed','text':'Read'})
        s.row({'id':'failed','role':'tool','status':'failed','text':'Edit'})
        s.subagent('child',status='running')
        s.update(running=True)
        s.event({'type':'result','session_id':'native','is_error':True,'errors':['Native execution failed']})
        self.assertFalse(s.data['running']);self.assertEqual(s.rows['tool']['status'],'unknown')
        self.assertEqual(s.rows['done']['status'],'completed');self.assertEqual(s.rows['failed']['status'],'failed')
        self.assertEqual(s.subagents['child']['status'],'running')
        self.assertEqual(s.data['error'],'Native execution failed')
    def test_claude_idle_history_reload_cannot_resurrect_an_orphan_tool_as_running(self):
        s=ClaudeSession({'cli':'Claude Code','cwd':'/tmp','nativeId':'native'},lambda:None)
        with tempfile.TemporaryDirectory(prefix='luma-idle-history-') as tmp:
            path=pathlib.Path(tmp)/'native.jsonl';s.history_path=lambda:path
            path.write_text(json.dumps({'uuid':'entry','type':'assistant','parentUuid':None,'sessionId':'native',
                'message':{'id':'api','role':'assistant','content':[{'type':'tool_use','id':'pending','name':'Bash','input':{}}]}})+'\n')
            s.load_history()
            self.assertEqual(s.rows['tool-pending']['status'],'unknown')
            s.load_history()
            self.assertEqual(s.rows['tool-pending']['status'],'unknown')
            s.update(running=True);s.load_history()
            self.assertEqual(s.rows['tool-pending']['status'],'running')

    def test_acp_cancel_still_uses_explicit_cancelled_not_unknown(self):
        s=self.acp();s.call=lambda *a,**kw:{'stopReason':'cancelled'}
        s.row({'id':'pending','role':'tool','status':'in_progress','text':'tool'})
        s.submit({'submissionId':'cancel','text':'native prompt'});self.wait_settled(s)
        self.assertEqual(s.rows['pending']['status'],'cancelled')

if __name__=='__main__':unittest.main(verbosity=2)
