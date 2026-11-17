#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys, tempfile, time
ROOT = pathlib.Path(__file__).resolve().parents[3]
SRC = ROOT / 'apps/gui/app/src/main/assets/cli_bridge'
sys.path.insert(0, str(SRC))
from luma_cli.server import Manager

def run(cli):
    with tempfile.TemporaryDirectory(prefix='luma-cli-native-') as home:
        tooling = os.environ.get('LUMA_CLI_TOOLING_DIR', '')
        path_value = (tooling + ':' if tooling else '') + '/usr/local/bin:/usr/bin:/bin'
        os.environ.clear()
        os.environ.update(HOME=home, PATH=path_value, LANG='C.UTF-8',
                          XDG_CONFIG_HOME=home+'/config', XDG_DATA_HOME=home+'/data', XDG_CACHE_HOME=home+'/cache',
                          DSH_HOME=home+'/dsh', PI_CODING_AGENT_DIR=home+'/pi', PI_NO_TITLE='1',
                          DO_NOT_TRACK='1', NO_COLOR='1', OPENAI_API_KEY='luma-fixture-not-a-real-key')
        manager = Manager(pathlib.Path(home)/'bridge')
        s = None
        try:
            s = manager.connect({'id':'fixture', 'cli':cli, 'cwd':home})
            snap=s.snapshot()
            print(json.dumps({k:snap[k] for k in ['cli','nativeId','capabilities','notice']},ensure_ascii=False), 'models',len(snap['models']), flush=True)
            assert snap['connected'] and snap['nativeId']
            native = snap['nativeId']
            if cli == 'pi':
                s.rename('Luma native fixture')
                assert s.snapshot()['title']=='Luma native fixture'
            s.peer.close()
            s = manager.connect({'id':'fixture', 'cli':cli, 'cwd':home, 'nativeId':native})
            assert s.snapshot()['nativeId']==native
            print('PASS '+cli+' create/catalog/same-native resume', flush=True)
        except Exception:
            for owned in manager.sessions.values():
                if owned.peer: print("isolated fixture stderr:", owned.peer.diagnostic.decode(errors="replace"), flush=True)
                print("session file:", owned.config.get("sessionFile"), flush=True)
            raise
        finally:
            for owned in manager.sessions.values():
                if owned.peer:owned.peer.close()
if __name__=='__main__':run(sys.argv[1])
