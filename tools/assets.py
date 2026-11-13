#!/usr/bin/env python3
import hashlib,json,sys
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
base=Path(__file__).resolve().parent
roots=['apps/gui/app/src/main/assets', 'runtime/noroot/src/main/assets', 'runtime/noroot/src/main/jniLibs',
       'runtime/root/src/main/assets', 'runtime/root/src/main/jniLibs',
       'shared/terminal-emulator/src/main/jniLibs',
       'shared/terminal-ghostty/assets', 'shared/terminal-ghostty/jniLibs']
rows={}
for name in roots:
 for f in sorted((REPO/name).rglob('*')):
  if '__pycache__' in f.parts or f.suffix in ('.pyc', '.pyo'): continue
  if f.is_file(): rows[str(f.relative_to(REPO))]={'bytes':f.stat().st_size,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest()}
for name in ['backend/root/daemon.py', 'backend/root/mode_state.py']:
 f=REPO/name
 rows[name]={'bytes':f.stat().st_size,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest()}
manifest=base/'assets.json'
if len(sys.argv)>1 and sys.argv[1]=='record':manifest.write_text(json.dumps(rows,indent=2)+'\n')
else:
 if rows!=json.loads(manifest.read_text()):
  raise SystemExit('Packaged assets changed: review then explicitly record')
 print('PASS packaged asset hashes:',len(rows))
