#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse, fcntl, http.server, json, os, pathlib, pty, re, socket, struct, subprocess, tempfile, termios, threading, time, urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[3]
class Tui:
 def __init__(self,fixture,tid):
  self.raw=bytearray();self.lock=threading.Lock();self.master,slave=pty.openpty()
  fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',34,110,0,0))
  self.proc=subprocess.Popen([fixture.binary,'resume',tid,'--remote',fixture.listen,'--no-alt-screen','-C',str(fixture.work)],stdin=slave,stdout=slave,stderr=slave,env=fixture.env,cwd=fixture.work,start_new_session=True,preexec_fn=lambda:fcntl.ioctl(0,termios.TIOCSCTTY,0))
  os.close(slave);os.set_blocking(self.master,False)
  self.thread=threading.Thread(target=self.pump,daemon=True);self.thread.start();time.sleep(2)
  if self.proc.poll() is not None:raise RuntimeError('Fixture TUI exited: '+self.text())
 def pump(self):
  while self.proc.poll() is None:
   try:data=os.read(self.master,65536)
   except BlockingIOError:data=b''
   except OSError:return
   if data:
    with self.lock:self.raw.extend(data)
    for q,r in [(b'\x1b[6n',b'\x1b[1;1R'),(b'\x1b[c',b'\x1b[?62;4;22c'),(b'\x1b[>c',b'\x1b[>0;136;0c'),(b'\x1b]11;?',b'\x1b]11;rgb:1010/1010/1010\x1b\\')]:
     if q in data:os.write(self.master,r)
   time.sleep(.01)
 def text(self,start=0):
  with self.lock:raw=bytes(self.raw[start:])
  return re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',raw.decode(errors='replace'))
 def key(self,key):
  start=len(self.raw);os.write(self.master,key);time.sleep(.6);return self.text(start)
 def close(self):
  self.proc.terminate()
  try:self.proc.wait(timeout=5)
  except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait()
  os.close(self.master);self.thread.join(timeout=1)
class Fixture(http.server.BaseHTTPRequestHandler):
 owner=None;next_tool=None;next_count=1;hold=False;counter=0;requests=[];release=threading.Event();tui=None;actions=[]
 def log_message(self,*args):pass
 def reply(self,data):
  out=json.dumps(data).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(out)));self.end_headers();self.wfile.write(out)
 def do_GET(self):
  cls=type(self);url=urllib.parse.urlparse(self.path);p=urllib.parse.parse_qs(url.query)
  cls.actions.append(url.path)
  try:
   if url.path.startswith('/next/'):
    cls.next_tool=url.path.rsplit('/',1)[-1];cls.next_count=int(p.get('count',['1'])[0]);cls.hold=p.get('hold',['false'])[0]=='true';cls.release.clear();self.reply({'ok':True})
   elif url.path=='/release':cls.release.set();self.reply({'ok':True})
   elif url.path=='/tui/start':
    cls.tui=Tui(cls.owner,p['thread'][0]);self.reply({'ok':True})
   elif url.path=='/tui/open':
    self.reply({'text':cls.tui.key(b'\x1b[1;2D')})
   elif url.path=='/tui/submit':self.reply({'text':cls.tui.key(b'\r')})
   else:self.send_error(404)
  except Exception as e:self.reply({'error':repr(e)})
 def do_POST(self):
  cls=type(self);req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));cls.requests.append(req);n=len(cls.requests)
  if cls.next_tool:
   kind=cls.next_tool;cls.next_tool=None;cls.counter+=1
   qs=[{'title':f'Fixture question {cls.counter}.{i+1}?','options':['One','Two']} for i in range(cls.next_count)]
   if kind=='sync':qs=[{'id':'choice','header':'Fixture','question':'Blocking fixture question?','options':[{'label':'One','description':'First fixture answer'},{'label':'Two','description':'Second fixture answer'}]}]
   item={'id':f'fixture-call-{cls.counter}','type':'function_call','call_id':f'fixture_question_{cls.counter}','name':'request_user_input_async' if kind=='async' else 'request_user_input','arguments':json.dumps({'questions':qs})};hold=False
  else:
   item={'id':f'fixture-answer-{n}','type':'message','role':'assistant','status':'completed','content':[{'type':'output_text','text':f'Fixture finished {n}.','annotations':[]}]};hold=cls.hold;cls.hold=False
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  try:
   self.wfile.write(b'event: response.created\ndata: {"type":"response.created","response":{"id":"fixture_response","status":"in_progress"}}\n\n');self.wfile.flush()
   if hold:cls.release.wait(30)
   for event in [{'type':'response.output_item.added','output_index':0,'item':item},{'type':'response.output_item.done','output_index':0,'item':item},{'type':'response.completed','response':{'id':f'fixture_response_{n}','status':'completed','output':[item],'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}}]:
    self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):pass

def run(args,transport):
 out=pathlib.Path(tempfile.mkdtemp(prefix=f'{transport}-',dir=args.output));home=out/'home';home.mkdir(mode=0o700);work=out/'work';work.mkdir()
 sd=pathlib.Path(tempfile.mkdtemp(prefix='luma-q-'));sock=sd/'server.sock'
 Fixture.requests=[];Fixture.actions=[];Fixture.next_tool=None;Fixture.counter=0;Fixture.hold=False;Fixture.release.clear();Fixture.tui=None
 provider=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=provider.serve_forever,daemon=True).start()
 catalog=ROOT/'tools/tests/cli/fixtures/codex-questions-model.json'
 (home/'config.toml').write_text(f'''model="fixture"
model_provider="fixture"
model_catalog_json="{catalog}"
[model_providers.fixture]
name="Question fixture"
base_url="http://127.0.0.1:{provider.server_port}/v1"
wire_api="responses"
requires_openai_auth=false
[projects."{work}"]
trust_level="trusted"
''')
 env={k:v for k,v in os.environ.items() if 'TOKEN' not in k and 'API_KEY' not in k};env.update(HOME=str(home),CODEX_HOME=str(home),TERM='xterm-256color',JAVA_HOME=toolenv.JAVA_HOME)
 if transport=='unix':listen='unix://'+str(sock)
 else:
  with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
  listen=f'ws://127.0.0.1:{port}'
 info=type('Owner',(),{'binary':args.codex,'work':work,'env':env,'listen':listen})();Fixture.owner=info
 env.update(LUMA_TEST_QUESTION_ENDPOINT=listen,LUMA_TEST_QUESTION_BINARY=args.codex,LUMA_TEST_QUESTION_WORK=str(work),LUMA_TEST_QUESTION_CONTROL=f'http://127.0.0.1:{provider.server_port}',LUMA_TEST_QUESTION_OUTPUT=str(out))
 gradle_env=os.environ.copy();gradle_env.update({k:v for k,v in env.items() if k.startswith('LUMA_TEST_QUESTION_') or k=='JAVA_HOME'})
 result=None
 with (out/'server.log').open('w') as log:
  owner=subprocess.Popen([args.codex,'app-server','--listen',listen],cwd=work,env=env,stdout=log,stderr=log)
  try:
   for _ in range(100):
    if owner.poll() is not None:raise RuntimeError('Private owner exited')
    if transport=='unix' and sock.exists():break
    if transport=='ws':
     try:
      with socket.create_connection(('127.0.0.1',port),timeout=.1):break
     except OSError:pass
    time.sleep(.1)
   command=[str(ROOT/'gradlew'),'-I',str(ROOT/'tools/tests/ui/compose/compose-tests.gradle'),':apps:gui:testDebugUnitTest','--offline','--no-daemon','--tests','*CodexQuestionsNativeIntegrationTest']
   if args.local_tests:
    for test in ['CodexAsyncQuestionsTest','CodexContractTest','HydrationBoundaryTest','CodexAsyncQuestionsControllerTest','SubmissionLedgerTest','SessionTitlePolicyTest','SessionOutboxStoreTest']:
     command.extend(['--tests','*'+test])
   result=subprocess.run(command,cwd=ROOT,env=gradle_env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
   (out/'gradle.log').write_text(result.stdout);print(result.stdout)
  finally:
   Fixture.release.set()
   if Fixture.tui:
    (out/'tui.ansi').write_bytes(Fixture.tui.raw);Fixture.tui.close()
   owner.terminate()
   try:owner.wait(timeout=5)
   except subprocess.TimeoutExpired:owner.kill();owner.wait()
 provider.shutdown();provider.server_close();sock.unlink(missing_ok=True);sd.rmdir()
 (out/'model-requests.json').write_text(json.dumps(Fixture.requests,indent=2));(out/'actions.json').write_text(json.dumps(Fixture.actions,indent=2))
 summary={'transport':transport,'version':subprocess.check_output([args.codex,'--version'],text=True).strip(),'gradleExit':result.returncode,'modelRequests':len(Fixture.requests),'scope':'Official owner/TUI and production Kotlin controller; isolated HOME and local fake provider only'}
 (out/'result.json').write_text(json.dumps(summary,indent=2));print(out);print(json.dumps(summary));assert result.returncode==0,'See '+str(out/'gradle.log')
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--codex',default='/root/.codex/packages/app-server-daemon/releases/0.166.0-aarch64-unknown-linux-musl/bin/codex');p.add_argument('--transport',choices=['unix','ws'],default='unix');p.add_argument('--local-tests',action='store_true');p.add_argument('--output',type=pathlib.Path,default=ROOT/'verification/gui-codex-questions-20261104');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);run(a,a.transport)
if __name__=='__main__':main()
