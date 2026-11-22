#!/usr/bin/env python3
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
import subprocess,tarfile,hashlib,json
base=Path(__file__).resolve().parent
paths=subprocess.check_output([str(base/'luma'),'git','ls-files','--cached','--others','--exclude-standard'],text=True).splitlines()
paths=sorted({name for name in paths if (REPO/name).is_file()})
rows={}
FORBIDDEN_PARTS=('signing','build','.gradle','jniLibs','__pycache__')
FORBIDDEN_NAMES=('local.properties','.env','.bash_history','root-backend.token')
FORBIDDEN_SUFFIXES=('.apk','.jks','.keystore','.key','.pem','.db','.env','.pyc','.pyo','release.properties')
with tarfile.open(base/'source-baseline.tar.gz','w:gz') as archive:
 for name in paths:
  path=REPO/name
  if not path.is_file():raise SystemExit('Source file disappeared: '+name)
  if any(s in path.parts for s in FORBIDDEN_PARTS):
   raise SystemExit('Private/generated source path: '+name)
  lower_name=path.name.lower()
  if lower_name in FORBIDDEN_NAMES or lower_name.endswith(FORBIDDEN_SUFFIXES):
   raise SystemExit('Private/generated source file: '+name)
  archive.add(path,arcname=name,recursive=False)
  with path.open('rb') as stream:
   rows[name]=hashlib.file_digest(stream,'sha256').hexdigest()
(base/'source-baseline.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Source snapshot files:',len(rows))

head=subprocess.run([str(base/'luma'),'git','rev-parse','--verify','HEAD'],text=True,capture_output=True)
with (base/'source-baseline.json').open('rb') as manifest_stream, (base/'source-baseline.tar.gz').open('rb') as archive_stream:
 provenance={'gitHead':head.stdout.strip() if head.returncode==0 else None,
  'sourceManifestSha256':hashlib.file_digest(manifest_stream,'sha256').hexdigest(),
  'sourceArchiveSha256':hashlib.file_digest(archive_stream,'sha256').hexdigest(),
  'apks':json.loads((base/'delivery.json').read_text()) if (base/'delivery.json').exists() else [],
  'note':'Local snapshot, no automatic commit. APKs validated separately by delivery.py.'}
(base/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
