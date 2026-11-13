#!/usr/bin/env python3
import argparse,http.server,json,os,pathlib,re,sys,tempfile,threading,time
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
from luma_cli.dsh import DshSession
class Model(http.server.BaseHTTPRequestHandler):
    requests=[]
    def log_message(self,*a):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));self.requests.append(body)
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        frames=[{'type':'message_start','message':{'id':'fixture','type':'message','role':'assistant','model':body['model'],'content':[],'stop_reason':None,'usage':{'input_tokens':5,'output_tokens':0}}},
            {'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}},
            {'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'DSH fixture complete.'}},
            {'type':'content_block_stop','index':0},
            {'type':'message_delta','delta':{'stop_reason':'end_turn','stop_sequence':None},'usage':{'output_tokens':4}},
            {'type':'message_stop'}]
        for frame in frames:
            self.wfile.write(('event: '+frame['type']+'\ndata: '+json.dumps(frame)+'\n\n').encode());self.wfile.flush()
def run(unsupported_permissions=False, without_permission_service=False):
    if unsupported_permissions: DshSession.supported_acp_version = re.compile(r"(?!)")
    with tempfile.TemporaryDirectory(prefix='luma-dsh-roundtrip-') as tmp:
        home=pathlib.Path(tmp);work=home/'work';work.mkdir()
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
        os.environ.clear();os.environ.update(HOME=tmp,PATH='/usr/local/bin:/usr/bin:/bin',LANG='C.UTF-8',DSH_HOME=tmp+'/dsh',XDG_CONFIG_HOME=tmp+'/config',XDG_DATA_HOME=tmp+'/data',XDG_CACHE_HOME=tmp+'/cache',
            DEEPSEEK_API_KEY='luma-fixture-not-real',DEEPSEEK_BASE_URL=f'http://127.0.0.1:{server.server_port}',NO_COLOR='1',DO_NOT_TRACK='1')
        if without_permission_service:
            (home/'dsh').mkdir()
            (home/'dsh/cordis.patch.yml').write_text('- id: permission\n  disabled: true\n')
        manager=Manager(home/'bridge')
        try:
            s=manager.connect({'id':'fixture','cli':'DeepSeek Harness','cwd':str(work)});native=s.data['nativeId']
            assert not s.data['capabilities']['queue']
            if unsupported_permissions or without_permission_service:
                assert s.data['permissions']==[]
                assert '权限扩展不可用' in s.data['notice']
                capability=s.initialize_info.get('_meta',{}).get('lumaPermissions',{})
                if without_permission_service: assert capability.get('protocol')==1 and capability.get('available') is False,capability
                else: assert capability=={},capability
                print('Native permission capability:',json.dumps(capability,ensure_ascii=False),'options:',s.data['permissions'])
            else: assert {p['id'] for p in s.data['permissions']} == {'read-only','workspace-write','danger-full-access'}
            s.configure({'effort':'off'});assert s.data['effort']=='off'
            s.submit({'submissionId':'fixture-prompt','text':'Reply with the fixture response.'})
            deadline=time.monotonic()+40
            while time.monotonic()<deadline:
                snap=s.snapshot()
                if not snap['running']:break
                time.sleep(.1)
            assert not snap['running'],snap
            assert any('DSH fixture complete.' in r['text'] for r in snap['messages']),snap
            assert snap['receipts']['fixture-prompt']=='accepted'
            s.peer.close();s=manager.connect({'id':'fixture','cli':'DeepSeek Harness','cwd':str(work),'nativeId':native})
            assert s.data['nativeId']==native
            assert s.data['effort']=='off'
            assert s.snapshot()['receipts']['fixture-prompt']=='accepted'
            print('PASS DSH native ACP prompt, text, receipt, model config, same-ID context resume; no history replay; local model requests',len(Model.requests))
        except Exception:
            for s in manager.sessions.values():
                if s.peer:print('isolated stderr',s.peer.diagnostic.decode(errors='replace'))
            raise
        finally:
            for s in manager.sessions.values():
                if s.peer:s.peer.close()
            server.shutdown();server.server_close()
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--unsupported-permissions',action='store_true',help='Exercise the standard ACP fallback without the DSH extension')
    p.add_argument('--without-native-permission-service',action='store_true',help='Exercise the same-process handshake when native service is absent')
    args=p.parse_args()
    run(args.unsupported_permissions,args.without_native_permission_service)
