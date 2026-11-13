#!/usr/bin/env python3
import json,os,pathlib,socket,subprocess,sys,tempfile,time,urllib.request,urllib.error
sys.dont_write_bytecode = True
ROOT=pathlib.Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='luma-cli-http-') as tmp:
    home=pathlib.Path(tmp);token='fixture-capability-'+'x'*48;(home/'token').write_text(token)
    peer=home/'pi';peer.write_text('''#!/usr/bin/python3
import json,sys
native=sys.argv[sys.argv.index('--session-id')+1]
waiting=[];approved=False
print(json.dumps({'type':'extension_ui_request','id':'startup','method':'confirm','title':'Native startup approval'}),flush=True)
def reply(f):
 kind=f['type'];data={}
 if kind=='get_state':data={'sessionId':native,'isStreaming':False,'pendingMessageCount':0,'model':{'provider':'fixture','id':'m','input':['text']}}
 elif kind=='get_messages':data={'messages':[]}
 elif kind=='get_commands':data={'commands':[]}
 elif kind=='get_available_models':data={'models':[]}
 elif kind=='get_available_thinking_levels':data={'levels':['off']}
 print(json.dumps({'type':'response','id':f['id'],'success':True,'data':data}),flush=True)
for line in sys.stdin:
 f=json.loads(line)
 if f['type']=='extension_ui_response':
  assert f['id']=='startup' and f['confirmed'] is False
  approved=True
  for req in waiting:reply(req)
  waiting=[]
 elif approved:reply(f)
 else:waiting.append(f)
''');peer.chmod(0o700)
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    env={'HOME':tmp,'PATH':tmp,'LANG':'C.UTF-8'}
    proc=subprocess.Popen([sys.executable,'-I','-B',str(ROOT/'apps/gui/app/src/main/assets/cli_bridge/main.py'),str(port),str(home/'token'),str(home/'state')],env=env,cwd=tmp,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    def call(path,body=None,auth=token,origin=None):
        headers={'Content-Type':'application/json','Connection':'close'}
        if auth is not None:headers['Authorization']='Bearer '+auth
        if origin:headers['Origin']=origin
        request=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
        try:
            with urllib.request.urlopen(request,timeout=5) as r:return r.status,json.load(r)
        except urllib.error.HTTPError as e:return e.code,json.load(e)
    try:
        for _ in range(80):
            try:
                status,health=call('/health');break
            except OSError:time.sleep(.05)
        assert status==200 and health['protocol']==1
        assert {'Qoder CLI','Grok Build'}.issubset(health['clis'])
        assert health['nativeProtocols']['Antigravity']==['antigravity-agy-acp','antigravity-cli-stream-json','antigravity-acp']
        assert call('/health',auth=None)[0]==401
        assert call('/health',origin='http://localhost')[0]==401
        assert call('/sessions/connect',{'id':'x','cwd':tmp,'cli':'disabled'})[0]==422
        for ident,cli,command in [('missing-qoder','Qoder CLI','qodercli'),('missing-kimi','Kimi Code','kimi')]:
            assert call('/sessions/connect',{'id':ident,'cwd':tmp,'cli':cli})[0]==200
            for _ in range(80):
                missing=call('/sessions/'+ident)[1]
                if not missing['connecting']:break
                time.sleep(.05)
            assert not missing['connected'] and not missing['nativeId'],missing
            assert command in missing['error'],missing
        start=time.monotonic();status,s=call('/sessions/connect',{'id':'owned','cwd':tmp,'cli':'pi'})
        assert status==200 and time.monotonic()-start<2
        for _ in range(80):
            s=call('/sessions/owned')[1]
            if s['requests']:break
            time.sleep(.05)
        assert s['connecting'] and s['requests'][0]['id']=='startup',s
        assert call('/sessions/owned/answer',{'id':'startup','choice':'no'})[0]==200
        for _ in range(80):
            s=call('/sessions/owned')[1]
            if s['connected']:break
            time.sleep(.05)
        assert s['connected'] and s['permissions']==[] and not s['requests'],s
        assert call('/sessions/owned?after='+str(s['revision']))[1]['unchanged']
        assert call('/sessions/owned/configure',{'permission':'full-access'})[0]==422
        print('PASS authenticated carrier, new ACP catalog/missing-command errors, Origin refusal, async startup native approval/denial, revision polling, no synthetic permissions')
    finally:
        proc.terminate()
        try:proc.communicate(timeout=8)
        except subprocess.TimeoutExpired:proc.kill();proc.communicate(timeout=5)
