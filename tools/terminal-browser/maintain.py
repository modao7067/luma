#!/usr/bin/env python3
import argparse, json, os, shutil, subprocess, time
from pathlib import Path

HERE=Path(__file__).resolve().parent
PIN='69c6653ac39d3fd607aecf82b21b252bfdf284cd'
APP=Path.home()/'.local/share/terminal-browser/app'
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['prepare','build','install','input-fix','restore'])
p.add_argument('--source',type=Path,default=HERE/'build/upstream')
p.add_argument('--app',type=Path,default=APP)
p.add_argument('--backup',type=Path)
a=p.parse_args();src=a.source.resolve();app=a.app.resolve()
def run(cmd,**kw):subprocess.run(list(map(str,cmd)),check=True,**kw)
def write(path,text):
    temporary=path.with_name(path.name+'.luma-new');temporary.write_text(text);shutil.copymode(path,temporary);os.replace(temporary,path)
def fix_wheel_input(code):
    start=code.find('      wheel(event) {\n')
    end=code.find('      wheelTick(event) {\n',start)
    if start<0 or end<0:raise SystemExit('Unrecognized v0.15.3 PageInput wheel code; no changes made')
    block=code[start:end]
    marker='        this.lastX = Math.max(0, Math.round(event.x / scale));\n        this.lastY = Math.max(0, Math.round(event.y / scale));\n'
    if marker in block:return code
    prefix='      wheel(event) {\n        this.syncFocus();\n'
    scale='        const scale = this.target.scale();\n'
    if not block.startswith(prefix) or block.count(scale)!=1:
        raise SystemExit('Unrecognized wheel coordinate handling; no changes made')
    block=block.replace(scale,'',1).replace(prefix,prefix+scale+marker,1)
    return code[:start]+block+code[end:]
if a.action=='prepare':
    if src.exists():raise SystemExit('Source already exists; use a fresh private directory.')
    src.parent.mkdir(parents=True,exist_ok=True)
    run(['git','clone','--depth','1','--branch','v0.15.3','https://github.com/zenbu-labs/terminal-browser.git',src])
    if subprocess.check_output(['git','rev-parse','HEAD'],cwd=src,text=True).strip()!=PIN:raise SystemExit('Upstream tag no longer matches fixed commit.')
    run(['git','apply',HERE/'ubuntu-v0.15.3.patch'],cwd=src)
elif a.action=='build':
    if subprocess.check_output(['git','rev-parse','HEAD'],cwd=src,text=True).strip()!=PIN:raise SystemExit('Wrong source revision')
    run(['cargo','build','--release','--locked','-p','pixel-node','-j','2'],cwd=src/'pixel/engine')
elif a.action=='input-fix':
    if (app/'VERSION').read_text().strip()!='v0.15.3':raise SystemExit('Only terminal-browser v0.15.3 is supported')
    browser_main=app/'browser/dist/main.js'
    original=browser_main.read_text();patched=fix_wheel_input(original)
    if patched==original:
        print('Wheel position fix already present. No processes restarted.')
    else:
        backup=(a.backup or HERE/'build'/('input-backup-'+str(time.time_ns()))).resolve()
        backup.mkdir(parents=True,exist_ok=False)
        shutil.copy2(browser_main,backup/'browser-main.js')
        (backup/'manifest.json').write_text(json.dumps([{'path':str(browser_main),'saved':'browser-main.js'}],indent=2)+'\n')
        write(browser_main,patched)
        print('Installed wheel position fix for future browser daemons. No processes restarted. Backup:',backup)
elif a.action=='restore':
    if not a.backup:raise SystemExit('--backup is required')
    manifest=json.loads((a.backup/'manifest.json').read_text())
    for entry in manifest:
        destination=Path(entry['path']); saved=a.backup/entry['saved']
        temporary=destination.with_name(destination.name+'.luma-restore');shutil.copy2(saved,temporary);os.replace(temporary,destination)
    print('Restored saved files. Existing processes are unchanged; use the restored version after natural close/reopen.')
else:
    if (app/'VERSION').read_text().strip()!='v0.15.3':raise SystemExit('Only terminal-browser v0.15.3 is supported; port/review before upgrading.')
    binary=src/'pixel/engine/target/release/libpixel_node.so'
    if not binary.is_file():raise SystemExit('Build pixel-node first')
    if subprocess.check_output(['git','rev-parse','HEAD'],cwd=src,text=True).strip()!=PIN:raise SystemExit('Wrong source revision')
    main=app/'cli/dist/main.js';wrapper=app/'bin/terminal-browser';browser_main=app/'browser/dist/main.js'
    native=app/'browser/node_modules/@zenbu-labs/pixel-native-linux-arm64/pixel.node'
    config=Path(os.environ.get('TERMINAL_BROWSER_CONFIG_DIR',str(Path.home()/'.config/terminal-browser')))/'settings.json'
    js=main.read_text()
    old='"--screen-info={8192x8192}"'
    dyn='`--screen-info={${process.env.TERMINAL_BROWSER_SCREEN_SIZE || "1272x2772"}}`'
    flags=dyn+', "--disable-frame-rate-limit", "--disable-features=FrameIntervalDecider"'
    if old not in js and 'TERMINAL_BROWSER_SCREEN_SIZE' not in js:raise SystemExit('Unrecognized launch code')
    if 'argv = ["--no-sandbox", ...argv]' not in js or '"--mute-audio"' not in js or '"--disable-gpu"' not in js:raise SystemExit('Apply the documented Ubuntu chroot launch adaptation first')
    bjs=browser_main.read_text()
    if bjs.startswith('if (process.env.LUMA_FRAME_TRACE)'):
        bjs=bjs.split('\n',1)[1]
    bjs=fix_wheel_input(bjs)
    backup=(a.backup or HERE/'build'/('backup-'+str(time.time_ns()))).resolve();backup.mkdir(parents=True,exist_ok=False)
    manifest=[]
    for i,path in enumerate([main,wrapper,native,config,browser_main]):
        saved=str(i)+'-'+path.name;shutil.copy2(path,backup/saved);manifest.append({'path':str(path),'saved':saved})
    (backup/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    if old in js:js=js.replace(old,flags)
    elif '"--disable-features=FrameIntervalDecider"' not in js:js=js.replace(dyn,flags)
    write(main,js)
    write(browser_main,bjs)
    shell=wrapper.read_text()
    marker='export ELECTRON_RUN_AS_NODE=1'
    if 'PIXEL_FPS=' not in shell:
        shell=shell.replace(marker,marker+'\nexport PIXEL_FPS="${PIXEL_FPS:-120}"')
    if 'TERMINAL_BROWSER_INLINE_MBPS=' not in shell:
        shell=shell.replace(marker,marker+'\nexport TERMINAL_BROWSER_INLINE_MBPS="${TERMINAL_BROWSER_INLINE_MBPS:-48}"\nexport TERMINAL_BROWSER_IMAGE_BUDGET_MIB="${TERMINAL_BROWSER_IMAGE_BUDGET_MIB:-32}"')
    if 'TERMINAL_BROWSER_SCREEN_SIZE=' not in shell:
        shell=shell.replace(marker,marker+'\n'+'export TERMINAL_BROWSER_SCREEN_SIZE="${TERMINAL_BROWSER_SCREEN_SIZE:-$(python3 -c \'import fcntl,termios,struct; r,c,w,h=struct.unpack("HHHH",fcntl.ioctl(0,termios.TIOCGWINSZ,bytes(8))); print(str(w)+"x"+str(h) if w and h else "1272x2772")\' 2>/dev/null)}"')
    write(wrapper,shell)
    temporary=native.with_name('pixel.node.luma-new');shutil.copy2(binary,temporary);os.replace(temporary,native)
    settings=json.loads(config.read_text());settings.update({'render.fps':'120','render.presenter':'patched'})
    write(config,json.dumps(settings,indent=2)+'\n')
    print('Installed for future browser daemons; no processes restarted. Backup:',backup)
