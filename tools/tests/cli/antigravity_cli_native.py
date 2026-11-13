#!/usr/bin/env python3
import sys
sys.dont_write_bytecode = True
import argparse
import json
import os
import pathlib
import resource
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.antigravity_cli import AntigravityCliSession, PROTOCOL
from luma_cli.server import Manager

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agy',type=pathlib.Path,default=pathlib.Path('/root/.local/bin/agy'))
    parser.add_argument('--output',type=pathlib.Path,required=True)
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    for n in ('home','workspace','tmp'):(out/n).mkdir(mode=0o700)
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    requests=[];frames=[];sessions=[];checks={};phase={'cancel':False,'cancel_sent':False};proof=out/'workspace'
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*unused):pass
        def do_GET(self):
            raw=b'{"models":[]}';self.send_response(200);self.end_headers();self.wfile.write(raw)
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append({'path':self.path,'body':body})
            contents=body.get('contents',[])
            has_response=any('functionResponse' in part for part in (contents[-1].get('parts',[]) if contents else []))
            want_tool=(phase['cancel'] and not phase['cancel_sent']) or (not phase['cancel'] and not has_response)
            if body.get('tools') and want_tool:
                command=('printf started > cancel-started; sleep 10; printf finished > cancel-finished' if phase['cancel']
                         else 'printf native-tool-ok > native-tool-proof; printf native-tool-output')
                parts=[{'text':'fixture internal thought','thought':True},
                    {'functionCall':{'name':'run_command','args':{'CommandLine':command,
                     'WaitMsBeforeAsync':10000,'toolSummary':'Isolated native fixture command'}}}]
                if phase['cancel']:phase['cancel_sent']=True
            else:parts=[{'text':'native fixture response'}]
            reply={'candidates':[{'content':{'role':'model','parts':parts},'finishReason':'STOP'}],
                'usageMetadata':{'promptTokenCount':10,'candidatesTokenCount':3,'totalTokenCount':13}}
            raw=('data: '+json.dumps(reply)+'\n\n').encode()
            self.send_response(200);self.send_header('Content-Type','text/event-stream')
            self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server_thread=threading.Thread(target=server.serve_forever,daemon=True);server_thread.start()
    env={'HOME':str(out/'home'),'GEMINI_HOME':str(out/'home/.gemini'),'TMPDIR':str(out/'tmp'),
        'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','NO_PROXY':'localhost,127.0.0.1,::1',
        'GEMINI_API_KEY':'fixture-not-a-real-key','GOOGLE_GEMINI_BASE_URL':f'http://127.0.0.1:{server.server_port}'}
    original_event=AntigravityCliSession.event
    def event(s,f):frames.append(f);return original_event(s,f)
    def discover(s):s.update(models=[{'id':'gemini-3.8-flash-low','label':'Isolated fixture model','efforts':[],'supportsImages':False}])
    def wait(predicate,seconds=20):
        end=time.monotonic()+seconds
        while not predicate() and time.monotonic()<end:time.sleep(.02)
        assert predicate(),'native condition timed out'
    try:
        with patch.dict(os.environ,env,clear=True),patch.object(AntigravityCliSession,'argv',
            [str(args.agy.resolve()),'--settings','{"modelProvider":"gemini"}']),\
            patch.object(AntigravityCliSession,'discover',discover),patch.object(AntigravityCliSession,'event',event):
            manager=Manager(out/'index')
            s=manager.connect({'id':'fixture','cli':'Antigravity','cwd':str(proof),'model':'gemini-3.8-flash-low'})
            sessions.append(s);native=s.data['nativeId']
            assert s.config['nativeProtocol']==PROTOCOL and s.data['permission']=='always-proceed'
            checks['realNativeFullAccessInit']=True
            s.submit({'submissionId':'first','text':'Run the isolated fixture tool and answer.'})
            wait(lambda:not s.data['running'])
            assert s.config['receipts']['first']=='accepted' and (proof/'native-tool-proof').read_text()=='native-tool-ok'
            assert any(r.get('toolName')=='run_command' and r.get('status')=='completed' for r in s.rows.values())
            assert any('native-tool-output' in r.get('outcome','') for r in s.rows.values())
            assert any('native fixture response' in r.get('text','') for r in s.rows.values())
            checks['realToolAndStreamReceipt']=True
            assert not any(r.get('kind')=='reasoning' for r in s.rows.values())
            checks['thinkingTokensDidNotInventThoughtText']=True
            before=len(s.rows);s.peer.close();assert s.peer.proc.returncode==0
            restored=Manager(out/'index').connect({'id':'fixture','cli':'Antigravity','cwd':str(proof),'nativeId':native})
            sessions.append(restored)
            assert restored.data['nativeId']==native and len(restored.rows)==before
            checks['sameNativeIdResumeWithLocalProjection']=True
            restored.submit({'submissionId':'second','text':'Answer again in the same isolated native conversation.'})
            wait(lambda:not restored.data['running'])
            assert restored.config['receipts']['second']=='accepted' and restored.data['nativeId']==native
            checks['realSecondTurnAfterResume']=True
            phase['cancel']=True
            restored.submit({'submissionId':'stop','text':'Run the isolated long fixture command.'})
            wait(lambda:(proof/'cancel-started').exists() or not restored.data['running'])
            assert (proof/'cancel-started').exists(),'long tool did not start'
            stopped_peer=restored.peer
            restored.stop();assert stopped_peer.proc.poll() is not None
            time.sleep(.15)
            assert not (proof/'cancel-finished').exists(),'native command ran to completion despite stop'
            checks['nativeProcessCollectedAfterStop']=True
            checks['longToolDidNotReachFinishedMarker']=True
            checks['stopNativeSettlementObserved']=restored.current_submission is None
            phase['cancel']=False
            resumed=restored
            assert resumed.data['connected'] and resumed.peer is not stopped_peer
            assert resumed.data['nativeId']==native
            resumed.submit({'submissionId':'after-stop','text':'Answer the next message after the interrupted fixture turn.'})
            wait(lambda:not resumed.data['running'])
            assert resumed.config['receipts']['after-stop']=='accepted'
            assert len([r for r in resumed.rows.values() if r.get('role')=='user' and r['id']=='after-stop'])==1
            assert resumed.data['nativeId']==native
            checks['sameNativeIdNextSendAfterNativeInterrupt']=True
            checks['stoppedNativeProcess']={'pid':stopped_peer.proc.pid,'exit':stopped_peer.proc.poll()}
            checks['passed']=True
    finally:
        for s in sessions:
            if s.peer:s.peer.close()
        for index,s in enumerate(sessions):
            if s.peer:(out/f'native-{index}.stderr').write_bytes(s.peer.diagnostic)
        server.shutdown();server.server_close();server_thread.join(2)
        (out/'frames.json').write_text(json.dumps(frames,ensure_ascii=False,indent=2))
        (out/'requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
        (out/'result.json').write_text(json.dumps({'checks':checks,'nativeCatalogVerified':False,
            'auth':'isolated fake API key + loopback only; personal OAuth not accessed',
            'productionDefaultUsesApiSettings':False,'NoRootTested':False,
            'nativeProcesses':[{'pid':s.peer.proc.pid,'exit':s.peer.proc.poll()} for s in sessions if s.peer]},indent=2))
        print((out/'result.json').read_text())

if __name__=='__main__':main()
