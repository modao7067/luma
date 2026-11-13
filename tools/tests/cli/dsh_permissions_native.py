#!/usr/bin/env python3
import argparse,http.server,json,os,pathlib,sys,tempfile,threading,time
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
from luma_cli.transport import Peer,Rejected
class Model(http.server.BaseHTTPRequestHandler):
    calls=[];hold=threading.Event();release=threading.Event();work=''
    def log_message(self,*a):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));self.calls.append(body)
        last=body.get('messages',[])[-1].get('content',[])
        text=str(last)
        tool_result=isinstance(last,list) and any(b.get('type')=='tool_result' for b in last if isinstance(b,dict))
        execute='LUMA_EXEC_' in text and not tool_result
        if 'LUMA_HOLD' in text and not tool_result:self.hold.set();self.release.wait(30)
        block={'type':'text','text':''};delta={'type':'text_delta','text':'LUMA_DSH_FIXTURE_DONE'};stop='end_turn'
        if execute:
            args={'description':'Run the owned permission fixture printf','command':'printf LUMA_DSH_EXEC_OK','workdir':self.work,'timeout':10000}
            if 'LUMA_EXEC_ESCALATE' in text:args.update(sandbox_permissions='danger-full-access',justification='Allow this one fixture printf without the unavailable file sandbox.')
            block={'type':'tool_use','id':'fixture-bash-'+str(len(self.calls)),'name':'bash','input':{}}
            delta={'type':'input_json_delta','partial_json':json.dumps(args)};stop='tool_use'
        frames=[{'type':'message_start','message':{'id':'fixture-'+str(len(self.calls)),'type':'message','role':'assistant','model':body['model'],'content':[],'stop_reason':None,'usage':{'input_tokens':5,'output_tokens':0}}},
            {'type':'content_block_start','index':0,'content_block':block},{'type':'content_block_delta','index':0,'delta':delta},
            {'type':'content_block_stop','index':0},{'type':'message_delta','delta':{'stop_reason':stop,'stop_sequence':None},'usage':{'output_tokens':4}},{'type':'message_stop'}]
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        for frame in frames:self.wfile.write(('event: '+frame['type']+'\ndata: '+json.dumps(frame)+'\n\n').encode());self.wfile.flush()
def main(a):
    report={'environment':a.environment,'native':'DSH installed native package','provider':'deterministic localhost Messages fixture','paidInference':False,'phases':{},'controls':[],'privateUpdates':[]}
    with tempfile.TemporaryDirectory(prefix='luma-dsh-permissions-') as tmp:
        home=pathlib.Path(tmp);work=home/'work';work.mkdir();Model.work=str(work)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
        os.environ.clear();os.environ.update(HOME=tmp,PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',LANG='C.UTF-8',DSH_HOME=tmp+'/dsh',XDG_CONFIG_HOME=tmp+'/config',XDG_DATA_HOME=tmp+'/data',XDG_CACHE_HOME=tmp+'/cache',DEEPSEEK_API_KEY='public-local-fixture',DEEPSEEK_BASE_URL=f'http://127.0.0.1:{server.server_port}',NO_COLOR='1',DSH_TELEMETRY_MODE='OFF')
        (home/'dsh').mkdir();patch=home/'dsh/cordis.patch.yml'
        patch.write_text("- id: session-persistence-jsonl\n  config:\n    root: !!js dshHomePath('sessions')\n    compression: none\n- id: session-telemetry-otel\n  disabled: true\n")
        initial_patch=patch.read_text();manager=Manager(home/'bridge');s=None
        original_send=Peer.send
        def send(peer,frame):
            if frame.get('method','').startswith('luma/permissions/'):
                report['controls'].append(frame)
            return original_send(peer,frame)
        Peer.send=send
        def snapshot(name):report['phases'][name]=s.snapshot()
        def observe():
            original=s.peer.event
            def event(frame):
                if frame.get('method')=='luma/permissions/updated':report['privateUpdates'].append(frame)
                original(frame)
            s.peer.event=event
        def wait(timeout=45):
            end=time.monotonic()+timeout
            while time.monotonic()<end:
                for request in list(s.data['requests']):
                    raw=s.pending_requests[request['id']];tool=raw.get('params',{}).get('toolCall',{}).get('toolCallId')
                    row=s.rows.get('tool-'+str(tool),{})
                    if 'printf LUMA_DSH_EXEC_OK' not in row.get('input',''):raise AssertionError('Unexpected native approval outside the owned printf')
                    choice=next(c['id'] for c in request['choices'] if c['id']=='allow-once')
                    report.setdefault('approvals',[]).append({'id':request['id'],'choice':choice,'tool':row})
                    s.answer({'id':request['id'],'choice':choice})
                if not s.data['running']:return
                if s.peer.closed:raise AssertionError('Native CLI disconnected')
                time.sleep(.05)
            raise TimeoutError('Native fixture did not settle')
        def execute(name):
            before=set(s.rows);s.submit({'submissionId':name,'text':'LUMA_EXEC_'+name});wait();snapshot(name)
            rows=[r for k,r in s.rows.items() if k not in before and r.get('role')=='tool']
            report.setdefault('executions',{})[name]=rows
            return rows
        def close():
            if not s or not s.peer or s.peer.proc.poll() is not None:return
            s.call('session/close',{'sessionId':s.data['nativeId']})
            s.peer.proc.stdin.close();s.peer.proc.wait(timeout=20)
        try:
            s=manager.connect({'id':'fixture','cli':'DeepSeek Harness','cwd':str(work)});observe();native=s.data['nativeId'];report['nativeId']=native;report['handshake']=s.initialize_info
            snapshot('initial');assert s.data['permission']=='workspace-write',s.snapshot()
            assert {p['id'] for p in s.data['permissions']}=={'read-only','workspace-write','danger-full-access','auto','plan'}
            assert not any(f['method'].endswith('/set') for f in report['controls'])
            s.configure({'permission':'plan'});snapshot('plan');assert s.data['permission']=='plan'
            s.configure({'permission':'auto'});snapshot('auto');assert s.data['permission']=='auto'
            s.configure({'permission':'workspace-write'});snapshot('workspace-write');assert s.data['permission']=='workspace-write'
            execute('WORKSPACE')
            s.configure({'permission':'read-only'});snapshot('read-only');assert s.data['permission']=='read-only'
            execute('READ_ONLY')
            close();s=manager.connect({'id':'fixture','cli':'DeepSeek Harness','cwd':str(work),'nativeId':native});observe();snapshot('same-id-restored');assert s.data['permission']=='read-only';assert s.data['nativeId']==native
            s.configure({'permission':'workspace-write'});Model.hold.clear();Model.release.clear()
            s.submit({'submissionId':'held','text':'LUMA_HOLD'});assert Model.hold.wait(20)
            try:s.call('luma/permissions/set',{'sessionId':native,'value':'read-only'});raise AssertionError('Busy native session accepted a permission change')
            except Rejected as e:report['busyRejection']=str(e)
            Model.release.set();wait();assert s.data['permission']=='workspace-write'
            for params in ({'sessionId':native,'value':'invented'},{'sessionId':'foreign','value':'danger-full-access'}):
                try:s.call('luma/permissions/set',params);raise AssertionError('Invalid native set succeeded')
                except Rejected as e:report.setdefault('invalidRejections',[]).append(str(e))
            rows=execute('ESCALATE');assert any(r.get('status')=='completed' and 'LUMA_DSH_EXEC_OK' in r.get('outcome','') for r in rows),rows
            assert s.data['permission']=='workspace-write';report['oneShotKeepsWorkspacePreset']=True
            s.configure({'permission':'danger-full-access'});snapshot('full-access-explicit')
            count=len(report.get('approvals',[]));rows=execute('FULL_ACCESS')
            assert any(r.get('status')=='completed' and 'LUMA_DSH_EXEC_OK' in r.get('outcome','') for r in rows),rows
            report['fullAccessApprovalCount']=len(report.get('approvals',[]))-count
            s.configure({'permission':'read-only'});close()
            events=[]
            for path in (home/'dsh/sessions').rglob('*'):
                if path.is_file():
                    for line in path.read_text(errors='replace').splitlines():
                        try:event=json.loads(line)
                        except ValueError:continue
                        if event.get('type') in ('permission/preset','sandbox/mode','approval/policy'):events.append(event)
            report['nativePermissionEvents']=events;assert events
            assert patch.read_text()==initial_patch;report['userConfigUnchanged']=True;report['result']='PASS'
        except Exception as e:
            report['result']='FAIL';report['error']=str(e)
            if s:snapshot('failure')
            raise
        finally:
            Peer.send=original_send;Model.release.set()
            for owned in manager.sessions.values():
                if owned.peer:owned.peer.close()
            server.shutdown();server.server_close()
            report['modelRequestCount']=len(Model.calls)
            out=pathlib.Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps({'result':report.get('result'),'environment':a.environment,'output':str(out)},ensure_ascii=False))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True);p.add_argument('--environment',default='Ubuntu 26.04.1 ARM64 chroot, isolated HOME')
    main(p.parse_args())
