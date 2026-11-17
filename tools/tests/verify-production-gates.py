#!/usr/bin/env python3
from pathlib import Path
import gzip
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
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
    (tools/'artifacts.lock.json').write_text('{}')
    asset=root/'shared/terminal-ghostty/assets/fixture.txt';asset.parent.mkdir(parents=True);asset.write_text('changed')
    for name in ('daemon.py','mode_state.py'):
        file=root/'backend/root'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('fixture')
    (tools/'luma').write_text('#!/usr/bin/env python3\nfrom pathlib import Path\nprint((Path(__file__).parent/"paths.txt").read_text(),end="")\n')
    (tools/'luma').chmod(0o755)
    tests=tools/'tests';tests.mkdir()
    for name in ('verify-production-gates','verify-layout','verify-build-entry','test_native_builds','test_source_hygiene','verify-row-projection','verify-root-hook','verify-session-policy','verify-font-coverage','verify-font-cache','verify-terminal-interaction','verify-terminal-scroll','verify-root-keeper','verify-root-mode-state'):
        (tests/(name+'.py')).write_text('')
    shutil.copy2(REPO/'tools/tests/verify-no-host-paths.py',tests/'verify-no-host-paths.py')
    shutil.copy2(REPO/'tools/tests/verify-jvm-comments.py',tests/'verify-jvm-comments.py')
    shutil.copy2(REPO/'tools/tests/verify-python-comments.py',tests/'verify-python-comments.py')
    for product in ('luma','root'):
        (tests/product).mkdir()
        for name in ('status','input'):(tests/product/('verify-'+name+'.py')).write_text('')
    image=root/'shared/terminal-emulator/java/ImageFixture.java';image.parent.mkdir(parents=True);image.write_text('')
    pinned=root/'pinned-tool';pinned.write_text('changed')
    env=os.environ.copy();env['PYTHONDONTWRITEBYTECODE']='1'
    def run(script,optimized):
        return subprocess.run([sys.executable,*(['-O'] if optimized else []),str(tools/script)],cwd=root,env=env,text=True,capture_output=True)
    leak_samples=[
        ('host PRoot dir','/workspace/'+'PRoot-R5/work/toolchain/bin'),
        ('host NDK dir','/workspace/'+'android-ndk-r27c'),
        ('root gradle cache','/root/.'+'gradle/caches/modules-2'),
        ('pinned codex release','/root/.'+'codex/packages/app-server-daemon'),
        ('removed verification fixture','verification/'+'gui-cli-live-20261103/proot-fix'),
        ('private package id','local.'+'luma.rootgui'),
        ('checkout sample','/workspace/'+'luma/src/Main.kt'),
        ('checkout docs alias','/workspace/docs/'+'luma'),
    ]
    for optimized in (False,True):
        tag='-O' if optimized else 'normal'
        result=run('assets.py',optimized)
        check(result.returncode!=0 and 'Packaged assets changed' in result.stderr,tag+' rejects changed assets')
        subprocess.run([sys.executable,str(tools/'assets.py'),'record'],env=env,check=True)
        (tools/'toolchain.json').write_text(json.dumps({'artifactSha256':{str(pinned):'incorrect'}}))
        (tools/'paths.txt').write_text('')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'Pinned tool changed' in result.stderr,tag+' rejects toolchain mismatch')
        sdk=root/'sdk/build-tools/37.0.0/aapt2';sdk.parent.mkdir(parents=True,exist_ok=True);sdk.write_bytes(b'sdk fixture')
        active=root/'selected-aapt2';active.write_bytes(b'override fixture')
        sdk_digest=hashlib.sha256(sdk.read_bytes()).hexdigest()
        active_digest=hashlib.sha256(active.read_bytes()).hexdigest()
        (tools/'toolchain.json').write_text(json.dumps({'artifactSha256':{
            '$AAPT2':[active_digest,sdk_digest],
            '$ANDROID_HOME/build-tools/37.0.0/aapt2':sdk_digest,
        }}))
        previous_env=env.copy()
        env['ANDROID_HOME']=str(root/'sdk')
        props=root/'gradle.properties'
        for source in ('environment','gradle'):
            env.pop('AAPT2',None)
            if source=='environment': env['AAPT2']=str(active)
            else: props.write_text('android.aapt2FromMavenOverride='+str(active)+'\n')
            active.write_bytes(b'override fixture')
            result=run('verify.py',optimized)
            check(result.returncode==0,tag+' accepts reviewed '+source+' aapt2: '+result.stderr)
            active.write_bytes(b'changed override')
            result=run('verify.py',optimized)
            check(result.returncode!=0 and 'Pinned tool changed: '+str(active) in result.stderr,tag+' rejects changed '+source+' aapt2 with unchanged SDK')
        env['AAPT2']=str(sdk)
        result=run('verify.py',optimized)
        check(result.returncode==0,tag+' accepts reviewed SDK aapt2: '+result.stderr)
        env['AAPT2']=str(root/'missing-aapt2')
        env['LUMA_STRICT_TOOLCHAIN']='1'
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'Pinned tool missing: '+env['AAPT2'] in result.stderr,tag+' rejects missing selected aapt2 in strict mode')
        props.unlink();env=previous_env
        (tools/'toolchain.json').write_text('{"artifactSha256":{}}')
        for name,reason in [('secret.txt','outside allowed scope'),('runtime/build/private.txt','Private/generated source path'),('runtime/private.key','Private/generated source file')]:
            file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('fixture')
            (tools/'paths.txt').write_text(name+'\n')
            result=run('verify.py',optimized)
            check(result.returncode!=0 and reason in result.stderr,tag+' rejects '+name)
        for label,leaked in leak_samples:
            leak_file=root/'tools/leak-check.txt';leak_file.write_text('example='+leaked+'\n')
            (tools/'paths.txt').write_text('tools/leak-check.txt\n')
            result=run('verify.py',optimized)
            check(result.returncode!=0 and 'Host/private path leak check failed' in result.stderr,tag+' rejects '+label)
            leak_file.unlink()
        comment_file=root/'tools/CommentFixture.kt'
        comment_file.write_text('val fixture = "https://example.test"\n// explanatory text\n')
        (tools/'paths.txt').write_text('tools/CommentFixture.kt\n')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'CommentFixture.kt:2:' in result.stderr,tag+' rejects JVM comments through normal verify')
        comment_file.write_text('val fixture = "// test data"\n')
        result=run('verify.py',optimized)
        check(result.returncode==0,tag+' accepts comment-like test strings: '+result.stderr)
        comment_file.unlink()
        python_file=root/'tools/comment_fixture.py'
        python_file.write_text('"""explanatory docstring"""\n')
        (tools/'paths.txt').write_text('tools/comment_fixture.py\n')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'comment_fixture.py:1: Python docstring' in result.stderr,tag+' rejects Python docstrings through normal verify')
        python_file.write_text('COMMAND_DESCRIPTION = """Command help text"""\n')
        result=run('verify.py',optimized)
        check(result.returncode==0,tag+' preserves functional CLI help text: '+result.stderr)
        python_file.unlink()
        bundle=root/'runtime/noroot/src/main/assets/native-sources/proot-sources.tgz'
        bundle.parent.mkdir(parents=True,exist_ok=True)
        with bundle.open('wb') as raw:
            with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as gz:
                with tarfile.open(fileobj=gz,mode='w') as tf:
                    payload=('/workspace/'+'PRoot-R5/work\n').encode()
                    info=tarfile.TarInfo('recipe/build.sh');info.size=len(payload);tf.addfile(info,io.BytesIO(payload))
        subprocess.run([sys.executable,str(tools/'assets.py'),'record'],env=env,check=True)
        (tools/'paths.txt').write_text('')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'proot-sources.tgz!recipe/build.sh' in result.stderr,tag+' rejects host path inside proot-sources.tgz')
        bundle.unlink()
        subprocess.run([sys.executable,str(tools/'assets.py'),'record'],env=env,check=True)
        image.unlink()
        (tools/'paths.txt').write_text('')
        result=run('verify.py',optimized)
        check(result.returncode!=0 and 'image-capable engine is missing' in result.stderr,tag+' rejects missing shared image engine')
        image.write_text('')
        private=root/'runtime/signing/private.txt';private.parent.mkdir(parents=True,exist_ok=True);private.write_text('fixture')
        (tools/'paths.txt').write_text('runtime/signing/private.txt\n')
        result=run('source-snapshot.py',optimized)
        check(result.returncode!=0 and 'Private/generated source path' in result.stderr,tag+' snapshot rejects signing path')
        guest_safe=(
            'cwd=/workspace\n'
            'sample=/workspace/project/src/Main.kt\n'
            'rc=/root/.bashrc\n'
            'pnpm=/root/.local/share/pnpm\n'
            'downloads=/sdcard/Download\n'
            'runtime=/opt/luma/bin\n'
        )
        for script in ('verify.py','source-snapshot.py'):
            (tools/'paths.txt').write_text('runtime/safe.txt\n');(root/'runtime/safe.txt').write_text(guest_safe)
            result=run(script,optimized)
            check(result.returncode==0,tag+' valid guest paths pass '+script+': '+result.stderr)
        (tools/'toolchain.json').write_text('invalid SDK metadata')
        result=subprocess.run([sys.executable,*(['-O'] if optimized else []),str(tools/'verify.py'),'--static'],cwd='/tmp',env=env,text=True,capture_output=True)
        check(result.returncode==0 and 'PASS static gates' in result.stdout,tag+' static gates do not need SDK metadata or current repo cwd: '+result.stderr)
        (tools/'assets.json').write_text('{}')
print('PRODUCTION GATES OK checks='+str(checks))
