#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import os
import pty
import subprocess
import struct
import zlib
import shutil
import argparse
import json
import zipfile
REPO=Path(__file__).resolve().parents[2]
TESTS=REPO/'tools/tests/terminal'
BASELINE='2fbcfc9'
R5_BASELINE='7f17bd6a155961329f70d4f5dead92dde5694211'
SDK=toolenv.ANDROID_JAR
ANNOTATIONS=str(REPO/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar')
JAVA=toolenv.JAVA
D8=toolenv.D8_JAR
HOST_ROOT=toolenv.DEVICE_REPO

def host(path):
    return HOST_ROOT+'/'+str(path.relative_to(REPO))

def current_han_ink_fit(source):
    old = "                    scaleX = Math.min(HAN_GLYPH_SCALE, width / mes);"
    new = """                    float halfAdvance = mes / 2.f;
                    float inkHalfWidth = Math.max(halfAdvance - mHanBounds.left,
                            mHanBounds.right - halfAdvance);
                    float widthLimit = inkHalfWidth > 0 ? width / (2.f * inkHalfWidth) : width / mes;
                    scaleX = Math.min(HAN_GLYPH_SCALE, widthLimit);"""
    assert source.count(old) == 1, "Historical Han fit fixture changed"
    return source.replace(old, new)

def sample_protocol(work):
    def chunk(tag,data):return struct.pack('!I',len(data))+tag+data+struct.pack('!I',zlib.crc32(tag+data)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',24,24,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\xff\0\0'*24)*24))+chunk(b'IEND',b'')
    image=work/'sample.png';image.write_bytes(png)
    master,slave=pty.openpty()
    process=subprocess.Popen(['python3',str(REPO/'tools/show-image.py'),str(image),'--width','40'],stdout=slave,stderr=subprocess.PIPE)
    os.close(slave);data=bytearray()
    try:
        while True:
            try:
                block=os.read(master,65536)
                if not block:break
                data.extend(block)
            except OSError:break
    finally:os.close(master)
    assert process.wait(timeout=5)==0,process.stderr.read().decode()
    (work/'show-image-output.bin').write_bytes(data)
    master,slave=pty.openpty()
    process=subprocess.Popen(['python3',str(REPO/'tools/show-image.py'),str(image),'--width','40','--protocol','kitty'],stdout=slave,stderr=subprocess.PIPE)
    os.close(slave);data=bytearray()
    try:
        while True:
            try:
                block=os.read(master,65536)
                if not block:break
                data.extend(block)
            except OSError:break
    finally:os.close(master)
    assert process.wait(timeout=5)==0,process.stderr.read().decode()
    (work/'show-kitty-output.bin').write_bytes(data)

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--reference-java',action='store_true',help='Explicitly test the frozen pre-N0 Java oracle, never production')
parser.add_argument('--markdown-ansi',type=Path,help='Actual guest-rendered Markdown ANSI fixture for offscreen preview')
parser.add_argument('--target',choices=['all','standard','root'],default='all')
parser.add_argument('--check',choices=['all','ImageCheck','KittyGraphicsCheck','CacheRegression','AnnotationCheck','FontCoverageCheck','ModeRestoreCheck','ItalicHanCheck','HanSizeCheck','HyperlinkCheck','NativePreviewCheck','FontFactoryCheck','KernelR4Check','CacheAccountingCheck','R5RegressionCheck'],default='all')
parser.add_argument('--negative-control',action='store_true',help='Disable Kitty APC dispatch in an isolated source copy and require the support-query regression to fail')
args=parser.parse_args()
if not args.reference_java:
    parser.error('Java VT has retired: use tools/tests/ghostty/verify-android.py --case all for current production, or --reference-java for the historical oracle')
from reference_java import materialize
REFERENCE=materialize(REPO)
if args.negative_control and args.check!='KittyGraphicsCheck':
    parser.error('--negative-control requires --check KittyGraphicsCheck')
subprocess.run(['python3',str(REPO/'tools/tests/verify-font-coverage.py')],check=True)
for app in ['standard','root']:
    if args.target not in ('all',app):continue
    work=TESTS/'build'/(app+'-kitty-negative' if args.negative_control else app);work.mkdir(parents=True,exist_ok=True)
    baseline=subprocess.check_output(['git','-C',str(REPO),'show',BASELINE+':apps/'+app+'/terminal-view/src/main/java/com/termux/view/TerminalRenderer.java'],text=True)
    before=work/'BeforeRenderer.java';before.write_text(current_han_ink_fit(baseline).replace('TerminalRenderer','BeforeRenderer'))
    r5_baseline=subprocess.check_output(['git','-C',str(REPO),'show',R5_BASELINE+':shared/terminal-view/java/com/termux/view/TerminalRenderer.java'],text=True)
    r5_before=work/'R5BeforeRenderer.java';r5_before.write_text(current_han_ink_fit(r5_baseline).replace('TerminalRenderer','R5BeforeRenderer'))
    han_before=work/'HanSizeBeforeRenderer.java';han_before.write_text(r5_baseline.replace('TerminalRenderer','HanSizeBeforeRenderer'))
    sources=list((REFERENCE/'shared/terminal-emulator/java').rglob('*.java'))
    if args.negative_control:
        original=REFERENCE/'shared/terminal-emulator/java/com/termux/terminal/TerminalEmulator.java'
        dispatch='mScreen.kittyGraphics().handle(mTerminalControlArgs.substring(1), this);'
        source=original.read_text();assert source.count(dispatch)==1
        negative=work/'negative/TerminalEmulator.java';negative.parent.mkdir(exist_ok=True)
        negative.write_text(source.replace(dispatch,'; // Deliberately ignore Kitty APC for the negative control.'))
        sources=[negative if path==original else path for path in sources]
    sources += [REFERENCE/'shared/terminal-view/java/com/termux/view'/name for name in ['TerminalRenderer.java','BundledTerminalFonts.java','TerminalPreviewScreen.java']]
    sources += [REPO/'shared/ui/java/io/github/modao7067/luma/shared'/name for name in ['FontCacheInvalidator.java','LumaAppComponentFactory.java']]
    sources += list(TESTS.glob('*.java'))+[before,r5_before,han_before]
    classes=work/'classes'
    if classes.exists():shutil.rmtree(classes)
    classes.mkdir()
    subprocess.run(['javac','--release','17','-cp',SDK+':'+ANNOTATIONS,'-d',str(classes),*map(str,sources)],check=True)
    jar=work/'checks.jar';subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
    dex=work/'dex';dex.mkdir(exist_ok=True)
    subprocess.run([JAVA,'-Xmx256m','-cp',D8,'com.android.tools.r8.D8','--lib',SDK,'--min-api','31','--output',str(dex),str(jar)],check=True)
    sample_protocol(work)
    if args.markdown_ansi:
        shutil.copyfile(args.markdown_ansi,work/'markdown.ansi')
    font_zip=work/'fonts.zip'
    source_assets=REPO/'runtime'/('noroot' if app in ('standard','gui') else 'root')/'src/main/assets'
    with zipfile.ZipFile(font_zip,'w',compression=zipfile.ZIP_STORED) as archive:
        for row in json.loads((source_assets/'terminal-fonts-manifest.json').read_text()):
            archive.write(source_assets/row['file'],'assets/'+row['file'])
    for test,log in [('ImageCheck','image-check.txt'),('KittyGraphicsCheck','kitty-graphics-check.txt'),('CacheRegression','cache-regression.txt'),('AnnotationCheck','annotation-check.txt'),('FontCoverageCheck','font-coverage-check.txt'),('ModeRestoreCheck','mode-restore-check.txt'),('ItalicHanCheck','italic-han-check.txt'),('HanSizeCheck','han-size-check.txt'),('HyperlinkCheck','hyperlink-check.txt'),('NativePreviewCheck','native-preview-check.txt'),('FontFactoryCheck','font-factory-check.txt'),('KernelR4Check','kernel-r4-check.txt'),('CacheAccountingCheck','cache-accounting-check.txt'),('R5RegressionCheck','r5-regression-check.txt')]:
        if args.check not in ('all',test):continue
        main_class={'FontCoverageCheck':'com.termux.view.FontCoverageCheck','FontFactoryCheck':'io.github.modao7067.luma.shared.FontFactoryCheck','CacheAccountingCheck':'com.termux.view.CacheAccountingCheck','NativePreviewCheck':'com.termux.view.NativePreviewCheck'}.get(test,test)
        log_path=work/log
        if log_path.exists():log_path.unlink()
        result=subprocess.run(['android-sh','sh',host(TESTS/'run-android.sh'),host(dex),host(font_zip),main_class,host(work)],check=not args.negative_control,timeout=90)
        text=log_path.read_text()
        if args.negative_control:
            assert result.returncode!=0 and 'AssertionError: support query replies immediately before device attributes' in text,text[-4000:]
            print(app,'PASS negative control: ignoring Kitty APC fails support detection',flush=True)
            continue
        assert 'PASS' in text and not any(x in text for x in ['AssertionError','Exception:','FAIL']),text[-4000:]
        print(app,test,text.splitlines()[-1],flush=True)
