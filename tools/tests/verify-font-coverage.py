#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import re
import unicodedata
from fontTools.ttLib import TTFont
REPO=Path(__file__).resolve().parents[2]
policy=json.loads((REPO/'tools/font-coverage.json').read_text())
source=(REPO/'shared/terminal-view/java/com/termux/view/BundledTerminalFonts.java').read_text()
filenames=re.findall(r'"([^"\n]+\.(?:ttf|otf))"',re.search(r'FILES=\{(.*?)\};',source,re.S)[1])
assert len(filenames)==len(set(filenames))
expected_by_block={row['name']:{cp for cp in range(int(row['start'],16),int(row['end'],16)+1)
    if unicodedata.category(chr(cp)) not in ['Cn','Cc','Cf','Cs','Co']} for row in policy['blocks']}
covered_codepoints=set()
for app in ['standard','root']:
 assets=REPO/'runtime'/('noroot' if app in ('standard','gui') else 'root')/'src/main/assets'
 manifest={row['file']:row for row in json.loads((assets/'terminal-fonts-manifest.json').read_text())}
 covered=set()
 for name in filenames:
  path=assets/name
  with path.open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
  assert actual==manifest[name]['sha256'],name
  assert path.stat().st_size==manifest[name]['bytes'],name
  font=TTFont(path,lazy=True);covered.update(font.getBestCmap());font.close()
 for name,expected in expected_by_block.items():
  missing=expected-covered
  assert not missing,(app,name,[f'U+{cp:04X}' for cp in sorted(missing)])
  covered_codepoints.update(expected)
 print('PASS',app,len(filenames),'verified fonts;',len(expected_by_block),'whole blocks;',len(covered_codepoints),'assigned characters; FF65 covered')
work=REPO/'tools/tests/terminal/build';work.mkdir(parents=True,exist_ok=True)
visible=[cp for cp in sorted(covered_codepoints) if unicodedata.category(chr(cp))[0] not in ['M','Z'] and cp not in [0x3164,0xffa0]]
(work/'font-codepoints.txt').write_text('\n'.join(map(str,visible))+'\n')
print('Unicode data:',unicodedata.unidata_version,'; Android runtime probe list:',len(visible),'characters')
