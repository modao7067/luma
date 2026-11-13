import json,os,pathlib,sys,tempfile,unittest,subprocess
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.base import Session
from luma_cli.pi import PiSession
from luma_cli.pi_native import PiNativeSession,probe_pi_native,REQUIRED_SDK_EXPORTS
from luma_cli.acp import AcpSession
from luma_cli.native_owner import NativeOwner
from luma_cli.server import Manager
from luma_cli.transport import Rejected
class Contracts(unittest.TestCase):
 def test_new_carrier_declares_consumption_per_adapter_not_globally(self):
  old=PiSession({'cli':'pi','cwd':'/tmp'},lambda:None)
  old.receipt('same-a','accepted');old.receipt('same-b','accepted')
  self.assertEqual(old.snapshot()['inputEvidenceProtocol'],0)
  self.assertEqual(old.snapshot()['consumedSubmissions'],{})
  for cls in (PiNativeSession,AcpSession):
   self.assertEqual(cls({'cli':'fixture','cwd':'/tmp'},lambda:None).snapshot()['inputEvidenceProtocol'],1)
 def test_front_identity_change_retains_old_ledger(self):
  config={'cli':'fixture','cwd':'/tmp','nativeId':'old','receipts':{'old-request':'unknown'}}
  session=PiNativeSession(config,lambda:None);session.update(connected=True,canOpenTui=True)
  session.event({'type':'luma_state','_nativeId':'new','model':{}})
  self.assertFalse(session.data['connected']);self.assertFalse(session.data['canOpenTui'])
  self.assertEqual(config['nativeId'],'old');self.assertEqual(config['receipts']['old-request'],'unknown')
 def test_durable_host_book_survives_restart_without_replaying_unknown(self):
  with tempfile.TemporaryDirectory(prefix='luma-receipt-book-') as tmp:
   module=(ROOT/'apps/gui/app/src/main/assets/cli_bridge/native/host-server.mjs').as_uri()
   file=tmp+'/receipts.json'
   def run(body):
    script="import {receiptBook} from "+json.dumps(module)+";const book=receiptBook("+json.dumps(file)+",()=> 'native',()=>{});"+body
    subprocess.run(['node','--input-type=module','-e',script],check=True)
   run("book.begin('a',{text:'same',images:[]});")
   run("if(book.begin('a',{images:[],text:'same'}).fresh||book.entries.a.state!=='unknown')throw Error('replayed');if(!book.begin('b',{text:'same',images:[]}).fresh)throw Error('text dedup');book.settle('b','accepted',{consumed:true});")
   run("if(!book.entries.b.consumed||book.begin('b',{text:'same',images:[]}).fresh)throw Error('lost receipt');let rejected=false;try{book.begin('b',{text:'different'});}catch{rejected=true;}if(!rejected)throw Error('payload identity changed');")
 def test_private_bundled_tmux_without_system_tmux(self):
  with tempfile.TemporaryDirectory(prefix='luma-bundled-tmux-') as tmp:
   session=Session({'cli':'fixture','cwd':tmp,'ownerDirectory':tmp+'/owner'},lambda:None)
   with patch('luma_cli.native_owner.shutil.which',return_value=None):
    owner=NativeOwner(session)
    try:
     self.assertIn(b'tmux 3.5a',owner.tmux('-V').stdout)
     owner.launch_tui(['/bin/sh','-c','read value; printf "%s" "$value"; sleep 5'])
     owner.tmux('send-keys','TMUX_FIXTURE','Enter')
     self.assertTrue(owner.alive());self.assertEqual(owner.terminal()['instanceId'],session.config['nativeOwner']['instanceId'])
    finally:owner.tmux('kill-server',check=False)
 def test_pi_native_probe_accepts_newer_semver_only_when_sdk_contract_holds(self):
  with tempfile.TemporaryDirectory(prefix='luma-pi-probe-') as tmp:
   pkg=pathlib.Path(tmp)/'pi-pkg';dist=pkg/'dist';(dist/'core').mkdir(parents=True);(dist/'cli').mkdir(parents=True);(dist/'bundle').mkdir(parents=True)
   cli_bin=dist/'bundle/cli.js';cli_bin.write_text('#!/usr/bin/env node\n');cli_bin.chmod(0o755)
   (dist/'index.js').write_text('\n'.join(f'export function {s}(){{}}' for s in REQUIRED_SDK_EXPORTS))
   (dist/'core/project-trust.js').write_text('export function resolveProjectTrusted(){}')
   (dist/'cli/project-trust.js').write_text('export function createProjectTrustContext(){}')
   (dist/'core/agent-session.js').write_text('const preflightResult = options?.preflightResult;')
   for ver in ('1.5.0','1.5.7','1.6.0-rc.1'):
    (pkg/'package.json').write_text(json.dumps({'version':ver}))
    found,ok,reason,resolved=probe_pi_native(str(cli_bin))
    self.assertTrue(found and ok,reason);self.assertEqual(resolved,pkg)
   (pkg/'package.json').write_text(json.dumps({'version':'1.4.9'}))
   self.assertEqual(probe_pi_native(str(cli_bin))[:2],(True,False))
   (pkg/'package.json').write_text(json.dumps({'version':'1.6.0'}))
   (dist/'core/agent-session.js').write_text('// preflight removed')
   found,ok,reason,_=probe_pi_native(str(cli_bin))
   self.assertEqual((found,ok),(True,False));self.assertIn('契约',reason)
 def test_new_session_falls_back_to_standard_protocol_without_migrating_existing_or_explicit_sessions(self):
  with tempfile.TemporaryDirectory(prefix='luma-protocol-fallback-') as tmp:
   work=pathlib.Path(tmp)/'work';work.mkdir();state=pathlib.Path(tmp)/'state'
   with patch('luma_cli.server.probe_pi_native',return_value=(True,False,'contract','/usr/bin/pi')), \
        patch.object(PiSession,'connect',lambda s:s.update(connected=True,notice=s.capability_notice())):
    m=Manager(state)
    pi_s=m.connect({'id':'new-pi','cli':'pi','cwd':str(work)})
    self.assertIs(type(pi_s),PiSession);self.assertEqual(pi_s.config['nativeProtocol'],'pi-rpc');self.assertIn('契约',pi_s.data['notice'])
   with patch('luma_cli.server.probe_pi_native',return_value=(False,False,'missing',None)), \
        patch.object(PiNativeSession,'connect',lambda s:None):
    missing_s=Manager(state).connect({'id':'missing-pi','cli':'pi','cwd':str(work)})
    self.assertIs(type(missing_s),PiNativeSession);self.assertEqual(missing_s.config['nativeProtocol'],'pi-native-sdk-v1')
   with patch('luma_cli.pi_native.probe_pi_native',return_value=(True,False,'原生双端 Pi 需要 1.5.0 或更新版本','/usr/bin/pi')):
    with self.assertRaisesRegex(Rejected,'1.5.0'):
     Manager(state).connect({'id':'explicit-pi','cli':'pi','cwd':str(work),'nativeProtocol':'pi-native-sdk-v1'})
    (state/'persisted-pi.json').write_text(json.dumps({'id':'persisted-pi','cli':'pi','cwd':str(work),'nativeProtocol':'pi-native-sdk-v1'}))
    with self.assertRaisesRegex(Rejected,'1.5.0'):
     Manager(state).connect({'id':'persisted-pi','cli':'pi','cwd':str(work)})
    self.assertEqual(json.loads((state/'persisted-pi.json').read_text())['nativeProtocol'],'pi-native-sdk-v1')
 def test_owner_connect_surfaces_host_contract_error_immediately(self):
  with tempfile.TemporaryDirectory(prefix='luma-owner-err-') as tmp:
   s=Session({'cli':'fixture','cwd':tmp,'ownerDirectory':tmp+'/owner'},lambda:None)
   owner=NativeOwner(s)
   pathlib.Path(owner.socket+'.error').write_text('契约不兼容测试哨兵',encoding='utf-8')
   with self.assertRaisesRegex(Rejected,'契约不兼容测试哨兵'):
    owner.connect(lambda e:None,lambda e:None)
   self.assertFalse(pathlib.Path(owner.socket+'.error').exists())
if __name__=='__main__':unittest.main(verbosity=2)
