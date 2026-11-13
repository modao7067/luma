#!/usr/bin/env python3
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
import subprocess,tarfile,hashlib,json
base=Path(__file__).resolve().parent
paths=subprocess.check_output([str(base/'luma'),'git','ls-files','--cached','--others','--exclude-standard'],text=True).splitlines()
paths=sorted({name for name in paths if (REPO/name).is_file()})
rows={}
with tarfile.open(base/'source-baseline.tar.gz','w:gz') as archive:
 for name in paths:
  path=REPO/name
  if not path.is_file():raise SystemExit('Source file disappeared: '+name)
  if any(s in path.parts for s in ['signing','build','.gradle']):
   raise SystemExit('Private/generated source path: '+name)
  archive.add(path,arcname=name,recursive=False)
  rows[name]=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
(base/'source-baseline.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Source snapshot files:',len(rows))

head=subprocess.run([str(base/'luma'),'git','rev-parse','--verify','HEAD'],text=True,capture_output=True)
provenance={'gitHead':head.stdout.strip() if head.returncode==0 else None,
 'sourceManifestSha256':hashlib.file_digest((base/'source-baseline.json').open('rb'),'sha256').hexdigest(),
 'sourceArchiveSha256':hashlib.file_digest((base/'source-baseline.tar.gz').open('rb'),'sha256').hexdigest(),
 'apks':json.loads((base/'delivery.json').read_text()) if (base/'delivery.json').exists() else [],
 'note':'Local snapshot, no automatic commit. APKs validated separately by delivery.py.'}
(base/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
