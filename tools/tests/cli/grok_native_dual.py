import importlib.util,sys,os,pathlib,tempfile,http.server,threading,subprocess,time,json,signal,shlex
ROOT=pathlib.Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('fixture',ROOT/'verification/gui-grok-build-20261104/native_adapter_probe.py');fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
from luma_cli.grok import GrokSession
from luma_cli.native_owner import NativeOwner
sys.path.insert(0,str(ROOT/'verification/gui-dual-control-research-20261104'));from native_research import Tui
Stub=fixture.Stub;Stub.mode='success';Stub.calls=[]
def wait(check,timeout=25):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if check():return
  time.sleep(.05)
 raise AssertionError('condition timed out')
srv=http.server.ThreadingHTTPServer(('127.0.0.1',0),Stub);threading.Thread(target=srv.serve_forever,daemon=True).start();result={};old=dict(os.environ);session=tui=None
with tempfile.TemporaryDirectory(prefix='luma-grok-production-') as tmp:
 home=pathlib.Path(tmp);bin_dir=home/'bin';bin_dir.mkdir();real='/root/.grok/bin/grok';url='http://127.0.0.1:'+str(srv.server_port)
 wrapper=bin_dir/'grok';wrapper.write_text('#!/usr/bin/python3\nimport os,sys\na=sys.argv[1:]\ni=a.index("agent")+1 if "agent" in a else len(a)\nif "agent" in a:a[i:i]=["--xai-api-base-url",'+repr(url)+',"--cli-chat-proxy-base-url",'+repr(url)+']\nos.execv('+repr(real)+',['+repr(real)+']+a)\n');wrapper.chmod(0o700)
 os.environ.clear();os.environ.update({'PATH':str(bin_dir)+':/usr/local/bin:/usr/bin:/bin','HOME':tmp,'GROK_HOME':tmp+'/.grok','XDG_CONFIG_HOME':tmp+'/config','XDG_DATA_HOME':tmp+'/data','XAI_API_KEY':'xai-isolated-fake','LANG':'C.UTF-8','TERM':'xterm-256color','HTTP_PROXY':'http://127.0.0.1:9','HTTPS_PROXY':'http://127.0.0.1:9','ALL_PROXY':'http://127.0.0.1:9','NO_PROXY':'127.0.0.1,localhost','GROK_WS_URL':'ws://127.0.0.1:9','GROK_WS_ORIGIN':'http://127.0.0.1:9'})
 config={'id':'production-grok','cli':'Grok Build','cwd':tmp,'nativeProtocol':'grok-private-leader-v1'}
 def save():(home/'config.json').write_text(json.dumps(config))
 try:
  session=GrokSession(config,save);session.connect();sid=session.data['nativeId'];assert session.data['canOpenTui'];terminal=session.terminal();assert terminal['nativeId']==sid
  tui=Tui(terminal['argv'],tmp,dict(os.environ));time.sleep(2)
  session.configure({'effort':'low'});assert session.data['effort']=='low';session.configure({'permission':'always-approve'});assert session.data['permission']=='always-approve';session.configure({'permission':'default'})
  session.submit({'submissionId':'gui-one','text':'GUI_SENTINEL no tools'});wait(lambda:not session.data['running']);wait(lambda:b'LOCAL_OK' in tui.raw)
  tui.send(b'TUI_SENTINEL no tools\r');wait(lambda:any('TUI_SENTINEL' in r.get('text','') for r in session.rows.values()));wait(lambda:not session.data['running'])
  Stub.next_tool=('run_terminal_command',{'command':'printf NATIVE_FILE > '+shlex.quote(tmp+'/approved.txt'),'description':'write isolated fixture'})
  session.submit({'submissionId':'approval','text':'Run the fixture tool'});wait(lambda:bool(session.data['requests']))
  assert not (home/'approved.txt').exists();request=session.data['requests'][0];choice=next(c['id'] for c in request['choices'] if c['id']=='approve' or 'allow' in c['id'] or 'approve' in c['id'])
  session.answer({'id':request['id'],'choice':choice,'answers':{}});wait(lambda:(home/'approved.txt').exists());assert (home/'approved.txt').read_text()=='NATIVE_FILE';wait(lambda:not session.data['running'])
  release=threading.Event();Stub.delay=release;Stub.entered.clear();session.submit({'submissionId':'hold-main','text':'HOLD_MAIN'});assert Stub.entered.wait(10)
  for ident in ('same-a','same-b'):session.submit({'submissionId':ident,'text':'SAME_TEXT','steer':True})
  wait(lambda:all(config.get('receipts',{}).get(i)=='accepted' for i in ('same-a','same-b')))
  assert all(not config.get('admittedDisplaySubmissions',{}).get(i) and not config.get('consumedSubmissions',{}).get(i) for i in ('same-a','same-b'))
  session.submit({'submissionId':'same-a','text':'SAME_TEXT','steer':True});release.set();wait(lambda:not session.data['running'])
  assert all(config['admittedDisplaySubmissions'].get(i) and not config.get('consumedSubmissions',{}).get(i) for i in ('same-a','same-b'))
  session.peer.close();time.sleep(.3);assert session.owner.alive() and tui.proc.poll() is None
  session2=GrokSession(config,save);session2.connect();session=session2;assert session.data['nativeId']==sid and config['receipts']['gui-one']=='accepted'
  session.submit({'submissionId':'gui-one','text':'GUI_SENTINEL no tools'});assert config['receipts']['gui-one']=='accepted'
  result={'passed':True,'twoWay':True,'sameNativeId':sid,'privateLeader':True,'nativePermission':True,'modelThinkingReadBack':True,'ownerSurvivesObserverClose':True,'receiptAfterReconnect':True,'sameIdNoReplay':True,'busyExactAdmissionMatchesNativePagerWithoutClaimingConsumption':True,'localRequests':len(Stub.calls)}
 except Exception as exc:
  result={'passed':False,'error':repr(exc),'snapshot':session.snapshot() if session else None,'localRequests':len(Stub.calls)}
 finally:
  if session and session.peer:session.peer.close()
  if tui:tui.close()
  if session and hasattr(session,'owner') and session.owner.alive():
   os.kill(session.owner.handle['pid'],signal.SIGTERM);time.sleep(1)
  os.environ.clear();os.environ.update(old)
srv.shutdown();srv.server_close();out=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path('/tmp/grok-native-dual.json');out.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));sys.exit(0 if result.get('passed') else 1)
