#!/usr/bin/env python3
import copy,json,os,pathlib,sys,tempfile
from unittest.mock import patch
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import ClaudeSession

with tempfile.TemporaryDirectory(prefix='luma-claude-effort-') as tmp:
    env={'PATH':os.environ['PATH'],'HOME':tmp,'CLAUDE_CONFIG_DIR':tmp+'/.claude',
         'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC':'1','DISABLE_AUTOUPDATER':'1',
         'HTTP_PROXY':'http://127.0.0.1:9','HTTPS_PROXY':'http://127.0.0.1:9','ALL_PROXY':'http://127.0.0.1:9','NO_PROXY':'127.0.0.1,localhost'}
    config={'id':'fixture','cli':'Claude Code','cwd':tmp}; saved=[]
    report={'scope':'production ClaudeSession; isolated HOME, blocked external traffic, no prompt or credentials'}
    with patch.dict(os.environ,env,clear=True):
        s=ClaudeSession(config,lambda:saved.append(copy.deepcopy(config)))
        try:
            s.connect(); report['initial']={k:s.data[k] for k in ('model','effort','permissions','models')}
            initial=s.data['model']; entry=next(m for m in s.data['models'] if m['id']==initial)
            assert len(entry['efforts'])>1,entry
            levels=entry['efforts']; report['sets']=[]
            for level in (levels[0],levels[-1]):
                s.configure({'model':initial,'effort':level})
                readback=s.call('get_settings')['applied']
                assert readback['effort']==level==s.data['effort']==config['selectedEffort']
                report['sets'].append({'requested':level,'applied':readback['effort']})
            s.configure({'permission':'auto'}); assert s.call('initialize',{'hooks':None})['current_permission_mode']=='auto'
            report['permission']='auto'
            alternate=next(m for m in s.data['models'] if m['id']!=initial and 'low' in m['efforts'])
            s.configure({'model':alternate['id'],'effort':'low'}); assert s.data['model']==alternate['id'] and s.data['effort']=='low'
            report['alternate']={'model':s.data['model'],'effort':s.data['effort']}
            native=s.data['nativeId']
        finally:
            if s.peer:s.peer.close()
        restored=ClaudeSession(config,lambda:saved.append(copy.deepcopy(config)))
        try:
            restored.connect()
            assert restored.data['nativeId']==native
            assert restored.data['model']==alternate['id'] and restored.data['effort']=='low'
            assert restored.data['permission']=='auto'
            report['restored']={k:restored.data[k] for k in ('model','effort','permission')}
        finally:
            if restored.peer:restored.peer.close()
        assert not (pathlib.Path(tmp)/'.claude/settings.json').exists()
        assert not (pathlib.Path(tmp)/'.claude/settings.local.json').exists()
        report['sessionIdentityPreserved']=True; report['globalSettingsWritten']=False
    print(json.dumps(report,ensure_ascii=False,indent=2))
