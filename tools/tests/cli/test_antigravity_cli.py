#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
import json
import os
import pathlib
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.antigravity import AntigravitySession
from luma_cli.antigravity_cli import (AntigravityCliSession, PROTOCOL, FULL_ACCESS,
    invoke_child, child_log_path, project_child_transcript)
from luma_cli.i18n import tr
from luma_cli.server import Manager
from luma_cli.transport import Rejected

FIXTURE = r'''#!/usr/bin/python3
import json,os,pathlib,signal,sys,time,uuid
args=sys.argv[1:];mode=os.environ.get('FIXTURE_MODE','normal')
log=pathlib.Path(os.environ['FIXTURE_LOG'])
with log.open('a') as f:f.write(json.dumps(args)+'\n')
if '--version' in args:print('1.4.12');sys.exit(0)
if 'models' in args:
 if mode=='catalog-auth':print('authentication required',file=sys.stderr);sys.exit(1)
 if mode=='bad-catalog':print('not TSV');sys.exit(0)
 print('opaque/low\tNative low\nopaque/high\tNative high');sys.exit(0)
native=args[args.index('--conversation')+1] if '--conversation' in args else str(uuid.uuid4())
if mode=='wrong-id':native='foreign'
def emit(event,**payload):print(json.dumps({'event':event,**payload}),flush=True)
if mode=='startup-stall':time.sleep(20);sys.exit(0)
if mode=='startup-auth':
 emit('result',result={'conversation_id':'','status':'ERROR','error':'authentication required'})
 sys.exit(1)
if mode=='malformed-init':emit('init',conversation_id=native,init={'permission_mode':'request-review'});time.sleep(20)
emit('init',conversation_id=native,init={'permission_mode':'always-proceed',
 'model':args[args.index('--model')+1] if '--model' in args else None})
turn=0
def step(index,kind,state='DONE',**data):
 emit('step_update',step_update={'conversation_id':native,'step_index':index,'state':state,'step_type':kind,**data})
def interrupted(*unused):
 if mode!='stop-no-result':emit('result',result={'conversation_id':native,'status':'ERROR','error':'interrupted','num_turns':turn})
 sys.exit(0)
signal.signal(signal.SIGINT,interrupted)
for line in sys.stdin:
 f=json.loads(line);assert f['event']=='user' and isinstance(f['message']['content'],str)
 with log.open('a') as out:out.write(json.dumps({'prompt':f})+'\n')
 turn+=1;index=(turn-1)*10
 if mode=='stall':time.sleep(20);continue
 if mode=='foreign-output':
  emit('step_update',step_update={'conversation_id':'foreign','step_index':index,'state':'DONE','step_type':'user_input'})
  time.sleep(20);continue
 if mode=='native-error':
  emit('result',result={'conversation_id':native,'status':'ERROR','error':'native refused before admission','num_turns':0});continue
 step(index,'user_input')
 if mode=='held':
  step(index+1,'agent_response','DONE')
  step(index+2,'tool','ACTIVE',tool_name='run_command',tool_info={'name':'run_command','parameters':{'CommandLine':'sleep 45'}})
  rel=os.environ.get('FIXTURE_RELEASE')
  while not (rel and pathlib.Path(rel).exists()):time.sleep(.05)
  step(index+2,'tool',tool_name='run_command',tool_info={'output':'slept'})
  step(index+3,'agent_response','DONE')
  step(index+4,'tool',tool_name='run_command',tool_info={'name':'run_command','parameters':{'CommandLine':'echo second'},'output':'second'})
  step(index+5,'agent_response','ACTIVE',text_delta='final ')
  step(index+5,'agent_response','DONE',text_delta='reply')
  emit('result',result={'conversation_id':native,'status':'SUCCESS','response':'final reply','num_turns':turn})
  continue
 if mode in ('stop','stop-no-result'):time.sleep(20);continue
 step(index+1,'agent_response','ACTIVE',text_delta='hello ')
 step(index+1,'agent_response','DONE',text_delta='world',usage={'input_tokens':10,'output_tokens':2,'thinking_tokens':3})
 step(index+2,'tool','ACTIVE',tool_name='run_command',tool_info={'name':'run_command','parameters':{'CommandLine':'echo fixture'}})
 if mode!='orphan-tool':step(index+2,'tool',tool_name='run_command',tool_info={'output':'fixture output'})
 if mode=='subagent':
  child=os.environ.get('FIXTURE_CHILD_LOG','')
  params={'Role':'helper','Prompt':'test task','TypeName':'researcher','Model':'inherit','Workspace':'inherit'}
  info={'name':'invoke_subagent','parameters':params}
  step(index+3,'tool','ACTIVE',tool_name='invoke_subagent',tool_info=info)
  created='Created the following subagent:\n'+json.dumps({'conversationId':'child-native','logAbsoluteUri':'file://'+child,'workspaceUris':['/workspace']})
  step(index+3,'tool',tool_name='invoke_subagent',tool_info={**info,'output':created})
  mark=os.environ.get('FIXTURE_MARK')
  if mark:pathlib.Path(mark).write_text('ready')
  rel=os.environ.get('FIXTURE_RELEASE')
  if rel:
   while not pathlib.Path(rel).exists():time.sleep(.05)
  step(index+4,'agent_response','DONE',text_delta='<SYSTEM_MESSAGE>[Message] timestamp=2026-11-06T00:00:00Z sender=child-native priority=normal content=done</SYSTEM_MESSAGE>')
 if mode=='structured-subagent':
  step(index+3,'tool','ACTIVE',tool_name='invoke_subagent',tool_info={'name':'invoke_subagent','parameters':{'Role':'helper','TypeName':'researcher'}})
  step(index+3,'subagent',tool_name='invoke_subagent',subagent_info={'subagents':[{'type_name':'researcher','conversation_id':'child-native','role':'helper','initial_prompt':'test task','log_uri':'file://'+os.environ.get('FIXTURE_CHILD_LOG','')}]})
 if mode=='subagent-kill':
  params={'Role':'helper','Prompt':'test task','TypeName':'researcher'}
  created=json.dumps({'conversationId':'child-native','logAbsoluteUri':'file:///dev/null','workspaceUris':[]})
  step(index+3,'tool',tool_name='invoke_subagent',tool_info={'name':'invoke_subagent','parameters':params,'output':'Created the following subagent:\n'+created})
  step(index+4,'tool','ACTIVE',tool_name='manage_subagents',tool_info={'name':'manage_subagents','parameters':{'Action':'kill','ConversationIds':['child-native']}})
  step(index+4,'tool',tool_name='manage_subagents',tool_info={'name':'manage_subagents','parameters':{'Action':'kill','ConversationIds':['child-native']},'output':'killed'})
 if mode=='subagent-late':
  params={'Role':'helper','Prompt':'test task','TypeName':'researcher'}
  created=json.dumps({'conversationId':'child-native','workspaceUris':['/workspace']})
  step(index+3,'tool',tool_name='invoke_subagent',tool_info={'name':'invoke_subagent','parameters':params,'output':'Created the following subagent:\n'+created})
  emit('result',result={'conversation_id':native,'status':'SUCCESS','response':'parent done','num_turns':turn,'usage':{'input_tokens':1,'output_tokens':1,'thinking_tokens':0,'cache_read_tokens':0,'total_tokens':2}})
  rel=os.environ.get('FIXTURE_RELEASE')
  if rel:
   while not pathlib.Path(rel).exists():time.sleep(.05)
  step(index+4,'agent_response','DONE',text_delta='<SYSTEM_MESSAGE>[Message] timestamp=2026-11-06T00:00:00Z sender=child-native content=done</SYSTEM_MESSAGE>')
  continue
 if mode=='duplicate-usage':step(index+1,'agent_response',usage={'input_tokens':10,'output_tokens':2,'thinking_tokens':3})
 emit('result',result={'conversation_id':native,'status':'SUCCESS','response':'hello world','num_turns':turn,
  'usage':{'input_tokens':10*turn,'output_tokens':2*turn,'thinking_tokens':3*turn,'cache_read_tokens':0,'total_tokens':15*turn}})
 if mode=='tail-exit':break
'''

class AgyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='luma-agy-fixture-')
        self.root = pathlib.Path(self.temp.name)
        self.binary = self.root/'agy';self.binary.write_text(FIXTURE);self.binary.chmod(0o700)
        self.log = self.root/'argv.jsonl'
        self.env = patch.dict(os.environ, {'PATH': str(self.root)+':/usr/bin:/bin', 'HOME': str(self.root/'home'),
            'GEMINI_HOME': str(self.root/'home/.gemini'), 'FIXTURE_LOG': str(self.log), 'LANG': 'C.UTF-8'}, clear=True)
        self.env.start();self.sessions=[]

    def tearDown(self):
        for s in self.sessions:
            if s.peer:s.peer.close()
        self.env.stop();self.temp.cleanup()

    def connect(self, mode='normal', native=None, **config):
        os.environ['FIXTURE_MODE']=mode
        c={'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL,'receipts':{},**config}
        if native:c['nativeId']=native
        s=AntigravityCliSession(c,lambda:None);self.sessions.append(s);s.connect();return s

    def wait(self, predicate, timeout=3):
        end=time.monotonic()+timeout
        while not predicate() and time.monotonic()<end:time.sleep(.01)
        self.assertTrue(predicate())

    def prompts(self):
        return [x['prompt'] for x in map(json.loads,self.log.read_text().splitlines()) if isinstance(x,dict)]

    def send(self,s,ident='send'):
        s.submit({'submissionId':ident,'text':'fixture question'});self.wait(lambda:not s.data['running']);return s.snapshot()

    def write_parent_transcript(self, native, steps):
        path = self.root/'home/.gemini/antigravity-cli/brain'/native/'.system_generated/logs/transcript.jsonl'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(''.join(json.dumps(s)+'\n' for s in steps))

    HELD_TRANSCRIPT = [
        {'step_index':0,'type':'USER_INPUT','status':'DONE','content':'fixture question'},
        {'step_index':1,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'run_command','args':{'CommandLine':'"sleep 45"'}}]},
        {'step_index':2,'type':'GENERIC','status':'RUNNING','content':'Tool is running as a background task'},
        {'step_index':3,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'run_command','args':{'CommandLine':'"echo second"'}}]},
        {'step_index':4,'type':'GENERIC','status':'DONE','content':'second'},
        {'step_index':5,'type':'PLANNER_RESPONSE','status':'DONE','content':'final reply'},
    ]

    def test_reply_held_behind_background_tool_is_filled_from_transcript_once(self):
        release=self.root/'release';os.environ['FIXTURE_RELEASE']=str(release)
        s=self.connect('held');s.submit({'submissionId':'send','text':'fixture question'})
        self.wait(lambda:'agy:send:2:tool' in s.rows)
        self.write_parent_transcript(s.data['nativeId'], self.HELD_TRANSCRIPT)
        self.wait(lambda:any(r.get('text')=='final reply' for r in s.rows.values()), timeout=8)
        self.assertTrue(s.data['running'], 'turn is still unsettled natively')
        second=s.rows['agy:send:4:tool'];self.assertEqual((second['outcome'],second['status']),('second','completed'))
        self.assertIn('echo second',second['input'])
        release.write_text('go');self.wait(lambda:not s.data['running'])
        replies=[r['text'] for r in s.snapshot()['messages'] if r['role']=='assistant']
        self.assertEqual(replies,['final reply'])
        self.assertEqual(s.rows['agy:send:2:tool']['outcome'],'slept')

    def test_steps_lost_by_relaunch_are_recovered_from_transcript(self):
        native='native-held'
        rows=[{'id':'send','role':'user','text':'fixture question','turnId':'send','status':'completed'},
              {'id':'agy:send:2:tool','role':'tool','text':'run_command','kind':'tool','toolName':'run_command','turnId':'send','status':'running'}]
        self.write_parent_transcript(native, self.HELD_TRANSCRIPT)
        s=self.connect(native=native,localProjection={'version':1,'nativeProtocol':PROTOCOL,'nativeId':native,'messages':rows})
        self.wait(lambda:any(r.get('text')=='final reply' for r in s.rows.values()), timeout=5)
        self.assertEqual(s.rows['agy:send:5:agent_response']['turnId'],'send')
        self.assertNotIn('agy:send:0:agent_response',s.rows)

    def test_exact_native_entry_full_access_without_acp_or_api_flags(self):
        s=self.connect();a=list(map(json.loads,self.log.read_text().splitlines()))[-1]
        self.assertEqual(a,['--input-format','stream-json','--output-format','stream-json','--add-dir',str(self.root),'--disable-slash-commands','--dangerously-skip-permissions'])
        self.assertEqual(s.data['permission'],FULL_ACCESS);self.assertEqual(s.data['notice'],'')
        self.assertEqual(s.data['permissions'],[{'id':FULL_ACCESS,'label':tr('全权限（跳过审批）','Full access (skip approvals)')}])
        self.assertFalse(any(s.data['capabilities'][k] for k in ('queue','steer','images','skills','compact','rename')))
        self.assertFalse(s.pending_requests)

    def test_native_model_ids_and_no_independent_effort(self):
        s=self.connect();self.assertEqual([m['id'] for m in s.data['models']],['opaque/low','opaque/high'])
        self.assertEqual(s.data['models'][0]['efforts'],[])
        for p in ({'effort':'high'},{'speed':'fast'},{'permission':'default'},{'model':'invented'}):
            self.assertRaises(Rejected,s.configure,p)

    def test_text_tool_result_and_native_receipt(self):
        s=self.connect();v=self.send(s)
        self.assertEqual(v['receipts']['send'],'accepted')
        replies=[r for r in v['messages'] if r['role']=='assistant'];self.assertEqual([r['text'] for r in replies],['hello world'])
        tool=next(r for r in v['messages'] if r['role']=='tool')
        self.assertEqual(tool['toolName'],'run_command');self.assertEqual(tool['status'],'completed')
        self.assertEqual(tool['outcome'],'fixture output');self.assertIn('echo fixture',tool['input'])
        self.assertFalse(any(r.get('kind')=='reasoning' for r in v['messages']))

    def test_pipe_write_and_local_user_row_never_confirm_receipt_or_resend(self):
        s=self.connect('stall');result=s.submit({'submissionId':'send','text':'fixture question'})
        self.assertEqual(result,{'receipt':'unknown'});self.assertEqual(s.config['receipts']['send'],'unknown')
        self.assertIn('send',s.rows);s.submit({'submissionId':'send','text':'do not resend'})
        self.wait(lambda:len(self.prompts())==1);self.assertEqual(len(self.prompts()),1)

    def test_foreign_conversation_cannot_confirm_or_project(self):
        s=self.connect('foreign-output');s.submit({'submissionId':'send','text':'fixture question'})
        self.wait(lambda:len(self.prompts())==1);time.sleep(.05)
        self.assertEqual(s.config['receipts']['send'],'unknown');self.assertEqual(list(s.rows),['send'])

    def test_native_error_without_admission_leaves_unknown_not_rejected(self):
        s=self.connect('native-error');v=self.send(s)
        self.assertEqual(v['receipts']['send'],'unknown');self.assertIn('native refused',v['error'])

    def test_repeated_done_usage_is_not_added_to_cumulative_result(self):
        s=self.connect('duplicate-usage');v=self.send(s);self.send(s,'next')
        self.assertEqual(next(m['value'] for m in v['metrics'] if m['label']==tr('CLI 累计 · 输入缓存命中','CLI total · input cache hit')),'0 / 10 · 0.0%')
        self.assertEqual(next(m['value'] for m in s.data['metrics'] if m['label']==tr('CLI 累计 · 输入缓存命中','CLI total · input cache hit')),'0 / 20 · 0.0%')

    def test_parent_result_never_completes_missing_tool(self):
        s=self.connect('orphan-tool');v=self.send(s)
        self.assertEqual(next(r['status'] for r in v['messages'] if r['role']=='tool'),'unknown')

    def test_child_identity_parser_rejects_unrelated_files(self):
        raw='Created\n{"conversationId":"child-native","logAbsoluteUri":"file:///etc/passwd","workspaceUris":["/workspace"]}'
        self.assertEqual(invoke_child(raw),('child-native','file:///etc/passwd'))
        path=child_log_path('child-native','file:///etc/passwd')
        self.assertTrue(path.endswith('/antigravity-cli/brain/child-native/.system_generated/logs/transcript.jsonl'))
        self.assertFalse(path.startswith('/etc/'))
        self.assertEqual(child_log_path('../etc','file:///etc/passwd'),'')

    def test_invoke_error_or_unparsed_output_registers_nothing(self):
        s=self.connect();s.current_submission='send'
        s.step({'step_index':1,'step_type':'tool','state':'DONE','tool_name':'invoke_subagent','tool_info':{
            'name':'invoke_subagent','error':'nope','parameters':{'TypeName':'researcher','Role':'helper','Prompt':'x'},
            'output':'{"conversationId":"child-err"}'}})
        s.step({'step_index':2,'step_type':'tool','state':'DONE','tool_name':'invoke_subagent','tool_info':{
            'name':'invoke_subagent','parameters':{'TypeName':'researcher','Role':'helper','Prompt':'x'},'output':'no json'}})
        s.note_manage({'Action':'kill','ConversationIds':['missing']})
        self.assertEqual(s.subagents,{})

    def test_native_structured_done_registers_child_and_completes_the_same_tool(self):
        s=self.connect('structured-subagent');v=self.send(s)
        child=s.subagents['child-native']
        self.assertEqual((child['name'],child['role'],child['task'],child['status']),('researcher','helper','test task','running'))
        tools=[r for r in v['messages'] if r.get('toolName')=='invoke_subagent']
        self.assertEqual(len(tools),1)
        self.assertEqual(tools[0]['status'],'completed')
        self.assertEqual(child['itemId'],tools[0]['id'])
        self.assertIn('helper',tools[0]['input'])

    def test_manage_subagents_kill_cancels_and_parent_success_does_not_restore_it(self):
        s=self.connect('subagent-kill');self.send(s)
        self.assertEqual(s.subagents['child-native']['status'],'cancelled')
        self.assertEqual(s.subagents['child-native']['nativeStatus'],'manage_subagents')
        self.assertFalse(s.data['running'])

    def test_parent_success_and_child_message_do_not_settle_child(self):
        release=self.root/'release-late'
        os.environ['FIXTURE_RELEASE']=str(release)
        s=self.connect('subagent-late');self.send(s)
        self.assertEqual(s.subagents['child-native']['status'],'running')
        self.assertEqual(s.subagents['child-native']['name'],'researcher')
        self.assertFalse(s.data['running'])
        release.write_text('1')
        self.wait(lambda:any('sender=child-native' in r.get('text','') for r in s.rows.values()))
        self.assertEqual(s.subagents['child-native']['status'],'running')
        self.assertFalse(s.data['running'])

    def test_native_invoke_tails_child_transcript_with_idle_and_reactivation(self):
        log=self.root/'home/.gemini/antigravity-cli/brain/child-native/.system_generated/logs/transcript.jsonl'
        release=self.root/'release-child'
        os.environ['FIXTURE_CHILD_LOG']=str(log)
        os.environ['FIXTURE_RELEASE']=str(release)
        os.environ['FIXTURE_MARK']=str(self.root/'mark-child')
        s=self.connect('subagent')
        s.submit({'submissionId':'send','text':'fixture question'})
        try:
            self.wait(lambda:s.subagents.get('child-native',{}).get('status')=='running')
            child=s.subagents['child-native']
            self.assertEqual((child['name'],child['role'],child['task'],child['turnId']),('researcher','helper','test task','send'))
            self.assertEqual(s.rows[child['itemId']]['status'],'completed')
            self.assertEqual(s.rows[child['itemId']]['toolName'],'invoke_subagent')
            self.assertEqual(child['model'],'inherit')
            self.assertEqual(pathlib.Path(child['logPath']),log.resolve())
            self.assertFalse(log.exists())
            s.note_manage({'Action':'list'})
            self.assertEqual(s.subagents['child-native']['status'],'running')
            log.parent.mkdir(parents=True,exist_ok=True)
            lines=[
                {"step_index":0,"type":"PLANNER_RESPONSE","status":"DONE","thinking":"look","tool_calls":[{"name":"view_file","args":{"path":"README"}}]},
                {"step_index":1,"type":"GENERIC","status":"DONE","content":"file body"},
                {"step_index":2,"type":"PLANNER_RESPONSE","status":"DONE","content":"finished note"},
            ]
            log.write_text(''.join(json.dumps(x)+'\n' for x in lines))
            self.wait(lambda:any(m.get('text')=='finished note' for m in s.subagents['child-native'].get('messages',[])),5)
            child=s.subagents['child-native']
            self.assertEqual(child['status'],'succeeded')
            self.assertEqual(child['detail'],'finished note')
            tool=next(m for m in child['messages'] if m.get('toolName')=='view_file')
            self.assertEqual(tool['input'],json.dumps({"path":"README"}))
            self.assertEqual(tool['outcome'],'file body')
            self.assertEqual(tool['status'],'completed')
            self.assertTrue(any(m.get('kind')=='reasoning' and m.get('text')=='look' for m in child['messages']))
            self.assertTrue(s.data['running'])
        finally:
            if not release.exists():release.write_text('go')
        self.wait(lambda:not s.data['running'],5)
        self.assertEqual(s.subagents['child-native']['status'],'succeeded')
        self.assertTrue(any(m.get('text')=='finished note' for m in s.subagents['child-native']['messages']))
        extra={"step_index":3,"type":"PLANNER_RESPONSE","status":"RUNNING","tool_calls":[{"name":"view_file","args":{"path":"MORE"}}]}
        with log.open('a') as handle:handle.write(json.dumps(extra)+'\n')
        self.wait(lambda:any('MORE' in (m.get('input') or '') for m in s.subagents['child-native'].get('messages',[])),5)
        self.assertEqual(s.subagents['child-native']['status'],'running')
        self.assertEqual(s.subagents['child-native']['detail'],'view_file')
        s.note_manage({'Action':'kill','ConversationIds':['child-native']})
        with log.open('a') as handle:handle.write(json.dumps({"step_index":4,"type":"PLANNER_RESPONSE","status":"DONE","content":"late output"})+'\n')
        self.wait(lambda:s.subagents['child-native']['detail']=='late output')
        self.assertEqual(s.subagents['child-native']['status'],'cancelled')

    def test_parent_transcript_recovers_missing_children_and_native_kill_without_new_prompt(self):
        s=self.connect(native='parent-native')
        parent=self.root/'home/.gemini/antigravity-cli/brain/parent-native/.system_generated/logs/transcript.jsonl'
        parent.parent.mkdir(parents=True)
        def write(steps): parent.write_text(''.join(json.dumps(x)+'\n' for x in steps))
        steps=[
            {'step_index':1,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'invoke_subagent','args':{'Role':'"helper"','TypeName':'"self"','Prompt':'"task"','Model':'"flash"'}}]},
            {'step_index':2,'type':'GENERIC','status':'DONE','content':'Created the following subagent:\n{"conversationId":"recovered"}'},
        ]
        write(steps)
        self.wait(lambda:'recovered' in s.subagents)
        child=s.subagents['recovered']
        self.assertEqual((child['role'],child['task'],child['status']),('helper','task','running'))
        self.assertEqual(child['model'],'flash')
        self.assertTrue(child['logPath'].endswith('/brain/recovered/.system_generated/logs/transcript.jsonl'))
        steps.extend([
            {'step_index':3,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'manage_subagents','args':{'Action':'"kill_all"'}}]},
            {'step_index':4,'type':'GENERIC','status':'DONE','content':'Successfully killed 1 subagent(s)'},
        ])
        write(steps);self.wait(lambda:s.subagents['recovered']['status']=='cancelled')
        self.assertEqual(self.prompts(),[])

    def test_parent_recovery_reads_early_launches_and_scopes_historical_kill_all(self):
        s=self.connect(native='parent-native')
        s.subagent('new-child', status='running')
        parent=self.root/'home/.gemini/antigravity-cli/brain/parent-native/.system_generated/logs/transcript.jsonl'
        parent.parent.mkdir(parents=True)
        def launch(index, child):
            return [
                {'step_index':index,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'invoke_subagent','args':{}}]},
                {'step_index':index+1,'type':'GENERIC','status':'DONE','content':json.dumps({'conversationId':child})},
            ]
        steps=launch(1,'old-child')+[
            {'step_index':3,'type':'PLANNER_RESPONSE','status':'DONE','tool_calls':[{'name':'manage_subagents','args':{'Action':'"kill_all"'}}]},
            {'step_index':4,'type':'GENERIC','status':'DONE','content':'Successfully killed 1 subagent(s)'},
        ]+launch(5,'new-child')+[
            {'step_index':7,'type':'USER_INPUT','status':'DONE','content':'x'*2_000_100},
        ]
        parent.write_text(''.join(json.dumps(x)+'\n' for x in steps))
        self.wait(lambda:s.subagents.get('old-child',{}).get('status')=='cancelled')
        self.assertEqual(s.subagents['new-child']['status'],'running')
        with parent.open('a') as f:f.write('{"step_index":8,"type":"PLANNER_RESPONSE","status":"DONE","tool_calls":')
        time.sleep(.3)
        with parent.open('a') as f:f.write('[{"name":"manage_subagents","args":{"Action":"\\"kill_all\\""}}]}\n')
        with parent.open('a') as f:f.write(json.dumps({'step_index':9,'type':'GENERIC','status':'DONE','content':'Successfully killed 1 subagent(s)'})+'\n')
        self.wait(lambda:s.subagents['new-child']['status']=='cancelled')

    def test_restore_merges_old_split_launch_rows_and_assigns_child_log(self):
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL,'nativeId':'parent-native'},lambda:None)
        prefix='agy:send:3'
        s.config['localProjection']={'version':1,'nativeProtocol':PROTOCOL,'nativeId':'parent-native',
            'messages':[{'id':prefix+':tool','role':'tool','toolName':'invoke_subagent','status':'unknown','input':'native args'},
                        {'id':prefix+':subagent','role':'tool','toolName':'invoke_subagent','status':'completed'}],
            'subAgents':[{'id':'child-native','itemId':prefix+':subagent','status':'unknown'}]}
        s.restore_projection()
        self.assertEqual(len(s.rows),1)
        self.assertEqual(s.rows[prefix+':tool']['status'],'completed')
        self.assertEqual(s.rows[prefix+':tool']['input'],'native args')
        self.assertEqual(s.subagents['child-native']['itemId'],prefix+':tool')
        self.assertTrue(s.subagents['child-native']['logPath'].endswith('/brain/child-native/.system_generated/logs/transcript.jsonl'))

    def test_child_model_is_native_selection_not_parent_current_model_and_is_persisted(self):
        s=self.connect(model='opaque/high')
        s.note_invoke('tool','turn',{'parameters':{'Model':'child/model'},'output':'{"conversationId":"child-native"}'})
        self.assertEqual(s.subagents['child-native']['model'],'child/model')
        s.note_invoke('tool','turn',{'parameters':{},'output':'{"conversationId":"child-native"}'})
        self.assertEqual(s.subagents['child-native']['model'],'child/model')
        s.checkpoint(force=True)
        self.assertEqual(s.config['localProjection']['subAgents'][0]['model'],'child/model')
        restored=AntigravityCliSession(dict(s.config),lambda:None);restored.restore_projection()
        self.assertEqual(restored.subagents['child-native']['model'],'child/model')
        s.note_invoke('other','turn',{'parameters':{},'output':'{"conversationId":"no-model"}'})
        self.assertNotIn('model',s.subagents['no-model'])

    def test_parallel_transcript_tools_match_results_and_decode_native_argument_json(self):
        rows=project_child_transcript('child',[
            {'step_index':1,'type':'PLANNER_RESPONSE','status':'DONE','thinking':'native thought','tool_calls':[
                {'name':'view_file','args':{'path':'"README"'}}, {'name':'view_file','args':{'path':'"LICENSE"'}}]},
            {'step_index':2,'type':'GENERIC','status':'DONE','content':'readme body'},
            {'step_index':3,'type':'GENERIC','status':'DONE','content':'license body'},
        ])
        tools=[r for r in rows if r.get('toolName')]
        self.assertEqual([r['outcome'] for r in tools],['readme body','license body'])
        self.assertEqual([json.loads(r['input']) for r in tools],[{'path':'README'},{'path':'LICENSE'}])
        self.assertTrue(all(r['status']=='completed' for r in tools))

    def test_model_change_restarts_same_native_conversation_and_persists_confirmed_choice(self):
        s=self.connect();native=s.data['nativeId'];old=s.peer
        s.configure({'model':'opaque/high'})
        self.assertIsNotNone(old.proc.poll());self.assertEqual(s.data['nativeId'],native)
        self.assertEqual(s.data['model'],'opaque/high');self.assertEqual(s.config['model'],'opaque/high')
        a=list(map(json.loads,self.log.read_text().splitlines()))[-1]
        self.assertEqual(a[a.index('--conversation')+1],native)
        self.assertEqual(a[a.index('--model')+1],'opaque/high')

    def test_resume_identity_mismatch_refused_and_original_id_preserved(self):
        os.environ['FIXTURE_MODE']='wrong-id'
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL,'nativeId':'expected'},lambda:None)
        self.sessions.append(s)
        with self.assertRaisesRegex(Rejected,tr('不同原生会话','different native session')):s.connect()
        self.assertEqual(s.config['nativeId'],'expected');self.assertIsNotNone(s.peer.proc.poll())

    def test_startup_auth_failure_is_actionable_and_not_unknown_creation(self):
        os.environ['FIXTURE_MODE']='startup-auth'
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL},lambda:None)
        self.sessions.append(s)
        with self.assertRaisesRegex(Rejected,tr('原生登录','sign in')):s.connect()
        self.assertFalse(s.config['creationUnknown']);self.assertIsNotNone(s.peer.proc.poll())

    def test_timeout_preserves_unknown_creation_and_no_automatic_second_spawn(self):
        os.environ['FIXTURE_MODE']='startup-stall'
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL},lambda:None)
        s.startup_timeout=.1;self.sessions.append(s)
        with self.assertRaisesRegex(Rejected,tr('不要自动重建','do not recreate')):s.connect()
        before=self.log.read_text()
        with self.assertRaisesRegex(Rejected,tr('创建结果未知','creation result is unknown')):s.connect()
        self.assertEqual(self.log.read_text(),before);self.assertIsNotNone(s.peer.proc.poll())

    def test_wrong_permission_mode_is_not_presented_as_full_access(self):
        os.environ['FIXTURE_MODE']='malformed-init'
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL},lambda:None)
        self.sessions.append(s)
        with self.assertRaisesRegex(Rejected,tr('未确认已选全权限','did not confirm full-access')):s.connect()
        self.assertEqual(s.data['permissions'],[])

    def test_catalog_auth_or_bad_data_never_starts_or_fakes_models(self):
        for mode in ('catalog-auth','bad-catalog'):
            os.environ['FIXTURE_MODE']=mode
            s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL},lambda:None)
            with self.assertRaises(Rejected):s.connect()
            self.assertIsNone(s.peer);self.assertEqual(s.data['models'],[]);self.assertFalse(s.config.get('creationUnknown'))

    def test_stop_native_interrupted_result_is_confirmed_but_absent_result_is_unknown(self):
        for mode in ('stop','stop-no-result'):
            s=self.connect(mode);s.submit({'submissionId':'send','text':'wait'})
            self.wait(lambda:s.config['receipts'].get('send')=='accepted')
            old=s.peer;s.stop();self.assertIsNotNone(old.proc.poll());self.assertFalse(s.data['running'])
            self.assertEqual(s.config['receipts']['send'],'accepted')
            if mode=='stop-no-result':
                self.assertIn(tr('未收到原生结算','turn settled'),s.data['error']);self.assertFalse(s.data['connected']);self.assertIs(s.peer,old)
            else:
                self.assertIsNone(s.data['error']);self.assertIsNone(s.current_submission);self.assertTrue(s.data['connected']);self.assertIsNot(s.peer,old)

    def test_stop_serializes_concurrent_next_send_and_never_replays_old_input(self):
        s=self.connect('stop');native=s.data['nativeId']
        s.submit({'submissionId':'first','text':'wait'});self.wait(lambda:s.config['receipts'].get('first')=='accepted')
        old=s.peer;close=old.close;entered=threading.Event();release=threading.Event();errors=[]
        def delayed_close(**kwargs):
            entered.set();release.wait(2);close(**kwargs)
        old.close=delayed_close
        os.environ['FIXTURE_MODE']='normal'
        def stop():
            try:s.stop()
            except Exception as exc:errors.append(exc)
        def send():
            try:s.submit({'submissionId':'next','text':'next question'})
            except Exception as exc:errors.append(exc)
        stopper=threading.Thread(target=stop);stopper.start();self.assertTrue(entered.wait(2))
        self.assertTrue(s.data['connecting']);self.assertFalse(s.data['connected'])
        sender=threading.Thread(target=send);sender.start();time.sleep(.03)
        self.assertEqual(len(self.prompts()),1)
        release.set();stopper.join(3);sender.join(3);self.assertFalse(stopper.is_alive());self.assertFalse(sender.is_alive())
        self.assertEqual(errors,[]);self.wait(lambda:not s.data['running'])
        self.assertEqual(s.data['nativeId'],native);self.assertEqual(s.config['receipts']['next'],'accepted')
        self.assertEqual([p['message']['content'] for p in self.prompts()],['wait','next question'])
        self.assertFalse(s.data['connecting']);self.assertTrue(s.data['connected'])

    def test_stop_rebind_failure_is_visible_without_replaying_old_or_new_input(self):
        s=self.connect('stop');native=s.data['nativeId']
        s.submit({'submissionId':'first','text':'wait'});self.wait(lambda:s.config['receipts'].get('first')=='accepted')
        os.environ['FIXTURE_MODE']='wrong-id'
        with self.assertRaisesRegex(Rejected,tr('不同原生会话','different native session')):s.stop()
        self.assertEqual(s.data['nativeId'],native);self.assertFalse(s.data['connecting']);self.assertFalse(s.data['connected'])
        with self.assertRaisesRegex(Rejected,tr('未连接','not connected')):s.submit({'submissionId':'next','text':'next'})
        self.assertEqual(len(self.prompts()),1);self.assertNotIn('next',s.config['receipts'])

    def test_final_stdout_is_drained_even_when_native_process_exits(self):
        s=self.connect('tail-exit');v=self.send(s);self.wait(lambda:s.peer.closed)
        self.assertEqual(s.config['receipts']['send'],'accepted');self.assertEqual(s.last_result_turn,1)
        self.assertEqual(next(r['text'] for r in v['messages'] if r['role']=='assistant'),'hello world')

    def test_restart_restores_only_local_observation_without_native_history_reads(self):
        directory=self.root/'state';m=Manager(directory)
        s=m.connect({'id':'new','cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL});self.sessions.append(s)
        self.assertEqual(s.snapshot()['nativeProtocol'],PROTOCOL)
        self.send(s);native=s.data['nativeId'];s.peer.close()
        cached=json.loads((directory/'new.json').read_text())
        self.assertEqual(cached['localProjection']['nativeId'],native)
        restored=Manager(directory).connect({'id':'new','cli':'Antigravity','cwd':str(self.root),'nativeId':native})
        self.sessions.append(restored)
        self.assertEqual(restored.data['nativeId'],native);self.assertEqual(restored.data['historySource'],'luma-observed')
        self.assertEqual([r['text'] for r in restored.rows.values()],[r['text'] for r in s.rows.values()])
        self.assertEqual(len(self.prompts()),1)

    def test_persisted_unmarked_and_imported_native_reference_still_use_acp(self):
        directory=self.root/'state';directory.mkdir()
        (directory/'legacy.json').write_text(json.dumps({'cli':'Antigravity','cwd':str(self.root),'nativeId':'legacy-native'}))
        with patch.object(AntigravitySession,'connect',lambda s:s.update(connected=True)):
            m=Manager(directory)
            for payload in ({'id':'legacy'},{'id':'external','nativeId':'external-native'}):
                s=m.connect({'cli':'Antigravity','cwd':str(self.root),**payload})
                self.assertIs(type(s),AntigravitySession);self.assertEqual(s.config['nativeProtocol'],'antigravity-acp')

    def test_explicit_or_persisted_protocol_cannot_be_changed(self):
        from luma_cli.antigravity_agy_acp import AntigravityAgyAcpSession, AGY_ACP_PROTOCOL
        m=Manager(self.root/'state')
        with patch.object(AntigravityAgyAcpSession,'connect',lambda s:s.update(connected=True)):
            default_s=m.connect({'id':'default-agy-acp','cli':'Antigravity','cwd':str(self.root)})
        self.assertIs(type(default_s),AntigravityAgyAcpSession);self.assertEqual(default_s.config['nativeProtocol'],AGY_ACP_PROTOCOL)
        s=m.connect({'id':'new','cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL});self.sessions.append(s)
        self.assertIs(type(s),AntigravityCliSession);self.assertEqual(s.config['nativeProtocol'],PROTOCOL)
        with self.assertRaisesRegex(Rejected,tr('协议身份不可变更','protocol identity cannot be changed')):
            m.connect({'id':'new','cli':'Antigravity','cwd':str(self.root),'nativeProtocol':'antigravity-acp'})
        with self.assertRaisesRegex(Rejected,tr('未知原生协议','Unknown native protocol')):
            m.connect({'id':'other','cli':'Antigravity','cwd':str(self.root),'nativeProtocol':'pretend-acp'})

    def test_unknown_receipt_and_partial_projection_survive_without_replay(self):
        s=self.connect('stop-no-result');s.submit({'submissionId':'send','text':'wait'})
        self.wait(lambda:s.config['receipts'].get('send')=='accepted');s.stop()
        saved=json.loads(json.dumps(s.config));saved['receipts']['send']='unknown'
        restored=AntigravityCliSession(saved,lambda:None);restored.restore_projection()
        self.assertEqual(restored.config['receipts']['send'],'unknown')
        self.assertIn(tr('未重发','without resending'),restored.data['error']);self.assertEqual(list(restored.rows),['send'])

    def test_documented_terminal_and_nonterminal_result_statuses_remain_distinct(self):
        s=self.connect()
        for status in ('CANCELED','INTERRUPTED','INVALID','WAITING','RUNNING'):
            s.current_submission='case-'+status;s.turn_has_output=True;s.update(running=True)
            ident='partial-'+status;s.row({'id':ident,'role':'assistant','text':'partial','turnId':s.current_submission,'status':'running'})
            s.result({'conversation_id':s.data['nativeId'],'status':status})
            if status in ('WAITING','RUNNING'):
                self.assertTrue(s.data['running']);self.assertIsNotNone(s.current_submission)
                self.assertEqual(s.rows[ident]['status'],'running')
            else:
                self.assertFalse(s.data['running']);self.assertIsNone(s.current_submission)
                self.assertEqual(s.rows[ident]['status'],'failed' if status=='INVALID' else 'cancelled')

    def test_projection_identity_mismatch_never_starts_or_relabels_native_history(self):
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL,'nativeId':'native',
            'localProjection':{'version':1,'nativeProtocol':PROTOCOL,'nativeId':'other','messages':[]}},lambda:None)
        with self.assertRaisesRegex(Rejected,tr('投影与原生会话身份不匹配','projection does not match native session identity')):s.connect()
        self.assertIsNone(s.peer)

    def test_failed_local_checkpoint_never_crosses_native_send_boundary(self):
        s=self.connect()
        with patch.object(s,'save',side_effect=OSError('fixture disk failure')):
            self.assertRaises((Rejected,OSError),s.submit,{'submissionId':'disk','text':'must not send'})
        self.assertFalse(any(isinstance(v,dict) for v in map(json.loads,self.log.read_text().splitlines())))

    def test_unmarked_persisted_empty_record_keeps_legacy_acp_meaning(self):
        d=self.root/'state';d.mkdir();(d/'empty.json').write_text(json.dumps({'cli':'Antigravity','cwd':str(self.root)}))
        with patch.object(AntigravitySession,'connect',lambda s:None):
            value=Manager(d).connect({'id':'empty','cli':'Antigravity','cwd':str(self.root)})
        self.assertIs(type(value),AntigravitySession);self.assertEqual(value.config['nativeProtocol'],'antigravity-acp')
        self.assertEqual(value.snapshot()['nativeProtocol'],'antigravity-acp')

    def test_management_can_explicitly_attach_existing_agy_id_without_guessing(self):
        value=Manager(self.root/'state').connect({'id':'attached','cli':'Antigravity','cwd':str(self.root),
            'nativeId':'existing-agy-native','nativeProtocol':PROTOCOL})
        self.sessions.append(value)
        self.assertIs(type(value),AntigravityCliSession)
        self.assertEqual(value.snapshot()['nativeProtocol'],PROTOCOL)
        self.assertEqual(value.data['nativeId'],'existing-agy-native')

    def test_active_fragment_flushes_even_without_a_later_native_event(self):
        s=self.connect();s.current_submission='partial'
        s.step({'step_index':1,'step_type':'agent_response','state':'ACTIVE','text_delta':'only fragment'})
        self.wait(lambda:any(r['text']=='only fragment' for r in s.config['localProjection']['messages']))
        self.assertIsNone(s.checkpoint_timer)

    def test_discover_accepts_prefixed_and_prerelease_semver_outputs(self):
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeProtocol':PROTOCOL},lambda:None)
        for banner in (b'agy 1.5.2\n',b'1.6.0-rc.1\n'):
            with patch('luma_cli.antigravity_cli.shutil.which',return_value='/bin/true'),patch('luma_cli.antigravity_cli.subprocess.run',side_effect=[
                type('R',(),{'returncode':0,'stdout':banner})(),
                type('R',(),{'returncode':0,'stdout':b'opaque/high\tNative high\n'})(),
            ]):
                s.discover()
                self.assertEqual([m['id'] for m in s.data['models']],['opaque/high'])
        with patch('luma_cli.antigravity_cli.shutil.which',return_value='/bin/true'),patch('luma_cli.antigravity_cli.subprocess.run',return_value=type('R',(),{'returncode':0,'stdout':b'agy 1.1.14\n'})()):
            with self.assertRaisesRegex(Rejected,'1.1.15'):s.discover()

    def test_restored_step_replay_deduplicates_without_dropping_continuation(self):
        s=AntigravityCliSession({'cli':'Antigravity','cwd':str(self.root),'nativeId':'n1','nativeProtocol':PROTOCOL,
            'localProjection':{'version':1,'nativeProtocol':PROTOCOL,'nativeId':'n1','messages':[
                {'id':'agy:t1:5:agent_response','role':'assistant','text':'hello ','turnId':'t1','status':'running'}]}},lambda:None)
        s.update(nativeId='n1');s.restore_projection();s.current_submission='t1'
        s.step({'step_index':5,'step_type':'agent_response','state':'ACTIVE','text_delta':'hello '})
        self.assertEqual(s.rows['agy:t1:5:agent_response']['text'],'hello ')
        s.step({'step_index':5,'step_type':'agent_response','state':'DONE','text_delta':'world'})
        self.assertEqual(s.rows['agy:t1:5:agent_response']['text'],'hello world')

if __name__=='__main__':unittest.main(verbosity=2)
