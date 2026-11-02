#!/usr/bin/env python3
"""Private, sequential Japanese recognizer screening on the released workflow's crops.
No photos or transcriptions are written into the repository.
"""
import argparse, csv, hashlib, importlib.util, json, math, resource, time, unicodedata
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('baseline', REPO/'scripts/benchmark-ocr.py')
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
_original_normalize = b.normalize
b.normalize = lambda s: _original_normalize(s).replace('，', '、').replace(',', '、')

class Assets(b.Models):
    def __init__(self, detector=None, recognizer=None):
        super().__init__(b.ASSETS/'models'); self.detector=detector; self.recognizer=recognizer
    def model_path(self, name):
        if name == 'ppocr6-det/inference.onnx' and self.detector: return self.detector
        if name == 'ppocr6-rec/inference.onnx' and self.recognizer: return self.recognizer
        return super().model_path(name)

def box_iou(a,c):
    intersection=max(0,min(a[2],c[2])-max(a[0],c[0]))*max(0,min(a[3],c[3])-max(a[1],c[1]))
    return intersection/max(1,(a[2]-a[0])*(a[3]-a[1])+(c[2]-c[0])*(c[3]-c[1])-intersection)

def extract(image, box):
    if len(box) == 4:return image[box[1]:box[3],box[0]:box[2]].copy()
    points=np.float32(box[4]);w=max(2,int(max(np.linalg.norm(points[0]-points[1]),np.linalg.norm(points[2]-points[3]))));h=max(2,int(max(np.linalg.norm(points[0]-points[3]),np.linalg.norm(points[1]-points[2]))))
    matrix=cv2.getPerspectiveTransform(points,np.float32([[0,0],[w,0],[w,h],[0,h]]))
    return cv2.warpPerspective(image,matrix,(w,h),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)

def prepare(evaluation):
    dest=evaluation/'japanese-v071';(dest/'crops').mkdir(parents=True,exist_ok=True)
    gold=json.loads((evaluation/'lm/gold.private.json').read_text());m=Assets();m.native_boxes=True;rows=[]
    for image_name in gold['references']:
        image=cv2.imread(str(evaluation/'input'/image_name));start=time.perf_counter()
        if image_name=='user-03.jpg':image=m.unwarp(image)
        layout=m.ndl_detect(image)
        boxes=m.pp_detect(image) if image_name=='user-01.jpg' else [x[:4] for x in layout]
        detections=time.perf_counter()-start
        ordered=b.ordered([[*box[:4],str(i)] for i,box in enumerate(boxes)])
        crops=[]
        for i,item in enumerate(ordered):
            box=boxes[int(item[4])];crop=extract(image,box)
            if crop.size==0:continue
            path=dest/'crops'/f'{image_name}-{i:03}.png';cv2.imwrite(str(path),crop)
            closest=max(layout,key=lambda x:box_iou(box,x)) if layout else None
            crops.append(dict(file=str(path),box=[int(x) for x in box[:4]],tier=int(closest[5]) if closest is not None else 1))
        rows.append(dict(image=image_name,crops=crops,preparation_seconds=detections,rectified=image_name=='user-03.jpg'))
        print(json.dumps(dict(image=image_name,crops=len(crops))),flush=True)
    (dest/'crops.private.json').write_text(json.dumps(rows,indent=2));return dest

def yomi_input(image, variant):
    if image.shape[0]>image.shape[1]*2:image=cv2.rotate(image,cv2.ROTATE_90_COUNTERCLOCKWISE)
    h,w=image.shape[:2];scale=min(32/h,800/w)
    if variant.startswith('large'):scale=min(1,scale)
    rh,rw=max(1,int(h*scale)),max(1,int(w*scale))
    width=800 if variant.startswith('large') else min(800,math.ceil((rw+96)/8)*8)
    content=cv2.resize(image,(rw,rh),interpolation=cv2.INTER_AREA if rh<=h else cv2.INTER_LINEAR)
    canvas=np.zeros((32,width,3),np.uint8);canvas[:rh,:rw]=content[:,:,::-1]
    return ((canvas.astype(np.float32)/127.5-1).transpose(2,0,1))[None]

class Yomi:
    def __init__(self, model_dir, variant):
        self.variant=variant;self.path=model_dir/(variant.removeprefix('yomi-')+'.onnx')
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.log_severity_level=3
        start=time.perf_counter();self.session=ort.InferenceSession(str(self.path),options,providers=['CPUExecutionProvider']);self.load_seconds=time.perf_counter()-start
        resources=model_dir/'official-source/src/yomitoku/resource'
        self.chars=['']+list((resources/('charsetv2.txt' if variant.startswith('yomi-large') else 'charsetv3.txt')).read_text())
        self.expand={}
        if not variant.startswith('yomi-large'):
            with (resources/'character_post_expand_table_v3.csv').open() as stream:
                rows=list(csv.reader(stream))
            for row in rows[1:]:
                if len(row)>=2:self.expand[row[0]]=row[1]
        self.bytes=self.path.stat().st_size
    def read(self,image,tier=1):
        output=self.session.run(None,{'input':yomi_input(image,self.variant.removeprefix('yomi-'))})[0][0]
        assert output.shape[1]==len(self.chars),(output.shape,len(self.chars))
        ids=output.argmax(-1);stop=next((i for i,x in enumerate(ids) if x==0),len(ids));text=''.join(self.chars[x] for x in ids[:stop])
        if self.variant.startswith('yomi-large'):text=unicodedata.normalize('NFKC',text)
        else:text=''.join(self.expand.get(c,c) for c in text)
        logits=output[:stop];confidence=0.
        if stop:
            peak=logits.max(-1);confidence=float(np.mean(1/np.exp(logits-peak[:,None]).sum(-1)))
        return text,confidence

def screen(evaluation,model_dir,variant,repeat):
    dest=evaluation/'japanese-v071';data=json.loads((dest/'crops.private.json').read_text());gold=json.loads((evaluation/'lm/gold.private.json').read_text())['references']
    if variant.startswith('yomi-'):recognizer=Yomi(model_dir,variant);read=recognizer.read
    else:
        path={'pp-small':b.ASSETS/'models/ppocrv6-small-rec.onnx','pp-medium':b.ASSETS/'models/ppocrv6-medium-rec.onnx'}.get(variant)
        recognizer=Assets(recognizer=path)
        if variant=='ndl':
            def read(image,tier):
                text=recognizer.ndl_recognize(image,tier);return text,recognizer.last_confidence
        else:
            def read(image,tier):
                text=recognizer.pp_recognize(image);return text,recognizer.last_confidence
    results=[]
    for page in data:
        crops=[cv2.imread(c['file']) for c in page['crops']];runs=[];predictions=[]
        # First page pass includes session loading; later passes measure warmed kernels.
        for iteration in range(repeat+1):
            rows=[];start=time.perf_counter()
            for crop,entry in zip(crops,page['crops']):
                then=time.perf_counter();text,confidence=read(crop,entry['tier']);rows.append(dict(text=text,confidence=confidence,seconds=time.perf_counter()-then))
            runs.append(time.perf_counter()-start);predictions=rows
        text='\n'.join(x['text'] for x in predictions)
        errors=[b.excerpt_errors(ref['text'] if isinstance(ref,dict) else ref,text) for ref in gold[page['image']]]
        row=dict(image=page['image'],seconds=runs[1:],median_seconds=float(np.median(runs[1:])),first_pass_seconds=runs[0],errors=sum(x[0] for x in errors),characters=sum(x[1] for x in errors),spans=errors,rows=predictions)
        results.append(row);print(json.dumps({k:row[k] for k in ['image','median_seconds','errors','characters']}),flush=True)
    output=dict(variant=variant,runtime=ort.__version__,repeat=repeat,peak_rss_mib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,images=results)
    if variant.startswith('yomi-'):output.update(model_bytes=recognizer.bytes,load_seconds=recognizer.load_seconds)
    else:output.update(model_bytes=sum(recognizer.model_path(k).stat().st_size for k in recognizer.sessions),load_seconds=recognizer.load_seconds)
    (dest/(variant+'.private.json')).write_text(json.dumps(output,ensure_ascii=False,indent=2))

def main():
    p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('--models',type=Path,required=True);p.add_argument('--prepare',action='store_true');p.add_argument('--recognizer');p.add_argument('--repeat',type=int,default=1);args=p.parse_args();cv2.setNumThreads(1)
    if args.prepare:prepare(args.evaluation)
    elif args.recognizer:screen(args.evaluation,args.models,args.recognizer,args.repeat)
    else:p.error('--prepare or --recognizer is required')
if __name__=='__main__':main()
