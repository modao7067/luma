#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse,http.server,json,os,pathlib,shutil,socket,subprocess,tempfile,threading,time
ROOT=pathlib.Path(__file__).resolve().parents[3]
class Model(http.server.BaseHTTPRequestHandler):
 requests=[];release=threading.Event();finish_release=threading.Event()
 def log_message(self,*a):pass
 def do_GET(self):
  if self.path=='/release-text':self.release.set()
  elif self.path=='/finish':self.release.set();self.finish_release.set()
  else:self.send_error(404);return
  self.send_response(200);self.send_header('Content-Length','2');self.end_headers();self.wfile.write(b'{}')
 def do_POST(self):
  req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));type(self).requests.append(req);n=len(self.requests)
  child=req.get('client_metadata',{}).get('thread_id')!=req.get('client_metadata',{}).get('session_id')
  item=({'id':'fixture-spawn','type':'function_call','call_id':'fixture-spawn-call','namespace':'multi_agent_v1','name':'spawn_agent','arguments':json.dumps({'task_name':'fixture_child','message':'Return the deterministic native child reasoning and output.'})} if n==1 else {'id':f'fixture-output-{n}','type':'message','role':'assistant','status':'completed','content':[{'type':'output_text','text':'Native child streamed output' if child else 'Native parent output','annotations':[]}]})
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def emit(e):self.wfile.write(('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n').encode());self.wfile.flush()
  try:
   emit({'type':'response.created','response':{'id':f'resp_{n}','status':'in_progress'}})
   reason={'id':'fixture-reason','type':'reasoning','summary':[{'type':'summary_text','text':'Native child reasoning'}]}
   if child:
    emit({'type':'response.output_item.added','output_index':0,'item':dict(reason,summary=[])})
    emit({'type':'response.reasoning_summary_text.delta','output_index':0,'item_id':reason['id'],'summary_index':0,'delta':'Native child reasoning'})
    emit({'type':'response.output_item.done','output_index':0,'item':reason})
    self.release.wait(60)
   idx=1 if child else 0
   emit({'type':'response.output_item.added','output_index':idx,'item':dict(item,content=[]) if child else item})
   if child:
    emit({'type':'response.output_text.delta','output_index':idx,'item_id':item['id'],'content_index':0,'delta':'Native child streamed output'})
    self.finish_release.wait(60)
   emit({'type':'response.output_item.done','output_index':idx,'item':item})
   emit({'type':'response.completed','response':{'id':f'resp_{n}','status':'completed','output':([reason,item] if child else [item]),'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}})
  except (BrokenPipeError,ConnectionResetError):pass

def main():
 p=argparse.ArgumentParser();p.add_argument('--codex',default=os.environ.get('LUMA_TEST_CODEX') or shutil.which('codex') or 'codex');p.add_argument('--output',type=pathlib.Path,default=ROOT/'artifacts/test-runs/subagent-observer');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 run=pathlib.Path(tempfile.mkdtemp(prefix='production-native-',dir=a.output));home=run/'home';home.mkdir(mode=0o700);work=run/'work';work.mkdir()
 provider=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=provider.serve_forever,daemon=True).start()
 (home/'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
model_catalog_json="{ROOT/'tools/tests/cli/fixtures/codex-subagent-model.json'}"
[model_providers.fixture]
name="Subagent observer fixture"
base_url="http://127.0.0.1:{provider.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
 env={k:v for k,v in os.environ.items() if 'TOKEN' not in k and 'API_KEY' not in k};env.update(HOME=str(home),CODEX_HOME=str(home))
 with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
 endpoint=f'ws://127.0.0.1:{port}'
 gradle_env=os.environ.copy();gradle_env.update(LUMA_TEST_SUBAGENT_ENDPOINT=endpoint,LUMA_TEST_SUBAGENT_WORK=str(work),LUMA_TEST_SUBAGENT_OUTPUT=str(run),LUMA_TEST_SUBAGENT_CONTROL=f'http://127.0.0.1:{provider.server_port}',JAVA_HOME=toolenv.JAVA_HOME)
 with (run/'server.log').open('w') as log:
  owner=subprocess.Popen([a.codex,'app-server','--listen',endpoint],cwd=work,env=env,stdout=log,stderr=log)
  try:
   for _ in range(100):
    try:
     with socket.create_connection(('127.0.0.1',port),timeout=.1):break
    except OSError:time.sleep(.05)
   with (run/'gradle.log').open('w') as output:r=subprocess.run([str(ROOT/'luma'),'test-ui','*CodexSubAgentNativeIntegrationTest*'],cwd=ROOT,env=gradle_env,stdout=output,stderr=subprocess.STDOUT)
   result={'version':subprocess.check_output([a.codex,'--version'],text=True).strip(),'gradleExit':r.returncode,'modelRequests':len(Model.requests),'ownerAliveAfterProductionTest':owner.poll() is None,'scope':'Official native child, production Controller/RPC/history, isolated HOME, loopback model only'}
  finally:
   Model.release.set();Model.finish_release.set();owner.terminate()
   try:owner.wait(timeout=5)
   except subprocess.TimeoutExpired:owner.kill();owner.wait()
 provider.shutdown();provider.server_close();(run/'result.json').write_text(json.dumps(result,indent=2));(run/'model-requests.json').write_text(json.dumps(Model.requests,indent=2));print(run);print(json.dumps(result,indent=2));raise SystemExit(result['gradleExit'])
if __name__=='__main__':main()
