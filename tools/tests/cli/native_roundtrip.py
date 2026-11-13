#!/usr/bin/env python3
import http.server, json, os, pathlib, sys, tempfile, threading, time
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
class Model(http.server.BaseHTTPRequestHandler):
    requests=[]
    def log_message(self,*a):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.requests.append(body)
        done=any(m.get('role')=='tool' for m in body['messages'])
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        def emit(delta,finish=None):
            frame={'id':'fixture','object':'chat.completion.chunk','created':1,'model':'fixture','choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
            self.wfile.write(('data: '+json.dumps(frame)+'\n\n').encode());self.wfile.flush()
        emit({'role':'assistant'})
        if done:
            emit({'content':'Fixture roundtrip complete.'});emit({},'stop')
        else:
            emit({'tool_calls':[{'index':0,'id':'fixture-read','type':'function','function':{'name':'read','arguments':json.dumps({'path':'fixture.txt'})}}]});emit({},'tool_calls')
        self.wfile.write(b'data: [DONE]\n\n');self.wfile.flush()

def run(cli):
    with tempfile.TemporaryDirectory(prefix='luma-native-roundtrip-') as home:
        home=pathlib.Path(home);work=home/'work';work.mkdir();(work/'fixture.txt').write_text('owned fixture file\n')
        os.environ.clear();os.environ.update(HOME=str(home),PATH=str(ROOT/'verification/gui-cli-adapters-20261102/tooling')+':/usr/local/bin:/usr/bin:/bin',LANG='C.UTF-8',
            PI_CODING_AGENT_DIR=str(home/'agent'),XDG_CONFIG_HOME=str(home/'config'),XDG_DATA_HOME=str(home/'data'),XDG_CACHE_HOME=str(home/'cache'),PI_NO_TITLE='1',NO_COLOR='1',DO_NOT_TRACK='1')
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
        agent=home/'agent';agent.mkdir()
        (agent/'models.json').write_text(json.dumps({'providers':{'fixture':{'baseUrl':f'http://127.0.0.1:{server.server_port}/v1','apiKey':'fixture-not-a-real-key','api':'openai-completions',
            'models':[{'id':'fixture','name':'Fixture','reasoning':False,'input':['text'],'cost':{'input':0,'output':0,'cacheRead':0,'cacheWrite':0},'contextWindow':32000,'maxTokens':1024}]}}}))
        manager=Manager(home/'bridge')
        try:
            s=manager.connect({'id':'roundtrip','cli':cli,'cwd':str(work),'model':'fixture/fixture'})
            native=s.data['nativeId']
            assert s.data['permissions']==[]
            assert not s.data['capabilities']['images']
            s.submit({'submissionId':'fixture-send','text':'Read fixture.txt then answer.'})
            deadline=time.monotonic()+45
            while time.monotonic()<deadline:
                snap=s.snapshot()
                if any('Fixture roundtrip complete.' in r['text'] for r in snap['messages']) and not snap['running']:break
                if snap['requests']:raise AssertionError('Unexpected native request: '+str(snap['requests']))
                time.sleep(.1)
            else:raise AssertionError('No settled completion: '+str(s.snapshot()))
            assert snap['receipts']['fixture-send']=='accepted'
            assert any(r['role']=='tool' and r.get('status')=='completed' for r in snap['messages'])
            count=len(Model.requests);s.submit({'submissionId':'fixture-send','text':'must not duplicate'});assert len(Model.requests)==count
            s.peer.proc.stdin.close();s.peer.proc.wait(timeout=15)
            s=manager.connect({'id':'roundtrip','cli':cli,'cwd':str(work),'nativeId':native})
            assert s.data['nativeId']==native
            assert any('Fixture roundtrip complete.' in r['text'] for r in s.snapshot()['messages'])
            print('PASS',cli,'native tool/read, text, settlement, receipt deduplication, persisted history + same-ID resume; model requests',len(Model.requests))
        except Exception:
            for s in manager.sessions.values():
                if s.peer:print('isolated stderr',s.peer.diagnostic.decode(errors='replace'))
            raise
        finally:
            for s in manager.sessions.values():
                if s.peer:s.peer.close()
            server.shutdown();server.server_close()
if __name__=='__main__':run(sys.argv[1])
