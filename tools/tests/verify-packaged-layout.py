#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import argparse,json,re,subprocess
R=Path(__file__).resolve().parents[2]
policies=json.loads((R/'tools/release-policy.json').read_text())
def dump(product):
 return subprocess.check_output([toolenv.AAPT2,'dump','xmltree',str(R/policies[product]['apk']),'--file','AndroidManifest.xml'],text=True)
def elements(text,kind):
 lines=text.splitlines();out=[]
 for i,line in enumerate(lines):
  if line.strip().startswith('E: '+kind+' '):
   indent=len(line)-len(line.lstrip());j=i+1
   while j<len(lines) and len(lines[j])-len(lines[j].lstrip())>indent:j+=1
   out.append('\n'.join(lines[i:j]))
 return out
def named(text,kind,name):
 matches=[e for e in elements(text,kind) if re.search(r'android:name\([^\n]+="'+re.escape(name)+'"',e)]
 assert len(matches)==1,(kind,name)
 return matches[0]
def attr(element,name):
 match=re.search(r'android:'+re.escape(name)+r'\([^\n]+?\)=([^\n]+)',element)
 assert match,('missing attribute',name)
 return match[1].split(' (Raw:',1)[0]
def capture_boundary(text,product):
 capture=named(text,'provider','io.github.modao7067.luma.gui.camera.PhotoCaptureProvider')
 assert attr(capture,'authorities')=='"'+policies[product]['package']+'.capture-files"'
 assert attr(capture,'exported')=='false' and attr(capture,'grantUriPermissions')=='true'
 assert 'android:process' not in capture
 metadata=named(capture,'meta-data','android.support.FILE_PROVIDER_PATHS')
 assert len(elements(capture,'meta-data'))==1
 reference=attr(metadata,'resource')
 assert re.fullmatch(r'@0x[0-9a-fA-F]+',reference),reference
 resources=subprocess.check_output([toolenv.AAPT2,'dump','resources',str(R/policies[product]['apk'])],text=True)
 member=re.search(r'resource '+re.escape(reference[1:])+r' xml/photo_capture_paths\n\s+\(\) \(file\) (\S+) type=XML',resources)
 assert member,'capture provider resource must be the precise photo_capture_paths XML'
 paths=subprocess.check_output([toolenv.AAPT2,'dump','xmltree',str(R/policies[product]['apk']),'--file',member[1]],text=True)
 assert re.findall(r'E: ([\w-]+) ',paths)==['paths','cache-path']
 cache=elements(paths,'cache-path')[0]
 assert re.search(r'A: name="photo"(?: |$)',cache) and re.search(r'A: path="photo-capture/"(?: |$)',cache)
 queries=elements(text,'queries');assert len(queries)==1
 assert all(kind in ('queries','intent','action','data','package') for kind in re.findall(r'E: ([\w-]+) ',queries[0]))
 packages=sorted(attr(p,'name').strip('"') for p in elements(queries[0],'package'))
 assert packages==(['moe.shizuku.privileged.api'] if product=='gui' else []),packages
 pairs=[]
 for intent in elements(queries[0],'intent'):
  actions=elements(intent,'action');data=elements(intent,'data')
  assert len(actions)==1 and len(data)<=1
  pairs.append((attr(actions[0],'name').strip('"'),attr(data[0],'mimeType').strip('"') if data else None))
 assert sorted(pairs)==sorted([('android.intent.action.PROCESS_TEXT','text/plain'),('android.media.action.IMAGE_CAPTURE',None)])
 assert 'android.permission.CAMERA' not in text and 'android.permission.QUERY_ALL_PACKAGES' not in text
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target',choices=['all','gui'],default='all')
parser.add_argument('--require-media-capture',action='store_true',help='Require the new bounded capture provider/queries in selected GUI APKs; old APK checks remain usable without it')
args=parser.parse_args()
for product in ['gui']:
 text=dump(product);activities=elements(text,'activity')
 launchers=[x for x in activities if 'android.intent.category.LAUNCHER' in x]
 assert len(launchers)==1
 assert 'io.github.modao7067.luma.gui.MainActivity' in launchers[0]
 providers=[x for x in elements(text,'provider') if '.terminal-files' in x]
 assert len(providers)==1 and 'android:exported' in providers[0] and '=false' in providers[0]
 root=named(text,'activity','io.github.modao7067.luma.root.MainActivity');service=named(text,'service','io.github.modao7067.luma.root.ShellService')
 if product=='gui':
  if args.require_media_capture or 'io.github.modao7067.luma.gui.camera.PhotoCaptureProvider' in text:capture_boundary(text,product)
  assert ':root' in root and ':root' in service
  assert '=false' in root and '=false' in service
  for kind,name in ([('activity','io.github.modao7067.luma.SetupActivity'),('activity','io.github.modao7067.luma.MainActivity'),('service','io.github.modao7067.luma.ShellService'),('service','io.github.modao7067.luma.BackgroundAccessibilityService')] if product=='gui' else []):
   component=named(text,kind,name);assert 'android:process' not in component
  permission=named(text,'permission',policies[product]['package']+'.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
  assert '0x00000002' in permission
 else:
  assert 'android:process' not in root and 'android:process' not in service
  assert '=true' in root
 print('PASS',product,'actual APK launcher, component identities, provider and process isolation')
 if product=='gui' and (args.require_media_capture or 'io.github.modao7067.luma.gui.camera.PhotoCaptureProvider' in text):print('PASS',product,'bounded capture cache provider, PROCESS_TEXT text/plain and IMAGE_CAPTURE queries; no new privileged permissions')
