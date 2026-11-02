#!/usr/bin/env python3
"""Offline sequential fallback simulation on identical cached crops, never model racing."""
import argparse, importlib.util, json
from pathlib import Path
spec=importlib.util.spec_from_file_location('metrics',Path(__file__).with_name('score-japanese-grid.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def main():
    p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('--secondary',nargs='+',required=True);a=p.parse_args();root=a.evaluation/'japanese-v071';gold=json.loads((a.evaluation/'lm/gold.private.json').read_text())['references']
    def load(name):return {(r['image'],r['preprocessing'],r['detector']):r for r in json.loads((root/('grid-'+name+'.private.json')).read_text()) if r['ordering']=='xycut'}
    primary=load('pp-small');results=[]
    for name in a.secondary:
        for key,second in load(name).items():
            first=primary[key];assert len(first['rows'])==len(second['rows'])
            for threshold in [.90,.95,.98,.99]:
                selected=[bool(row['text'].strip()) and row['confidence']<threshold for row in first['rows']]
                rows=[second['rows'][i] if retry else first['rows'][i] for i,retry in enumerate(selected)];text='\n'.join(row['text'] for row in rows)
                extra=sum(row['seconds'] for row,retry in zip(second['rows'],selected) if retry)
                result=dict(image=key[0],preprocessing=key[1],detector=key[2],secondary=name,threshold=threshold,retry_rows=sum(selected),estimated_seconds=first['estimated_seconds']+extra,**m.evaluate(gold[key[0]],text,key[0]));results.append(result)
    (root/'retries.json').write_text(json.dumps(results,indent=2))
if __name__=='__main__':main()
