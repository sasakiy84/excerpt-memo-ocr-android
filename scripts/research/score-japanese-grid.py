#!/usr/bin/env python3
"""Evaluate cached candidate boxes sequentially, with optional layout ordering."""
import argparse, ast, importlib, importlib.util, json, sys, time, types
from pathlib import Path
import cv2
import numpy as np

spec=importlib.util.spec_from_file_location('study',Path(__file__).with_name('benchmark-japanese.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s);b=s.b

# Manually checked order of the existing private transcriptions. The book's
# section heading was transcribed last but precedes its following paragraphs.
REFERENCE_ORDER={'user-01.jpg':[0,1,5,2,3,4],'user-02.jpg':[0,1,2],'user-03.jpg':list(range(7))}

def load_order(source):
    for name,path in [('yomitoku',source),('yomitoku.utils',source/'utils')]:
        module=types.ModuleType(name);module.__path__=[str(path)];sys.modules[name]=module
    # These two upstream geometric functions do not use NetworkX. Load their
    # original definitions alone, keeping the full research/server stack optional.
    path=source/'utils/misc.py';tree=ast.parse(path.read_text());names={'is_intersected_vertical','is_intersected_horizontal'}
    functions=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in names];assert len(functions)==2
    misc=types.ModuleType('yomitoku.utils.misc');exec(compile(ast.Module(body=functions,type_ignores=[]),str(path),'exec'),misc.__dict__);sys.modules[misc.__name__]=misc
    return importlib.import_module('yomitoku.reading_order').prediction_reading_order

class Element:
    def __init__(self,box,index):self.box=box;self.index=index;self.order=0
    def dict(self):return {'box':self.box}

def order_records(records,regions,algorithm):
    if len(records)<2:return list(range(len(records)))
    vertical=sum(c['box'][3]-c['box'][1]>(c['box'][2]-c['box'][0])*1.2 for c in records)>len(records)/2
    direction='right2left' if vertical else 'top2bottom'
    if regions is None:
        elements=[Element(c['box'],i) for i,c in enumerate(records)];algorithm(elements,direction);return [x.index for x in sorted(elements,key=lambda x:x.order)]
    groups={};boxes={};labels={}
    for i,crop in enumerate(records):
        box=crop['box'];best=None;area=0
        for n,region in enumerate(regions):
            if region['label'] in [0,1]:continue
            r=region['box'];overlap=max(0,min(r[2],box[2])-max(r[0],box[0]))*max(0,min(r[3],box[3])-max(r[1],box[1]))
            score=overlap/max(1,(box[2]-box[0])*(box[3]-box[1]))
            if score>.5 and score>area:best=n;area=score
        key=best if best is not None else len(regions)+i
        groups.setdefault(key,[]).append(i);boxes[key]=regions[best]['box'] if best is not None else box;labels[key]=regions[best]['label'] if best is not None else 2
    elements=[Element(boxes[k],k) for k in groups];headers=[e for e in elements if labels[e.index]==4];footers=[e for e in elements if labels[e.index]==5];body=[e for e in elements if labels[e.index] not in [4,5]]
    for values,flow in [(headers,'left2right'),(body,direction),(footers,'left2right')]:algorithm(values,flow)
    result=[]
    for block in sorted(headers,key=lambda e:e.order)+sorted(body,key=lambda e:e.order)+sorted(footers,key=lambda e:e.order):
        members=[Element(records[i]['box'],i) for i in groups[block.index]];algorithm(members,direction);result.extend(e.index for e in sorted(members,key=lambda e:e.order))
    return result

def score(reference,prediction):
    ref,pred=b.normalize(reference),b.normalize(prediction);previous=[0]*(len(pred)+1)
    for i,a in enumerate(ref,1):
        current=[i]
        for j,c in enumerate(pred,1):current.append(min(previous[j]+1,current[-1]+1,previous[j-1]+(a!=c)))
        previous=current
    error=min(previous);return error,len(ref),previous.index(error)

def evaluate(refs,text,name):
    spans=[score(ref,text) for ref in refs];positions={i:item[2] for i,item in enumerate(spans) if item[0]/item[1]<=.1};order=REFERENCE_ORDER[name];inversions=0;pairs=0
    for n,i in enumerate(order):
        for j in order[n+1:]:
            if i not in positions or j not in positions or b.normalize(refs[i]) in b.normalize(refs[j]) or b.normalize(refs[j]) in b.normalize(refs[i]):continue
            pairs+=1;inversions+=positions[i]>positions[j]
    return dict(errors=sum(x[0] for x in spans),characters=sum(x[1] for x in spans),spans=[x[:2] for x in spans],order_inversions=inversions,order_pairs=pairs)

def main():
    p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('--models',type=Path,required=True);p.add_argument('--recognizer',required=True);p.add_argument('--selection',type=Path);args=p.parse_args();cv2.setNumThreads(1)
    root=args.evaluation/'japanese-v071';data=json.loads((root/'grid/grid.private.json').read_text());gold=json.loads((args.evaluation/'lm/gold.private.json').read_text())['references'];algorithm=load_order(args.models/'official-source/src/yomitoku')
    if args.selection:
        allowed={tuple(x) for x in json.loads(args.selection.read_text())};data=[row for row in data if (row['image'],row['preprocessing'],row['detector']) in allowed]
    variant=args.recognizer
    if variant.startswith('yomi-'):
        recognizer=s.Yomi(args.models,variant);read=recognizer.read
    else:
        recognizer=s.Assets(recognizer=b.ASSETS/('models/ppocrv6-medium-rec.onnx' if variant=='pp-medium' else 'models/ppocrv6-small-rec.onnx'))
        if variant=='ndl':
            def read(image,tier):
                text=recognizer.ndl_recognize(image,tier);return text,recognizer.last_confidence
        else:
            def read(image,tier):
                text=recognizer.pp_recognize(image);return text,recognizer.last_confidence
    output=[];cache={};metric_cache={};path=root/('grid-'+variant+'.private.json')
    for entry in data:
        rows=[]
        for item in entry['crops']:
            image=cv2.imread(item['file']);key=(image.shape,image.tobytes(),item['tier'] if variant=='ndl' else 0)
            if key not in cache:
                start=time.perf_counter();text,confidence=read(image,item['tier']);elapsed=time.perf_counter()-start;cache[key]=dict(text=text,confidence=confidence,seconds=elapsed)
            rows.append(cache[key])
        orders={'xycut':list(range(len(rows))),'graph':order_records(entry['crops'],None,algorithm),'layout':order_records(entry['crops'],entry['regions'],algorithm)}
        for ordering,indices in orders.items():
            text='\n'.join(rows[i]['text'] for i in indices);key=(entry['image'],b.normalize(text))
            if key not in metric_cache:metric_cache[key]=evaluate(gold[entry['image']],text,entry['image'])
            metrics=metric_cache[key]
            seconds=float(np.median(entry['preprocessing_seconds']))+entry['layout_seconds']+entry['detection_seconds']+sum(r['seconds'] for r in rows)+(entry['region_seconds'] if ordering=='layout' else 0)
            record=dict(image=entry['image'],preprocessing=entry['preprocessing'],detector=entry['detector'],recognizer=variant,ordering=ordering,estimated_seconds=seconds,**metrics,rows=rows)
            output.append(record)
        path.write_text(json.dumps(output,ensure_ascii=False,indent=2))
        print(json.dumps({k:output[-3][k] for k in ['image','preprocessing','detector','errors','estimated_seconds']}),flush=True)
if __name__=='__main__':main()
