#!/usr/bin/env python3
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
import subprocess,sys,tarfile,json,hashlib
base=Path(__file__).resolve().parent
shared=Path(REPO / 'shared')
sys.path.insert(0,str(base))
import os,toolenv
def resolve(name):
    return Path(name.replace('$ANDROID_HOME',str(toolenv.ANDROID_HOME)).replace('$LUMA_ROOT',str(REPO)).replace('$AAPT2',toolenv.AAPT2))
for name,digest in json.loads((base/'toolchain.json').read_text())['artifactSha256'].items():
    path=resolve(name)
    if name=='$AAPT2' and str(path)!='/opt/eta-aapt2/aapt2':
        print('NOTE pinned aapt2 hash applies to the device build host binary; skipped for',path); continue
    if not path.is_file():
        if os.environ.get('LUMA_STRICT_TOOLCHAIN'): raise SystemExit('Pinned tool missing: '+str(path))
        print('WARN pinned tool not present, skipped:',path); continue
    if hashlib.file_digest(open(path,'rb'),'sha256').hexdigest()!=digest:
        raise SystemExit('Pinned tool changed: '+str(path))
print('PASS pinned SDK/aapt2/D8/apksigner hashes')
subprocess.run([sys.executable,str(base/'tests/verify-production-gates.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-layout.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-build-entry.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-row-projection.py')],check=True)
for kind in ['status','input']:
 subprocess.run([sys.executable,str(base/'tests'/'luma'/('verify-'+kind+'.py'))],check=True)
if not list((shared/'terminal-emulator/java').rglob('*Image*.java')):
 raise SystemExit('Shared image-capable engine is missing')
print('PASS image-capable terminal engine present')
subprocess.run([sys.executable,str(base/'tests/verify-root-hook.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-session-policy.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-font-coverage.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-font-cache.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-terminal-interaction.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-terminal-scroll.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-root-keeper.py')],check=True)
subprocess.run([sys.executable,str(base/'tests/verify-root-mode-state.py')],check=True)
subprocess.run([sys.executable,str(base/'assets.py'),'check'],check=True)
paths=subprocess.check_output([str(base/'luma'),'git','ls-files','--cached','--others','--exclude-standard'],text=True).splitlines()
paths=sorted({path for path in paths if (REPO/path).is_file()})
for path in paths:
 if not (path.startswith(('apps/', 'shared/', 'runtime/', 'gradle/', 'tools/', 'backend/', '.github/')) or path in ['README.md', 'README.zh-CN.md', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'CONTRIBUTING.md', 'SECURITY.md', 'CHANGELOG.md', '.gitignore', 'luma', 'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat']):
  raise SystemExit('Source path outside allowed scope: '+path)
 if any(part in Path(path).parts for part in ['signing','build','.gradle','jniLibs']):
  raise SystemExit('Private/generated source path: '+path)
 if path.endswith(('.apk','.jks','.keystore','.key','.pem','local.properties')):
  raise SystemExit('Private/generated source file: '+path)
print('PASS Git scope:',len(paths),'allowed source files')
