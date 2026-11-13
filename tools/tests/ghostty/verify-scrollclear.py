#!/usr/bin/env python3
from pathlib import Path
import argparse,os,subprocess
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--negative-control',action='store_true',help='Require the unpatched private-source variant to fail the pruning regression');p.add_argument('--source',type=Path,default=REPO/'shared/terminal-ghostty/build/ghostty-source');p.add_argument('--zig',type=Path,required=True);p.add_argument('--cache',type=Path,required=True);p.add_argument('--global-cache',type=Path,required=True);a=p.parse_args()
source=a.source.resolve();assert source.is_relative_to(REPO/'shared/terminal-ghostty/build'),'Use only the private build source'
f=source/'src/terminal/Screen.zig';before=f.read_text();assert 'const old_node = self.cursor.page_pin.node;' in before
try:
 candidate=before
 if a.negative_control:
  old="""    // Track whether pruning invalidates the page-local cursor references.
    const old_node = self.cursor.page_pin.node;
    try self.pages.scrollClear();
    if (self.cursor.page_pin.node != old_node) {
        self.cursor.page_pin.garbage = false;
        self.cursorPageReplaced();
    } else {
        self.cursorReload();
    }"""
  assert old in candidate;candidate=candidate.replace(old,'    try self.pages.scrollClear();\n    self.cursorReload();',1)
 f.write_text(candidate+'\n'+(HERE/'scrollclear-prune.zig').read_text())
 env=os.environ.copy();env['ZIG_LOCAL_CACHE_DIR']=str(a.cache);env['ZIG_GLOBAL_CACHE_DIR']=str(a.global_cache)
 result=subprocess.run([str(a.zig.resolve()),'build','test-lib-vt','-Demit-lib-vt','-Doptimize=Debug','-Dtest-filter=Luma scrollClear pruned styled hyperlink cursor'],cwd=source,env=env)
 if a.negative_control:
  assert result.returncode!=0,"Unpatched security negative control unexpectedly passed"
 else:result.check_returncode()
finally:f.write_text(before)
print('PASS unpatched negative control fails as required' if a.negative_control else 'PASS scrollClear pruning regression: low budget, styled/hyperlinked cursor, page actually pruned')
