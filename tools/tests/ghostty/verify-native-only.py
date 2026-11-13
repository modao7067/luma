#!/usr/bin/env python3
from pathlib import Path
import argparse,json,re,struct,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
retired=['ITermImage','TerminalBitmap','TerminalSixel','KittyGraphics','TerminalHyperlink'];rows=[]
contract=(REPO/'shared/terminal-emulator/java/com/termux/terminal/TerminalEmulator.java').read_text()
assert 'public abstract class TerminalEmulator' in contract
assert not any(s in contract for s in ['processCodePoint','mMainBuffer','mAltBuffer','mTabStop','new TerminalBuffer'])
for f in list((REPO/'shared').rglob('*.java')):
 if 'build' in f.parts:continue
 text=f.read_text();assert not re.search(r'new\s+TerminalEmulator\s*\(',text),f
 assert not any(re.search(r'\b'+name+r'\b',text) for name in retired),f
for app,variant in [('root','debug'),('gui','release')]:
 apk=REPO/f'apps/{app}/app/build/outputs/apk/{variant}/{app}-{variant}.apk';class_descriptors=[];method_names=[]
 with zipfile.ZipFile(apk) as z:
  dexes=[n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n)]
  for name in dexes:
   d=z.read(name);assert d[:4]==b'dex\n';count,off=struct.unpack_from('<II',d,56);strings=[]
   for i in range(count):
    pos=struct.unpack_from('<I',d,off+4*i)[0]
    while d[pos]&128:pos+=1
    pos+=1;end=d.index(0,pos);strings.append(d[pos:end].decode('utf-8',errors='replace'))
   class_descriptors += [s for s in strings if s.startswith('L') and s.endswith(';')]
   method_names += strings
  assert not any('reference-java' in n or 'LegacyPixelOracle' in n for n in z.namelist())
 for name in retired:assert not any('/'+name+';' in s for s in class_descriptors),(app,name)
 for method in ['processCodePoint','clearDcsTypeVariables','sixelReadData','advanceKittyCursor']:assert method not in method_names,(app,method)
 if app=='gui':
  mapping=(REPO/'apps/gui/app/build/outputs/mapping/release/mapping.txt').read_text()
  for name in retired:assert not re.search(r'^com\.termux\.terminal\.'+name+r' ->',mapping,re.M),name
  for method in ['processCodePoint','clearDcsTypeVariables','advanceKittyCursor']:assert method not in mapping,method
 rows.append({'product':app,'apk':str(apk),'dex':dexes,'retiredParserClassesAbsent':retired,'javaVtDispatcherAbsent':True,'referenceOracleNotPackaged':True,'thinAbstractHostSource':True,'nativeRuntimeStructureProbe':'verify-packaged.py'})
a.output.write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows,indent=2))
