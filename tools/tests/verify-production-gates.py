#!/usr/bin/env python3
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile

REPO=Path(__file__).resolve().parents[2]
checks=0

def check(ok,message):
    global checks
    if not ok:raise AssertionError(message)
    checks+=1;print('PASS '+message)

with tempfile.TemporaryDirectory(prefix='luma-gates-') as temp:
    root=Path(temp);tools=root/'tools';tools.mkdir()
    for name in ('assets.py','verify.py','source-snapshot.py','toolenv.py'):
        shutil.copy2(REPO/'tools'/name,tools/name)
    (tools/'assets.json').write_text('{}')
    asset=root/'shared/terminal-ghostty/assets/fixture.txt';asset.parent.mkdir(parents=True);asset.write_text('changed')
    for name in ('daemon.py','mode_state.py'):
        file=root/'backend/root'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('fixture')
    paths=[]
    (tools/'luma').write_text('#!/usr/bin/env python3\nfrom pathlib import Path\nprint((Path(__file__).parent/"paths.txt").read_text(),end="")\n')
    (tools/'luma').chmod(0o755)
    tests=tools/'tests';tests.mkdir()
    for name in ('verify-production-gates','verify-layout','verify-build-entry','verify-row-projection','verify-root-hook','verify-session-policy','verify-font-coverage','verify-font-cache','verify-terminal-interaction','verify-terminal-scroll','verify-root-keeper','verify-root-mode-state'):
        (tests/(name+'.py')).write_text('')
    for product in ('luma','root'):
        (tests/product).mkdir()
        for name in ('status','input'):(tests/product/('verify-'+name+'.py')).write_text('')
    image=root/'shared/terminal-emulator/java/ImageFixture.java';image.parent.mkdir(parents=True);image.write_text('')
    pinned=root/'pinned-tool';pinned.write_text('changed')
    env=os.environ.copy();env['PYTHONDONTWRITEBYTECODE']='1'
    def run(script,optimized):
        return subprocess.run([sys.executable,*(['-O'] if optimized else []),str(tools/script)],cwd=root,env=env,text=True,capture_output=True)
    for optimized in (False,True):
        tag='-O' if optimized else 'normal'
        result=run('assets.py',optimized)
        check(result.returncode!=0 and 'Packaged assets changed' in result.stderr,tag+' rejects changed assets')
        subprocess.run([sys.executable,str(tools/'assets.py'),'record'],env=env,check=True)
        (tools/'toolchain.json').write_text(json.dumps({'artifactSha256':{str(pinned):'incorrect'}}))
        (tools/'paths.txt').write_text('')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'Pinned tool changed' in result.stderr,tag+' rejects toolchain mismatch')
        (tools/'toolchain.json').write_text('{"artifactSha256":{}}')
        for name,reason in [('secret.txt','outside allowed scope'),('runtime/build/private.txt','Private/generated source path'),('runtime/private.key','Private/generated source file')]:
            file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('fixture')
            (tools/'paths.txt').write_text(name+'\n')
            result=run('verify.py',optimized)
            check(result.returncode!=0 and reason in result.stderr,tag+' rejects '+name)
        image.unlink()
        (tools/'paths.txt').write_text('')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'image-capable engine is missing' in result.stderr,tag+' rejects missing shared image engine')
        image.write_text('')
        private=root/'runtime/signing/private.txt';private.parent.mkdir(parents=True,exist_ok=True);private.write_text('fixture')
        (tools/'paths.txt').write_text('runtime/signing/private.txt\n')
        result=run('source-snapshot.py',optimized)
        check(result.returncode!=0 and 'Private/generated source path' in result.stderr,tag+' snapshot rejects signing path')
        for script in ('verify.py','source-snapshot.py'):
            (tools/'paths.txt').write_text('runtime/safe.txt\n');(root/'runtime/safe.txt').write_text('safe')
            result=run(script,optimized)
            check(result.returncode==0,tag+' valid inputs pass '+script+': '+result.stderr)
        (tools/'assets.json').write_text('{}')
print('PRODUCTION GATES OK checks='+str(checks))
