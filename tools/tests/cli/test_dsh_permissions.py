#!/usr/bin/env python3
import pathlib,sys,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.dsh import DshSession
from luma_cli.acp import AcpSession
from luma_cli.kimi import KimiSession
from luma_cli.pi import PiSession
from luma_cli.transport import Rejected

def state(current='workspace-write',session='native',plan_available=True,plan_active=False):
    return {'sessionId':session,'currentValue':current,'planAvailable':plan_available,'planActive':plan_active,'options':[
        {'value':'read-only','name':'Read Only','description':'Read only','sandbox':'read-only','approval':'ask'},
        {'value':'workspace-write','name':'Native workspace','description':'Native description','sandbox':'workspace-write','approval':'ask'},
        {'value':'danger-full-access','name':'Native full','description':'No confinement','sandbox':'danger-full-access','approval':'never'},
        {'value':'auto','name':'Auto (Model Review)','description':'Auto review','sandbox':'danger-full-access','approval':'ask'}]}
def session():
    s=DshSession({'cli':'DeepSeek Harness','cwd':'/tmp','nativeId':'native'},lambda:None)
    s.permission_protocol=True;s.permission_revision=0;s.apply_permissions(state());return s
class DshPermissions(unittest.TestCase):
    def test_same_native_ids_labels_and_risk_metadata_are_kept(self):
        s=session();self.assertEqual(s.data['permission'],'workspace-write')
        self.assertTrue(s.data['planModeSupported']);self.assertFalse(s.data['planMode'])
        self.assertEqual(s.data['permissions'][2],{'id':'danger-full-access','label':'Native full','description':'No confinement','sandbox':'danger-full-access','approval':'never'})
        self.assertEqual(s.data['permissions'][3]['id'],'auto')
        self.assertNotIn('plan',[o['id'] for o in s.data['permissions']])
    def test_explicit_set_needs_matching_native_confirmation(self):
        s=session();calls=[]
        def call(method,params):
            calls.append((method,params))
            if method=='luma/permissions/plan':
                return state(s.data['permission'],plan_active=params['enabled'])
            return state(params['value'],plan_active=s.data['planMode'])
        s.call=call
        for target in ('danger-full-access','auto'):
            s.configure({'permission':target})
            self.assertEqual(s.data['permission'],target)
        s.configure({'planMode':True})
        self.assertTrue(s.data['planMode']);self.assertEqual(s.data['permission'],'auto')
        s.configure({'planMode':False})
        self.assertFalse(s.data['planMode']);self.assertEqual(s.data['permission'],'auto')
        self.assertRaises(Rejected,s.configure,{'permission':'plan'})
        self.assertRaises(Rejected,s.configure,{'planMode':'yes'})
        self.assertEqual(calls[0],('luma/permissions/set',{'sessionId':'native','value':'danger-full-access'}))
        self.assertEqual(calls[2],('luma/permissions/plan',{'sessionId':'native','enabled':True}))
        self.assertEqual(calls[3],('luma/permissions/plan',{'sessionId':'native','enabled':False}))
    def test_live_stream_frame_and_deduplication(self):
        s=session();s.chunk_kind=None;s.chunk_id=None;s.live_streamed=set()
        s.event({'method':'luma/stream/frame','params':{'sessionId':'native','frame':{'type':'start','attemptId':'a1'}}})
        s.event({'method':'luma/stream/frame','params':{'sessionId':'native','frame':{'type':'chunk','attemptId':'a1','chunk':{'type':'reasoning-delta','text':'think '}}}})
        s.event({'method':'luma/stream/frame','params':{'sessionId':'native','frame':{'type':'chunk','attemptId':'a1','chunk':{'type':'text-delta','text':'hello '}}}})
        s.event({'method':'luma/stream/frame','params':{'sessionId':'native','frame':{'type':'chunk','attemptId':'a1','chunk':{'type':'text-delta','text':'world'}}}})
        s.event({'method':'luma/stream/frame','params':{'sessionId':'native','frame':{'type':'end','attemptId':'a1'}}})
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'hello world'}}}})
        texts=[r['text'] for r in s.rows.values() if r['role']=='assistant']
        self.assertEqual(texts,['hello world'])
    def test_plan_review_and_user_questions(self):
        s=session();calls=[]
        s.call=lambda m,p:calls.append((m,p)) or {'ok':True}
        s.event({'method':'luma/questions/request','params':{
            'sessionId':'native','requestId':'q-plan',
            'intent':{'kind':'plan-review','approve':'Approve'},
            'questions':[{'header':'Plan','question':'Step 1: test'}],
        }})
        self.assertEqual(len(s.data['requests']),1)
        s.answer({'id':'q-plan','choice':'allow','answers':{'0':['批准计划 (Approve)']}})
        self.assertEqual(calls[-1],('luma/questions/answer',{'sessionId':'native','requestId':'q-plan','answers':{'Step 1: test':'Approve'}}))
    def test_steer_commands_inbox_subagents_and_rename(self):
        s=session();calls=[]
        s.call=lambda m,p:calls.append((m,p)) or {'accepted':True,'handled':True,'title':'Renamed','items':[]}
        s.commands={'plan','goal','preset','compact'}
        s.update(connected=True,running=True,capabilities={'steer':True,'rename':True,'skills':True,'maxSkills':1})
        self.assertTrue(s.can_steer({'text':'redirect now'}))
        self.assertFalse(s.can_steer({'text':'/plan'}))
        s.prompt({'submissionId':'sub-steer','text':'redirect now','steer':True})
        self.assertEqual(calls[-1][0],'luma/prompt/steer')
        s.event({'method':'luma/subagents/updated','params':{'sessionId':'native','subagent':{'id':'child-1','name':'explore','status':'running','messages':[]}}})
        self.assertEqual(s.subagents['child-1']['name'],'explore')
        s.rename('Renamed')
        self.assertEqual(s.data['title'],'Renamed')
    def test_busy_and_non_catalog_targets_never_dispatch(self):
        s=session();s.call=lambda *a: self.fail('Must not dispatch')
        self.assertRaises(Rejected,s.configure,{'permission':'invented'})
        s.update(running=True);self.assertRaises(Rejected,s.configure,{'permission':'danger-full-access'})
    def test_lost_reply_does_not_claim_success_or_retry(self):
        s=session();calls=[]
        def lost(*args):calls.append(args);raise TimeoutError('unknown native result')
        s.call=lost
        self.assertRaises(TimeoutError,s.configure,{'permission':'danger-full-access'})
        self.assertEqual(s.data['permission'],'');self.assertEqual(len(calls),1)
    def test_native_notification_remains_authoritative_when_reply_is_lost(self):
        s=session();calls=[]
        def lost(*args):
            calls.append(args);s.event({'method':'luma/permissions/updated','params':state('danger-full-access')});raise TimeoutError('reply lost')
        s.call=lost;self.assertRaises(TimeoutError,s.configure,{'permission':'danger-full-access'})
        self.assertEqual(s.data['permission'],'danger-full-access');self.assertEqual(len(calls),1)
    def test_rejected_or_empty_ack_refreshes_actual_state_without_repeating_set(self):
        for response in (None,{},state('workspace-write')):
            s=session();calls=[]
            def call(method,params):
                calls.append(method)
                if method.endswith('/read'):return state()
                if response is None:raise Rejected('native refused')
                return response
            s.call=call;self.assertRaises(Rejected,s.configure,{'permission':'danger-full-access'})
            self.assertEqual(s.data['permission'],'workspace-write');self.assertEqual(calls,['luma/permissions/set','luma/permissions/read'])
    def test_foreign_notifications_cannot_change_selection(self):
        s=session();s.event({'method':'luma/permissions/updated','params':state('danger-full-access','foreign')})
        self.assertEqual(s.data['permission'],'workspace-write')
    def test_malformed_private_update_does_not_break_standard_acp(self):
        s=session();s.current_submission=None;s.chunk_kind=None;s.chunk_id=None
        s.event({'method':'luma/permissions/updated','params':{'sessionId':'native','currentValue':None,'options':[]}})
        self.assertEqual(s.data['permissions'],[])
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'Still native ACP'}}}})
        self.assertTrue(any(row.get('text')=='Still native ACP' for row in s.rows.values()))
    def test_other_acp_and_pi_do_not_gain_dsh_presets(self):
        for cls,cli in [(AcpSession,'generic'),(KimiSession,'Kimi Code'),(PiSession,'pi')]:
            s=cls({'cli':cli,'cwd':'/tmp'},lambda:None)
            self.assertEqual(s.data['permissions'],[])
        s=session();s.permission_protocol=False;s.call=lambda *a:self.fail('Unsupported extension must not dispatch')
        self.assertRaises(Rejected,s.configure,{'permission':'danger-full-access'})
    def test_steer_creates_user_row_and_unattributed_command_done_keeps_foreground(self):
        s=session();s.current_submission='fg-1';s.config['receipts']={'fg-1':'unknown'}
        s.update(connected=True,running=True,capabilities={'steer':True})
        s.row({'id':'fg-1','role':'user','text':'initial'})
        s.call=lambda m,p:{'accepted':True}
        self.assertTrue(s.prompt({'submissionId':'st-1','text':'steered msg','steer':True}))
        self.assertEqual(s.rows['st-1']['text'],'steered msg')
        s.event({'method':'session/update','params':{'sessionId':'native','update':{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':'steered msg'}}}})
        self.assertEqual([r['id'] for r in s.rows.values() if r['role']=='user'],['fg-1','st-1'])
        s.event({'method':'luma/command/done','params':{'sessionId':'native','message':'bg command output'}})
        self.assertTrue(s.data['running'])
        self.assertEqual(s.current_submission,'fg-1')
        self.assertEqual(s.config['receipts']['fg-1'],'unknown')
    def test_request_permission_correlates_prior_tool_call_when_only_call_id_is_sent(self):
        s=session();s.current_submission=None;s.chunk_kind=None;s.chunk_id=None
        raw_input={
            'command':'echo hi > /workspace/x',
            'description':'重试写入测试文件以触发审批',
            'justification':'验证写权限升级',
            'sandbox_permissions':'workspace-write',
        }
        s.event({'method':'session/update','params':{'sessionId':'native','update':{
            'sessionUpdate':'tool_call','toolCallId':'call-1','title':'bash','kind':'execute','status':'in_progress','rawInput':raw_input,
        }}})
        s.event({'id':'perm-1','method':'session/request_permission','params':{
            'sessionId':'native','toolCall':{'toolCallId':'call-1'},
            'options':[{'optionId':'allow-once','name':'Allow once','kind':'allow_once'},{'optionId':'reject-once','name':'Reject','kind':'reject_once'}],
        }})
        req=s.data['requests'][-1]
        self.assertEqual(req['title'],'bash')
        self.assertIn('echo hi > /workspace/x',req['body'])
        self.assertIn('验证写权限升级',req['body'])
        self.assertIn('workspace-write',req['body'])
if __name__=='__main__':unittest.main(verbosity=2)
