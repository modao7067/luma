#!/usr/bin/env python3
from pathlib import Path
import re
import xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[2]
A='{http://schemas.android.com/apk/res/android}'
settings=(R/'settings.gradle').read_text()
modules=['apps:gui','runtime:noroot','runtime:root','shared:terminal-emulator','shared:terminal-view']
for name in modules:assert "':"+name+"'" in settings,name
for product in ['gui']:
 build=(R/f'apps/{product}/app/build.gradle').read_text()
 assert "id 'com.android.application'" in build
 assert "project(':runtime:root')" in build or "gradle/gui-dependencies.gradle" in build
assert "project(':runtime:noroot')" in (R/'apps/gui/app/build.gradle').read_text()
assert sorted(p.parent.parent.name for p in R.glob('apps/*/app/build.gradle'))==['gui']
assert "project(':runtime:root')" in (R/'gradle/gui-dependencies.gradle').read_text()
for runtime in ['root','noroot']:
 build=(R/f'runtime/{runtime}/build.gradle').read_text()
 assert "id 'com.android.library'" in build
 assert "project(':shared:terminal-view')" in build
 assert 'terminal-ui.gradle' in build
 assert not list((R/f'runtime/{runtime}').glob('src/main/java/com/termux/**/*.java'))
assert "api project(':shared:terminal-emulator')" in (R/'shared/terminal-view/build.gradle').read_text()
for product in ['gui']:
 app=ET.parse(R/f'apps/{product}/app/src/main/AndroidManifest.xml').find('application')
 providers=app.findall('provider')
 assert len(providers)==2
 if True:
  terminal_name='io.github.modao7067.luma.TerminalArtifactProvider'
  expected={terminal_name:'${applicationId}.terminal-files','io.github.modao7067.luma.gui.camera.PhotoCaptureProvider':'${applicationId}.capture-files'}
  assert {x.get(A+'name'):x.get(A+'authorities') for x in providers}==expected
  for provider in providers:
   assert provider.get(A+'exported')=='false' and provider.get(A+'grantUriPermissions')=='true'
   assert provider.get(A+'process') is None
  capture=next(x for x in providers if x.get(A+'name')=='io.github.modao7067.luma.gui.camera.PhotoCaptureProvider')
  metadata=capture.findall('meta-data')
  assert len(metadata)==1 and metadata[0].attrib=={A+'name':'android.support.FILE_PROVIDER_PATHS',A+'resource':'@xml/photo_capture_paths'}
  assert len(next(x for x in providers if x.get(A+'name')==terminal_name))==0
  manifest=ET.parse(R/f'apps/{product}/app/src/main/AndroidManifest.xml').getroot()
  assert not {x.get(A+'name') for x in manifest.findall('uses-permission')} & {'android.permission.CAMERA','android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.QUERY_ALL_PACKAGES'}
  queries=manifest.findall('queries')
  assert len(queries)==1 and all(x.tag=='intent' for x in queries[0])
  pairs=[]
  for intent in queries[0]:
   action=intent.findall('action');data=intent.findall('data')
   assert len(action)==1 and len(data)<=1 and all(x.tag in ('action','data') for x in intent)
   assert set(action[0].attrib)=={A+'name'}
   if data:assert set(data[0].attrib)=={A+'mimeType'}
   pairs.append((action[0].get(A+'name'),data[0].get(A+'mimeType') if data else None))
  assert sorted(pairs)==sorted([('android.intent.action.PROCESS_TEXT','text/plain'),('android.media.action.IMAGE_CAPTURE',None)])
 launchers=[x for x in app.findall('activity') if any(y.get(A+'name')=='android.intent.category.LAUNCHER' for y in x.findall('intent-filter/category'))]
 assert len(launchers)==1
 assert launchers[0].get(A+'name')=='io.github.modao7067.luma.gui.MainActivity'
 if True:
  for tag in ['activity','service']:
   component=next(x for x in app.findall(tag) if x.get(A+'name').startswith('io.github.modao7067.luma.root.'))
   assert component.get(A+'process')==':root'
paths=ET.parse(R/'apps/gui/app/src/main/res/xml/photo_capture_paths.xml').getroot()
assert paths.tag=='paths' and len(paths)==1 and paths[0].tag=='cache-path'
assert paths[0].attrib=={'name':'photo','path':'photo-capture/'} and len(paths[0])==0
for owner,expected_preferences in [('runtime/root','appearance'),('apps/gui/app','root-appearance')]:
 values=ET.parse(R/owner/'src/main/res/values/runtime-config.xml').getroot()
 assert values.find("integer[@name='root_notification_id']") is None
 assert values.find("string[@name='root_appearance_preferences']").text==expected_preferences
assert 'startForeground' not in (R/'runtime/root/src/main/java/io/github/modao7067/luma/root/ShellService.java').read_text()
assert 'root_appearance_preferences' in (R/'runtime/root/src/main/java/io/github/modao7067/luma/root/MainActivity.java').read_text()
for product in ['root','standard']:
 for mod in ['terminal-view','terminal-emulator']:
  assert not (R/f'apps/{product}/{mod}/build.gradle').exists()
print('PASS single application, shared runtime/terminal graph, exact terminal/capture providers, bounded queries/cache path, root process/notification/preferences isolation')
