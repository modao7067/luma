import copy,json,pathlib,sys,threading,time,unittest
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parents[1]/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.grok import GrokSession, safe_reason
from luma_cli.i18n import tr
from luma_cli.transport import Peer, Rejected, RpcRejected
from luma_cli.server import ADAPTERS, carrier_revision

MODEL={'currentModelId':'grok-test','availableModels':[{'modelId':'grok-test','name':'Grok test','_meta':{'totalContextTokens':500000}}]}
OPTIONS=[{'id':'model','name':'Model','category':'model','type':'select','currentValue':'grok-test','options':[{'value':'grok-test','name':'Grok test'}]},
         {'id':'reasoning_effort','name':'Reasoning','category':'thought_level','type':'select','currentValue':'high','options':[{'value':'high','name':'High'},{'value':'low','name':'Low'}]}]
class FakePeer:
 def __init__(self,session):self.s=session;self.lock=threading.RLock();self.pending={};self.frames=[];self.yolo=False;self.closed=False
 def call(self,method,params,*args,**kwargs):
  self.frames.append({'method':method,'params':params})
  if method=='initialize':return {'protocolVersion':1,'agentCapabilities':{'loadSession':True,'promptCapabilities':{'image':True}},'authMethods':[{'id':'cached_token'}],'_meta':{'grokShell':True,'modelState':MODEL,'defaultAuthMethodId':'cached_token'}}
  if method=='authenticate':return {}
  if method in ('session/new','session/load'):
   for f in self.s.replay:self.s.event(copy.deepcopy(f))
   return {'sessionId':self.s.config.get('nativeId','native'),'models':MODEL,'configOptions':copy.deepcopy(OPTIONS)}
  if method=='_x.ai/sessions/list':return {'result':{'sessions':[{'sessionId':self.s.data['nativeId'],'yolo':self.yolo}]}}
  if method=='_x.ai/session/usage':return {'usage':{'modelCalls':0,'inputTokens':0,'outputTokens':0}}
  if method=='_x.ai/interject':return {'result':{'status':'queued'}}
  if method=='session/set_mode':return {}
  if method=='session/set_config_option':
   values=copy.deepcopy(OPTIONS)
   for o in values:
    if o['id']==params['configId']:o['currentValue']=params['value']
   return {'configOptions':values}
  raise AssertionError(method)
 def send(self,frame):
  self.frames.append(frame)
  if frame.get('method')=='_x.ai/yolo_mode_changed':self.yolo=frame['params']['yolo_mode']
class Harness(GrokSession):
 replay=[]
 def start(self,*args,**kwargs):self.peer=FakePeer(self)

def notification(kind,ident='event1',session='native',prompt=None,replay=False,**values):
 meta={'eventId':ident}
 if prompt:meta['promptId']=prompt
 if replay:meta['isReplay']=True
 return {'method':'session/update' if kind in ('agent_message_chunk','agent_thought_chunk','user_message_chunk','tool_call','tool_call_update','usage_update') else '_x.ai/session_notification',
         'params':{'sessionId':session,'update':{'sessionUpdate':kind,**values},'_meta':meta}}
def wait(test):
 end=time.monotonic()+2
 while time.monotonic()<end:
  if test():return
  time.sleep(.01)
 raise AssertionError('did not settle')
class Contract(unittest.TestCase):
 def setUp(self):
  self.config={'cli':'Grok Build','cwd':'/tmp','id':'local'};self.s=Harness(self.config,lambda:None);self.s.connect()
 def begin(self,ident='a'):
  self.s.submit({'submissionId':ident,'text':'same'});wait(lambda:any(f.get('method')=='session/prompt' and f['params']['_meta']['promptId']==ident for f in self.s.peer.frames))
 def finish(self,ident='a',**fields):
  self.s.event(notification('turn_completed','done-'+ident,prompt_id=ident,stop_reason='end_turn',**fields))
  wait(lambda:ident not in self.s.turns)
 def tearDown(self):
  for ident in list(self.s.turns):self.s.complete_turn(ident,{'stopReason':'cancelled'})
 def test_official_registry_launch_and_no_synthetic_modes(self):
  self.assertIs(ADAPTERS['Grok Build'],GrokSession);self.assertIn('--no-leader',self.s.argv);self.assertIn('--no-auto-update',self.s.argv)
  self.assertEqual(['default','auto','plan','always-approve'],[x['id'] for x in self.s.data['permissions']]);self.assertEqual([],self.s.data['metrics']);self.assertFalse(self.s.data['capabilities']['queue'])
 def test_settings_ack_and_no_global_config(self):
  self.s.configure({'effort':'low','permission':'always-approve'});self.assertEqual('low',self.s.data['effort']);self.assertEqual('always-approve',self.s.data['permission'])
  self.s.configure({'permission':'auto'});self.assertEqual('auto',self.s.data['permission'])
  self.s.configure({'permission':'plan'});self.assertEqual('plan',self.s.data['permission'])
  self.s.configure({'permission':'default'});self.assertEqual('default',self.s.data['permission'])
  with self.assertRaises(Rejected):self.s.configure({'permission':'invented'})
 def test_new_session_does_not_enable_or_remember_permission(self):
  self.assertNotIn('selectedPermission',self.config)
  self.assertFalse(any(f.get('method')=='_x.ai/yolo_mode_changed' for f in self.s.peer.frames))
 def test_only_confirmed_explicit_selection_restores_same_session(self):
  self.s.configure({'permission':'always-approve'});self.assertEqual('always-approve',self.config['selectedPermission'])
  resumed=Harness(copy.deepcopy(self.config),lambda:None);resumed.connect()
  self.assertEqual('always-approve',resumed.data['permission']);self.assertTrue(resumed.peer.yolo)
  resumed.configure({'permission':'default'});again=Harness(copy.deepcopy(resumed.config),lambda:None);again.connect()
  self.assertEqual('default',again.data['permission']);self.assertFalse(again.peer.yolo)
 def test_permission_native_refusal_never_saves_requested_value(self):
  self.s.peer.send=lambda f:self.s.peer.frames.append(f)
  with self.assertRaises(Rejected):self.s.configure({'permission':'always-approve'})
  self.assertEqual('default',self.s.data['permission']);self.assertNotIn('selectedPermission',self.config)
 def test_permission_readback_timeout_clears_unconfirmed_display(self):
  self.s.configure({'permission':'default'});original=self.s.peer.call
  def missing(method,*args,**kwargs):
   if method=='_x.ai/sessions/list':raise TimeoutError('lost readback')
   return original(method,*args,**kwargs)
  self.s.peer.call=missing
  with self.assertRaises(TimeoutError):self.s.configure({'permission':'always-approve'})
  self.assertEqual('',self.s.data['permission']);self.assertEqual('default',self.config['selectedPermission'])
 def test_permission_missing_own_roster_is_not_an_ack(self):
  self.s.configure({'permission':'default'});original=self.s.peer.call
  def missing(method,*args,**kwargs):
   if method=='_x.ai/sessions/list':return {'result':{'sessions':[]}}
   return original(method,*args,**kwargs)
  self.s.peer.call=missing
  with self.assertRaises(Rejected):self.s.configure({'permission':'always-approve'})
  self.assertEqual('',self.s.data['permission']);self.assertEqual('default',self.config['selectedPermission'])
 def test_permission_save_failure_retains_previous_persisted_choice(self):
  self.s.configure({'permission':'default'})
  def fail():raise OSError('storage unavailable')
  self.s.save=fail
  with self.assertRaisesRegex(RuntimeError,tr('保存失败','saving session selection failed')):self.s.configure({'permission':'always-approve'})
  self.assertEqual('always-approve',self.s.data['permission']);self.assertEqual('default',self.config['selectedPermission'])
 def test_restore_refusal_is_not_silently_downgraded(self):
  class Refusing(Harness):
   def start(self,*args,**kwargs):
    super().start(*args,**kwargs);self.peer.send=lambda f:None
  config={**self.config,'nativeId':'native','selectedPermission':'always-approve'}
  resumed=Refusing(config,lambda:None)
  with self.assertRaises(Rejected):resumed.connect()
  self.assertEqual('default',resumed.data['permission']);self.assertEqual('always-approve',config['selectedPermission'])
 def test_unknown_stream_does_not_ack(self):
  self.begin();self.s.event(notification('agent_message_chunk',content={'type':'text','text':'unattributed'}));self.assertEqual('unknown',self.config['receipts']['a']);self.finish()
 def test_exact_prompt_stream_acks(self):
  self.begin();self.s.event(notification('agent_message_chunk',prompt='a',content={'type':'text','text':'yes'}));self.assertEqual('accepted',self.config['receipts']['a']);self.finish()
 def test_background_completion_cannot_end_foreground(self):
  self.begin();self.s.event(notification('agent_thought_chunk',prompt='a',content={'type':'text','text':'thinking'}));chunk=self.s.chunk_id
  self.s.event(notification('turn_completed','bg',prompt_id='task-completed-foo',stop_reason='end_turn'));self.assertTrue(self.s.data['running']);self.assertEqual('running',self.s.rows[chunk]['status']);self.finish()
 def test_error_completion_means_received_not_rejected(self):
  self.begin();self.s.event(notification('turn_completed','fail',prompt_id='a',stop_reason='error',agent_result='provider failed'));wait(lambda:not self.s.data['running']);self.assertEqual('accepted',self.config['receipts']['a']);self.assertEqual('provider failed',self.s.data['error'])
 def test_late_completion_cannot_end_next_turn(self):
  self.begin();self.finish();self.begin('b');self.s.event(notification('turn_completed','late-a',prompt_id='a',stop_reason='end_turn'));self.assertTrue(self.s.data['running']);self.assertEqual('b',self.s.current_submission);self.finish('b')
 def test_rpc_loss_stays_unknown(self):
  self.begin();key=next(iter(self.s.peer.pending));self.s.peer.pending[key].put(ConnectionError('lost'));wait(lambda:not self.s.data['running']);self.assertEqual('unknown',self.config['receipts']['a']);self.assertNotEqual('accepted',self.config['receipts']['a'])
 def test_steer_only_exact_broadcast_is_admitted_at_safe_boundary(self):
  self.begin();self.s.submit({'submissionId':'steer','text':'same','steer':True});wait(lambda:any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames));self.assertEqual('unknown',self.config['receipts']['steer']);self.assertNotIn('steer',self.s.rows)
  self.s.event(notification('agent_thought_chunk','th1',prompt='a',content={'type':'text','text':'思考'}));self.s.event({'method':'_x.ai/session/interjection','params':{'sessionId':'native','interjectionId':'steer','text':'same'}})
  self.assertEqual('accepted',self.config['receipts']['steer']);self.assertEqual('a',self.s.current_submission);self.assertNotIn('steer',self.s.rows);self.assertNotIn('steer',self.s.snapshot()['admittedDisplaySubmissions'])
  self.assertEqual([{'id':'steer','text':'same','status':tr('插话 · 等待当前步骤结束','Steer · Waiting for current step'),'clientUserMessageId':'steer','canRemove':False,'canSteer':False,'canEdit':False,'hostOwned':True}],self.s.snapshot()['hostQueue'])
  self.s.event(notification('agent_message_chunk','m1',prompt='a',content={'type':'text','text':'上一轮回答'}));self.assertNotIn('steer',self.s.rows)
  self.s.event(notification('response_completed','rc1'));self.assertNotIn('steer',self.s.rows)
  self.s.event(notification('agent_message_chunk','m2',prompt='a',content={'type':'text','text':'针对插话的回答'}))
  self.assertEqual(['a','grok:th1','grok:m1','steer','grok:m2'],list(self.s.rows));self.assertTrue(self.s.snapshot()['admittedDisplaySubmissions']['steer']);self.assertNotIn('steer',self.s.snapshot()['consumedSubmissions']);self.assertEqual('local',self.s.rows['steer']['origin']);self.assertEqual([],self.s.snapshot()['hostQueue']);self.finish()
 def test_mid_tool_interjection_waits_for_all_foreground_tools_before_row_insertion(self):
  self.begin();self.s.event(notification('agent_message_chunk','m0',prompt='a',content={'type':'text','text':'先查文件'}));self.s.event(notification('response_completed','rc0'))
  self.s.event(notification('tool_call','tc1',prompt='a',toolCallId='t1',title='read_a'));self.s.event(notification('tool_call','tc2',prompt='a',toolCallId='t2',title='read_b'))
  self.s.event(notification('tool_call_update','tu1',toolCallId='t1',status='in_progress'))
  self.s.submit({'submissionId':'ij','text':'顺便看下C','steer':True});wait(lambda:any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames))
  self.s.event({'method':'_x.ai/session/interjection','params':{'sessionId':'native','interjectionId':'ij','text':'顺便看下C'}})
  self.assertEqual('accepted',self.config['receipts']['ij']);self.assertNotIn('ij',self.s.rows)
  self.s.event(notification('tool_call_update','tu1d',prompt='a',toolCallId='t1',status='completed'));self.assertNotIn('ij',self.s.rows)
  self.s.event(notification('tool_call_update','tu2d',prompt='a',toolCallId='t2',status='completed'));self.assertNotIn('ij',self.s.rows)
  self.s.event(notification('agent_thought_chunk','th2',prompt='a',content={'type':'text','text':'收到插话'}))
  self.assertEqual(['a','grok:m0','tool-t1','tool-t2','ij','grok:th2'],list(self.s.rows));self.assertTrue(self.s.snapshot()['admittedDisplaySubmissions']['ij']);self.finish()
 def test_busy_admit_stays_in_host_queue_with_edit_remove_and_explicit_steer(self):
  self.begin()
  self.assertEqual({'hostAdmitted':True,'deliveryState':'queued'},self.s.admit({'submissionId':'q1','text':'first queued','steer':False}))
  self.assertEqual({'hostAdmitted':True,'deliveryState':'queued'},self.s.admit({'submissionId':'q2','text':'second steer','steer':False}))
  time.sleep(.03);self.assertFalse(any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames))
  hq=self.s.snapshot()['hostQueue'];self.assertEqual(['q1','q2'],[x['id'] for x in hq]);self.assertTrue(all(x['canSteer'] and x['canEdit'] and x['canRemove'] for x in hq))
  self.s.action('steer-pending',{'id':'q2'});wait(lambda:any(f.get('method')=='_x.ai/interject' and f['params']['interjectionId']=='q2' for f in self.s.peer.frames))
  self.assertEqual('queued',self.config['deliveries']['q1']['state'])
  self.s.action('take-back',{'id':'q1','operationId':'op1','expectedPayload':{'text':'first queued','images':[],'skills':[]}})
  self.assertIn('q1',self.s.snapshot()['takebacks']);self.finish()
 def test_late_interjection_fallback_turn_flushes_and_tracks_running(self):
  self.begin();self.s.submit({'submissionId':'late','text':'补一句','steer':True});wait(lambda:any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames))
  self.finish()
  self.s.event({'method':'_x.ai/session/interjection','params':{'sessionId':'native','interjectionId':'late','text':'补一句'}})
  self.assertIn('late',self.s.rows);self.assertTrue(self.s.snapshot()['admittedDisplaySubmissions']['late'])
  self.s.event({'method':'_x.ai/queue/changed','params':{'sessionId':'native','entries':[],'runningPromptId':'interject-fallback-1'}})
  self.assertTrue(self.s.data['running']);self.assertEqual('interject-fallback-1',self.s.current_submission)
  self.s.event(notification('agent_message_chunk','fb1',prompt='interject-fallback-1',content={'type':'text','text':'补答'}))
  self.s.event(notification('turn_completed','fbdone',prompt_id='interject-fallback-1',stop_reason='end_turn'))
  self.assertFalse(self.s.data['running']);self.assertEqual(['a','late','grok:fb1'],list(self.s.rows))
 def test_foreground_cancel_does_not_invent_background_cancellation(self):
  self.begin();self.s.event(notification('task_backgrounded','bg',tool_call_id='tool',task_id='task',command='running'))
  self.s.event(notification('turn_completed','cancel',prompt_id='a',stop_reason='cancelled'));wait(lambda:not self.s.data['running'])
  self.assertEqual('running',self.s.rows['tool-tool']['status'])
 def test_stop_waits_native_settlement(self):
  self.begin();self.s.stop();self.assertTrue(self.s.data['running']);self.assertEqual('ctrl_c',self.s.peer.frames[-1]['params']['_meta']['cancelTrigger']);self.finish()
 def test_replay_binds_identity_not_text(self):
  self.config.update(nativeId='native',receipts={'first':'unknown','second':'unknown'})
  frames=[]
  for i in ('first','second'):
   frames += [notification('user_message_chunk','u-'+i,replay=True,content={'type':'text','text':'same'}),notification('turn_completed','d-'+i,replay=True,prompt_id=i,stop_reason='end_turn')]
  s=Harness(self.config,lambda:None);s.replay=frames;s.connect();self.assertEqual(['first','second'],list(s.rows));self.assertEqual('accepted',self.config['receipts']['first']);self.assertEqual('accepted',self.config['receipts']['second'])
 def test_live_user_echo_stays_on_the_optimistic_row(self):
  self.begin()
  self.s.event(notification('user_message_chunk','echo',content={'type':'text','text':'same'}))
  self.s.event(notification('user_message_chunk','part',content={'type':'text','text':'sa'}))
  self.s.event(notification('user_message_chunk','image',content={'type':'image','mimeType':'image/png','data':'abcd'}))
  users=[r for r in self.s.rows.values() if r.get('role')=='user']
  self.assertEqual(['a'],[r['id'] for r in users]);self.assertEqual('same',self.s.rows['a']['text'])
  self.assertTrue(self.s.rows['a'].get('media'));self.assertEqual('unknown',self.config['receipts']['a'])
 def test_explicit_user_prompt_still_acks_without_a_second_row(self):
  self.begin();self.s.event(notification('user_message_chunk','echo',prompt='a',content={'type':'text','text':'same'}))
  self.assertEqual(['a'],[r['id'] for r in self.s.rows.values() if r.get('role')=='user']);self.assertEqual('accepted',self.config['receipts']['a'])
 def test_different_user_text_during_a_turn_stays_separate(self):
  self.begin();self.s.event(notification('user_message_chunk','other',content={'type':'text','text':'different'}))
  self.assertEqual('same',self.s.rows['a']['text']);self.assertEqual(2,sum(r.get('role')=='user' for r in self.s.rows.values()))
 def test_duplicate_event_id_not_duplicate_content(self):
  f=notification('agent_message_chunk',content={'type':'text','text':'x'});self.s.event(f);self.s.event(f);self.assertEqual(['x'],[r['text'] for r in self.s.rows.values()])
 def test_separate_prompt_streams_do_not_merge(self):
  for i in ['a','task-completed-bg']:self.s.event(notification('agent_message_chunk',i,prompt=i,content={'type':'text','text':i}))
  self.assertEqual(2,len(self.s.rows))
 def test_interjection_replay_prefers_display_text(self):
  self.s.event(notification('user_message_chunk',replay=True,content={'type':'text','text':'<system reminder>wrapped</system reminder>','_meta':{'displayText':'typed'}}));self.assertEqual('typed',next(iter(self.s.rows.values()))['text'])
 def test_native_hidden_wake_is_not_a_user_message(self):
  hidden=notification('user_message_chunk','hidden',replay=True,content={'type':'text','text':'system reminder'})
  hidden['params']['update']['_meta']={'hideFromScrollback':True,'syntheticPrompt':True}
  self.s.event(hidden);self.assertFalse(self.s.rows)
  human=notification('user_message_chunk','human',content={'type':'text','text':'system reminder'})
  self.s.event(human);self.assertEqual(1,len(self.s.rows))
 def test_foreign_session_is_not_projected(self):
  self.s.event(notification('agent_message_chunk',session='other',content={'type':'text','text':'leak'}));self.assertFalse(self.s.rows)
 def test_subagent_lifecycle_actual_fields(self):
  self.s.event(notification('subagent_spawned','s',subagent_id='child',child_session_id='child',description='Investigate',subagent_type='explore',parent_prompt_id='a'))
  self.s.event(notification('subagent_finished','f',subagent_id='child',child_session_id='child',status='completed',output='result'))
  row=self.s.subagents['child'];self.assertEqual('Investigate',row['task']);self.assertEqual('succeeded',row['status']);self.assertEqual('result',row['detail'])
  self.assertEqual('result',row['messages'][-1]['text']);self.assertEqual('assistant',row['messages'][-1]['role'])
 def test_spawn_tool_keeps_native_task_and_child_transcript(self):
  prompt='只回复一行'
  meta={'_meta':{'x.ai/tool':{'name':'spawn_subagent','kind':'task','label':'Subagent'}}}
  self.s.event(notification('tool_call','spawn-call',toolCallId='call1',title='spawn_subagent',rawInput={'prompt':prompt,'description':'子代理 A 就绪'},**meta))
  updated=notification('tool_call_update','spawn-upd',toolCallId='call1',title='子代理 A 就绪',rawInput={'prompt':prompt,'description':'子代理 A 就绪'},**meta)
  updated['params']['update']['kind']='other'
  self.s.event(updated)
  tool=self.s.rows['tool-call1'];self.assertEqual('task',tool['toolKind']);self.assertEqual('spawn_subagent',tool['toolName'])
  self.s.event(notification('subagent_spawned','spawned',subagent_id='child',child_session_id='child-sess',description='子代理 A 就绪',subagent_type='general-purpose',model='grok-4.7',parent_prompt_id='turn'))
  self.s.event(notification('agent_thought_chunk','th1',session='child-sess',content={'type':'text','text':'先'}))
  self.s.event(notification('agent_thought_chunk','th2',session='child-sess',content={'type':'text','text':'想'}))
  read={'_meta':{'x.ai/tool':{'name':'read_file','kind':'read'}}}
  self.s.event(notification('tool_call','ct',session='child-sess',toolCallId='c1',title='read_file',**read))
  child_tool=notification('tool_call_update','ctu',session='child-sess',toolCallId='c1',title='Read',**read)
  child_tool['params']['update']['kind']='other'
  self.s.event(child_tool)
  reply='子代理 A 已启动。'
  self.s.event(notification('agent_message_chunk','am1',session='child-sess',content={'type':'text','text':reply}))
  self.s.event(notification('agent_message_chunk','am2',session='child-sess',content={'type':'text','text':reply}))
  self.s.event(notification('subagent_finished','fin',subagent_id='child',child_session_id='child-sess',status='completed',output=reply))
  agent=self.s.subagents['child']
  self.assertEqual('tool-call1',agent['itemId']);self.assertEqual(prompt,agent['task']);self.assertEqual('grok-4.7',agent['model'])
  self.assertEqual(reply,agent['detail']);self.assertEqual('succeeded',agent['status'])
  self.assertEqual([('user',prompt),('tool','先想'),('tool','Read'),('assistant',reply)],[(m['role'],m['text']) for m in agent['messages']])
  self.assertEqual('reasoning',agent['messages'][1]['kind']);self.assertEqual('read',agent['messages'][2]['toolKind']);self.assertEqual('read_file',agent['messages'][2]['toolName'])
  self.assertEqual(1,sum(m['role']=='assistant' for m in agent['messages']))
  self.assertFalse(any(r.get('text')=='先想' for r in self.s.rows.values()));self.assertNotIn('tool-c1',self.s.rows)
 def test_spawn_tool_arriving_after_the_child_still_links(self):
  self.s.event(notification('subagent_spawned','s',subagent_id='child',child_session_id='child',description='Later',subagent_type='explore',parent_prompt_id='a'))
  self.assertEqual('Later',self.s.subagents['child']['task'])
  self.s.event(notification('tool_call','late',toolCallId='late',title='spawn_subagent',rawInput={'prompt':'real task','description':'Later'},**{'_meta':{'x.ai/tool':{'name':'spawn_subagent','kind':'task'}}}))
  row=self.s.subagents['child'];self.assertEqual('tool-late',row['itemId']);self.assertEqual('real task',row['task']);self.assertEqual('real task',row['messages'][0]['text'])
  self.assertEqual('task',self.s.rows['tool-late']['toolKind']);self.assertEqual('spawn_subagent',self.s.rows['tool-late']['toolName'])
 def test_spawn_matches_description_before_an_unnamed_tool(self):
  named={'_meta':{'x.ai/tool':{'name':'spawn_subagent','kind':'task'}}}
  self.s.event(notification('tool_call','n',toolCallId='named',rawInput={'prompt':'named prompt','description':'Named'},**named))
  self.s.event(notification('tool_call','b',toolCallId='blank',rawInput={'prompt':'blank prompt'},**named))
  self.s.event(notification('subagent_spawned','s2',subagent_id='second',child_session_id='c2',description='Other'))
  self.s.event(notification('subagent_spawned','s1',subagent_id='first',child_session_id='c1',description='Named'))
  self.assertEqual('tool-blank',self.s.subagents['second']['itemId']);self.assertEqual('blank prompt',self.s.subagents['second']['task'])
  self.assertEqual('tool-named',self.s.subagents['first']['itemId']);self.assertEqual('named prompt',self.s.subagents['first']['task'])
 def test_child_approval_survives_parent_completion(self):
  self.s.event(notification('subagent_spawned','s',subagent_id='child',child_session_id='child',description='Task'))
  self.begin();self.s.event({'id':42,'method':'session/request_permission','params':{'sessionId':'child','toolCall':{'title':'Tool'},'options':[{'optionId':'once','name':'Once','kind':'allow_once'}]}})
  self.finish();self.assertIn('42',self.s.pending_requests);self.s.answer({'id':'42','choice':'once'});self.assertEqual('once',self.s.peer.frames[-1]['result']['outcome']['optionId'])
 def test_spawn_permission_is_a_launch_approval(self):
  prompt='你是这次测试里的子代理丙。只回复一行：子代理丙已启动。'
  raw={'variant':'Task','prompt':prompt,'description':'子代理丙就绪','run_in_background':True,'task_id':None}
  self.s.event({'id':7,'method':'session/request_permission','params':{'sessionId':'native','toolCall':{'title':'子代理丙就绪','rawInput':raw,'_meta':{'x.ai/tool':{'name':'spawn_subagent','kind':'task'}}},'options':[{'optionId':'allow-once','name':'Yes, send once','kind':'allow_once'},{'optionId':'reject-once','name':'No, and tell Grok what to do differently','kind':'reject_once'}]}})
  req=self.s.data['requests'][-1]
  self.assertEqual(tr('启动子代理','Spawn subagent'),req['title']);self.assertEqual('子代理丙就绪\n\n'+prompt,req['body'])
  self.assertNotIn('variant',req['body']);self.assertEqual(['Yes, send once','No, and tell Grok what to do differently',tr('取消','Cancel')],[c['label'] for c in req['choices']])
  self.s.event({'id':8,'method':'session/request_permission','params':{'sessionId':'native','toolCall':{'title':'子代理丁就绪','rawInput':{'variant':'Task','prompt':'丁的任务','description':'子代理丁就绪'}},'options':[{'optionId':'allow-once','name':'Yes, send once','kind':'allow_once'}]}})
  self.assertEqual(tr('启动子代理','Spawn subagent'),self.s.data['requests'][-1]['title']);self.assertEqual('子代理丁就绪\n\n丁的任务',self.s.data['requests'][-1]['body'])
  self.s.event({'id':9,'method':'session/request_permission','params':{'sessionId':'native','toolCall':{'title':'bash','rawInput':{'command':'ls'}},'options':[{'optionId':'once','name':'Once','kind':'allow_once'}]}})
  other=self.s.data['requests'][-1];self.assertEqual('bash',other['title']);self.assertIn('"command": "ls"',other['body'])
 def test_question_freeform_native_notes(self):
  self.s.event({'id':'q','method':'_x.ai/ask_user_question','params':{'sessionId':'native','questions':[{'question':'Choose','options':[{'label':'A','description':'First'}]}]}})
  self.s.answer({'id':'q','choice':'accept','answers':{'0':['custom']}});r=self.s.peer.frames[-1]['result'];self.assertEqual(['Other'],r['answers']['Choose']);self.assertEqual('custom',r['annotations']['Choose']['notes'])
 def test_plan_requires_explicit_decision(self):
  self.s.event({'id':'plan','method':'_x.ai/exit_plan_mode','params':{'sessionId':'native','planContent':'plan'}});self.assertIn('plan',self.s.pending_requests);self.s.answer({'id':'plan','choice':'approved'});self.assertEqual({'outcome':'approved'},self.s.peer.frames[-1]['result'])
 def test_background_task_exit_status_and_truncated_snapshot(self):
  self.s.event(notification('task_backgrounded','bg',tool_call_id='t',task_id='task',command='command'))
  self.s.event(notification('background_tasks','snap',tasks=[],truncated=True));self.assertEqual('running',self.s.rows['tool-t']['status'])
  self.s.event(notification('task_completed','end',task_snapshot={'task_id':'task','exit_code':2,'output':'failed'}));self.assertEqual('failed',self.s.rows['tool-t']['status'])
 def test_background_spawn_rpc_completion_keeps_task_running(self):
  self.s.event(notification('task_backgrounded','bg',tool_call_id='t',task_id='task',command='command'))
  self.s.event(notification('tool_call_update','rpc',toolCallId='t',status='completed',rawOutput={'task_id':'task'}))
  self.assertEqual('running',self.s.rows['tool-t']['status'])
 def test_context_uses_exact_native_model_window(self):
  f=notification('agent_message_chunk',content={'type':'text','text':'x'});f['params']['_meta']['totalTokens']=1234;self.s.event(f);self.assertIn('500000',str(self.s.data['metrics']))
  self.s.update(model='grok-unknown');self.s.render_metrics();self.assertNotIn('500000',str(self.s.data['metrics']))
 def test_host_only_commands_not_falsely_supported(self):
  self.assertFalse(self.s.data['capabilities']['compact'])
  with self.assertRaises(Rejected):self.s.validate_prompt({'text':'/compact','submissionId':'id'})
 def test_cached_default_reuses_initialized_auth_without_interactive_fallthrough(self):
  self.s.authenticate({'authMethods':[{'id':'grok.com'}],'_meta':{'defaultAuthMethodId':'cached_token'}})
  self.assertFalse(any(f.get('method')=='authenticate' for f in self.s.peer.frames))
  self.assertEqual('ready',self.s.data['lifecycle']['phase'])
 def test_advertised_api_key_default_ignores_env_and_uses_native_choice(self):
  with patch.dict('os.environ',{},clear=True):
   self.s.authenticate({'authMethods':[{'id':'xai.api_key'},{'id':'cached_token'}],'_meta':{'defaultAuthMethodId':'xai.api_key'}})
  self.assertEqual({'methodId':'xai.api_key'},self.s.peer.frames[-1]['params'])
  self.s.authenticate({'authMethods':[{'id':'grok.com'}],'_meta':{'defaultAuthMethodId':'xai.api_key'}})
  self.assertEqual({'methodId':'xai.api_key'},self.s.peer.frames[-1]['params'])
 def test_missing_default_sole_noninteractive_advertisement_preserves_older_key_peers(self):
  self.s.authenticate({'authMethods':[{'id':'xai.api_key'},{'id':'grok.com'}]})
  self.assertEqual('xai.api_key',self.s.peer.frames[-1]['params']['methodId'])
 def test_missing_interactive_or_unknown_default_never_starts_login(self):
  for default in (None,'grok.com','future-auth'):
   with self.subTest(default=default):
    before=len(self.s.peer.frames)
    with self.assertRaises(Rejected):self.s.authenticate({'authMethods':[{'id':'grok.com'}],'_meta':{'defaultAuthMethodId':default}})
    self.assertEqual(before,len(self.s.peer.frames))
    self.assertEqual('blocked',self.s.data['lifecycle']['phase']);self.assertFalse(self.s.data['lifecycle']['retryable'])
 def test_authenticate_subscription_gate_is_visible_and_terminal(self):
  original=self.s.peer.call
  self.s.peer.call=lambda method,*a,**k: {'_meta':{'gate':{'message':'Subscription required'},'email':'private@example.test'}} if method=='authenticate' else original(method,*a,**k)
  with self.assertRaises(Rejected):self.s.authenticate({'authMethods':[{'id':'xai.api_key'}],'_meta':{'defaultAuthMethodId':'xai.api_key'}})
  self.assertEqual('configuration',self.s.data['lifecycle']['reason']);self.assertIn('Subscription',self.s.data['error']);self.assertNotIn('private@example',str(self.s.snapshot()))
 def test_empty_model_replacement_and_successful_end_turn_keeps_visible_block(self):
  self.begin();self.s.event(notification('model_auto_switched','gate',previous_model_id='grok-test',new_model_id='',reason='Model is no longer available'))
  self.s.complete_turn('a',{'stopReason':'end_turn'})
  self.assertEqual('accepted',self.config['receipts']['a']);self.assertEqual('model',self.s.data['lifecycle']['reason']);self.assertIn('no longer available',self.s.data['error']);self.assertEqual('grok-test',self.s.data['model'])
  with self.assertRaises(Rejected):self.s.validate_prompt({'submissionId':'b','text':'do not dispatch'})
 def test_native_successful_model_replacement_updates_current_without_blocking(self):
  self.s.event(notification('model_auto_switched','switch',previous_model_id='old',new_model_id='grok-test',reason='Native fallback'))
  self.assertEqual('grok-test',self.s.data['model']);self.assertEqual('ready',self.s.data['lifecycle']['phase']);self.assertEqual('Native fallback',self.s.data['notice'])
 def test_rpc_error_details_retained_without_changing_legacy_exception_text(self):
  p=Peer.__new__(Peer);p.lock=threading.RLock();p.pending={};p.waiting_for_user=lambda:False
  p.send=lambda f:p.pending[f['id']].put({'error':{'code':-32000,'message':'Authentication required','data':{'message':'Session expired api_key=xai-secret','http_status':401}}})
  with self.assertRaises(RpcRejected) as failure:p.call('authenticate')
  self.assertEqual('Authentication required',str(failure.exception))
  self.s.fail(failure.exception)
  self.assertEqual(-32000,self.s.data['errorInfo']['code']);self.assertEqual(401,self.s.data['errorInfo']['httpStatus']);self.assertNotIn('xai-secret',self.s.data['error']);self.assertEqual('authentication',self.s.data['lifecycle']['reason'])
 def test_retry_auth_is_terminal_and_transport_does_not_overwrite_it(self):
  self.s.event(notification('retry_state','auth',type='failed',error_type='auth',message='Session expired'))
  self.s.exited(ConnectionError('EOF'))
  self.assertEqual('blocked',self.s.data['lifecycle']['phase']);self.assertEqual('authentication',self.s.data['errorInfo']['kind']);self.assertFalse(self.s.data['connected'])
 def test_replayed_previous_auth_failure_does_not_block_recovered_connection(self):
  self.s.event(notification('retry_state','old-auth',replay=True,type='failed',error_type='auth',message='Session expired'))
  self.s.event(notification('model_auto_switched','old-model',replay=True,previous_model_id='old',new_model_id='',reason='Model unavailable'))
  self.assertEqual('ready',self.s.data['lifecycle']['phase']);self.assertIsNone(self.s.data['error'])
 def test_transport_loss_is_retryable_but_unknown_receipt_stays_unknown(self):
  self.begin();key=next(iter(self.s.peer.pending));self.s.peer.pending[key].put(ConnectionError('lost'))
  wait(lambda:not self.s.turns)
  self.assertEqual('unknown',self.config['receipts']['a']);self.assertTrue(self.s.data['lifecycle']['retryable']);self.assertEqual('disconnected',self.s.data['lifecycle']['phase'])
 def test_roster_observation_failure_does_not_destroy_ready_connection(self):
  class MissingRoster(Harness):
   def refresh_roster(self):raise TimeoutError('offline observation')
  s=MissingRoster({'cli':'Grok Build','cwd':'/tmp'},lambda:None);s.connect()
  self.assertTrue(s.data['connected']);self.assertEqual('ready',s.data['lifecycle']['phase']);self.assertEqual('',s.data['permission'])
 def test_not_ready_until_required_permission_restore_finishes(self):
  class Observe(Harness):
   def set_native_permission(inner,value):
    self.assertFalse(inner.data['connected']);self.assertEqual('connecting',inner.data['lifecycle']['phase'])
    return super(Observe,inner).set_native_permission(value)
  s=Observe({'cli':'Grok Build','cwd':'/tmp','nativeId':'native','selectedPermission':'default'},lambda:None);s.connect();self.assertTrue(s.data['connected'])
 def test_catalog_loading_is_distinct_from_confirmed_empty(self):
  class Empty(Harness):
   def apply_options(self,options):super().apply_options([{**OPTIONS[0],'options':[],'currentValue':''}])
  s=Empty({'cli':'Grok Build','cwd':'/tmp'},lambda:None);s.connect()
  self.assertEqual([],s.data['models']);self.assertEqual('ready',s.data['catalogState']);self.assertEqual('model',s.data['lifecycle']['reason'])
 def test_safe_error_text_is_bounded_and_hides_credentials_and_profile(self):
  value=safe_reason('Bearer bearer-secret api_key=xai-secret access_token=eyJtoken email=a@example.test https://host/path?key=secret\x1b'+'x'*5000)
  self.assertLessEqual(len(value),1200)
  for secret in ('bearer-secret','xai-secret','eyJtoken','a@example.test','?key=secret','\x1b'):self.assertNotIn(secret,value)
 def test_health_revision_comes_from_actual_immutable_carrier_path(self):
  with patch('luma_cli.server.__file__','/tmp/cli-bridge-v18/luma_cli/server.py'):self.assertEqual('cli-bridge-v18',carrier_revision())
  self.assertIsNone(carrier_revision())
 def test_grok_private_leader_rebinds_live_lock_holder_without_rotating_instance(self):
  import os,tempfile
  from luma_cli.native_owner import NativeOwner, recorded_owner_alive
  with tempfile.TemporaryDirectory(prefix='luma-grok-lock-') as tmp:
   instance='f'*32;sock_dir=pathlib.Path('/tmp')/('luma-native-'+instance);sock_dir.mkdir(mode=0o700,exist_ok=True)
   try:
    (sock_dir/'control.sock').write_text('');(sock_dir/'control.lock').write_text(str(os.getpid()))
    cfg={'cli':'Grok Build','cwd':tmp,'nativeId':'native-1','nativeProtocol':'grok-private-leader-v1',
         'nativeOwner':{'instanceId':instance,'kind':'grok-leader','pid':999999,'processStart':'dead'}}
    with patch('luma_cli.native_owner.Path.read_bytes',return_value=b'/root/.grok/bin/grok\x00agent\x00leader\x00--no-exit-on-disconnect'):
     self.assertTrue(recorded_owner_alive(cfg))
     session=Harness(cfg,lambda:None);owner=NativeOwner(session)
     self.assertEqual(instance,owner.handle['instanceId']);self.assertEqual(os.getpid(),owner.handle['pid'])
   finally:
    for f in ('control.sock','control.lock'):(sock_dir/f).unlink(missing_ok=True)
    sock_dir.rmdir()
 def test_conflicting_user_echo_with_prompt_id_does_not_overwrite_or_ack(self):
  self.begin()
  self.s.row({**self.s.rows['a'],'media':[{'source':'/tmp/orig.png','mimeType':'image/png'}]})
  self.s.event(notification('user_message_chunk','conflict',prompt='a',content={'type':'text','text':'totally different'}))
  self.assertEqual('same',self.s.rows['a']['text'])
  self.assertEqual([{'source':'/tmp/orig.png','mimeType':'image/png'}],self.s.rows['a']['media'])
  self.assertEqual('unknown',self.config['receipts']['a'])
  self.assertEqual('totally different',self.s.rows['grok:conflict']['text'])
  self.finish()
 def test_permission_failure_rolls_back_native_yolo_notification(self):
  self.s.configure({'permission':'default'});original=self.s.peer.call
  def missing(method,*args,**kwargs):
   if method=='_x.ai/sessions/list':raise TimeoutError('lost readback')
   return original(method,*args,**kwargs)
  self.s.peer.call=missing
  with self.assertRaises(TimeoutError):self.s.configure({'permission':'always-approve'})
  self.assertFalse(self.s.peer.yolo)
 def test_unqueued_interjection_marks_rejected_while_timeout_stays_unknown(self):
  self.begin();original=self.s.peer.call
  self.s.peer.call=lambda m,*a,**k:{'result':{'status':'ignored'}} if m=='_x.ai/interject' else original(m,*a,**k)
  self.s.admit({'submissionId':'ij-rej','text':'nope','steer':True})
  wait(lambda:self.config.get('receipts',{}).get('ij-rej')=='rejected')
  self.assertEqual('rejected',self.config['deliveries']['ij-rej']['state'])
  self.s.peer.call=lambda m,*a,**k:(_ for _ in ()).throw(TimeoutError('net')) if m=='_x.ai/interject' else original(m,*a,**k)
  self.s.admit({'submissionId':'ij-unk','text':'maybe','steer':True})
  wait(lambda:self.config.get('deliveries',{}).get('ij-unk',{}).get('state')=='unknown')
  self.assertEqual('unknown',self.config['receipts']['ij-unk'])
  self.finish()
 def test_armed_interjection_flushes_before_next_tool_call(self):
  self.begin()
  self.s.event(notification('agent_message_chunk','m0',prompt='a',content={'type':'text','text':'step1'}))
  self.s.submit({'submissionId':'ij-tool','text':'check D','steer':True})
  wait(lambda:any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames))
  self.s.event({'method':'_x.ai/session/interjection','params':{'sessionId':'native','interjectionId':'ij-tool','text':'check D'}})
  self.s.event(notification('response_completed','rc-tool'))
  self.assertNotIn('ij-tool',self.s.rows)
  self.s.event(notification('tool_call','tc-next',prompt='a',toolCallId='tnext',title='read_d'))
  self.assertEqual(['a','grok:m0','ij-tool','tool-tnext'],list(self.s.rows))
  self.finish()
 def test_background_task_and_autonomous_wakeup_protect_from_idle_release(self):
  from luma_cli.i18n import tr
  self.begin()
  self.s.update(historyReady=True)
  self.s.row({**self.s.rows['a'],'origin':''})
  self.s.event(notification('tool_call','tc-bg',prompt='a',toolCallId='tbg',title='bash'))
  self.s.event({'method':'x.ai/task_backgrounded','params':{'sessionId':'native','task_id':'bg1','tool_call_id':'tbg','command':'sleep 60'}})
  self.finish()
  self.assertFalse(self.s.data['running'])
  self.assertEqual(tr('后台任务尚未确认结束','Background task has not been confirmed finished'),self.s.release_blocker())
  self.s.event({'method':'x.ai/task_completed','params':{'sessionId':'native','task_snapshot':{'id':'bg1','exit_code':0,'output':'done'}}})
  self.assertEqual('completed',self.s.rows['tool-tbg']['status'])
  self.assertEqual(tr('后台唤醒回合尚未结算','Background wake-up turn has not settled'),self.s.release_blocker())
  self.s.event(notification('agent_message_chunk','m-bg',prompt='task-completed-bg1',content={'type':'text','text':'bg finished'}))
  self.assertTrue(self.s.data['running'])
  self.assertEqual(tr('原生回合尚未结算','Native turn has not settled'),self.s.release_blocker())
  self.s.event(notification('turn_completed','done-bg',prompt_id='task-completed-bg1',stop_reason='end_turn'))
  self.assertFalse(self.s.data['running'])
  self.assertEqual('completed',self.s.rows['grok:m-bg']['status'])
  self.assertIsNone(self.s.release_blocker())
 def test_admitted_interjection_allows_release_mode_after_turn_settles(self):
  self.begin();self.s.row({**self.s.rows['a'],'origin':''});self.s.update(historyReady=True)
  self.s.submit({'submissionId':'steer-rel','text':'admit me','steer':True})
  wait(lambda:any(f.get('method')=='_x.ai/interject' for f in self.s.peer.frames))
  self.s.event({'method':'_x.ai/session/interjection','params':{'sessionId':'native','interjectionId':'steer-rel','text':'admit me'}})
  self.assertIsNone(self.s.release_mode())
  self.s.event(notification('response_completed','rc-rel'))
  self.s.event(notification('agent_message_chunk','m-rel',prompt='a',content={'type':'text','text':'ok'}))
  self.assertTrue(self.config['admittedDisplaySubmissions']['steer-rel'])
  self.finish()
  self.assertEqual('process',self.s.release_mode())
  self.assertIsNone(self.s.release_blocker())
 def test_leader_reconnect_mid_turn_preserves_pending_interjection_until_turn_completes(self):
  cfg={'cli':'Grok Build','cwd':'/tmp','id':'local','nativeId':'native',
       'receipts':{'active-turn':'accepted','ij-pending':'accepted'},
       'consumedSubmissions':{'active-turn':True},
       'pendingInterjections':[{'id':'ij-pending','text':'wait for me','armed':False}]}
  frames=[notification('user_message_chunk','u-act',replay=True,prompt='active-turn',content={'type':'text','text':'work'}),
          notification('agent_message_chunk','a-act',replay=True,prompt='active-turn',content={'type':'text','text':'still going'})]
  class LeaderHarness(Harness):
   def start(inner,*args,**kwargs):
    super().start(*args,**kwargs)
    inner.owner=type('Owner',(),{'handle':{'instanceId':'inst-1','pid':99999},'alive':lambda _:True})()
  with patch('luma_cli.grok.non_mcp_background_child_alive',return_value=False):
   s=LeaderHarness(cfg,lambda:None);s.replay=frames;s.connect()
   self.assertTrue(s.data['running'])
   self.assertEqual(['ij-pending'],[e['id'] for e in cfg.get('pendingInterjections',[])])
   self.assertIsNone(s.release_mode())
   s.event(notification('turn_completed','done-act',prompt_id='active-turn',stop_reason='end_turn'))
   self.assertFalse(s.data['running'])
   self.assertEqual([],cfg.get('pendingInterjections',[]))
   self.assertTrue(cfg['admittedDisplaySubmissions']['ij-pending'])
   self.assertEqual('observer',s.release_mode())
 def test_multi_chunk_user_replay_tags_all_chunks_and_zero_monotonic_startup_not_blocked(self):
  with patch('luma_cli.grok.time.monotonic',return_value=1.0):
   self.begin();self.s.row({**self.s.rows['a'],'origin':''});self.s.update(historyReady=True)
   self.finish()
   self.assertIsNone(self.s.release_blocker())
  cfg={'cli':'Grok Build','cwd':'/tmp','id':'local','nativeId':'native','receipts':{'multi':'unknown'}}
  frames=[notification('user_message_chunk','u-txt',replay=True,content={'type':'text','text':'part1'}),
          notification('user_message_chunk','u-img',replay=True,content={'type':'image','mimeType':'image/png','data':'abcd'}),
          notification('turn_completed','d-multi',replay=True,prompt_id='multi',stop_reason='end_turn')]
  s=Harness(cfg,lambda:None);s.replay=frames;s.connect()
  users=[r for r in s.rows.values() if r.get('role')=='user']
  self.assertEqual(['multi'],[r['id'] for r in users])
  self.assertTrue(users[0].get('media'))
if __name__=='__main__':unittest.main()
