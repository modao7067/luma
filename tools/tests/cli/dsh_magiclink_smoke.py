#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import json,os,pathlib,sys,tempfile
sys.dont_write_bytecode=True
root=pathlib.Path('/proc/1/root'+toolenv.DEVICE_REPO)
sys.path.insert(0,str(root/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli import dsh
from luma_cli.server import Manager
module=pathlib.Path(dsh.__file__).with_name('dsh_acp.mjs')
assert str(module).startswith('/proc/1/root/') and module.is_file()
old=module.resolve()
assert not old.is_file(), 'This probe requires the actual distinct Android/Ubuntu path domains'
with tempfile.TemporaryDirectory(prefix='luma-dsh-magiclink-') as tmp:
 os.environ.clear();os.environ.update(HOME=tmp,DSH_HOME=tmp+'/dsh',PATH='/usr/bin:/bin',LANG='C.UTF-8',DEEPSEEK_API_KEY='public-no-inference-fixture',DEEPSEEK_BASE_URL='http://127.0.0.1:1',DSH_TELEMETRY_MODE='OFF')
 manager=Manager(pathlib.Path(tmp)/'bridge')
 try:
  s=manager.connect({'id':'magiclink','cli':'DeepSeek Harness','cwd':tmp})
  patch=json.loads(s.permission_patch.read_text())
  actual=patch[1]['insert'][0]['name']
  assert pathlib.Path(actual).is_file() and pathlib.Path(actual).read_text()==module.read_text()
  assert pathlib.Path(actual).parent==pathlib.Path(tmp).parent
  assert s.permission_protocol and len(s.data['permissions'])==3
  print(json.dumps({'result':'PASS','assetModule':str(module),'stagedModule':actual,'oldResolvedPath':str(old),'oldResolvedReadable':False,'capability':s.initialize_info['_meta']['lumaPermissions'],'modelCalls':0}))
  s.call('session/close',{'sessionId':s.data['nativeId']})
  s.peer.proc.stdin.close();s.peer.proc.wait(timeout=15)
 except Exception:
  for owned in manager.sessions.values():
   if owned.peer:print('Native stderr:',owned.peer.diagnostic.decode(errors='replace'))
  raise
 finally:
  for owned in manager.sessions.values():
   if owned.peer:owned.peer.close()
