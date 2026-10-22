#!/usr/bin/env python3
"""Optional build-time fontTools 4.67.0 + brotli 1.2.1; retain every encoded character.
Input: unmodified fonts from the preserved baseline. Output: web/static.
No Android dependency. OFL terms and copyright remain in INTER-LICENSE.txt.
"""
import argparse, json
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.pens.recordingPen import DecomposingRecordingPen
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('web/static'));a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True);rows=[];all_chars=set()
for original in sorted(a.source.glob('inter-*.woff2')):
 font=TTFont(original,recalcTimestamp=False);before=font.getBestCmap();all_chars.update(before)
 options=subset.Options();options.layout_features=['*'];options.name_IDs=['*'];options.name_legacy=True;options.name_languages=['*'];options.recalc_timestamp=False;options.retain_gids=True
 worker=subset.Subsetter(options=options);worker.populate(unicodes=before);worker.subset(font);font.flavor='woff2';dest=a.output/original.name;font.save(dest)
 result=TTFont(dest,recalcTimestamp=False);after=result.getBestCmap();assert before.keys()==after.keys(),original
 old=TTFont(original,recalcTimestamp=False);gs1=old.getGlyphSet();gs2=result.getGlyphSet()
 for codepoint,name in before.items():
  new_name=after[codepoint]
  assert old['hmtx'][name]==result['hmtx'][new_name],name
  first=DecomposingRecordingPen(gs1);second=DecomposingRecordingPen(gs2);gs1[name].draw(first);gs2[new_name].draw(second);assert first.value==second.value,name
 rows.append(dict(file=original.name,beforeBytes=original.stat().st_size,afterBytes=dest.stat().st_size,encodedCharacters=len(before),cmapAdvancesAndOutlinesEqual=True))
assert set(map(ord,'ÇĞİÖŞÜçğıöşü'))<=all_chars
print(json.dumps(dict(fontTools=__import__('fontTools').__version__,fonts=rows,turkishCoverage=True),indent=2))
