import fcntl,json,os,pty,signal,struct,sys,pathlib,tempfile,termios,http.server,threading,time,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.pi_native import PiNativeSession
class Tui:
 def __init__(self,argv,cwd,env):
  master,slave=pty.openpty()
  fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',40,110,0,0))
  self.master=master
  self.proc=subprocess.Popen(argv,cwd=cwd,env=env,stdin=slave,stdout=slave,stderr=slave,close_fds=True)
  os.close(slave)
  def drain():
   while True:
    try:
     if not os.read(self.master,4096):break
    except OSError:break
  threading.Thread(target=drain,daemon=True).start()
 def send(self,data):os.write(self.master,data)
 def close(self):
  try:os.close(self.master)
  except OSError:pass
  try:self.proc.terminate();self.proc.wait(3)
  except Exception:pass
calls=[];hold=threading.Event();release=threading.Event()
class Model(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  value=json.loads(self.rfile.read(int(self.headers['Content-Length'])));calls.append(value)
  last_msg=value.get('messages',[{}])[-1]
  last_text=str(last_msg.get('content',''))
  if 'HOLD_SENTINEL' in last_text:hold.set();release.wait(30)
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  if 'EDIT_SENTINEL' in last_text and last_msg.get('role')!='tool':
   tc={'id':'call_edit_1','type':'function','function':{'name':'edit','arguments':json.dumps({'path':'sample.txt','edits':[{'oldText':'alpha','newText':'beta'}]})}}
   chunks=[({'role':'assistant','content':'','tool_calls':[tc]},None),({},'tool_calls')]
  else:
   chunks=[({'role':'assistant'},None),({'content':'NATIVE_REPLY'},None),({},'stop')]
  for delta,finish in chunks:
   chunk={'id':'fixture','object':'chat.completion.chunk','created':1,'model':'fixture','choices':[{'index':0,'delta':delta,'finish_reason':finish}],'usage':{'prompt_tokens':120,'completion_tokens':15,'total_tokens':135}}
   try:self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode());self.wfile.flush()
   except (BrokenPipeError,ConnectionResetError):return
  try:self.wfile.write(b'data: [DONE]\n\n')
  except (BrokenPipeError,ConnectionResetError):pass

def wait(check,timeout=20):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if check():return
  time.sleep(.05)
 raise AssertionError('condition timed out')

srv=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=srv.serve_forever,daemon=True).start()
result={};old=dict(os.environ);session=None;tui=None
with tempfile.TemporaryDirectory(prefix='luma-pi-production-') as tmp:
 home=pathlib.Path(tmp);agent=home/'agent';agent.mkdir();work=home/'work';work.mkdir();state=home/'state';state.mkdir()
 (agent/'models.json').write_text(json.dumps({'providers':{'fixture':{'baseUrl':f'http://127.0.0.1:{srv.server_port}/v1','apiKey':'fixture-public','api':'openai-completions','models':[{'id':m,'name':m,'reasoning':True,'input':['text','image'],'cost':{'input':0,'output':0,'cacheRead':0,'cacheWrite':0},'contextWindow':32000,'maxTokens':1024} for m in ['fixture','fixture-other']]}}}))
 (agent/'extensions').mkdir();(agent/'extensions/dual.ts').write_text('import fs from \"node:fs\"; export default function(pi){pi.registerCommand(\"dual-select\",{handler:async(args,ctx)=>{const value= args===\"editor\" ? await ctx.ui.editor(\"DUAL_EDITOR\",\"prefill\") : await ctx.ui.select(\"DUAL_APPROVAL\",[\"APPROVE\",\"REJECT\"]);fs.writeFileSync('+json.dumps(str(agent/'selected.txt'))+',value??\"cancel\");}})}')
 os.environ.clear();os.environ.update({'HOME':tmp,'PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8','TERM':'xterm-256color','PI_CODING_AGENT_DIR':str(agent),'DO_NOT_TRACK':'1','PI_OFFLINE':'1','HTTP_PROXY':'http://127.0.0.1:9','HTTPS_PROXY':'http://127.0.0.1:9','ALL_PROXY':'http://127.0.0.1:9','NO_PROXY':'127.0.0.1,localhost'})
 config={'id':'production-pi','cli':'pi','cwd':str(work),'nativeProtocol':'pi-native-sdk-v1','ownerDirectory':str(state),'model':'fixture/fixture'}
 def save():
  f=home/'config.json';f.write_text(json.dumps(config))
 try:
  session=PiNativeSession(config,save);session.connect();sid=session.data['nativeId'];assert session.data['canOpenTui']
  assert not config.get('sessionFile'),config
  assert all('high' in m['efforts'] for m in session.data['models']),session.data['models']
  session.peer.close();session.owner.tmux('kill-server',check=False);time.sleep(.2)
  session=PiNativeSession(config,save);session.connect();assert session.data['nativeId']==sid
  tui=Tui(session.terminal()['argv'],str(work),dict(os.environ));time.sleep(.4)
  (work/'sample.txt').write_text('alpha\nkeep\n')
  payload=lambda ident,body,skills=None:{'submissionId':ident,'text':body,'images':[],'skills':skills or []}
  session.submit(payload('gui-one','GUI_SENTINEL'));wait(lambda:len(calls)>=1 and not session.data['running'])
  wait(lambda:any(r.get('text')=='NATIVE_REPLY' for r in session.rows.values()))
  wait(lambda:bool(session.data['metrics']) and bool(config.get('sessionFile')) and pathlib.Path(config['sessionFile']).is_file())
  session.submit(payload('gui-edit','EDIT_SENTINEL'));wait(lambda:not session.data['running'] and 'tool-call_edit_1' in session.rows and session.rows['tool-call_edit_1']['status']=='completed')
  assert '@@ ' in session.rows['tool-call_edit_1']['outcome'],session.rows['tool-call_edit_1']
  assert session.rows['tool-call_edit_1']['text']=='edit · sample.txt',session.rows['tool-call_edit_1']
  assert not any(r.get('role')=='assistant' and not r.get('text','').strip() for r in session.rows.values()),session.rows
  screen=session.owner.tmux('capture-pane','-p').stdout.decode();assert 'NATIVE_REPLY' in screen,screen
  tui.send(b'TUI_SENTINEL\r');wait(lambda:any('TUI_SENTINEL' in r.get('text','') for r in session.rows.values()));wait(lambda:not session.data['running'])
  tui.send(b'/name DualRenamed\r');wait(lambda:session.data['title']=='DualRenamed')
  session.configure({'model':'fixture/fixture-other','effort':'high'});assert session.data['model']=='fixture/fixture-other' and session.data['effort']=='high'
  command_errors=[]
  def command(ident,body):
   try:session.submit(payload(ident,body))
   except Exception as exc:command_errors.append(repr(exc))
  threading.Thread(target=command,args=('extension-remote','/dual-select remote'),daemon=True).start();wait(lambda:bool(session.data['requests']))
  request=session.data['requests'][0];assert request['title']=='DUAL_APPROVAL';session.answer({'id':request['id'],'choice':'0'});wait(lambda:(agent/'selected.txt').exists());assert (agent/'selected.txt').read_text()=='APPROVE'
  (agent/'selected.txt').unlink();tui.send(b'/dual-select native\r');wait(lambda:bool(session.data['requests']));time.sleep(.2);tui.send(b'\r');wait(lambda:(agent/'selected.txt').exists());wait(lambda:not session.data['requests']);assert (agent/'selected.txt').read_text()=='APPROVE'
  (agent/'selected.txt').unlink();threading.Thread(target=command,args=('extension-editor','/dual-select editor'),daemon=True).start();wait(lambda:any(r['title']=='DUAL_EDITOR' for r in session.data['requests']));request=session.data['requests'][0];assert request['questions'][0]['prompt']=='prefill';session.answer({'id':request['id'],'choice':'accept','answers':{'value':['GUI_EDITED']}});wait(lambda:(agent/'selected.txt').exists());assert (agent/'selected.txt').read_text()=='GUI_EDITED';assert not command_errors,command_errors

  session.submit(payload('hold','HOLD_SENTINEL'));assert hold.wait(10)
  a=payload('same-a','IDENTICAL_TEXT');b=payload('same-b','IDENTICAL_TEXT');session.submit(a);session.submit(b)
  assert session.call('get_state')['pendingMessageCount']==2
  assert {q.get('clientUserMessageId') for q in session.data['queue']}=={'same-a','same-b'},session.data['queue']
  assert not config.get('consumedSubmissions',{}).get('same-a') and not config.get('consumedSubmissions',{}).get('same-b')
  session.submit(a);assert session.call('get_state')['pendingMessageCount']==2
  assert {q.get('clientUserMessageId') for q in session.data['queue']}=={'same-a','same-b'},session.data['queue']
  session.peer.close();time.sleep(.2);session2=PiNativeSession(config,save);session2.connect();assert session2.data['nativeId']==sid and config['receipts']['same-b']=='accepted'
  session=session2;release.set();wait(lambda:config.get('consumedSubmissions',{}).get('same-a') and config.get('consumedSubmissions',{}).get('same-b'));wait(lambda:not session.data['running'])
  hold.clear();release.clear();session.submit(payload('abort-separate','HOLD_SENTINEL'));assert hold.wait(10);session.stop();release.set();wait(lambda:not session.data['running'])
  tui.send(b'/new\r');wait(lambda:not session.data['connected']);assert config['nativeId']==sid;assert config['receipts']['same-a']=='accepted'
  tui.close();tui=None;assert session.owner.alive();result={'passed':True,'sameNativeId':sid,'twoWay':True,'extensionSelectBothSides':True,'nativeEditorAnsweredFromGui':True,'duplicateTextDistinctIds':True,'sameIdNoReplay':True,'observerReconnectReceipt':True,'queuedConsumptionUsesExactObjectIdentity':True,'realTerminalAttachAndDetach':True,'modelThinkingReadBack':True,'abort':True,'nativeNewDoesNotRebindOldGui':True,'localRequests':len(calls)}
 except Exception as exc:
  result={'passed':False,'error':repr(exc),'traceback':__import__('traceback').format_exc(),'modelState':[session.data.get('model'),session.data.get('effort')],'localRequests':len(calls)}
  if session and hasattr(session,'owner'):
   result['screen']=session.owner.tmux('capture-pane','-p',check=False).stdout.decode(errors='replace')
 finally:
  release.set()
  if tui:tui.close()
  if session and session.peer:session.peer.close()
  if session and hasattr(session,'owner'):session.owner.tmux('kill-server',check=False)
  os.environ.clear();os.environ.update(old)
srv.shutdown();srv.server_close()
out=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path('/tmp/pi-native-dual.json');out.write_text(json.dumps(result,indent=2,ensure_ascii=False));print(json.dumps(result,ensure_ascii=False));sys.exit(0 if result.get('passed') else 1)
