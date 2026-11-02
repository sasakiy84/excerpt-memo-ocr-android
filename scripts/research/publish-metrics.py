#!/usr/bin/env python3
"""Export numbers only. Never publish photos, predictions, crops or transcriptions."""
import argparse, hashlib, json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('output',type=Path);a=p.parse_args();root=a.evaluation/'japanese-v071'
def without(rows,*excluded):return [{k:v for k,v in row.items() if k not in excluded} for row in rows]
result=dict(gold_sha256=hashlib.file_digest((a.evaluation/'lm/gold.private.json').open('rb'),'sha256').hexdigest(),recognizers=[],grid=[],retries=json.loads((root/'retries.json').read_text()),finalists=json.loads((root/'finalists.json').read_text()))
for path in sorted(root.glob('*.private.json')):
    if path.name.startswith('grid-'):
        result['grid']+=without(json.loads(path.read_text()),'rows')
    elif path.name!='crops.private.json':
        item=json.loads(path.read_text())
        if not isinstance(item,dict) or 'variant' not in item:continue
        item['images']=without(item['images'],'rows');result['recognizers'].append(item)
result['synthetic']=json.loads((root/'synthetic/results.json').read_text())
result['java_baseline']=without(json.loads((a.evaluation/'lm/java-benchmark-v071-baseline.json').read_text()),'text')
current=a.evaluation/'lm/java-benchmark.json'
if current.exists():result['java_current']=without(json.loads(current.read_text()),'text')
# Fail closed on unexpected text fields, rather than publishing a private string.
def validate(value):
    if isinstance(value,dict):
        assert not ({'text','file','crops','prediction','transcription','references','page'} & value.keys())
        for child in value.values():validate(child)
    elif isinstance(value,list):
        for child in value:validate(child)
validate(result)
# Keep one numeric record per line so regenerated measurements produce readable diffs.
lines=['{']
for index,(key,value) in enumerate(result.items()):
    prefix='  '+json.dumps(key)+': '
    if isinstance(value,list):
        lines.append(prefix+'[')
        lines.extend('    '+json.dumps(row,ensure_ascii=False,separators=(',',':'))+(',' if n<len(value)-1 else '') for n,row in enumerate(value))
        lines.append('  ]'+(',' if index<len(result)-1 else ''))
    else:lines.append(prefix+json.dumps(value,ensure_ascii=False)+(',' if index<len(result)-1 else ''))
lines.append('}');a.output.write_text('\n'.join(lines)+'\n')
