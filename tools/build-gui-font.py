#!/usr/bin/env python3
from pathlib import Path
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'runtime/noroot/src/main/assets/MapleMonoNL-NF-CN-Regular.ttf'
DEST = ROOT / 'apps/gui/app/src/main/assets/LumaGuiHan-Regular.ttf'
HAN_SCALE = 1.12
RANGES = ((0x3400, 0x4dbf), (0x4e00, 0x9fff), (0xf900, 0xfaff),
          (0x20000, 0x2fa1f), (0x30000, 0x3347f))

def is_han(cp):
    return any(lo <= cp <= hi for lo, hi in RANGES)

def closure(font, unicodes):
    options = subset.Options()
    options.layout_features = ['*']
    options.notdef_glyph = False
    sub = subset.Subsetter(options=options)
    sub.populate(unicodes=unicodes)
    sub._closure_glyphs(font)
    return set(sub.glyphs_gsubed), set(sub.glyphs_glyfed)

def main():
    source = TTFont(SOURCE, recalcTimestamp=False)
    result = TTFont(SOURCE, recalcTimestamp=False)
    cmap = source.getBestCmap()
    han, _ = closure(source, [cp for cp in cmap if is_han(cp)])
    _, other_components = closure(source, [cp for cp in cmap if not is_han(cp)])
    assert not han & other_components, 'Han outlines also used by non-Han characters'
    glyphs = source.getGlyphSet()
    asc, desc = source['hhea'].ascent, source['hhea'].descent
    limited = 0
    for name in sorted(han):
        old = source['glyf'][name]
        old.recalcBounds(source['glyf'])
        if not hasattr(old, 'xMin'):
            continue
        advance, _ = source['hmtx'][name]
        half = advance / 2
        ink_half = max(half - old.xMin, old.xMax - half)
        scale = min(HAN_SCALE, advance / (2 * ink_half),
                    (asc - desc) / max(1, old.yMax - old.yMin))
        dy = max(desc - old.yMin * scale, min(0, asc - old.yMax * scale))
        dx = half * (1 - scale)
        recording = DecomposingRecordingPen(glyphs)
        glyphs[name].draw(recording)
        pen = TTGlyphPen(None)
        recording.replay(TransformPen(pen, (scale, 0, 0, scale, dx, dy)))
        transformed = pen.glyph()
        transformed.recalcBounds(result['glyf'])
        result['glyf'][name] = transformed
        result['hmtx'][name] = (advance, transformed.xMin)
        limited += scale < HAN_SCALE
    names = {1: 'Luma GUI Han', 2: 'Regular', 3: 'LumaGuiHan-Regular-1.0',
             4: 'Luma GUI Han Regular', 6: 'LumaGuiHan-Regular',
             16: 'Luma GUI Han', 17: 'Regular'}
    for record in list(result['name'].names):
        if record.nameID in names:
            result['name'].setName(names[record.nameID], record.nameID,
                                   record.platformID, record.platEncID, record.langID)
    DEST.parent.mkdir(parents=True, exist_ok=True)
    result.save(DEST)
    actual = TTFont(DEST)
    assert actual.getBestCmap() == cmap
    assert actual.getGlyphOrder() == source.getGlyphOrder()
    assert all(actual['hmtx'][n][0] == source['hmtx'][n][0] for n in source.getGlyphOrder())
    for name in set(source.getGlyphOrder()) - han:
        assert actual['glyf'][name].compile(actual['glyf']) == source['glyf'][name].compile(source['glyf']), name
    for tag in ('GSUB', 'hhea', 'OS/2'):
        assert actual.getTableData(tag) == source.getTableData(tag), tag
    print(f'PASS {len(cmap)} codepoints; {len(han)} Han/shaping glyphs enlarged ({limited} limited); '
          f'all advances/other outlines/shaping/line metrics retained; {DEST.stat().st_size} added bytes')

if __name__ == '__main__':
    main()
