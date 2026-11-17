#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import base64, json, os, pathlib, secrets, shutil, socket, subprocess, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[3];BASE=ROOT/'artifacts/test-runs/opencode-stream'
BASE.mkdir(parents=True,exist_ok=True);home=BASE/'home';home.mkdir(exist_ok=True);work=BASE/'work';work.mkdir(exist_ok=True)
skills=BASE/'skills'/'fixture-skill';skills.mkdir(parents=True,exist_ok=True)
(skills/'SKILL.md').write_text('---\nname: fixture-skill\ndescription: Isolated native skill fixture.\n---\nOnly perform the explicitly requested fixture task.\n')
requests=[]
class Model(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(body)
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def chunk(delta,finish=None):
   value={'id':'chatcmpl-luma','object':'chat.completion.chunk','created':1,'model':'fixture','choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
   if finish:value['usage']={'prompt_tokens':20,'completion_tokens':8,'total_tokens':28}
   self.wfile.write(('data: '+json.dumps(value)+'\n\n').encode());self.wfile.flush();time.sleep(.04)
  chunk({'role':'assistant'})
  if not any(m.get('role')=='tool' for m in body.get('messages',[])):
   tools=[x['function']['name'] for x in body.get('tools',[])];name='shell' if 'shell' in tools else 'bash'
   chunk({'reasoning_content':'Inspecting the isolated fixture.'})
   chunk({'tool_calls':[{'index':0,'id':'call_luma','type':'function','function':{'name':name,'arguments':json.dumps({'command':'printf luma-fixture-tool','description':'Print isolated test output'})}}]})
   chunk({},'tool_calls')
  else:
   chunk({'content':'OpenCode fixture '});chunk({'content':'stream completed.'});chunk({},'stop')
  self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()
model=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=model.serve_forever,daemon=True).start()
password=secrets.token_hex(32)
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
url=f'http://127.0.0.1:{port}'
config={'model':'luma-fixture/fixture','skills':[str(skills.parent)],'providers':{'luma-fixture':{'package':'@opencode/ai/providers/openai-compatible','settings':{'baseURL':f'http://127.0.0.1:{model.server_port}/v1','apiKey':'local-fixture'},'models':{'fixture':{'name':'Luma Fixture','compatibility':{'reasoningField':'reasoning_content'},'capabilities':{'tools':True,'input':['text'],'output':['text'],'temperature':True},'limit':{'context':8192,'output':512},'variants':[{'id':'high','body':{'reasoning_effort':'high'}}]}}}}}
env={'PATH':os.environ['PATH'],'HOME':str(home),'XDG_DATA_HOME':str(home/'data'),'XDG_CONFIG_HOME':str(home/'config'),'XDG_STATE_HOME':str(home/'state'),'XDG_CACHE_HOME':str(home/'cache'),'OPENCODE_PASSWORD':password,'OPENCODE_CONFIG_CONTENT':json.dumps(config)}
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
opencode_bin=os.environ.get('LUMA_TEST_OPENCODE') or shutil.which('opencode') or 'opencode'
with (BASE/'server.log').open('w') as log:
 proc=subprocess.Popen([opencode_bin,'serve','--hostname','127.0.0.1','--port',str(port)],cwd=work,env=env,stdout=log,stderr=log)
 try:
  for _ in range(120):
   try:
    req=urllib.request.Request(url+'/api/info',headers={'Authorization':'Basic '+base64.b64encode(('opencode:'+password).encode()).decode()})
    with opener.open(req,timeout=1) as r: print('native',json.load(r)['version'],flush=True)
    break
   except OSError:
    if proc.poll() is not None:raise RuntimeError('native server exited')
    time.sleep(.25)
  else:raise RuntimeError('native startup timeout')
  testenv=os.environ.copy();testenv.update(JAVA_HOME=toolenv.JAVA_HOME,LUMA_TEST_OPENCODE_URL=url,LUMA_TEST_OPENCODE_PASSWORD=password,LUMA_TEST_OPENCODE_WORK=str(work))
  result=subprocess.run([str(ROOT/'gradlew'),':apps:gui:testDebugUnitTest','--tests','io.github.modao7067.luma.gui.agent.OpenCodeClientIntegrationTest','--offline','--console=plain'],cwd=ROOT,env=testenv)
  (BASE/'model-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
  assert result.returncode==0,'production transport native integration failed'
  assert len(requests)>=2,'fixture tool continuation did not execute'
  print('PASS production Kotlin client, native SSE, loopback model streaming + shell tool + skill + reconnect',flush=True)
 finally:
  proc.terminate()
  try:proc.wait(timeout=8)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
  model.shutdown();model.server_close()
