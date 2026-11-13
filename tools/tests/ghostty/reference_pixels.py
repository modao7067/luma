import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import subprocess,sys

def produce(repo,apk,out):
    sys.path.insert(0,str(repo/'tools/tests'))
    from reference_java import materialize,BASELINE
    source=materialize(repo);work=out/'reference';work.mkdir();classes=work/'classes';classes.mkdir();dex=work/'dex';dex.mkdir()
    tests=[]
    for name in ['ViewViewportCheck','SessionCoreCheck']:
        dest=work/(name+'.java');dest.write_bytes(subprocess.check_output(['git','show',BASELINE+':tools/tests/ghostty/'+name+'.java'],cwd=repo));tests.append(dest)
    sources=tests+[repo/'tools/tests/ghostty/reference/LegacyPixelOracle.java']
    for name in ['terminal-emulator','terminal-view','terminal-ghostty']:sources+=list((source/'shared'/name/'java').rglob('*.java'))
    sdk=toolenv.ANDROID_JAR;annotation=repo/'artifacts/maven/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar';r=repo/'runtime/root/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar'
    def host(p):return toolenv.DEVICE_ROOT+'/'+str(Path(p).resolve().relative_to('/workspace'))
    commands=[['javac','--release','17','-nowarn','-cp',':'.join(map(str,[sdk,annotation,r])),'-d',str(classes),*map(str,sources)],['jar','cf',str(work/'reference.jar'),'-C',str(classes),'.'],[toolenv.D8,'--lib',sdk,'--min-api','31','--output',str(dex),str(work/'reference.jar'),str(r)],['android-sh','sh',host(repo/'tools/tests/ghostty/run-android-luma-boot.sh'),host(dex),'com.termux.view.LegacyPixelOracle',host(apk),host(out)]]
    for i,cmd in enumerate(commands):
        log=work/('step-'+str(i)+'.log')
        with log.open('w') as f:result=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=120)
        if result.returncode:raise RuntimeError(log.read_text())
