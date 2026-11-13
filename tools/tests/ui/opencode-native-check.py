#!/usr/bin/env python3
import base64, json, os, pathlib, secrets, socket, subprocess, time, urllib.request, urllib.error
BASE=pathlib.Path(__file__).resolve().parents[3]/'verification/gui-opencode-20261102/native'
BASE.mkdir(parents=True,exist_ok=True)
home=BASE/'home';home.mkdir(exist_ok=True)
work=BASE/'work';work.mkdir(exist_ok=True)
password=secrets.token_hex(32)
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
url=f'http://127.0.0.1:{port}'
env={'PATH':os.environ['PATH'],'HOME':str(home),'XDG_DATA_HOME':str(home/'data'),'XDG_CONFIG_HOME':str(home/'config'),'XDG_STATE_HOME':str(home/'state'),'XDG_CACHE_HOME':str(home/'cache'),'OPENCODE_PASSWORD':password}
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,method='GET',body=None,auth=True):
 headers={'Content-Type':'application/json'}
 if auth:headers['Authorization']='Basic '+base64.b64encode(('opencode:'+password).encode()).decode()
 req=urllib.request.Request(url+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 with opener.open(req,timeout=40) as r:
  raw=r.read();return json.loads(raw) if raw else None
with (BASE/'server.log').open('w') as log:
 proc=subprocess.Popen(['/root/.opencode/bin/opencode','serve','--hostname','127.0.0.1','--port',str(port)],cwd=work,env=env,stdout=log,stderr=log)
 try:
  for _ in range(120):
   try:info=call('/api/info');break
   except (OSError,ValueError):
    if proc.poll() is not None:raise RuntimeError('isolated server exited')
    time.sleep(.25)
  else:raise RuntimeError('server timeout')
  print('native',info.get('version'),flush=True)
  try:call('/api/info',auth=False);raise AssertionError('unauthenticated access')
  except urllib.error.HTTPError as e:assert e.code==401
  sid='ses_luma'+secrets.token_hex(12)
  session=call('/api/session','POST',{'id':sid,'title':'Luma isolated contract','location':{'directory':str(work)},'model':{'providerID':'luma-contract-unconfigured','id':'no-network'}})['data'];assert session['id']==sid
  outputs={'info':info,'session':session}
  for path in ['/api/model','/api/agent','/api/session/active',f'/api/session/{sid}/message?order=asc&limit=100',f'/api/session/{sid}/permission',f'/api/session/{sid}/form',f'/api/session/{sid}/inbox','/api/skill','/api/mcp']:
   outputs[path]=call(path); print('GET',path.split('?')[0],'ok',flush=True)
  mid='msg_luma'+secrets.token_hex(12)
  entry=call(f'/api/session/{sid}/prompt','POST',{'id':mid,'text':'Contract fixture; do not execute','delivery':'queue','resume':False})['data'];assert entry['id']==mid
  q=call(f'/api/session/{sid}/inbox')['data'];assert any(x['id']==mid for x in q)
  outputs['queued']=q
  call(f'/api/session/{sid}/inbox/{mid}','DELETE')
  assert not call(f'/api/session/{sid}/inbox')['data']
  call(f'/api/session/{sid}','PATCH',{'title':'Renamed by native API'})
  assert call(f'/api/session/{sid}')['data']['title']=='Renamed by native API'
  mid2='msg_luma'+secrets.token_hex(12)
  call(f'/api/session/{sid}/prompt','POST',{'id':mid2,'text':'No-network delivery fixture','delivery':'queue','resume':False})
  call(f'/api/session/{sid}/inbox/{mid2}','PATCH',{'delivery':'steer'})
  time.sleep(.4)
  outputs['afterSteer']=call(f'/api/session/{sid}/message?order=asc&limit=100')
  assert any(m['id']==mid2 for m in outputs['afterSteer']['data']) or any(m['id']==mid2 for m in call(f'/api/session/{sid}/inbox')['data'])
  call(f'/api/session/{sid}/interrupt','POST',{})
  call(f'/api/session/{sid}','DELETE')
  (BASE/'fixtures.json').write_text(json.dumps(outputs,ensure_ascii=False,indent=2))
  print('PASS auth, explicit session ID, native snapshots, durable inbox, steer delivery, cancel, rename; no external model requests (unconfigured fixture provider)',flush=True)
 finally:
  proc.terminate()
  try:proc.wait(timeout=8)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
