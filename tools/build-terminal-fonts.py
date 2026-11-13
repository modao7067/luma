#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from fontTools import subset
from fontTools.ttLib import TTFont

REPO=Path(__file__).resolve().parents[1]
BLOCKS=[
 ('General Punctuation',0x2000,0x206f),
 ('Miscellaneous Technical',0x2300,0x23ff),
 ('CJK Symbols and Punctuation',0x3000,0x303f),
 ('Hiragana',0x3040,0x309f),
 ('Katakana',0x30a0,0x30ff),
 ('Bopomofo',0x3100,0x312f),
 ('Hangul Compatibility Jamo',0x3130,0x318f),
 ('Katakana Phonetic Extensions',0x31f0,0x31ff),
 ('Enclosed CJK Letters and Months',0x3200,0x32ff),
 ('Vertical Forms',0xfe10,0xfe1f),
 ('CJK Compatibility Forms',0xfe30,0xfe4f),
 ('Small Form Variants',0xfe50,0xfe6f),
 ('Halfwidth and Fullwidth Forms',0xff00,0xffef),
]

def digest(path):
 with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--source',type=Path,default=Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'))
 parser.add_argument('--face',type=int,default=2,help='Noto Sans CJK SC in the Debian Noto CJK collection')
 args=parser.parse_args()
 font=TTFont(args.source,fontNumber=args.face,recalcTimestamp=False)
 source_family=font['name'].getDebugName(1)
 assert source_family=='Noto Sans CJK SC',source_family
 wanted={cp for _,start,end in BLOCKS for cp in range(start,end+1)}
 available=set(font.getBestCmap())
 options=subset.Options();options.name_IDs=['*'];options.name_languages=['*'];options.layout_features=['*']
 sub=subset.Subsetter(options=options);sub.populate(unicodes=wanted&available);sub.subset(font)
 family='Luma CJK Forms';postscript='LumaCjkForms-Regular'
 names={1:family,2:'Regular',3:postscript+'-NotoCJK-2.004',4:family+' Regular',6:postscript,16:family,17:'Regular'}
 for record in list(font['name'].names):
  if record.nameID in names:font['name'].setName(names[record.nameID],record.nameID,record.platformID,record.platEncID,record.langID)
 cff=font['CFF '].cff;cff.fontNames=[postscript]
 top=cff.topDictIndex[0];top.FamilyName=family;top.FullName=family+' Regular'
 filename='LumaCjkForms-Regular.otf'
 asset_dirs=[REPO/'runtime'/('noroot' if app in ('standard','gui') else 'root')/'src/main/assets' for app in ['standard','root']]
 output=asset_dirs[0]/filename;font.save(output)
 shutil.copy2(output,asset_dirs[1]/filename)
 row={'file':filename,'source':'Noto Sans CJK SC 2.004, full declared Unicode-block subset; https://github.com/notofonts/noto-cjk',
      'sourceCollectionSha256':digest(args.source),'sourceFace':args.face,
      'sha256':digest(output),'bytes':output.stat().st_size,'license':'SIL Open Font License 1.1; LumaCjkForms-OFL.txt',
      'blocks':[{'name':name,'start':f'{start:04X}','end':f'{end:04X}'} for name,start,end in BLOCKS]}
 for assets in asset_dirs:
  p=assets/'terminal-fonts-manifest.json';data=json.loads(p.read_text());data=[x for x in data if x['file']!=filename];data.append(row);p.write_text(json.dumps(data,indent=2)+'\n')
 print('Built',filename,row['bytes'],'bytes;',len(TTFont(output).getBestCmap()),'code points')

if __name__=='__main__':main()
