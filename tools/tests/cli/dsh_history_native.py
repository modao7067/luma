#!/usr/bin/env python3
import argparse,http.server,json,os,pathlib,sys,tempfile,threading,time,subprocess
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description='Actual installed DSH history restore, isolated HOME and localhost provider; no paid inference.');p.add_argument('--bridge',default=str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'));p.add_argument('--home');p.add_argument('--output',required=True);p.add_argument('--environment',default='Ubuntu chroot isolated HOME');a=p.parse_args();sys.path.insert(0,a.bridge)
from luma_cli.server import Manager
from luma_cli.transport import Rejected
class Model(http.server.BaseHTTPRequestHandler):
    requests=[];work='';held=threading.Event();release=threading.Event()
    def log_message(self,*a):pass
    def do_POST(self):
        b=json.loads(self.rfile.read(int(self.headers['Content-Length'])));Model.requests.append(b)
        last=b.get('messages',[])[-1].get('content',[]);toolresult=isinstance(last,list) and any(x.get('type')=='tool_result' for x in last if isinstance(x,dict));idx=len(Model.requests)
        if 'HOLD_NEXT' in str(last) and not toolresult:Model.held.set();Model.release.wait(30)
        blocks=[({'type':'thinking','thinking':''},{'type':'thinking_delta','thinking':'OWNED_NATIVE_REASONING'})]
        if not toolresult and 'READ_FIXTURE' in str(last):
            missing='READ_MISSING' in str(last);path=Model.work+'/owned-file'+('-missing' if missing else '')
            blocks.append(({'type':'tool_use','id':'owned-read-'+str(idx),'name':'read','input':{}},{'type':'input_json_delta','partial_json':json.dumps({'file_path':path})}));stop='tool_use'
        else:blocks.append(({'type':'text','text':''},{'type':'text_delta','text':'OWNED_NATIVE_FINAL_'+str(idx)}));stop='end_turn'
        f=[{'type':'message_start','message':{'id':'history-native-'+str(idx),'type':'message','role':'assistant','model':b['model'],'content':[],'stop_reason':None,'usage':{'input_tokens':5,'output_tokens':0}}}]
        for i,(block,delta) in enumerate(blocks):f.extend([{'type':'content_block_start','index':i,'content_block':block},{'type':'content_block_delta','index':i,'delta':delta},{'type':'content_block_stop','index':i}])
        f.extend([{'type':'message_delta','delta':{'stop_reason':stop,'stop_sequence':None},'usage':{'output_tokens':4}},{'type':'message_stop'}]);self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        for x in f:self.wfile.write(('event: '+x['type']+'\ndata: '+json.dumps(x)+'\n\n').encode());self.wfile.flush()
temporary=tempfile.TemporaryDirectory(prefix='luma-dsh-history-') if not a.home else None
home=pathlib.Path(a.home or temporary.name);home.mkdir(parents=True,exist_ok=True);work=home/'work';work.mkdir(exist_ok=True);(work/'owned-file').write_text('OWNED_NATIVE_FILE_CONTENT\n');Model.work=str(work)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=server.serve_forever,daemon=True).start()
os.environ.clear();os.environ.update(HOME=str(home),DSH_HOME=str(home/'dsh'),PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',LANG='C.UTF-8',XDG_CONFIG_HOME=str(home/'config'),XDG_DATA_HOME=str(home/'data'),XDG_CACHE_HOME=str(home/'cache'),DEEPSEEK_API_KEY='public-local-fixture',DEEPSEEK_BASE_URL=f'http://127.0.0.1:{server.server_port}',DSH_TELEMETRY_MODE='OFF')
(home/'dsh').mkdir(exist_ok=True);(home/'dsh/cordis.patch.yml').write_text("- id: session-persistence-jsonl\n  config:\n    root: !!js dshHomePath('sessions')\n    compression: none\n- id: session-telemetry-otel\n  disabled: true\n")
report={'environment':a.environment,'nativeVersion':subprocess.check_output(['dsh','--version'],text=True).strip(),'provider':'deterministic localhost Messages fixture','paidInference':False};manager=Manager(home/'bridge');s=None;params={'id':'history','cli':'DeepSeek Harness','cwd':str(work)}
def wait():
    deadline=time.monotonic()+45
    while s.data['running'] and time.monotonic()<deadline:
        assert not s.data['requests'],'unexpected approval';time.sleep(.02)
    assert not s.data['running'],s.snapshot()
def close():
    s.call('session/close',{'sessionId':s.data['nativeId']});s.peer.proc.stdin.close();s.peer.proc.wait(timeout=20);s.peer.reader_thread.join(timeout=2)
try:
    s=manager.connect(params);native=s.data['nativeId'];report['nativeId']=native;report['handshake']=s.initialize_info
    assert s.initialize_info['_meta']['lumaHistory']=={'protocol':1,'available':True}
    s.configure({'effort':'off'})
    for ident,text in [('read-ok','READ_FIXTURE'),('read-missing','READ_FIXTURE READ_MISSING')]:
        s.submit({'submissionId':ident,'text':text});wait()
    before=s.snapshot();report['before']=before;assert any(r['role']=='reasoning' for r in before['messages']);assert before['title']=='READ_FIXTURE'
    calls=len(Model.requests);peer=s.peer;same=manager.connect({**params,'nativeId':native});assert same.peer is peer and same.snapshot()['messages']==before['messages'];assert len(Model.requests)==calls;report['liveObserverSamePeer']=True
    close();manager=Manager(home/'bridge');s=manager.connect({**params,'nativeId':native});restored=s.snapshot();report['restored']=restored
    assert restored['nativeId']==native and restored['effort']=='off';assert len(Model.requests)==calls;assert not restored['error'] and restored['historyReady'],restored
    assert restored['receipts']==before['receipts'];assert {r['id'] for r in restored['messages'] if r['role']=='user'}=={'read-ok','read-missing'}
    assert sum(r['role']=='user' for r in restored['messages'])==2;assert any(r['role']=='reasoning' and r['text']=='OWNED_NATIVE_REASONING' for r in restored['messages'])
    tools=[r for r in restored['messages'] if r['role']=='tool'];assert len(tools)==2;assert {r['status'] for r in tools}=={'completed','failed'};assert any('OWNED_NATIVE_FILE_CONTENT' in r.get('outcome','') for r in tools)
    ids=[r['id'] for r in restored['messages']];assert len(ids)==len(set(ids));assert all('eventSeq' in r for r in restored['messages'])
    again=s.call('luma/history/read',{'sessionId':native});assert again['sessionId']==native;assert len(Model.requests)==calls
    try:s.call('luma/history/read',{'sessionId':'foreign'});raise AssertionError('foreign session read')
    except Rejected as e:report['foreignReadRejection']=str(e)
    close();manager=Manager(home/'bridge');s=manager.connect({**params,'nativeId':native});assert s.snapshot()['messages']==restored['messages'];report['secondRestoreStableIds']=True
    close()
    config_path=home/'bridge/history.json';saved_config=json.loads(config_path.read_text());legacy_config=json.loads(config_path.read_text())
    legacy_config.pop('submissionNativeIds',None);legacy_config['receipts']['read-missing']='unknown';legacy_config['receipts']['legacy-no-native-message']='unknown';config_path.write_text(json.dumps(legacy_config))
    manager=Manager(home/'bridge');s=manager.connect({**params,'nativeId':native});legacy=s.snapshot();report['legacyUnmappedReceipts']=legacy
    assert not legacy['error'];assert legacy['receipts']['read-ok']=='accepted';assert legacy['receipts']['read-missing']=='unknown';assert legacy['receipts']['legacy-no-native-message']=='unknown'
    assert all(r['id'].startswith('dsh-message-') for r in legacy['messages'] if r['role']=='user');assert len(Model.requests)==calls
    assert 'submissionNativeIds' not in s.config;close();config_path.write_text(json.dumps(saved_config))
    manager=Manager(home/'bridge');s=manager.connect({**params,'nativeId':native});assert s.snapshot()['messages']==restored['messages']
    s.submit({'submissionId':'continue','text':'HOLD_NEXT'});assert Model.held.wait(20)
    try:s.call('luma/history/read',{'sessionId':native});raise AssertionError('busy native history read')
    except Rejected as e:report['busyReadRejection']=str(e)
    live=s.snapshot();assert live['running'] and live['receipts']['continue']=='accepted';report['whileModelHeld']=live
    assert any('READ_FIXTURE' in str(x) for x in Model.requests[-1].get('messages',[]));report['sameIdOriginalContext']=True
    Model.release.set();wait();after=s.snapshot();report['afterContinue']=after
    assert sum(r['role']=='user' for r in after['messages'])==3;assert len(after['messages'])==len({r['id'] for r in after['messages']});close();report['result']='PASS'
finally:
    Model.release.set()
    if s and s.peer:s.peer.close()
    server.shutdown();server.server_close();report['modelRequests']=len(Model.requests);pathlib.Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:report[k] for k in ['result','nativeVersion','liveObserverSamePeer','secondRestoreStableIds','sameIdOriginalContext','modelRequests'] if k in report},ensure_ascii=False))

if temporary: temporary.cleanup()
