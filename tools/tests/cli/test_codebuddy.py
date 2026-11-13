#!/usr/bin/env python3
import copy,json,pathlib,re,sys,tempfile,threading,time,unittest
from unittest.mock import Mock,patch
sys.dont_write_bytecode=True
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[3]/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.codebuddy import CodeBuddyProjection,CodeBuddySession,request_id
from luma_cli.codebuddy_http import CodeBuddyHttpPeer
from luma_cli.codebuddy_owner import CodeBuddyOwner
from luma_cli.native_owner import recorded_owner_alive
from luma_cli.transport import Rejected

def option(ident,category,current,values):
 return {'id':ident,'category':category,'type':'select','currentValue':current,'options':[{'value':v,'name':v} for v in values]}
def session():
 s=CodeBuddyProjection({'cli':'CodeBuddy Code','cwd':'/tmp','nativeId':'native'},lambda:None)
 s.current_submission=None;s.chunk_kind=s.chunk_id=None;s.replaying_history=False;s.history_seen=set();s.external_request=None;s.native_caps={}
 s.update(connected=True,capabilities={'steer':True,'images':True});return s
def emit(s,update_kind,mid=None,meta=None,**fields):
 update={'sessionUpdate':update_kind,'_meta':meta or {},**fields}
 if mid:update['messageId']=mid
 s.event({'method':'session/update','params':{'sessionId':'native','update':update}})
def wait(predicate):
 until=time.monotonic()+2
 while not predicate():
  if time.monotonic()>until:raise AssertionError("fixture timed out")
  time.sleep(.002)
class CodeBuddyContracts(unittest.TestCase):
 def test_uuid7_native_format(self):
  self.assertRegex(request_id(),r'^[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15}$')
 def test_conversation_id_persisted_before_write_and_native_binding_is_immutable(self):
  s=session();s.current_submission='local';calls=[]
  class Peer:
   def call(self,method,params,*a,**kw):
    calls.append(params);self_test.assertEqual(params['_meta']['codebuddy.ai/conversationRequestId'],s.config['codebuddyRequests'][s.current_submission]);return {'userMessageId':'user1'}
  self_test=self;s.peer=Peer();s.call('session/prompt',{'sessionId':'native'})
  self.assertEqual(s.config['codebuddyUserMessages'],{'user1':'local'})
  s.current_submission='other'
  with self.assertRaises(ConnectionError):s.call('session/prompt',{'sessionId':'native'})
 def test_wrong_native_id_and_stale_request_id_do_not_ack(self):
  s=session();s.current_submission='new';s.config['codebuddyRequests']={'new':'new-request','old':'old-request'}
  emit(s,'agent_message_chunk','old-assistant',{'codebuddy.ai/conversationRequestId':'old-request'},content={'type':'text','text':'late'})
  emit(s,'user_message_chunk','external',{'codebuddy.ai/requestId':'new-request'},content={'type':'text','text':'external'})
  s.event({'method':'session/update','params':{'sessionId':'wrong','update':{'sessionUpdate':'agent_message_chunk','messageId':'wrong','content':{'type':'text','text':'wrong'},'_meta':{'codebuddy.ai/conversationRequestId':'new-request'}}}})
  self.assertNotIn('new',s.config.get('receipts',{}));self.assertNotIn('codebuddy:assistant:wrong',s.rows)
 def test_exact_native_user_replay_binds_once_remote_steer_does_not_steal_id(self):
  s=session();s.config['codebuddyRequests']={'local':'request'}
  emit(s,'user_message_chunk','user1',{'codebuddy.ai/conversationRequestId':'request'},content={'type':'text','text':'mine'})
  emit(s,'user_message_chunk','remote',{'codebuddy.ai/conversationRequestId':'request','codebuddy.ai/steerClientMeta':{}},content={'type':'text','text':'not mine'})
  emit(s,'user_message_chunk','different',{'codebuddy.ai/conversationRequestId':'request'},content={'type':'text','text':'also not mine'})
  self.assertEqual(s.rows['local']['text'],'mine');self.assertEqual(s.config['codebuddyUserMessages'],{'user1':'local'})
  self.assertTrue(s.config['consumedSubmissions']['local']);self.assertIn('codebuddy:user:remote',s.rows)
 def test_missing_native_identity_never_fabricates_chunk_identity(self):
  s=session();emit(s,'agent_message_chunk',content={'type':'text','text':'anonymous'});self.assertFalse(s.rows)
 def test_native_model_efforts_images_and_ack_required(self):
  s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','nativeId':'native'},lambda:None);s.native_caps={};s.model_metadata={'model':{'supportsImages':True,'supportsReasoning':True,'effortOptions':[{'value':'high'}]}}
  opts=[option('model','model','model',['model']),option('thought_level','thought_level','high',['disabled','low','high','enabled']),option('mode','mode','default',['default','plan'])]
  s.apply_options(opts);s.update_model_capabilities();self.assertEqual(s.data['models'][0]['efforts'],['disabled','high','enabled']);self.assertTrue(s.data['capabilities']['images'])
  s.call=lambda *a,**kw:{'configOptions':opts}
  with self.assertRaises(Rejected):s.configure({'effort':'low'})
  with self.assertRaises(Rejected):s.configure({'permission':'plan'})
  self.assertNotIn('codebuddyControls',s.config)
 def test_metrics_replace_not_add_missing_zero_model_compact(self):
  s=session();u={'sessionUpdate':'usage_update','used':120,'size':100,'_meta':{'usage':{'prompt_tokens':120,'completion_tokens':8,'prompt_tokens_details':{'cached_tokens':40}}}}
  s.apply_usage(u);m=copy.deepcopy(s.data['metrics']);s.apply_usage(u);self.assertEqual(m,s.data['metrics']);self.assertTrue(any('33.3%' in r['value'] for r in m));self.assertTrue(any('120.0%' in r['value'] for r in m))
  emit(s,'agent_message_chunk','compact',{'codebuddy.ai/isCompactInternal':True},content={'type':'text','text':'internal'})
  self.assertNotIn('codebuddy:assistant:compact',s.rows);self.assertFalse(any(r['label']=='上下文' for r in s.data['metrics']))
  s.apply_usage({'used':0,'size':100});self.assertTrue(any(r['label']=='上下文' and '0.0%' in r['value'] for r in s.data['metrics']))
  s.apply_usage({});self.assertFalse(any(r['label']=='上下文' for r in s.data['metrics']))
 def test_actual_child_metadata_scopes_content_and_does_not_end_background_task(self):
  for background,expected in [(False,'succeeded'),(True,'running')]:
   s=session();s.current_submission='main';s.config['codebuddyRequests']={'main':'req'}
   emit(s,'tool_call',meta={'codebuddy.ai/isSubagent':True,'codebuddy.ai/isBackground':background,'codebuddy.ai/subagentType':'general-purpose','codebuddy.ai/toolName':'Agent'},toolCallId='child-call',rawInput={'prompt':'task'},status='in_progress')
   emit(s,'agent_message_chunk','child-message',{'codebuddy.ai/parentToolCallId':'child-call','codebuddy.ai/conversationRequestId':'req'},content={'type':'text','text':'child only'})
   emit(s,'usage_update',meta={'codebuddy.ai/parentToolCallId':'child-call'},used=999,size=1000)
   self.assertFalse(s.data['metrics']);self.assertNotIn('main',s.config.get('receipts',{}));self.assertFalse(any('child only' in r.get('text','') for r in s.rows.values()))
   emit(s,'tool_call_update',toolCallId='child-call',status='completed',rawOutput={'output':'actual result'})
   a=s.subagents['codebuddy-task:child-call'];self.assertEqual(a['status'],expected);self.assertEqual(a['itemId'],'tool-child-call');self.assertNotIn('threadId',a);self.assertIn('actual result',a['detail'] if not background else s.rows['tool-child-call']['outcome'])
 def test_native_tool_name_not_title_guessed(self):
  s=session();emit(s,'tool_call',meta={'codebuddy.ai/toolName':'Glob'},toolCallId='glob',title='anything',kind='search',status='completed')
  self.assertEqual(s.rows['tool-glob']['toolName'],'Glob')
 def test_native_steer_ack_not_consumption_and_preserves_prompt_owner(self):
  s=session();s.current_submission='main';s.call=Mock(return_value={'steered':True,'ownerRequestId':'native-turn'})
  self.assertFalse(s.prompt({'submissionId':'steer','text':'hi','steer':True}));wait(lambda:s.config.get('receipts',{}).get('steer')=='accepted');self.assertEqual(s.current_submission,'main');self.assertNotIn('steer',s.config.get('consumedSubmissions',{}))
  params=s.call.call_args.args[1];self.assertEqual(params['clientUserMessageId'],'steer')
  emit(s,'user_message_chunk','steer',{'codebuddy.ai/steerClientMeta':{'lumaSubmissionId':'steer'}},content={'type':'text','text':'hi'})
  self.assertTrue(s.config['consumedSubmissions']['steer']);self.assertEqual(s.rows['steer']['text'],'hi')
 def test_steer_idle_rejection_and_unknown_never_auto_resend(self):
  s=session();s.current_submission='main';s.call=Mock(return_value={'steered':False,'reason':'idle'})
  s.submit({'submissionId':'idle','text':'hi','steer':True});wait(lambda:s.config.get('receipts',{}).get('idle')=='rejected')
  self.assertEqual(s.config['receipts']['idle'],'rejected')
  s.call=Mock(side_effect=ConnectionError('transport'))
  s.submit({'submissionId':'unknown','text':'hi','steer':True});wait(lambda:s.call.call_count==1)
  self.assertEqual(s.config['receipts']['unknown'],'unknown');s.submit({'submissionId':'unknown','text':'hi','steer':True});self.assertEqual(s.call.call_count,1)
 def test_activity_ack_suppression_cannot_lose_prompt_owner_to_result_thread(self):
  s=session();s.current_submission='owner';observed=[];threads=[]
  def fake_event(adapter,frame):
   def result():
    with adapter.lock:observed.append(adapter.current_submission)
   thread=threading.Thread(target=result);threads.append(thread);thread.start();thread.join(.01)
   self.assertTrue(thread.is_alive())
  with patch('luma_cli.acp.AcpSession.event',fake_event):emit(s,'config_option_update')
  threads[0].join(1);self.assertEqual(observed,['owner'])
 def test_mcp_transport_failure_is_inventory_error_not_empty_success(self):
  s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','nativeId':'native'},lambda:None);s.current_submission=None;s.call=Mock(return_value={'ok':True,'applied':'snapshot','runInFlight':False});s.peer=Mock();s.peer.request.side_effect=ConnectionError('private diagnostic')
  s.refresh();self.assertTrue(s.data['mcpAvailable']);self.assertEqual(s.data['mcpError'],'无法获取 MCP')
 def test_question_answers_validate_cardinality_then_native_ack_and_duplicate_unknown_fenced(self):
  s=session();s.peer=Mock();s.call=Mock(return_value={'resolved':True})
  frame={'jsonrpc':'2.0','id':'question','method':'session/request_permission','params':{'sessionId':'native','toolCall':{'toolCallId':'ask-tool','_meta':{'codebuddy.ai/toolName':'AskUserQuestion'},'rawInput':{'questions':[{'question':'pick','options':[{'label':'Blue'}],'multiSelect':False}]}},'options':[{'optionId':'allow','name':'Allow','kind':'allow_once'}]}}
  s.event(frame);q=s.data['requests'][0];self.assertEqual(q['submitChoice'],'allow')
  with self.assertRaises(Rejected):s.answer({'id':'question','choice':'allow','answers':{'q_0':['Blue','Green']}})
  self.assertFalse(s.call.called)
  s.answer({'id':'question','choice':'allow','answers':{'q_0':['Blue']}});self.assertFalse(s.data['requests']);self.assertEqual(s.call.call_args.args[1]['answers'],{'q_0':['Blue']})
  frame['id']='unknown';s.event(frame);s.call.side_effect=ConnectionError('lost')
  with self.assertRaises(ConnectionError):s.answer({'id':'unknown','choice':'allow','answers':{'q_0':['Blue']}})
  with self.assertRaisesRegex(Rejected,'已提交'):s.answer({'id':'unknown','choice':'allow','answers':{'q_0':['Blue']}})
 def test_reconnected_native_phase_finishes_only_matching_turn_without_prompt_waiter(self):
  s=session();s.external_request='new';s.update(running=True)
  emit(s,'session_info_update',meta={'codebuddy.ai/agentPhase':{'phase':'idle'},'codebuddy.ai/requestId':'old'})
  self.assertTrue(s.data['running'])
  emit(s,'session_info_update',meta={'codebuddy.ai/agentPhase':{'phase':'idle'},'codebuddy.ai/requestId':'new'})
  self.assertFalse(s.data['running'])
  s.current_submission='own';s.update(running=True)
  emit(s,'session_info_update',meta={'codebuddy.ai/agentPhase':{'phase':'idle'},'codebuddy.ai/requestId':'own'})
  self.assertTrue(s.data['running'])
 def test_existing_initialized_connection_uses_saved_capability_handshake(self):
  s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','codebuddyInitialize':{'protocolVersion':1,'agentCapabilities':{'loadSession':True}}},lambda:None);s.peer=Mock(resumed_connection=True)
  self.assertEqual(s.call('initialize',{}),s.config['codebuddyInitialize']);s.peer.call.assert_not_called()
 def test_unknown_steer_does_not_lock_later_host_admissions(self):
  s=session();s.current_submission='main';s.update(running=True);entered=threading.Event();finish=threading.Event();calls=[]
  def call(method,params,**kw):
   ident=params['clientUserMessageId'];calls.append(ident)
   if ident=='slow':entered.set();finish.wait(2)
   return {'steered':True}
  s.call=call
  try:
   s.admit({'submissionId':'slow','text':'one'});self.assertTrue(entered.wait(1));s.admit({'submissionId':'fast','text':'two'})
   wait(lambda:s.config.get('receipts',{}).get('fast')=='accepted');self.assertEqual(s.config['receipts']['slow'],'unknown');self.assertEqual(s.current_submission,'main')
  finally:finish.set()
  wait(lambda:s.config['receipts'].get('slow')=='accepted');self.assertEqual(calls,['slow','fast'])
 def test_native_interruption_replay_can_answer_without_inventing_rpc(self):
  s=session();s.peer=Mock();s.call=Mock(return_value={'resolved':True})
  native={'interruptionId':'ir-tool','toolCallId':'tool','toolName':'AskUserQuestion','toolTitle':'AskUserQuestion','toolInput':{'questions':[{'question':'pick','options':[{'label':'Blue'}],'multiSelect':False}]},'options':['allow','allowAll','deny']}
  emit(s,'session_info_update',meta={'codebuddy.ai/interruptionRequest':native});emit(s,'session_info_update',meta={'codebuddy.ai/interruptionRequest':native})
  self.assertEqual(len(s.data['requests']),1);s.answer({'id':'ir-tool','choice':'allow','answers':{'q_0':['Blue']}})
  self.assertFalse(s.data['requests']);s.peer.send.assert_not_called();self.assertEqual(s.call.call_args.args[1]['toolCallId'],'tool')
  raw={'id':'native-rpc'};s.drain_permission(raw);s.drain_permission(raw);self.assertEqual(s.peer.send.call_count,1)
 def test_permission_resolved_retires_only_exact_pending_tool(self):
  s=session();s.peer=Mock()
  for tool in ('one','two'):
   emit(s,'session_info_update',meta={'codebuddy.ai/interruptionRequest':{'interruptionId':'ir-'+tool,'toolCallId':tool,'toolName':'Bash','toolInput':{},'options':['allow','deny']}})
  emit(s,'session_info_update',meta={'codebuddy.ai/permissionResolved':True,'codebuddy.ai/toolCallId':'one'})
  self.assertEqual([q['id'] for q in s.data['requests']],['ir-two'])
 def test_observer_retirement_never_stops_executor_but_failed_process_close_stays_fenced(self):
  def prepared():
   s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','nativeId':'native','nativeProtocol':'codebuddy-http-acp'},lambda:None);s.current_submission=None;s.native_caps={'loadSession':True};s.update(connected=True,historyReady=True);s.peer=Mock(closed=False,pending={});s.owner=Mock();return s
  s=prepared();s.retire_observer();s.owner.stop.assert_not_called()
  s=prepared();s.owner.stop.side_effect=OSError('not closed')
  with self.assertRaises(OSError):s.release_resources('native','codebuddy-http-acp')
  self.assertTrue(s.close_pending);self.assertFalse(s.released);self.assertFalse(s.terminate_on_close)
  s.owner.stop.side_effect=None;s.release_resources('native','codebuddy-http-acp');self.assertTrue(s.released);self.assertEqual(s.config['nativeId'],'native')
 def test_skill_and_slash_use_fifo_while_plain_text_still_steers(self):
  s=session();s.current_submission='main';s.update(running=True,skills=[{'id':'skill'}],capabilities={'steer':True,'skills':True});s.prompt=Mock(return_value=True)
  self.assertTrue(s.can_steer({'text':'plain'}));self.assertFalse(s.can_steer({'text':'/compact'}));self.assertFalse(s.can_steer({'text':'hi','skills':['skill']}));self.assertTrue(s.can_steer({'text':'/tmp/path'}))
  s.admit({'submissionId':'skill','text':'use it','skills':['skill']});self.assertEqual(s.config['deliveries']['skill']['state'],'queued');s.prompt.assert_not_called()
  s.current_submission=None;s.update(running=False);wait(lambda:s.config.get('receipts',{}).get('skill')=='accepted');self.assertFalse(s.prompt.call_args.args[0]['steer'])
 def test_auth_status_fail_closed_before_any_connect(self):
  for enabled in (False,None):
   response=Mock(status=200);response.read.return_value=json.dumps({'authEnabled':enabled}).encode()
   with patch.object(CodeBuddyHttpPeer,'request',return_value=(Mock(),response)) as request,patch.object(CodeBuddyHttpPeer,'finish'):
    with self.assertRaises(Rejected):CodeBuddyHttpPeer(123,'test-local-secret',Mock(),lambda e:None,lambda *a:None)
    self.assertEqual(request.call_count,1)
 def test_recorded_owner_probe_requires_same_pid_incarnation(self):
  cfg={'nativeOwner':{'kind':'codebuddy-server','pid':123,'processStart':'one'}}
  with patch('luma_cli.native_owner.process_start',return_value='two'):self.assertFalse(recorded_owner_alive(cfg))
  with patch('luma_cli.native_owner.process_start',return_value='one'):self.assertTrue(recorded_owner_alive(cfg))
 def test_unknown_creation_never_silently_new_again(self):
  s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','creationUnknown':True},lambda:None);s.start=lambda *a:None;s.call=Mock(return_value={'protocolVersion':1,'agentCapabilities':{'loadSession':True}})
  with self.assertRaisesRegex(Rejected,'创建结果未知'):s.connect()
  self.assertEqual([v.args[0] for v in s.call.call_args_list],['initialize'])
 def test_wrong_restore_id_refused(self):
  s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':'/tmp','nativeId':'native'},lambda:None);s.start=lambda *a:None;s.call=Mock(side_effect=[{'protocolVersion':1,'agentCapabilities':{'loadSession':True}},{'sessionId':'different'}])
  with self.assertRaisesRegex(Rejected,'不同会话身份'):s.connect()
  self.assertEqual(s.config['nativeId'],'native')
 def test_owner_accepts_2_180_and_newer_semver_but_rejects_older_versions(self):
  for ok in (b'2.180.0\n',b'2.185.3\n',b'codebuddy 2.190.0-rc.1\n'):
   with tempfile.TemporaryDirectory() as tmp:
    cfg={'cli':'CodeBuddy Code','cwd':tmp,'ownerDirectory':tmp+'/owner'}
    s=Session_for_owner=CodeBuddySession(cfg,lambda:None)
    owner=CodeBuddyOwner(s)
    proc=Mock(pid=4242)
    with patch('luma_cli.codebuddy_owner.shutil.which',return_value='/bin/true'), \
         patch('luma_cli.codebuddy_owner.subprocess.run',return_value=Mock(returncode=0,stdout=ok)), \
         patch('luma_cli.codebuddy_owner.spawn_process',return_value=proc) as spawn, \
         patch('luma_cli.codebuddy_owner.process_start',return_value='stamp'):
     owner.launch()
     self.assertEqual(spawn.call_count,1)
  for bad in (b'2.179.9\n',b'1.999.0\n',b'not-a-version\n'):
   with tempfile.TemporaryDirectory() as tmp:
    s=CodeBuddySession({'cli':'CodeBuddy Code','cwd':tmp,'ownerDirectory':tmp+'/owner'},lambda:None)
    owner=CodeBuddyOwner(s)
    with patch('luma_cli.codebuddy_owner.shutil.which',return_value='/bin/true'), \
         patch('luma_cli.codebuddy_owner.subprocess.run',return_value=Mock(returncode=0,stdout=bad)):
     with self.assertRaisesRegex(Rejected,'2.180.0'):owner.launch()
if __name__=='__main__':unittest.main()
