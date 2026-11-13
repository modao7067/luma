#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
from pathlib import Path
import os,subprocess,shutil
repo=Path(__file__).resolve().parents[2]
work=repo/'tools/tests/terminal-files/build';inputs=work/'input';inputs.mkdir(parents=True,exist_ok=True)
(inputs/"quote ' $(printf WRONG) space.txt").write_text('中文文档\nsecond line\n')
(inputs/'sample.md').write_text('# 排版标题\n\n## 子标题\n\n- **粗体条目**\n\n> 引用段落\n\n```python\ndef preview(x):\n    return x + 1\n```\n\n[网站](https://example.test)\n\n| 表头 | 值 |\n| -- | -- |\n| 中文 | 123 |\n')
fifo=inputs/'pipe'
if not fifo.exists():os.mkfifo(fifo)
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>',
b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 4 0 R /Resources << >> >>',
b'<< /Length 25 >>\nstream\n0 0 0 rg 40 40 80 80 re f\nendstream',
b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 6 0 R /Resources << >> >>',
b'<< /Length 29 >>\nstream\n1 0 0 rg 100 100 150 150 re f\nendstream']
for index in [3,5]:
 stream=objects[index].split(b'stream\n',1)[1].split(b'endstream',1)[0]
 objects[index]=f'<< /Length {len(stream)} >>\nstream\n'.encode()+stream+b'endstream'
data=bytearray(b'%PDF-1.4\n');offsets=[0]
for i,obj in enumerate(objects,1):offsets.append(len(data));data.extend(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
xref=len(data);data.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
for offset in offsets[1:]:data.extend(f'{offset:010d} 00000 n \n'.encode())
data.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode());(inputs/'sample.pdf').write_bytes(data)
classes=work/'classes';dex=work/'dex'
if classes.exists():shutil.rmtree(classes)
classes.mkdir();dex.mkdir(exist_ok=True)
sdk=toolenv.ANDROID_JAR
shared=repo/'shared/ui/java/io/github/modao7067/luma/shared';generated=work/'src';generated.mkdir(exist_ok=True)
sources=[]
for name in ['TerminalDocuments.java','TerminalSystemLinks.java','TerminalFileTransfer.java','TerminalFileProvider.java','WallpaperImages.java']:
 f=generated/name;f.write_text((shared/name).read_text().replace('io.github.modao7067.luma.shared','io.github.modao7067.luma.root'));sources.append(f)
sources += [repo/'runtime/root/src/main/java/io/github/modao7067/luma/root/TerminalLinkReader.java',repo/'tools/tests/terminal-files/PreviewFileCheck.java']
subprocess.run(['javac','--release','17','-cp',sdk,'-d',str(classes),*map(str,sources)],check=True)
jar=work/'checks.jar';subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
subprocess.run([toolenv.JAVA,'-Xmx256m','-cp',toolenv.D8_JAR,'com.android.tools.r8.D8','--lib',sdk,'--min-api','31','--output',str(dex),str(jar)],check=True)
def host(path):return toolenv.DEVICE_REPO+'/'+str(path.relative_to(repo))
log=work/'preview-file-check.txt';log.unlink(missing_ok=True)
subprocess.run(['android-sh','sh',host(repo/'tools/tests/terminal/run-android.sh'),host(dex),host(jar),'io.github.modao7067.luma.root.PreviewFileCheck',host(work)],check=True,timeout=90)
text=log.read_text();print(text);assert 'ALL PASS' in text and 'AssertionError' not in text
