#!/usr/bin/env python3
import argparse,json,os,pathlib,sys,tempfile,time,uuid
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager

def isolate(a):
    home=pathlib.Path(a.home).resolve();home.mkdir(parents=True,exist_ok=True)
    work=pathlib.Path(a.work).resolve();work.mkdir(parents=True,exist_ok=True)
    bin_dir=home/'bin';bin_dir.mkdir(exist_ok=True)
    program=pathlib.Path(a.kimi if a.cli=='kimi' else a.claude).resolve()
    dest=bin_dir/('kimi' if a.cli=='kimi' else 'claude')
    if not dest.exists():dest.symlink_to(program)
    key=pathlib.Path(a.key_file).read_text().strip()
    env={'HOME':str(home),'PATH':str(bin_dir)+':/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8',
         'XDG_CONFIG_HOME':str(home/'config'),'XDG_DATA_HOME':str(home/'data'),'XDG_CACHE_HOME':str(home/'cache'),
         'KIMI_CODE_HOME':str(home/'kimi'),'CLAUDE_CONFIG_DIR':str(home/'claude'),
         'MIMO_API_KEY':key,'ANTHROPIC_API_KEY':key,'ANTHROPIC_BASE_URL':'https://api.xiaomimimo.com/anthropic',
         'ANTHROPIC_MODEL':'mimo-v2.6-flash','ANTHROPIC_DEFAULT_OPUS_MODEL':'mimo-v2.6-flash',
         'ANTHROPIC_DEFAULT_SONNET_MODEL':'mimo-v2.6-flash','ANTHROPIC_DEFAULT_HAIKU_MODEL':'mimo-v2.6-flash',
         'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC':'1','DISABLE_AUTOUPDATER':'1','NO_COLOR':'1',
         'NO_PROXY':'127.0.0.1,localhost,::1'}
    if os.environ.get('SSL_CERT_FILE'):env['SSL_CERT_FILE']=os.environ['SSL_CERT_FILE']
    os.environ.clear();os.environ.update(env)
    if a.cli=='kimi':
        cfg=home/'kimi';cfg.mkdir(exist_ok=True)
        (cfg/'config.toml').write_text('''default_model = "mimo-flash"
telemetry = false
[providers.mimo]
type = "openai"
base_url = "https://api.xiaomimimo.com/v1"
api_key_env = "MIMO_API_KEY"
[models.mimo-flash]
provider = "mimo"
model = "mimo-v2.6-flash"
max_context_size = 1000000
capabilities = ["tool_use"]
''')
    return home,work

def run(a):
    home,work=isolate(a)
    report={'cli':a.cli,'model':'mimo-v2.6-flash','phases':{},'eventCounts':{}}
    manager=Manager(home/'bridge');s=None
    def snapshot(phase):report['phases'][phase]=s.snapshot()
    def observe(frame):
        kind=frame.get('method',frame.get('type','?'))
        if kind=='session/update':kind+=':'+frame.get('params',{}).get('update',{}).get('sessionUpdate','?')
        if kind=='stream_event':kind+=':'+frame.get('event',{}).get('type','?')
        report['eventCounts'][kind]=report['eventCounts'].get(kind,0)+1
        original(frame)
    def answer():
        for req in list(s.data['requests']):
            raw=s.pending_requests.get(req['id'],{})
            tool=raw.get('params',{}).get('toolCall',{})
            raw_input=tool or raw.get('request',{}).get('input',{})
            native_tool_row=s.rows.get('tool-'+tool.get('toolCallId',''),{})
            if not any(token in json.dumps([raw_input,native_tool_row]) for token in ('LUMA_NATIVE_TOOL_OK','LUMA_STOP_SHOULD_NOT_FINISH')):
                raise AssertionError('Unexpected native approval during isolated test')
            choice=next((c['id'] for c in req['choices'] if c['id'] in ('approve_once','allow')),None)
            if choice is None:raise AssertionError('No native one-shot approval option')
            snapshot('approval-'+str(len([p for p in report['phases'] if p.startswith('approval-')])))
            s.answer({'id':req['id'],'choice':choice})
    def wait(predicate,timeout=120):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            answer()
            if predicate():return
            if s.peer.closed:raise AssertionError('Native CLI disconnected: '+str(s.data['error']))
            time.sleep(.08)
        raise AssertionError('Timed out: '+str(s.data['error']))
    try:
        s=manager.connect({'id':'fixture','cli':'Kimi Code' if a.cli=='kimi' else 'Claude Code','cwd':str(work)})
        snapshot('connected');native=s.data['nativeId'];original=s.peer.event;s.peer.event=observe
        models=s.data['models'];selected=next(m['id'] for m in models if m['id'] in ('mimo-flash','mimo-v2.6-flash') or m['label']=='mimo-v2.6-flash')
        s.configure({'model':selected});snapshot('model-selected')
        s.configure({'permission':'plan'});assert s.data['permission']=='plan';snapshot('native-plan-mode')
        s.configure({'permission':'default'});snapshot('native-default-mode')
        ident=str(uuid.uuid4())
        s.submit({'submissionId':ident,'text':"Use the Bash tool to run exactly `printf 'LUMA_NATIVE_TOOL_OK\\n'`, then reply with that marker. Do not delegate or use any other tools."})
        wait(lambda:not s.data['running']);snapshot('tool-complete')
        assert s.config['receipts'][ident]=='accepted'
        assert any('LUMA_NATIVE_TOOL_OK' in r.get('outcome','') for r in s.rows.values() if r['role']=='tool'),s.snapshot()
        assert any(r['role']=='assistant' and 'LUMA_NATIVE_TOOL_OK' in r['text'] for r in s.rows.values()),s.snapshot()
        assert any(('delta' in k or k=='session/update:agent_message_chunk') and n for k,n in report['eventCounts'].items())
        stop_id=str(uuid.uuid4())
        command='python3 -c \'import time;from pathlib import Path;Path("luma-stop-started").write_text("started");time.sleep(30);Path("luma-stop-finished").write_text("LUMA_STOP_SHOULD_NOT_FINISH")\''
        s.submit({'submissionId':stop_id,'text':"Use the Bash tool to run exactly `"+command+"` in the foreground, run_in_background=false and timeout=60000. Do not delegate or use any other tools. Wait for the command."})
        wait(lambda:(work/'luma-stop-started').exists())
        snapshot('before-stop');s.stop();wait(lambda:not s.data['running'],45);snapshot('stopped')
        assert not (work/'luma-stop-finished').exists()
        s.peer.close()
        s=manager.connect({'id':'fixture','cli':'Kimi Code' if a.cli=='kimi' else 'Claude Code','cwd':str(work),'nativeId':native})
        assert s.data['nativeId']==native
        assert any('LUMA_NATIVE_TOOL_OK' in r.get('outcome','') for r in s.rows.values() if r['role']=='tool'),s.snapshot()
        snapshot('same-id-history-resume')
        report['result']='PASS'
    except Exception as exc:
        report['result']='FAIL';report['error']=str(exc)
        if s:snapshot('failed')
        raise
    finally:
        for session in manager.sessions.values():
            if session.peer:session.peer.close()
        output=pathlib.Path(a.output);output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({'cli':a.cli,'result':report['result'],'output':str(output)},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cli',choices=['kimi','claude'])
    p.add_argument('--key-file',required=True);p.add_argument('--home',required=True);p.add_argument('--work',required=True);p.add_argument('--output',required=True)
    p.add_argument('--kimi',default='kimi');p.add_argument('--claude',default='claude')
    run(p.parse_args())
