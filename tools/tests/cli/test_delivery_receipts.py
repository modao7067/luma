#!/usr/bin/env python3
import pathlib,sys,threading,time,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).absolute().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.dsh import DshSession
from luma_cli.transport import Rejected

def session(cls=DshSession):
    s=cls({'cli':'DeepSeek Harness','cwd':'/tmp','nativeId':'native','receipts':{'send':'unknown'}},lambda:None)
    s.initialize_info={'_meta':{'lumaSubmissions':{'protocol':1}}}
    s.current_submission='send';s.chunk_kind=None;s.chunk_id=None
    return s

def receipt(**values):
    return {'method':'luma/submissions/accepted','params':{'sessionId':'native','submissionId':'send','nativeMessageId':'real-inbox-id',**values}}

class DeliveryReceipts(unittest.TestCase):
    def test_local_optimistic_user_row_is_not_native_admission(self):
        s=session(AcpSession);s.config['receipts']={};s.update(connected=True,capabilities={})
        held=threading.Event();release=threading.Event();calls=[]
        def call(*args,**kwargs):calls.append(args);held.set();release.wait(2);return {'stopReason':'end_turn'}
        s.call=call
        try:
            result=s.submit({'submissionId':'send','text':'你好'})
            self.assertTrue(held.wait(1));self.assertEqual('unknown',result['receipt'])
            self.assertEqual('unknown',s.snapshot()['receipts']['send'])
            self.assertEqual('你好',s.rows['send']['text'])
            self.assertEqual('unknown',s.submit({'submissionId':'send','text':'must-not-send'})['receipt'])
            self.assertEqual(1,len(calls))
        finally:release.set()
    def test_actual_native_inbox_proof_confirms_before_turn_settles(self):
        s=session();s.update(running=True);s.event(receipt())
        self.assertEqual('accepted',s.snapshot()['receipts']['send']);self.assertTrue(s.data['running'])
        self.assertEqual({'send':'real-inbox-id'},s.config['submissionNativeIds'])
    def test_foreign_session_and_old_submission_cannot_confirm_current(self):
        for values in ({'sessionId':'foreign'},{'submissionId':'older'},{'nativeMessageId':''}):
            s=session();s.event(receipt(**values));self.assertEqual('unknown',s.snapshot()['receipts']['send'])
    def test_unadvertised_extension_is_not_admission(self):
        s=session();s.initialize_info={};s.event(receipt());self.assertEqual('unknown',s.snapshot()['receipts']['send'])
    def test_native_user_echo_has_one_stable_row_and_is_genuine_admission(self):
        s=session(AcpSession);s.row({'id':'send','role':'user','text':'你好'})
        for text in ('你','好'):
            s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':text}}}})
        self.assertEqual(['send'],list(s.rows));self.assertEqual('你好',s.rows['send']['text'])
        self.assertEqual('accepted',s.snapshot()['receipts']['send'])
    def test_late_native_proof_does_not_mark_execution_complete(self):
        s=session();s.update(running=True);s.event(receipt());s.event(receipt())
        self.assertTrue(s.data['running']);self.assertEqual('send',s.current_submission)
        self.assertEqual({'send':'accepted'},s.snapshot()['receipts'])
    def test_native_foreign_user_echo_is_not_current_delivery(self):
        s=session(AcpSession)
        s.event({'method':'session/update','params':{'sessionId':'foreign','update':{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':'你好'}}}})
        self.assertEqual({},s.rows);self.assertEqual('unknown',s.snapshot()['receipts']['send'])
    def test_stale_tool_update_and_second_user_chunk_do_not_corrupt_turn(self):
        s=session(AcpSession);s.initialize_info={}
        s.row({'id':'tool-stale','role':'tool','text':'old','kind':'tool','status':'running'})
        s.preexisting_tool_ids={'stale'};s.echoed_user_submission=False
        s.row({'id':'send','role':'user','text':'你好'})
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'tool_call_update','toolCallId':'stale','status':'completed'}}})
        self.assertEqual('unknown',s.snapshot()['receipts']['send'])
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':'你好'}}}})
        self.assertEqual('accepted',s.snapshot()['receipts']['send'])
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'答复'}}}})
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':'第二条用户消息'}}}})
        self.assertEqual('你好',s.rows['send']['text'])
        self.assertTrue(any(r['role']=='user' and r['id']!='send' and r['text']=='第二条用户消息' for r in s.rows.values()))
if __name__=='__main__':unittest.main(verbosity=2)
