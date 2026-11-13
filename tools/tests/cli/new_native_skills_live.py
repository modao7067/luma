#!/usr/bin/env python3
import argparse,json,pathlib,sys,time,uuid
from new_native_live import isolate,Manager

def run(a):
    home,work=isolate(a)
    folder=work/('.agents' if a.cli=='kimi' else '.claude')/'skills/luma-native-fixture'
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'SKILL.md').write_text('''---
name: luma-native-fixture
description: Return the fixed marker for the isolated native Skill integration test.
---
Reply exactly LUMA_NATIVE_SKILL_OK. Do not use tools, delegate, or add explanation.
''')
    report={'cli':a.cli,'model':'mimo-v2.6-flash','phases':{}};s=None;manager=Manager(home/'bridge')
    def snapshot(phase):report['phases'][phase]=s.snapshot()
    def wait(predicate):
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            for req in list(s.data['requests']):
                raw=s.pending_requests[req['id']]
                if raw.get('request',{}).get('tool_name')!='Skill':raise AssertionError('Unexpected approval')
                args=raw['request'].get('input',{})
                if args.get('skill')!='luma-native-fixture':raise AssertionError('Unexpected skill approval')
                s.answer({'id':req['id'],'choice':'allow'})
            if predicate():return
            if s.peer.closed:raise AssertionError(s.data['error'])
            time.sleep(.08)
        raise AssertionError('Skill native roundtrip timeout: '+str(s.data['error']))
    try:
        s=manager.connect({'id':'fixture','cli':'Kimi Code' if a.cli=='kimi' else 'Claude Code','cwd':str(work)})
        if a.cli=='claude':
            s.submit({'submissionId':str(uuid.uuid4()),'text':'Reply exactly LUMA_SKILL_READY. Do not use any tools.'})
            wait(lambda:not s.data['running'])
        wait(lambda:any('luma-native-fixture' in k['id'] for k in s.data['skills']))
        snapshot('native-discovery')
        chosen=next(k['id'] for k in s.data['skills'] if 'luma-native-fixture' in k['id'])
        ident=str(uuid.uuid4());s.submit({'submissionId':ident,'text':'Follow the selected Skill.','skills':[chosen]})
        wait(lambda:not s.data['running']);snapshot('native-skill-result')
        assert s.config['receipts'][ident]=='accepted'
        assert any(r['role']=='assistant' and 'LUMA_NATIVE_SKILL_OK' in r['text'] for r in s.rows.values())
        report['result']='PASS'
    except Exception as exc:
        report['result']='FAIL';report['error']=str(exc)
        if s:snapshot('failed')
        raise
    finally:
        if s and s.peer:s.peer.close()
        pathlib.Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({'cli':a.cli,'skills':report['result'],'output':a.output}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cli',choices=['kimi','claude'])
    for field in ('key-file','home','work','output'):p.add_argument('--'+field,required=True)
    p.add_argument('--kimi',default='kimi');p.add_argument('--claude',default='claude')
    run(p.parse_args())
