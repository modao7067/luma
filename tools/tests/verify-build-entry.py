#!/usr/bin/env python3
from pathlib import Path
import shutil,subprocess,tempfile
R=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='luma-entry-') as tmp:
 p=Path(tmp);(p/'tools').mkdir();shutil.copyfile(R/'tools/luma',p/'tools/luma')
 (p/'gradlew').write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n');(p/'gradlew').chmod(0o755)
 for action in ['build','lint']:
  for target in ['gui','luma','all']:
   output=subprocess.check_output(['bash',str(p/'tools/luma'),action,target],text=True).splitlines()
   expected=['gui']
   tasks=[x for x in output if x.startswith(':apps:')]
   assert len(tasks)==len(expected),(action,target,tasks)
   for name in expected:
    variant='lintDebug' if action=='lint' else 'assembleRelease'
    assert f':apps:{name}:{variant}' in tasks
   assert '--offline' in output and 'clean' not in output
   if action=='lint':assert ':runtime:noroot:lintDebug' in output and ':runtime:root:lintDebug' in output
 print('PASS gui/all targets, legacy luma alias and offline incremental dispatch')
 invalid=subprocess.run(['bash',str(p/'tools/luma'),'build','root'],capture_output=True)
 assert invalid.returncode==2

 for pattern in [None, '*QueueAgentsInteractionTest*']:
  args=['bash',str(p/'tools/luma'),'test-ui'] + ([pattern] if pattern else [])
  output=subprocess.check_output(args,text=True,cwd='/tmp').splitlines()
  assert output[output.index('-p')+1]==str(p)
  assert output[output.index('-I')+1]==str(p/'tools/tests/ui/compose/compose-tests.gradle')
  assert output[output.index('--tests')+1]==(pattern or 'io.github.modao7067.luma.gui.ui.*')
  assert ':apps:gui:testDebugUnitTest' in output and '--offline' in output
  assert not any('assemble' in arg or 'install' in arg for arg in output)
 invalid=subprocess.run(['bash',str(p/'tools/luma'),'test-ui','one','two'],capture_output=True)
 assert invalid.returncode==2
 print('PASS opt-in offscreen Compose test dispatch, pattern, cwd independence, and argument validation')
