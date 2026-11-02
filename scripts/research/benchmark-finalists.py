#!/usr/bin/env python3
"""Measure complete sequential candidate workflows, without cached boxes or crops.
Plans choose page treatments externally for research, not a user model selector.
"""
import argparse, importlib.util, json, resource, time
from pathlib import Path
import cv2
import numpy as np
spec=importlib.util.spec_from_file_location('grid',Path(__file__).with_name('prepare-japanese-grid.py'))
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g);s=g.s;b=s.b
spec=importlib.util.spec_from_file_location('metrics',Path(__file__).with_name('score-japanese-grid.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def main():
    p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('--models',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--repeat',type=int,default=3);p.add_argument('--output',type=Path,required=True);a=p.parse_args();cv2.setNumThreads(1)
    gold=json.loads((a.evaluation/'lm/gold.private.json').read_text())['references'];results=[]
    for plan in json.loads(a.plan.read_text()):
        layout=g.Layout();detector=g.Db(a.models/'yomi-db.onnx') if plan['detector']=='yomi-db' else s.Assets(detector=a.models/(plan['detector']+'-det.onnx') if plan['detector'] in ['pp-tiny','pp-medium'] else None);detector.native_boxes=True;uv=s.Assets();recognizers={}
        def read(crop,tier,name):
            if name not in recognizers:
                recognizers[name]=s.Yomi(a.models,name) if name.startswith('yomi-') else s.Assets(recognizer=b.ASSETS/('models/ppocrv6-medium-rec.onnx' if name=='pp-medium' else 'models/ppocrv6-small-rec.onnx'))
            value=recognizers[name]
            if name.startswith('yomi-'):return value.read(crop,tier)
            text=value.ndl_recognize(crop,tier) if name=='ndl' else value.pp_recognize(crop)
            return text,value.last_confidence
        source=cv2.imread(str(a.evaluation/'input'/plan['image']));runs=[];retry_counts=[]
        for iteration in range(a.repeat+1):
            start=time.perf_counter();initial=layout.ndl_detect(source)
            image=g.preprocess(source,plan['preprocessing'],uv)
            body=initial if plan['preprocessing']=='none' else layout.ndl_detect(image)
            masked=layout.masked(image)
            boxes=[row[:4] for row in body] if plan['detector']=='ndl' else detector.detect(image) if plan['detector']=='yomi-db' else detector.pp_detect(image)
            if plan['detector']!='ndl':boxes=[box for box in boxes if all(g.overlap(box,block)<=.5 for block in masked)]
            lines=[];retried=0
            for box in boxes:
                crop=s.extract(image,box)
                if not crop.size:continue
                closest=max(body,key=lambda row:s.box_iou(box,row)) if body else None;tier=int(closest[5]) if closest is not None else 1
                text,confidence=read(crop,tier,plan['recognizer'])
                if plan.get('retry') and text.strip() and confidence<plan.get('threshold',.95):text,confidence=read(crop,tier,plan['retry']);retried+=1
                if text.strip():lines.append([*box[:4],text])
            text='\n'.join(row[4] for row in b.ordered(lines));runs.append(time.perf_counter()-start);retry_counts.append(retried)
        metrics=m.evaluate(gold[plan['image']],text,plan['image']);row=dict(**plan,runtime=s.ort.__version__,seconds=runs[1:],median_seconds=float(np.median(runs[1:])),cold_seconds=runs[0],retry_rows=retry_counts[-1],lines=len(lines),peak_process_rss_mib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,**metrics)
        results.append(row);a.output.write_text(json.dumps(results,indent=2));print(json.dumps(row),flush=True)
        # Releasing sessions keeps the next plan from retaining all previous models.
        del layout,detector,uv,recognizers
if __name__=='__main__':main()
