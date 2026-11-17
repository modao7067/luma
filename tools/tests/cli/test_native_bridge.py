#!/usr/bin/env python3
import base64,json,pathlib,subprocess,sys,tempfile,threading,time,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.transport import Peer,Rejected
from luma_cli.base import Session
from luma_cli.pi import PiSession
from luma_cli.pi_native import PiNativeSession
from luma_cli.claude import ClaudeSession
from luma_cli.acp import AcpSession
from luma_cli.server import Manager

def session(cls=PiSession,cli='pi'):
    s=cls({'cli':cli,'cwd':'/tmp','receipts':{}},lambda:None)
    s.active_message=None
    return s
class BridgeTests(unittest.TestCase):
    def test_utf8_and_chunk_sequence(self):
        p=Peer.__new__(Peer);p.chunks=None
        raw=json.dumps({'text':'中文😀'},ensure_ascii=False).encode();split=len(raw)//2
        frames=[{'type':'rpc_chunk','chunkId':'one','index':i,'count':2,'byteLength':len(raw),'data':base64.b64encode(b).decode()} for i,b in enumerate([raw[:split],raw[split:]])]
        self.assertIsNone(p._frame(frames[0]));self.assertEqual(p._frame(frames[1]),{'text':'中文😀'})
        p._frame(frames[0]);self.assertRaises(ValueError,p._frame,{'type':'event'})
    def test_chunk_invalid_order_rejected(self):
        p=Peer.__new__(Peer);p.chunks=None
        self.assertRaises(ValueError,p._frame,{'type':'rpc_chunk','index':1,'byteLength':1})
    def test_transport_correlates_concurrent_requests_and_eof(self):
        script='''import sys,json
frames=[json.loads(sys.stdin.readline()) for _ in range(2)]
for f in reversed(frames): print(json.dumps({'id':f['id'],'result':{'method':f['method']}}),flush=True)
'''
        p=Peer([sys.executable,'-c',script],'/tmp',lambda e:None,lambda e:None);p.start_read();out={}
        def call(m):out[m]=p.call(m)
        ts=[threading.Thread(target=call,args=(m,)) for m in ['one','two']]
        for t in ts:t.start()
        for t in ts:t.join(5)
        self.assertEqual(out,{'one':{'method':'one'},'two':{'method':'two'}});p.close()
    def test_native_user_dialog_pauses_rpc_timeout(self):
        script="import sys,json,time; f=json.loads(sys.stdin.readline()); time.sleep(.15); print(json.dumps({'id':f['id'],'result':{}}),flush=True)"
        p=Peer([sys.executable,'-c',script],'/tmp',lambda e:None,lambda e:None,waiting_for_user=lambda:True)
        p.start_read()
        try:self.assertEqual(p.call('initialize',timeout=.03),{})
        finally:p.close()
    def test_uncertain_submission_is_never_resent(self):
        s=session(Session);s.update(connected=True,capabilities={});calls=[]
        def prompt(p):calls.append(p);raise TimeoutError()
        s.prompt=prompt
        self.assertRaises(TimeoutError,s.submit,{'submissionId':'a','text':'one'})
        self.assertEqual(s.submit({'submissionId':'a','text':'different'}),{'receipt':'unknown'})
        self.assertEqual(len(calls),1)
    def test_accepted_prompt_status_failure_is_not_rejected(self):
        s=session();s.update(connected=True,capabilities={})
        def call(method,*a,**kw):
            if method=='prompt':return {}
            raise Rejected('status unavailable')
        s.call=call
        self.assertEqual(s.submit({'submissionId':'one','text':'accepted'}),{'receipt':'accepted'})
        self.assertIn('状态读取失败',s.data['error'])
    def test_stale_state_cannot_overwrite_settlement(self):
        s=session();s.event({'type':'agent_start'});before=s.run_revision
        s.event({'type':'agent_end'});s.apply_running({'isStreaming':True},before)
        self.assertFalse(s.data['running'])
    def test_receipt_invalidates_conditional_snapshot(self):
        s=session();before=s.seq;s.receipt('one','accepted')
        self.assertNotIn('unchanged',s.snapshot(before))
        self.assertTrue(s.snapshot(s.seq)['unchanged'])
    def test_definitive_rejection_retains_receipt(self):
        s=session(Session);s.update(connected=True,capabilities={});s.prompt=lambda p:(_ for _ in ()).throw(Rejected('bad'))
        self.assertRaises(Rejected,s.submit,{'submissionId':'r','text':'one'})
        self.assertEqual(s.snapshot()['receipts']['r'],'rejected')
    def test_native_permission_and_no_global_setting(self):
        s=session();self.assertEqual(s.data['permissions'],[])
        self.assertRaises(Rejected,s.configure,{'permission':'full-access'})
        self.assertRaises(Rejected,s.configure,{'speed':'fast'})
    def test_pi_fallback_notice_uses_gui_runtime_notice_without_static_intro(self):
        for fallback in ('', 'RPC fallback: SDK contract changed'):
            with self.subTest(fallback=fallback):
                s=session();s.config.update(nativeId='native',protocolFallbackNotice=fallback)
                s.start=lambda argv: None
                s.call=lambda method,*args,**kwargs: {'sessionId':'native','isSettled':True} if method=='get_state' else {}
                s.connect()
                self.assertEqual(s.snapshot()['permissionNotice'],fallback)
                self.assertEqual(s.snapshot()['notice'],'')
                s.refresh()
                self.assertEqual(s.snapshot()['permissionNotice'],fallback)
    def test_pi_notification_during_connect_is_not_replaced_by_fallback(self):
        s=session();s.config.update(nativeId='native',protocolFallbackNotice='RPC fallback')
        s.start=lambda argv: s.ui_request({'method':'notify','message':'Native startup warning'})
        s.call=lambda method,*args,**kwargs: {'sessionId':'native','isSettled':True} if method=='get_state' else {}
        s.connect()
        self.assertEqual(s.snapshot()['permissionNotice'],'Native startup warning')
    def test_pi_native_notifications_survive_routine_refresh_and_can_be_cleared(self):
        for cls in (PiSession,PiNativeSession):
            with self.subTest(protocol=cls.__name__):
                s=session(cls);s.config['nativeId']='native'
                s.call=lambda method,*args,**kwargs: {'sessionId':'native','isSettled':True} if method=='get_state' else {}
                s.ui_request({'method':'notify','message':'Extension runtime warning'})
                self.assertEqual(s.snapshot()['permissionNotice'],'Extension runtime warning')
                s.refresh()
                self.assertEqual(s.snapshot()['permissionNotice'],'Extension runtime warning')
                self.assertEqual(s.snapshot()['notice'],'')
                s.ui_request({'method':'notify','message':''})
                self.assertEqual(s.snapshot()['permissionNotice'],'')
    def test_native_extension_approval_exact_values(self):
        s=session();sent=[]
        s.peer=type('Peer',(),{'send':lambda _,e:sent.append(e)})()
        s.ui_request({'type':'extension_ui_request','method':'select','id':'native','title':'授权','options':['允许一次','拒绝']})
        self.assertRaises(Rejected,s.answer,{'id':'native','choice':'-1'})
        s.answer({'id':'native','choice':'1'});self.assertEqual(sent[0],{'type':'extension_ui_response','id':'native','value':'拒绝'})
        self.assertEqual(s.data['requests'],[])
    def test_extension_cancel_removes_request(self):
        s=session();s.ui_request({'method':'confirm','id':'a','title':'approval'})
        s.ui_request({'method':'cancel','targetId':'a'});self.assertEqual(s.data['requests'],[])
    def test_queue_identity_survives_shift_not_reused(self):
        s=session();s.project_queue({'followUp':['one','two']})
        old=s.data['queue'];s.project_queue({'followUp':['two','three']})
        self.assertEqual(old[1]['id'],s.data['queue'][0]['id'])
        self.assertNotIn(old[0]['id'],[q['id'] for q in s.data['queue']])
    def test_duplicate_queue_text_not_mutable(self):
        s=session();s.project_queue({'followUp':['same','same']})
        self.assertTrue(all(not q['canRemove'] and not q['canSteer'] for q in s.data['queue']))
    def test_settlement_pi(self):
        s=session(cli='pi');s.event({'type':'agent_start'});s.event({'type':'agent_end'})
        self.assertFalse(s.data['running'])
        s.event({'type':'session_settled'});self.assertFalse(s.data['running'])
    def test_tool_result_keeps_input(self):
        s=session();s.project({'role':'assistant','timestamp':1,'content':[{'type':'toolCall','id':'x','name':'read','arguments':{'path':'x'}}]},'1')
        s.project({'role':'toolResult','toolCallId':'x','content':[{'type':'text','text':'output'}]},'2')
        self.assertIn('path',s.rows['tool-x']['input']);self.assertEqual(s.rows['tool-x']['status'],'completed')
    def test_pi_history_keeps_native_tool_name_after_result(self):
        s=session(cli='pi')
        s.project({'role':'assistant','timestamp':1,'content':[{'type':'toolCall','id':'x','name':'read','arguments':{'path':'x'}}]},'1')
        s.project({'role':'toolResult','toolCallId':'x','content':[{'type':'text','text':'output'}]},'2')
        row=s.snapshot()['messages'][0]
        self.assertEqual(row['toolName'],'read');self.assertEqual(row['kind'],'tool')
        self.assertEqual(json.loads(row['input']),{'path':'x'});self.assertEqual(row['outcome'],'output')
        self.assertEqual(row['status'],'completed');self.assertEqual(row.get('toolKind',''),'')
    def test_pi_tool_lifecycle_preserves_initial_metadata(self):
        s=session(cli='pi')
        s.event({'type':'tool_execution_start','toolCallId':'x','toolName':'native/custom_tool','args':{'path':'x'}})
        s.event({'type':'tool_execution_update','toolCallId':'x','partialResult':{'content':[{'type':'text','text':'partial'}]}})
        self.assertEqual(s.rows['tool-x']['toolName'],'native/custom_tool')
        s.event({'type':'tool_execution_end','toolCallId':'x','result':{'content':[{'type':'text','text':'done'}]},'isError':True})
        s.project({'role':'toolResult','toolCallId':'x','content':[{'type':'text','text':'done'}],'isError':True},'result')
        row=s.snapshot()['messages'][0]
        self.assertEqual(row['toolName'],'native/custom_tool');self.assertEqual(row['status'],'failed')
        self.assertEqual(json.loads(row['input']),{'path':'x'});self.assertEqual(row['outcome'],'done')
    def test_tool_result_can_supply_native_name_without_call_history(self):
        s=session(cli='pi')
        s.project({'role':'toolResult','toolCallId':'x','toolName':'bash','content':[]},'result')
        self.assertEqual(s.rows['tool-x']['toolName'],'bash')
    def test_missing_tool_name_is_not_inferred_from_text_or_arguments(self):
        s=session()
        s.project({'role':'assistant','content':[{'type':'tool_use','id':'x','input':{'command':'cat file.txt'}}]},'assistant')
        self.assertEqual(s.rows['tool-x']['toolName'],'')
        s.event({'type':'tool_execution_end','toolCallId':'x','result':{}})
        self.assertEqual(s.rows['tool-x']['toolName'],'')
    def test_delta_and_final_reasoning(self):
        s=session();s.event({'type':'message_start','message':{'role':'assistant','timestamp':1,'content':[]}})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'thinking_delta','contentIndex':0,'delta':'why'}})
        self.assertEqual(s.rows['1-0']['text'],'why');self.assertEqual(s.rows['1-0']['status'],'running')
        s.event({'type':'message_end','message':{'role':'assistant','timestamp':1,'content':[{'type':'thinking','thinking':'why done'}]}})
        self.assertEqual(s.rows['1-0']['status'],'completed')
    def test_streaming_deltas_never_mutate_provider_blocks(self):
        s=session();block={'type':'text','text':''};source={'role':'assistant','timestamp':1,'content':[block]}
        s.event({'type':'message_start','message':source})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'text_delta','contentIndex':0,'delta':'怎么'}})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'text_delta','contentIndex':0,'delta':'啦'}})
        self.assertEqual(s.rows['1-0']['text'],'怎么啦');self.assertEqual(block['text'],'')
    def test_thinking_end_and_mismatched_message_end_and_settlement_fallbacks(self):
        s=session()
        s.event({'type':'message_start','message':{'id':'old-stream','role':'assistant','timestamp':1,'content':[]}})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'thinking_delta','contentIndex':0,'delta':'plan'}})
        self.assertEqual(s.rows['old-stream-0']['status'],'running')
        s.event({'type':'message_update','assistantMessageEvent':{'type':'thinking_end','contentIndex':0,'content':'plan done'}})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'text_delta','contentIndex':1,'delta':'answer'}})
        self.assertEqual(s.rows['old-stream-0']['status'],'completed')
        self.assertEqual(s.rows['old-stream-0']['text'],'plan done')
        self.assertEqual(s.rows['old-stream-1']['status'],'running')
        s.event({'type':'message_end','message':{'id':'final-msg','role':'assistant','timestamp':1,'content':[{'type':'thinking','thinking':'plan done'},{'type':'text','text':'answer'}]}})
        self.assertNotIn('old-stream-0',s.rows);self.assertNotIn('old-stream-1',s.rows)
        self.assertEqual(s.rows['final-msg-0']['status'],'completed')
        self.assertEqual(s.rows['final-msg-1']['status'],'completed')
        s.event({'type':'agent_start'})
        s.event({'type':'message_start','message':{'id':'aborted-stream','role':'assistant','timestamp':2,'content':[]}})
        s.event({'type':'message_update','assistantMessageEvent':{'type':'thinking_delta','contentIndex':0,'delta':'half thought'}})
        self.assertEqual(s.rows['aborted-stream-0']['status'],'running')
        s.event({'type':'session_settled'})
        self.assertEqual(s.rows['aborted-stream-0']['status'],'completed')
    def test_message_identity_tracks_active_stream_and_avoids_same_ms_collision(self):
        module=(ROOT/'apps/gui/app/src/main/assets/cli_bridge/native/message-identity.mjs').as_uri()
        script=f"""
import {{observeMessages}} from {json.dumps(module)};
let handler;
const messages=[];
const session={{
 messages,
 sessionManager:{{getEntries:()=>messages.map((m,i)=>({{type:'message',id:'entry-'+i,message:m}}))}},
 agent:{{prompt(){{}},steer(){{}},followUp(){{}}}},
 subscribe(fn){{handler=fn;return ()=>{{}};}}
}};
const emitted=[];
const obs=observeMessages(session,{{entries:{{}},settle(){{}}}},e=>emitted.push(e),()=>{{}},{{compactUpdates:true}});
const p1={{role:'assistant',timestamp:1000,content:[]}};
messages.push(p1);
handler({{type:'message_start',message:{{...p1}}}});
handler({{type:'message_update',message:{{...p1}},assistantMessageEvent:{{type:'text_delta',contentIndex:0,delta:'hi',partial:p1}}}});
const f1={{role:'assistant',timestamp:2000,content:[{{type:'text',text:'hi'}}]}};
messages[0]=f1;
handler({{type:'message_end',message:f1}});
const p2={{role:'assistant',timestamp:1000,content:[]}};
messages.push(p2);
handler({{type:'message_start',message:{{...p2}}}});
const f2={{role:'assistant',timestamp:1000,content:[{{type:'text',text:'second'}}]}};
messages[1]=f2;
handler({{type:'message_end',message:f2}});
const snap=obs.messages();
console.log(JSON.stringify({{
 start1:emitted[0].message.id,
 upd1:emitted[1]._messageId,
 end1:emitted[2].message.id,
 start2:emitted[3].message.id,
 end2:emitted[4].message.id,
 snap0:snap[0].id,
 snap1:snap[1].id,
}}));
"""
        out=json.loads(subprocess.check_output(['node','--input-type=module','-e',script]))
        self.assertEqual(out['start1'],'assistant-1000')
        self.assertEqual(out['upd1'],'assistant-1000')
        self.assertEqual(out['end1'],'assistant-1000')
        self.assertEqual(out['snap0'],'assistant-1000')
        self.assertEqual(out['start2'],'assistant-1000-1')
        self.assertEqual(out['end2'],'assistant-1000-1')
        self.assertEqual(out['snap1'],'assistant-1000-1')
    def test_acp_config_is_not_admission_evidence(self):
        s=session(AcpSession,cli='DeepSeek Harness');s.native_caps={};s.options=[];s.current_submission='a';s.config['receipts']['a']='unknown'
        s.event({'method':'session/update','params':{'update':{'sessionUpdate':'config_option_update','configOptions':[]}}})
        self.assertEqual(s.config['receipts']['a'],'unknown')
    def test_acp_native_tool_kind_survives_replay_and_partial_updates(self):
        s=session(AcpSession,cli='DeepSeek Harness');s.chunk_kind=None;s.chunk_id=None;s.current_submission=None
        def update(value):s.event({'method':'session/update','params':{'update':value}})
        update({'sessionUpdate':'tool_call','toolCallId':'x','title':'Arbitrary native title','kind':'read','rawInput':{'path':'x'},'status':'in_progress'})
        update({'sessionUpdate':'tool_call_update','toolCallId':'x','content':[{'type':'content','content':{'type':'text','text':'output'}}]})
        update({'sessionUpdate':'tool_call_update','toolCallId':'x','kind':None,'status':'completed'})
        row=s.snapshot()['messages'][0]
        self.assertEqual(row['toolKind'],'read');self.assertEqual(row['kind'],'tool')
        self.assertEqual(row['text'],'Arbitrary native title');self.assertEqual(row['status'],'completed')
        self.assertEqual(json.loads(row['input']),{'path':'x'});self.assertEqual(row['outcome'],'output')
        self.assertEqual(row.get('toolName',''),'')
        update({'sessionUpdate':'tool_call_update','toolCallId':'x','kind':'native/future-kind'})
        self.assertEqual(s.rows['tool-x']['toolKind'],'native/future-kind')
    def test_acp_missing_tool_kind_stays_empty_despite_title_or_command(self):
        s=session(AcpSession);s.chunk_kind=None;s.chunk_id=None;s.current_submission=None
        s.event({'method':'session/update','params':{'update':{'sessionUpdate':'tool_call','toolCallId':'x','title':'Read file','rawInput':{'command':'cat file.txt'}}}})
        self.assertEqual(s.rows['tool-x'].get('toolKind',''),'')
        self.assertEqual(s.rows['tool-x'].get('toolName',''),'')
    def test_acp_rejects_other_session_events(self):
        s=session(AcpSession);s.update(nativeId='native');s.event({'method':'session/update','params':{'sessionId':'other','update':{'sessionUpdate':'session_info_update','title':'wrong'}}})
        self.assertEqual(s.data['title'],'')
    def test_acp_permission_ids_are_opaque(self):
        s=session(AcpSession);sent=[];s.peer=type('Peer',(),{'send':lambda _,e:sent.append(e)})()
        s.event({'jsonrpc':'2.0','id':17,'method':'session/request_permission','params':{'options':[{'optionId':'opaque-allow','name':'允许一次'}]}})
        self.assertRaises(Rejected,s.answer,{'id':'17','choice':'yes'})
        s.answer({'id':'17','choice':'opaque-allow'});self.assertEqual(sent[0]['id'],17);self.assertEqual(sent[0]['result']['outcome']['optionId'],'opaque-allow')
    def test_answer_does_not_wait_for_operation(self):
        s=session(Session);s.answer=lambda p: 'answered';s.operation.acquire();results=[]
        t=threading.Thread(target=lambda:results.append(s.action('answer',{})));t.start();t.join(1)
        s.operation.release();self.assertEqual(results,['answered'])
    def test_manager_rejects_identity_mutation_without_start(self):
        with tempfile.TemporaryDirectory() as d:
            m=Manager(d);s=session();s.config.update(cli='pi',cwd=d,nativeId='old');m.sessions['local']=s
            self.assertRaises(Rejected,m.connect,{'id':'local','cli':'pi','cwd':d,'nativeId':'other'})
    def test_unavailable_features_rejected_before_receipt(self):
        s=session(Session);s.update(connected=True,capabilities={})
        self.assertRaises(Rejected,s.submit,{'submissionId':'bad','text':'x','images':['x.png']})
        self.assertEqual(s.config['receipts'],{})
    def test_noop_updates_rows_and_claude_signature_deltas_do_not_bump_revision(self):
        s=session(ClaudeSession)
        s.update(running=True)
        rev_after_running=s.seq
        s.update(running=True)
        self.assertEqual(rev_after_running,s.seq)
        s.row({'id':'msg-1','role':'assistant','text':'hello','status':'running'})
        rev_after_row=s.seq
        s.row({'id':'msg-1','role':'assistant','text':'hello','status':'running'})
        self.assertEqual(rev_after_row,s.seq)
        s.subagent('sub-1',status='running',detail='working')
        rev_after_sub=s.seq
        s.subagent('sub-1',status='running',detail='working')
        self.assertEqual(rev_after_sub,s.seq)
        s.stream({'type':'message_start','message':{'id':'m1'}})
        s.stream({'type':'content_block_start','index':0,'content_block':{'type':'thinking','thinking':''}})
        rev_after_start=s.seq
        for _ in range(10):
            s.stream({'type':'content_block_delta','index':0,'delta':{'type':'signature_delta','signature':'enc'}})
        self.assertEqual(rev_after_start,s.seq)
        snap=s.snapshot()
        snap['messages'][0]['text']='mutated-caller-copy'
        self.assertEqual('hello',s.rows['msg-1']['text'])
    def test_pi_empty_text_blocks_system_prompt_edit_patch_and_special_messages(self):
        s=session()
        s.project({'id':'sys','role':'system','content':'','sections':{'preamble':'x'}},'sys')
        self.assertEqual(s.rows,{})
        s.project({'id':'hidden','role':'custom','display':False,'content':'secret'},'hidden')
        self.assertEqual(s.rows,{})
        s.project({'id':'a1','role':'assistant','content':[{'type':'toolCall','id':'c1','name':'edit','arguments':{'path':'src/App.kt','edits':[{'oldText':'a','newText':'b'}]}},{'type':'text','text':''}]},'a1')
        self.assertEqual(list(s.rows),['tool-c1'])
        self.assertEqual(s.rows['tool-c1']['text'],'edit · src/App.kt')
        patch_txt='--- a/src/App.kt\n+++ b/src/App.kt\n@@ -10,1 +10,1 @@\n-a\n+b'
        s.project({'id':'r1','role':'toolResult','toolCallId':'c1','toolName':'edit','content':[{'type':'text','text':'Successfully replaced 1 block(s)'}],'details':{'patch':patch_txt}},'r1')
        self.assertEqual(s.rows['tool-c1']['outcome'],patch_txt)
        s.project({'id':'cmp','role':'compactionSummary','summary':'compacted'},'cmp')
        self.assertEqual(s.rows['cmp']['kind'],'compaction')
        s.project({'id':'bsh','role':'bashExecution','command':'ls -la','output':'ok','exitCode':0},'bsh')
        self.assertEqual(s.rows['bash-bsh']['toolName'],'bash');self.assertEqual(s.rows['bash-bsh']['text'],'bash · ls -la')
        s.project({'id':'sk','role':'user','content':[{'type':'text','text':'<skill name="luma-browser">huge</skill>\n\nopen page'}],'_skillBlock':{'name':'luma-browser','userMessage':'open page'}},'sk')
        self.assertEqual(s.rows['sk']['text'],'/luma-browser open page')
    def test_pi_retry_and_compaction_settlement_and_toolcall_start_stream(self):
        s=session()
        s.event({'type':'agent_start'});self.assertTrue(s.data['running'])
        s.event({'type':'agent_end','willRetry':True});self.assertTrue(s.data['running'])
        s.event({'type':'agent_settled','aborted':False});self.assertFalse(s.data['running'])
        s.event({'type':'compaction_start','reason':'threshold'});self.assertTrue(s.data['running'])
        s.event({'type':'compaction_end','reason':'threshold','aborted':False,'willRetry':False});self.assertFalse(s.data['running'])
        s.event({'type':'message_start','message':{'id':'m2','role':'assistant','content':[]}})
        s.event({'type':'message_update','_messageId':'m2','assistantMessageEvent':{'type':'toolcall_start','contentIndex':0,'id':'tc2','toolName':'write'}})
        self.assertEqual(s.rows['tool-tc2']['toolName'],'write');self.assertEqual(s.rows['tool-tc2']['status'],'running')
if __name__=='__main__':unittest.main(verbosity=2)
