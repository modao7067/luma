#!/usr/bin/env python3
import json,pathlib,sys,tempfile,threading,time,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.transport import Peer,Rejected
from luma_cli.claude import ClaudeSession
from luma_cli.kimi import KimiSession
from luma_cli.acp import AcpSession
from luma_cli.pi import PiSession
from luma_cli.claude_history import _build_conversation_chain

def session(cls=ClaudeSession):
    s=cls({'cli':'Claude Code','cwd':'/tmp','receipts':{}},lambda:None)
    s.current_submission=None;s.stream_id=None;s.stream_blocks={};s.finalized_blocks=set()
    s.chunk_kind=None;s.chunk_id=None
    return s

class NativeTests(unittest.TestCase):
    def test_claude_control_reply_and_native_result_are_distinct(self):
        script='''import sys,json
f=json.loads(sys.stdin.readline())
assert f['type']=='control_request' and f['request']['subtype']=='initialize'
print(json.dumps({'type':'control_response','response':{'subtype':'success','request_id':f['request_id'],'response':{'models':[]}}}),flush=True)
print(json.dumps({'type':'result','result':'native outcome','is_error':False}),flush=True)
'''
        frames=[];p=Peer([sys.executable,'-c',script],'/tmp',frames.append,lambda e:None);p.start_read()
        try:
            self.assertEqual(p.call('initialize',dialect='claude'),{'models':[]})
            p.reader_thread.join(2);self.assertEqual(frames[0]['result'],'native outcome')
        finally:p.close()
    def test_claude_control_error_is_definitive_rejection(self):
        script="import sys,json;f=json.loads(sys.stdin.readline());print(json.dumps({'type':'control_response','response':{'subtype':'error','request_id':f['request_id'],'error':'native rejected'}}),flush=True)"
        p=Peer([sys.executable,'-c',script],'/tmp',lambda e:None,lambda e:None);p.start_read()
        try:self.assertRaises(Rejected,p.call,'set_model',{},'claude')
        finally:p.close()
    def test_claude_split_native_entries_keep_thought_and_text(self):
        s=session()
        for uid,block in [('a',{'type':'thinking','thinking':'reason'}),('b',{'type':'text','text':'answer'})]:
            s.project({'type':'assistant','uuid':uid,'message':{'id':'same-api-id','role':'assistant','content':[block]}})
        self.assertEqual([(r['role'],r['text']) for r in s.rows.values()],[('reasoning','reason'),('assistant','answer')])
    def test_claude_partial_then_final_uses_native_entry_once(self):
        s=session();s.stream({'type':'message_start','message':{'id':'api'}})
        s.stream({'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}})
        s.stream({'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'answer'}})
        s.event({'type':'assistant','uuid':'native','message':{'id':'api','role':'assistant','content':[{'type':'text','text':'answer'}]}})
        s.stream({'type':'content_block_stop','index':0})
        self.assertEqual(list(s.rows),['native:0']);self.assertEqual(s.rows['native:0']['text'],'answer')
    def test_claude_tool_result_keeps_exact_native_name_and_input(self):
        s=session();s.project({'uuid':'a','message':{'role':'assistant','content':[{'type':'tool_use','id':'opaque','name':'mcp__server__Read','input':{'path':'x'}}]}})
        s.project({'uuid':'b','message':{'role':'user','content':[{'type':'tool_result','tool_use_id':'opaque','content':'failed','is_error':True}]}})
        self.assertEqual(s.rows['tool-opaque']['toolName'],'mcp__server__Read');self.assertEqual(json.loads(s.rows['tool-opaque']['input']),{'path':'x'})
        self.assertEqual(s.rows['tool-opaque']['status'],'failed')
    def test_claude_approval_never_adds_persistent_rules(self):
        s=session();sent=[];s.peer=type('P',(),{'send':lambda _,f:sent.append(f)})()
        s.permission_request({'type':'control_request','request_id':'native','request':{'subtype':'can_use_tool','tool_name':'Bash','input':{'command':'true'},'permission_suggestions':[{'type':'addRules'}]}})
        self.assertRaises(Rejected,s.answer,{'id':'native','choice':'always'})
        s.answer({'id':'native','choice':'allow'})
        self.assertEqual(sent[0]['response']['response'],{'behavior':'allow','updatedInput':{'command':'true'}})
        self.assertEqual(s.data['requests'],[])
    def test_claude_native_questions_keep_multiselect(self):
        s=session();sent=[];s.peer=type('P',(),{'send':lambda _,f:sent.append(f)})()
        q={'question':'Choose','header':'H','options':[{'label':'A'},{'label':'B'}],'multiSelect':True}
        s.permission_request({'request_id':'q','request':{'tool_name':'AskUserQuestion','input':{'questions':[q]}}})
        self.assertTrue(s.data['requests'][0]['questions'][0]['multiple'])
        s.answer({'id':'q','choice':'allow','answers':{'0':['A','B']}})
        self.assertEqual(sent[0]['response']['response']['updatedInput']['answers'],{'Choose':'A, B'})
    def test_claude_receipt_needs_native_ack_not_write(self):
        s=session();s.update(connected=True,capabilities={});sent=[];s.peer=type('P',(),{'send':lambda _,f:sent.append(f)})()
        self.assertEqual(s.submit({'submissionId':'native','text':'hello'}),{'receipt':'unknown'})
        s.event({'type':'user','uuid':'native','message':{'role':'user','content':'hello'}})
        self.assertEqual(s.config['receipts']['native'],'accepted')
    def test_claude_settings_only_use_control_and_not_global_config(self):
        s=session();s.data['models']=[{'id':'opaque'}];calls=[];s.call=lambda *a,**kw:calls.append(a) or {}
        s.configure({'model':'opaque','permission':'plan'})
        self.assertEqual([c[0] for c in calls],['set_model','get_settings','set_permission_mode'])
        self.assertEqual(s.config['selectedPermission'],'plan')
        self.assertRaises(Rejected,s.configure,{'permission':'invented-permission'})
        self.assertRaises(Rejected,s.configure,{'effort':'high'})
    def test_claude_mcp_status_never_projects_credentials(self):
        s=session();s.call=lambda *a,**kw:{'mcpServers':[{'name':'native','status':'connected','config':{'env':{'TOKEN':'private'}},'headers':{'Authorization':'private'}}]}
        s.refresh_mcp();self.assertEqual(s.data['mcp'],[{'name':'native','detail':'connected'}]);self.assertTrue(s.data['mcpAvailable'])
    def test_claude_reset_commands_cannot_rotate_managed_identity(self):
        s=session();s.update(connected=True,capabilities={})
        for command in ('/clear','/reset','/new'):
            self.assertRaises(Rejected,s.submit,{'submissionId':command,'text':command})
        self.assertEqual(s.config['receipts'],{})
    def test_native_history_parent_chain_excludes_abandoned_branch(self):
        entries=[{'uuid':'a','type':'user','parentUuid':None}, {'uuid':'old','type':'assistant','parentUuid':'a'},
                 {'uuid':'new','type':'assistant','parentUuid':'a'}]
        self.assertEqual([e['uuid'] for e in _build_conversation_chain(entries)],['a','new'])
    def test_native_history_parallel_tools_are_not_dropped(self):
        def a(uid,parent,call):return {'uuid':uid,'type':'assistant','parentUuid':parent,'message':{'id':'api','content':[{'type':'tool_use','id':call}]}}
        def r(uid,parent,call):return {'uuid':uid,'type':'user','parentUuid':parent,'message':{'content':[{'type':'tool_result','tool_use_id':call}]}}
        entries=[{'uuid':'u','type':'user','parentUuid':None},a('a','u','one'),a('b','a','two'),r('r1','a','one'),r('r2','b','two'),{'uuid':'end','type':'assistant','parentUuid':'r1','message':{'id':'end','content':[]}}]
        self.assertEqual(set(e['uuid'] for e in _build_conversation_chain(entries)),set(e['uuid'] for e in entries))
    def test_kimi_skills_are_native_advertised_commands(self):
        s=session(KimiSession);s.commands=[];s.apply_commands([{'name':'compact'},{'name':'mcp'},{'name':'skill:opaque'}])
        self.assertEqual(s.data['skills'],[{'id':'skill:opaque','name':'skill:opaque','path':''}]);self.assertEqual(s.data['capabilities']['maxSkills'],1)
        s.update(connected=True);s.validate_prompt({'text':'hello','skills':['skill:opaque']})
        self.assertRaises(Rejected,s.validate_prompt,{'text':'hello','skills':['invented']})
    def test_kimi_native_form_is_lossless_for_multiselect(self):
        s=session(KimiSession);sent=[];s.peer=type('P',(),{'send':lambda _,f:sent.append(f)})()
        s.event({'id':0,'method':'elicitation/create','params':{'mode':'form','message':'Choose','requestedSchema':{'type':'object','required':['q'],'properties':{'q':{'type':'array','title':'Choice','items':{'anyOf':[{'const':'A'},{'const':'B'}]}}}}}})
        self.assertTrue(s.data['requests'][0]['questions'][0]['multiple'])
        s.answer({'id':'0','choice':'accept','answers':{'q':['A','B']}})
        self.assertEqual(sent[0],{'jsonrpc':'2.0','id':0,'result':{'action':'accept','content':{'q':['A','B']}}})
    def test_acp_native_cancel_option_is_not_confused_with_cancellation(self):
        s=session(AcpSession);sent=[];s.peer=type('P',(),{'send':lambda _,f:sent.append(f)})()
        s.event({'id':0,'method':'session/request_permission','params':{'options':[{'optionId':'cancel','name':'Native choice'}]}})
        self.assertEqual([c['id'] for c in s.data['requests'][0]['choices']],['cancel','luma:cancel'])
        s.answer({'id':'0','choice':'cancel'})
        self.assertEqual(sent[0]['result']['outcome'],{'outcome':'selected','optionId':'cancel'})
    def test_acp_native_cancel_settles_incomplete_rows(self):
        s=session(AcpSession);s.call=lambda *a,**kw:{'stopReason':'cancelled'}
        s.row({'id':'tool','role':'tool','text':'Bash','status':'in_progress'})
        s.prompt({'submissionId':'a','text':'stop'})
        deadline=time.monotonic()+1
        while s.data['running'] and time.monotonic()<deadline:time.sleep(.01)
        self.assertFalse(s.data['running']);self.assertEqual(s.rows['tool']['status'],'cancelled')
    def test_pi_refresh_preserves_running_tools_missing_from_history(self):
        s=PiSession({'cli':'pi','cwd':'/tmp'},lambda:None);s.run_revision=0;s.active_message=None
        s.row({'id':'live','role':'tool','text':'bash','status':'running'})
        replies={'get_state':{'sessionId':'native','isStreaming':True,'model':{},'queuedMessages':{}},'get_messages':{'messages':[]}}
        s.call=lambda method,*a,**kw:replies.get(method,{})
        s.refresh();self.assertIn('live',s.rows)
        replies['get_state']['isStreaming']=False;s.refresh();self.assertNotIn('live',s.rows)
    def test_pi_tool_title_prefers_display_summary_and_falls_back_to_command(self):
        from luma_cli.pi import _pi_tool_title
        s=PiSession({'cli':'pi','cwd':'/tmp'},lambda:None);s.run_revision=0;s.active_message=None
        self.assertEqual(
            'bash · Check node stability',
            _pi_tool_title('bash', {'command': 'CANDIDATES=(\n  a\n)', 'displaySummary': '\x1b[32mCheck node\n  stability\x1b[0m'}),
        )
        self.assertEqual(
            'bash · git status',
            _pi_tool_title('bash', {'command': '\n  git status\ngit diff', 'displaySummary': '   '}),
        )
        self.assertEqual(
            'bash · git status',
            _pi_tool_title('bash', {'command': 'git status', 'displaySummary': 'bash'}),
        )
        self.assertEqual(
            'custom_tool · Fetch manifest',
            _pi_tool_title('custom_tool', {'url': 'https://example.invalid', 'displaySummary': 'Fetch manifest'}),
        )
        s.event({'type': 'tool_execution_start', 'toolCallId': 'call-1', 'toolName': 'bash',
                 'args': {'command': 'CANDIDATES=(\n  a\n)', 'displaySummary': 'Verify endpoints'}})
        self.assertEqual('bash · Verify endpoints', s.rows['tool-call-1']['text'])
        s.project({'role': 'toolResult', 'toolCallId': 'call-1', 'toolName': 'bash', 'content': [{'type': 'text', 'text': 'ok'}]}, 'res-1')
        self.assertEqual('bash · Verify endpoints', s.rows['tool-call-1']['text'])

if __name__=='__main__':unittest.main(verbosity=2)
