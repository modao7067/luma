#!/usr/bin/env python3
import base64,http.server,json,os,pathlib,secrets,shutil,signal,socket,subprocess,tempfile,threading,time,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[3]
OUT=ROOT/'artifacts/test-runs/opencode-child-failure'
class Model(http.server.BaseHTTPRequestHandler):
    agent='explore'
    calls=[]
    def log_message(self,*a):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.calls.append(body)
        messages=body.get('messages',[])
        if any('LUMA_CHILD_FAIL_ONLY' in str(m.get('content','')) for m in messages if m.get('role')=='user'):
            raw=json.dumps({'error':{'message':'LUMA_INTENTIONAL_CHILD_PROVIDER_ERROR','type':'invalid_request_error'}}).encode()
            self.send_response(400);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw);return
        if any(m.get('role')=='tool' for m in messages):
            delta={'role':'assistant','content':'LUMA_PARENT_SETTLED'};finish='stop'
        else:
            delta={'role':'assistant','tool_calls':[{'index':0,'id':'call-luma-subagent-failure','type':'function','function':{'name':'subagent','arguments':json.dumps({'agent':self.agent,'description':'Failure fixture','prompt':'LUMA_CHILD_FAIL_ONLY','background':False})}}]};finish='tool_calls'
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        for d,f in [(delta,None),({},finish)]:
            chunk={'id':'fixture-'+str(len(self.calls)),'object':'chat.completion.chunk','created':1,'model':'fixture','choices':[{'index':0,'delta':d,'finish_reason':f}]}
            self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='luma-opencode-child-failure-') as tmp:
        home=pathlib.Path(tmp);work=home/'work';work.mkdir()
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
        with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        config={'model':'fixture/fixture','providers':{'fixture':{'package':'@opencode/ai/providers/openai-compatible','settings':{'baseURL':f'http://127.0.0.1:{server.server_port}/v1','apiKey':'public-local-fixture'},'models':{'fixture':{'name':'Local fixture','capabilities':{'tools':True,'input':['text'],'output':['text']},'limit':{'context':100000,'output':256}}}}}}
        password=secrets.token_hex(24)
        authorization='Basic '+base64.b64encode(('opencode:'+password).encode()).decode()
        env={'OPENCODE_PASSWORD':password,'PATH':'/usr/local/bin:/usr/bin:/bin','HOME':str(home),'LANG':'C.UTF-8','OPENCODE_CONFIG_CONTENT':json.dumps(config),'XDG_DATA_HOME':str(home/'data'),'XDG_CONFIG_HOME':str(home/'config'),'XDG_CACHE_HOME':str(home/'cache'),'XDG_STATE_HOME':str(home/'state')}
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def call(path,body=None):
            req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':authorization})
            with opener.open(req,timeout=10) as r:return json.load(r)
        opencode_bin=os.environ.get('LUMA_TEST_OPENCODE') or shutil.which('opencode') or 'opencode'
        with (OUT/'opencode-native-failure-server.log').open('w') as log:
            proc=subprocess.Popen([opencode_bin,'serve','--hostname','127.0.0.1','--port',str(port)],env=env,cwd=work,stdout=log,stderr=log,start_new_session=True)
            try:
                deadline=time.monotonic()+35
                while True:
                    try:call('/api/info');break
                    except OSError:
                        if proc.poll() is not None or time.monotonic()>deadline:raise
                        time.sleep(.1)
                catalog=call('/api/agent').get('data',[])
                choices=[a for a in catalog if a.get('mode')!='primary' and not a.get('hidden')]
                if choices:Model.agent=choices[0]['id']
                parent=call('/api/session',{'location':{'directory':str(work)},'model':{'providerID':'fixture','id':'fixture'},'permissions':[{'action':'subagent','resource':'*','effect':'allow'}]})['data']['id']
                call('/api/session/'+parent+'/prompt',{'text':'LUMA_START_PARENT','delivery':'queue'})
                deadline=time.monotonic()+90;rows=[]
                while time.monotonic()<deadline:
                    rows=call('/api/session/'+parent+'/message?order=asc&limit=100')['data']
                    parts=[p for m in rows for p in m.get('content',[]) if p.get('type')=='tool' and p.get('name')=='subagent']
                    failed=[p for p in parts if p.get('state',{}).get('status')=='error']
                    if failed and parent not in call('/api/session/active')['data']:break
                    time.sleep(.1)
                else:raise TimeoutError('Native parent/subagent error did not settle')
                part=failed[-1];child=part['state'].get('metadata',{}).get('sessionID')
                assert child,part
                native=call('/api/session/'+child)['data']
                result={'source':'OpenCode 2.1.4 + deterministic localhost chat-completions model','paidInference':False,'parentSessionId':parent,'messages':rows,'child':native,'active':call('/api/session/active')['data']}
                (OUT/'opencode-native-child-failure.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
                assert native['outcome']=='failed',native
                assert part['state']['metadata']['status']=='running',part
                print('Native failure confirmed: parent tool error retains child metadata running; child outcome failed.')
            finally:
                os.killpg(proc.pid,signal.SIGTERM)
                try:proc.wait(timeout=8)
                except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL);proc.wait()
                server.shutdown();server.server_close()
                (OUT/'opencode-local-model-requests.json').write_text(json.dumps(Model.calls,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
