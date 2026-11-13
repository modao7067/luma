#!/usr/bin/env python3
import argparse,http.server,json,os,pathlib,sys,tempfile,threading,time
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).absolute().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
class Model(http.server.BaseHTTPRequestHandler):
    held=threading.Event();release=threading.Event();calls=0
    def log_message(self,*a):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));Model.calls+=1
        Model.held.set();Model.release.wait(40)
        frames=[{'type':'message_start','message':{'id':'owned-fixture','type':'message','role':'assistant','model':body['model'],'content':[],'stop_reason':None,'usage':{'input_tokens':5,'output_tokens':0}}},
            {'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}},
            {'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'LUMA_DELIVERY_DONE'}},
            {'type':'content_block_stop','index':0},{'type':'message_delta','delta':{'stop_reason':'end_turn','stop_sequence':None},'usage':{'output_tokens':4}},{'type':'message_stop'}]
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        for frame in frames:self.wfile.write(('event: '+frame['type']+'\ndata: '+json.dumps(frame)+'\n\n').encode());self.wfile.flush()
def main(args):
    result={'environment':args.environment,'provider':'deterministic localhost fixture','paidInference':False,'rawReceipts':[]}
    with tempfile.TemporaryDirectory(prefix='luma-dsh-delivery-') as tmp:
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
        os.environ.clear();os.environ.update(HOME=tmp,DSH_HOME=tmp+'/dsh',PATH='/usr/local/bin:/usr/bin:/bin',LANG='C.UTF-8',XDG_CONFIG_HOME=tmp+'/config',XDG_DATA_HOME=tmp+'/data',XDG_CACHE_HOME=tmp+'/cache',DEEPSEEK_API_KEY='public-local-fixture',DEEPSEEK_BASE_URL=f'http://127.0.0.1:{server.server_port}',DSH_TELEMETRY_MODE='OFF')
        manager=Manager(pathlib.Path(tmp)/'bridge')
        try:
            s=manager.connect({'id':'delivery','cli':'DeepSeek Harness','cwd':tmp})
            assert s.initialize_info['_meta']['lumaSubmissions']['protocol']==1
            event=s.peer.event
            def observe(frame):
                if frame.get('method')=='luma/submissions/accepted':result['rawReceipts'].append(frame)
                event(frame)
            s.peer.event=observe
            start=time.monotonic();s.submit({'submissionId':'delivery-owned','text':'你好'})
            assert Model.held.wait(20),'native model did not start'
            deadline=time.monotonic()+5
            while s.snapshot()['receipts'].get('delivery-owned')!='accepted' and time.monotonic()<deadline:time.sleep(.01)
            before=s.snapshot();result['whileModelHeld']=before;result['confirmationSeconds']=time.monotonic()-start
            assert before['running'] and before['receipts']['delivery-owned']=='accepted',before
            assert not any('LUMA_DELIVERY_DONE' in x.get('text','') for x in before['messages'])
            assert len(result['rawReceipts'])==1 and result['rawReceipts'][0]['params']['nativeMessageId']
            assert len([x for x in before['messages'] if x['role']=='user'])==1
            s.submit({'submissionId':'delivery-owned','text':'must-not-resend'})
            assert Model.calls==1
            Model.release.set();deadline=time.monotonic()+20
            while s.data['running'] and time.monotonic()<deadline:time.sleep(.02)
            assert not s.data['running'];result['afterTurn']=s.snapshot();result['modelCalls']=Model.calls
            assert any('LUMA_DELIVERY_DONE' in x.get('text','') for x in s.rows.values())
            s.call('session/close',{'sessionId':s.data['nativeId']});s.peer.proc.stdin.close();s.peer.proc.wait(timeout=15)
            result['result']='PASS'
        except Exception as exc:result.update(result='FAIL',error=str(exc));raise
        finally:
            Model.release.set()
            for owned in manager.sessions.values():
                if owned.peer:owned.peer.close()
            server.shutdown();server.server_close();pathlib.Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2))
            print(json.dumps({'result':result.get('result'),'confirmationSeconds':result.get('confirmationSeconds'),'output':args.output},ensure_ascii=False))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--environment',default='Ubuntu chroot isolated HOME')
    main(p.parse_args())
