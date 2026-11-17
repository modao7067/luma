#!/usr/bin/env python3
import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import websockets

ROOT = Path(__file__).resolve().parents[3]

async def connect(path):
    ws = await websockets.unix_connect(str(path)); sequence = 0
    async def call(method, params=None):
        nonlocal sequence
        sequence += 1; ident = sequence
        await ws.send(json.dumps({'id':ident,'method':method,'params':params or {}}))
        while True:
            message = json.loads(await asyncio.wait_for(ws.recv(), 15))
            if message.get('id') == ident:
                if 'error' in message: raise RuntimeError(message['error'])
                return message.get('result', {})
    await call('initialize', {'clientInfo':{'name':'luma-recovery-fixture','version':'1'},'capabilities':{'experimentalApi':True}})
    await ws.send(json.dumps({'method':'initialized'}))
    return ws, call

def stamp(pid):
    return Path('/proc',str(pid),'stat').read_text().rsplit(')',1)[1].split()[19]

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--codex', default=os.environ.get('LUMA_TEST_CODEX') or shutil.which('codex') or 'codex')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/test-runs/session-recovery/native-production-recovery.json')
    args = parser.parse_args(); args.output.parent.mkdir(parents=True, exist_ok=True); folder = Path(tempfile.mkdtemp(prefix='luma-recovery-native-'))
    home = folder/'home'; home.mkdir(mode=0o700); bindir = folder/'bin'; bindir.mkdir(); (bindir/'codex').symlink_to(args.codex)
    (home/'config.toml').write_text('model="fixture"\nmodel_provider="fixture"\n[model_providers.fixture]\nname="Offline fixture"\nbase_url="http://127.0.0.1:1/v1"\nwire_api="responses"\nrequires_openai_auth=false\n')
    env = {'HOME':str(home),'CODEX_HOME':str(home),'PATH':str(bindir)+':/usr/bin:/bin','LANG':'C.UTF-8','TERM':'dumb'}
    result = {'fixtureDirectory':str(folder),'requests':[]}
    def daemon(command):
        completed = subprocess.run([args.codex,'app-server','daemon',command],env=env,cwd=folder,text=True,capture_output=True,timeout=35)
        if completed.returncode: raise RuntimeError('isolated daemon '+command+' failed')
        return json.loads(completed.stdout)
    try:
        owner = daemon('start'); alias = Path(owner['socketPath']); selected = alias.resolve()
        owner_stamp = stamp(owner['pid'])
        ws, call = await connect(selected)
        thread = (await call('thread/start', {'cwd':str(folder),'persistExtendedHistory':True}))['thread']; native = thread['id']
        await call('thread/name/set', {'threadId':native,'name':'Recovery fixture'})
        result['requests'] += ['initialize','thread/start (fixture setup only)','thread/name/set (fixture setup only)']
        await ws.close()
        assert Path('/proc',str(owner['pid']),'exe').resolve() == Path(owner['managedCodexPath']).resolve()
        assert stamp(owner['pid']) == owner_stamp
        os.kill(owner['pid'], signal.SIGKILL)
        source = (ROOT/'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RuntimeScripts.kt').read_text().split('val externalCodexRecovery = """',1)[1].split('""".trimIndent()',1)[0]
        recovered = subprocess.run(['/usr/bin/python3','-I','-c',source,str(selected)],env=env,cwd=folder,text=True,capture_output=True,timeout=35)
        result['productionRecoveryExit'] = recovered.returncode
        if recovered.returncode: raise RuntimeError(recovered.stderr)
        assert alias.resolve() == selected
        ws, call = await connect(selected)
        loaded = (await call('thread/loaded/list'))['data']; assert native not in loaded
        resumed = await call('thread/resume', {'threadId':native,'excludeTurns':True})
        assert resumed['thread']['id'] == native
        assert resumed['thread']['name'] == 'Recovery fixture'; assert resumed['model'] == 'fixture'
        unsubscribed = await call('thread/unsubscribe', {'threadId':native})
        await ws.close()
        result.update(passed=True,nativeId=native,socketStable=True,loadedBeforeResume=loaded,resumedTitle=resumed['thread']['name'],resumedModel=resumed['model'],unsubscribe=unsubscribed,
                      modelTurnRequests=0,recoveryCreatedThreads=0)
        result['requests'] += ['initialize','thread/loaded/list','thread/resume','thread/unsubscribe']
    finally:
        result['cleanup'] = daemon('stop')['status']
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))

if __name__ == '__main__': asyncio.run(main())
