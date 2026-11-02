#!/usr/bin/env python3
"""Sequential known-text line recognition; global CER, without free boundaries."""
import argparse, importlib.util, json, time
from pathlib import Path
import cv2
spec=importlib.util.spec_from_file_location('study',Path(__file__).with_name('benchmark-japanese.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
def distance(a,b):
    previous=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        row=[i]
        for j,y in enumerate(b,1):row.append(min(previous[j]+1,row[-1]+1,previous[j-1]+(x!=y)))
        previous=row
    return previous[-1]
def main():
    p=argparse.ArgumentParser();p.add_argument('gold',type=Path);p.add_argument('--models',type=Path,required=True);p.add_argument('--recognizers',nargs='+',required=True);a=p.parse_args();cv2.setNumThreads(1)
    data=json.loads(a.gold.read_text())['records'];results=[]
    for name in a.recognizers:
        if name.startswith('yomi-'):model=s.Yomi(a.models,name);read=model.read
        else:
            model=s.Assets(recognizer=s.b.ASSETS/('models/ppocrv6-medium-rec.onnx' if name=='pp-medium' else 'models/ppocrv6-small-rec.onnx'))
            def read(image,tier=1):
                value=model.ndl_recognize(image,tier) if name=='ndl' else model.pp_recognize(image);return value,model.last_confidence
        read(cv2.imread(data[0]['file']),1) # Warm kernels before measured lines.
        rows=[]
        for record in data:
            image=cv2.imread(record['file']);start=time.perf_counter();text,confidence=read(image,1);seconds=time.perf_counter()-start
            ref,pred=s.b.normalize(record['text']),s.b.normalize(text)
            rows.append(dict(font=record['font'],size=record['size'],effect=record['effect'],errors=distance(ref,pred),characters=len(ref),seconds=seconds))
        result=dict(recognizer=name,errors=sum(x['errors'] for x in rows),characters=sum(x['characters'] for x in rows),seconds=sum(x['seconds'] for x in rows),rows=rows);results.append(result)
        print(json.dumps({k:v for k,v in result.items() if k!='rows'}),flush=True)
        (a.gold.parent/'results.json').write_text(json.dumps(results,indent=2))
        del model
if __name__=='__main__':main()
