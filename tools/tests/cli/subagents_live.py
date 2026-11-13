#!/usr/bin/env python3
import argparse,json,os,pathlib,shutil,sys,tempfile,time,uuid
from types import SimpleNamespace
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.server import Manager
from new_native_live import isolate

def run(a):
    key=pathlib.Path(a.key_file).read_text().strip()
    assert key and pathlib.Path(a.key_file).stat().st_mode & 0o077 == 0
    evidence=pathlib.Path(a.evidence);evidence.mkdir(parents=True,exist_ok=True)
    temporary=tempfile.TemporaryDirectory(prefix='luma-subagents-'+a.cli+'-')
    home=pathlib.Path(temporary.name);work=home/'work';work.mkdir()
    home,work=isolate(SimpleNamespace(home=str(home),work=str(work),cli='claude',claude=shutil.which('claude') or '/usr/bin/claude',key_file=a.key_file))
    report={'cli':a.cli,'model':'mimo-v2.6-flash','provider':'https://api.xiaomimimo.com','events':[],'snapshots':[]}
    manager=Manager(home/'bridge');s=None;started=time.monotonic()
    def snapshot(label):report['snapshots'].append({'label':label,'seconds':round(time.monotonic()-started,3),'view':s.snapshot()})
    try:
        config={'id':'owned','cli':'Claude Code','cwd':str(work)}
        s=manager.connect(config)
        original=s.peer.event
        def observe(e):
            if e.get('type')=='system' and e.get('subtype','').startswith('task_'):
                report['events'].append(e)
            original(e)
        s.peer.event=observe
        marker='LUMA_'+a.cli.upper()+'_CHILD_DONE'
        instruction='Use your native Agent tool exactly ONCE with subagent_type=general-purpose and run_in_background=false. '
        instruction+=f'The child task is: Reply exactly {marker}; do not call tools, read files, inspect environment or configuration, or delegate. Use only the current configured mimo-v2.6-flash model/provider for child and parent. Do not create any other child or use other tools. After the child finishes, reply briefly LUMA_PARENT_DONE. This is an isolated lifecycle acceptance test.'
        s.submit({'submissionId':str(uuid.uuid4()),'text':instruction})
        revision=-1;deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            value=s.snapshot()
            if value['revision']!=revision:
                revision=value['revision'];snapshot('progress')
            for req in list(value['requests']):
                raw=s.pending_requests[req['id']]
                if marker not in json.dumps(raw):raise AssertionError('Unexpected permission outside the one-child task')
                choice=next((c['id'] for c in req['choices'] if c['id'] in ('allow','yes','allow_once') or 'once' in c['id']),None)
                if not choice:raise AssertionError('No native one-shot permission choice')
                s.answer({'id':req['id'],'choice':choice})
            agents=value.get('subAgents',[])
            assert len(agents)<=1,'Unexpected extra native agent'
            if agents and not value['running'] and agents[0]['status'] in ('succeeded','failed','cancelled'):
                assert agents[0]['status']=='succeeded',agents
                assert report['events'],'No native lifecycle events recorded'
                report['result']='PASS';snapshot('settled');break
            if s.peer.closed:raise AssertionError('Native process disconnected')
            if value.get('error'):raise AssertionError(value['error'])
            time.sleep(.08)
        else:raise TimeoutError('Native one-child lifecycle did not settle')
    except Exception as e:
        report['result']='FAIL';report['error']=str(e)
        if s:snapshot('failed')
        raise
    finally:
        for v in manager.sessions.values():
            if v.peer:v.peer.close()
        raw=json.dumps(report,ensure_ascii=False,indent=2).replace(key,'[REDACTED_API_KEY]')
        (evidence/(a.cli+'-native-subagent.json')).write_text(raw)
        print(json.dumps({'cli':a.cli,'result':report.get('result'),'events':len(report['events']),'evidence':str(evidence)},ensure_ascii=False))
        temporary.cleanup()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cli',choices=['claude']);p.add_argument('--key-file',required=True);p.add_argument('--evidence',required=True)
    run(p.parse_args())
