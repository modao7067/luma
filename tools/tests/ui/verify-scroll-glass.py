#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse,pathlib,re,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[3]
UI=ROOT/'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=pathlib.Path,default=ROOT/'verification/gui-scroll-crash-20261102')
parser.add_argument('--centered',action='store_true')
parser.add_argument('--highlight',action='store_true',help='Also exercise the current production highlight shader')
args=parser.parse_args()
OUT=args.output.resolve();OUT.mkdir(parents=True,exist_ok=True)
BASELINE=ROOT/'verification/gui-scroll-crash-20261102'

def block(source, token):
    at=source.index(token)+len(token);start=source.index('{',at);depth=1;end=start+1
    while depth:
        if source[end]=='{':depth+=1
        if source[end]=='}':depth-=1
        end+=1
    return source[start+1:end-1]
def check(chrome,detail):
    body=block(chrome,'CompositionLocalProvider(LocalGlassScene provides null)')
    assert 'content(' in body, 'recorded content must not see its own sampled backdrop'
    overlay=block(detail,'overlay =')
    assert 'ConversationScrollOverlay(' in overlay, 'scroll button must be outside recorded content'
    content=block(detail,') { padding ->')
    assert 'ConversationScrollOverlay(' not in content and 'LiquidGlass(' not in content, 'recorded scene must not consume itself'
check((UI/'LiquidChrome.kt').read_text(),(UI/'SessionDetail.kt').read_text())
old_chrome = (BASELINE/'LiquidChrome.before.kt').read_text() if (BASELINE/'LiquidChrome.before.kt').exists() else (UI/'LiquidChrome.kt').read_text().replace('LocalGlassScene provides null', 'LocalGlassScene provides GlassScene(backdrop, rootOffset)')
old_detail = (BASELINE/'SessionDetail.before.kt').read_text() if (BASELINE/'SessionDetail.before.kt').exists() else (UI/'SessionDetail.kt').read_text()
try:check(old_chrome,old_detail)
except (AssertionError,ValueError):print('PASS negative control: pre-fix layout fails the no-cycle boundary')
else:raise AssertionError('Old layout passed')
print('PASS production scaffold capture boundary and scroll overlay placement')
if args.centered:
    control=block((UI/'SessionDetail.kt').read_text(),'internal fun BoxScope.ConversationScrollOverlay(')
    assert '.align(Alignment.BottomCenter)' in control, 'return-to-bottom must be horizontally centered'
    assert 'LiquidGlass(' in control, 'return-to-bottom must remain glass'
    print('PASS centered glass return-to-bottom control')
work=OUT/'hwui';classes=work/'classes';dex=work/'dex'
classes.mkdir(parents=True,exist_ok=True);dex.mkdir(parents=True,exist_ok=True)
source=(UI/'LiquidChrome.kt').read_text();shader=re.search(r'RuntimeShader\(\s*"""(.*?)"""',source,re.S)[1]
(work/'lens.agsl').write_text(shader)
extra=[]
if args.highlight:
    highlight=re.search(r'BLOOM_STROKE_DUAL = """(.*?)"""',(UI/'SukiGlassHighlight.kt').read_text(),re.S)[1]
    (work/'highlight.agsl').write_text(highlight)
    defaults=(UI/'SukiGlassDefaults.kt').read_text()
    values={name:re.search(r'const val '+name+r' = ([0-9.]+)f',defaults)[1] for name in ['highlightAlpha','highlightStrokeAlpha','highlightInnerBlurDp','highlightWidthDp']}
    (work/'highlight.properties').write_text('\n'.join(k+'='+v for k,v in values.items()))
    extra=[work/'highlight.agsl',work/'highlight.properties']
sdk=toolenv.ANDROID_JAR
subprocess.run(['javac','--release','17','-cp',sdk,'-d',str(classes),str(ROOT/'tools/tests/ui/GlassClipCheck.java'),str(ROOT/'tools/tests/ui/ScrollGlassCheck.java')],check=True)
jar=work/'checks.jar';subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
subprocess.run([toolenv.JAVA,'-cp',toolenv.D8_JAR,'com.android.tools.r8.D8','--lib',sdk,'--min-api','33','--output',str(dex),str(jar)],check=True)
def host(path):return toolenv.DEVICE_REPO+'/'+str(path.relative_to(ROOT))
subprocess.run(['android-sh','sh',host(ROOT/'tools/tests/terminal/run-android.sh'),host(dex),host(jar),'ScrollGlassCheck',host(work),host(work/'lens.agsl'),*map(host,extra)],check=True,timeout=45)
log=(work/'scroll-gpu.txt').read_text();print(log);assert 'PASS:' in log and 'AssertionError' not in log
