#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,json,os,stat,zipfile,lzma,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--xz',action='store_true');p.add_argument('builder',type=Path);p.add_argument('base',type=Path);p.add_argument('assets',type=Path);a=p.parse_args()
root=a.builder.resolve();assets=a.assets;archive=assets/('debian-arm64.zip.xz' if a.xz else 'debian-arm64.zip');temporary=archive.with_suffix(archive.suffix+'.partial');zip_output=a.builder.parent/'rootfs-stored.zip' if a.xz else temporary
base_manifest=json.loads((a.base/'rootfs-manifest.json').read_text());base_modes=dict(base_manifest['modes'])
empty=('dev','proc','sys','tmp','run','workspace','opt/luma','var/lib/apt/lists','var/cache/apt','var/log','var/cache/debconf','var/lib/systemd')
exclude=('root/.npm','root/.local/share/luma-shell','root/.cache/blesh','core','root/.bash_history','root/.python_history','root/.lesshst','var/lib/dpkg/status-old')
def skip(name):
 if Path(name).name.startswith(('.l2s.','.proot.l2s.')):return True
 if any(name.startswith(x+'/') for x in empty):return True
 if any(name==x or name.startswith(x+'/') for x in exclude):return True
 if name.startswith('root/.cache/') and not name.startswith('root/.cache/bat/'):return True
 return False
modes=[];links=[];size=files=0;names=[]
with zipfile.ZipFile(a.base/'debian-arm64.zip') as old,zipfile.ZipFile(zip_output,'w',zipfile.ZIP_STORED if a.xz else zipfile.ZIP_DEFLATED,compresslevel=None if a.xz else 9,allowZip64=True) as z:
 restore={n:old.read(n) for n in ['etc/resolv.conf','etc/profile.d/luma.sh'] if n in old.namelist()}
 for folder,dirs,entries in os.walk(root,followlinks=False):
  dirs.sort();entries.sort()
  for entry in dirs[:]+entries:
   file=Path(folder)/entry;name=file.relative_to(root).as_posix()
   if skip(name):
    if entry in dirs:dirs.remove(entry)
    continue
   info=file.lstat()
   if stat.S_ISLNK(info.st_mode):
    target=os.readlink(file)
    assert not target.startswith(('/data/','/proc/1/root',str(root))) and '.l2s.' not in target,f'{name} -> {target}'
    links.append([name,target])
    if entry in dirs:dirs.remove(entry)
   elif stat.S_ISDIR(info.st_mode):
    modes.append([name,stat.S_IMODE(info.st_mode)&0o1777]);z.writestr(name+'/',b'')
   elif stat.S_ISREG(info.st_mode):
    assert name.endswith('/copyright') or not any(x in name.split('/') for x in ['.ssh','.git','.aws','.codex','.claude','.grok']),name
    assert not name.endswith(('.pem','.key','.jks','.keystore')) or name.startswith('usr/'),name
    data=restore.get(name)
    if data is not None:z.writestr(name,data);n=len(data)
    else:z.write(file,name);n=info.st_size
    size+=n;files+=1;names.append(name);modes.append([name,base_modes.get(name,stat.S_IMODE(info.st_mode))&0o1777])
   else:raise AssertionError('Special node would be packaged: '+name)
 for name in ['dev','proc','sys','tmp','run','run/lock','workspace','opt/luma','var/lib/apt/lists','var/lib/apt/lists/partial','var/cache/apt','var/cache/apt/archives','var/cache/apt/archives/partial','var/log','var/log/apt','var/cache/debconf','var/lib/systemd']:
  if name not in dict(modes):z.writestr(name+'/',b'');modes.append([name,0o1777 if name in ('tmp','run/lock') else (0o700 if name.endswith('/partial') else 0o755)])
 if 'etc/mtab' not in dict(links):links.append(['etc/mtab','/proc/self/mounts'])
 for name in ['usr/local/bin/node','usr/local/lib/node_modules/npm/package.json']:
  assert z.getinfo(name).file_size==old.getinfo(name).file_size,name
if a.xz:
 with zip_output.open('rb') as source,lzma.open(temporary,'wb',preset=6) as target:shutil.copyfileobj(source,target,1024*1024)
 zip_output.unlink()
temporary.replace(archive)
manifest={k:v for k,v in base_manifest.items() if k not in ['sha256','unpackedBytes','regularFiles','modes','symlinks']}
manifest.update(archive=archive.name,sha256=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest(),unpackedBytes=size,regularFiles=files,modes=sorted(modes),symlinks=sorted(links))
(assets/'rootfs-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,separators=(',',':')))
print(json.dumps({k:v for k,v in manifest.items() if k not in ['modes','symlinks']},indent=2))
if a.xz:(assets/'debian-arm64.zip').unlink(missing_ok=True)
print('archiveBytes',archive.stat().st_size,'baseArchiveBytes',(a.base/'debian-arm64.zip').stat().st_size)
