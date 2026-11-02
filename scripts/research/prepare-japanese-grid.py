#!/usr/bin/env python3
"""Cache Android-compatible preprocessing and text-box candidates for private studies."""
import argparse, importlib.util, json, math, time
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort

spec=importlib.util.spec_from_file_location('study',Path(__file__).with_name('benchmark-japanese.py'))
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
b=s.b

class Recording:
    def __init__(self,inner,owner):self.inner=inner;self.owner=owner
    def __getattr__(self,name):return getattr(self.inner,name)
    def run(self,*args):
        result=self.inner.run(*args);self.owner.last_layout=result;return result

class Layout(s.Assets):
    def session(self,name):
        inner=super().session(name)
        return Recording(inner,self) if name=='deim-s-1024x1024.onnx' else inner
    def masked(self,image):
        labels,boxes,scores,*rest=self.last_layout;side=max(image.shape[:2]);result=[]
        for label,box,score in zip(labels.reshape(-1),boxes.reshape(-1,4),scores.reshape(-1)):
            if score>.2 and label in [10,11,16]:result.append([float(x)*side/1024 for x in box])
        return result

class Db:
    def __init__(self,path):
        self.path=path;options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1;options.log_severity_level=3
        self.session=ort.InferenceSession(str(path),options,providers=['CPUExecutionProvider'])
    def detect(self,image):
        h,w=image.shape[:2];ratio=min(1280/min(h,w),1600/max(h,w));rh=max(32,int(h*ratio/32)*32);rw=max(32,int(w*ratio/32)*32)
        x=cv2.resize(image[:,:,::-1],(rw,rh),interpolation=cv2.INTER_AREA).astype(np.float32)/255
        x=((x-np.array([.485,.456,.406],np.float32))/np.array([.229,.224,.225],np.float32)).transpose(2,0,1)[None]
        pred=self.session.run(None,{'input':x})[0][0,0]
        contours,_=cv2.findContours((pred>.3).astype(np.uint8)*255,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE);boxes=[]
        for contour in contours[:1500]:
            rect=cv2.minAreaRect(contour)
            if min(rect[1])<2:continue
            points=cv2.boxPoints(rect);left,top=np.floor(contour.min((0,1))).astype(int);right,bottom=np.ceil(contour.max((0,1))).astype(int)
            left,top=max(0,left),max(0,top);right,bottom=min(pred.shape[1]-1,right),min(pred.shape[0]-1,bottom)
            mask=np.zeros((bottom-top+1,right-left+1),np.uint8);local=contour.copy();local[:,:,0]-=left;local[:,:,1]-=top;cv2.fillPoly(mask,[local],1)
            if cv2.mean(pred[top:bottom+1,left:right+1],mask)[0]<.4:continue
            # Closed-form offset of the minimum-area rectangle. Android uses OpenCV;
            # no Shapely/Clipper or table recognition is needed in the deployed path.
            distance=cv2.contourArea(points)*(3.5/math.sqrt(max(1,min(np.ptp(points[:,0]),np.ptp(points[:,1])))))/max(1,cv2.arcLength(points,True))
            rect=(rect[0],(rect[1][0]+2*distance,rect[1][1]+2*distance),rect[2])
            if min(rect[1])<4:continue
            points=sorted(cv2.boxPoints(rect),key=lambda p:p[0]);a=sorted(points[:2],key=lambda p:p[1]);c=sorted(points[2:],key=lambda p:p[1]);points=np.float32([a[0],c[0],c[1],a[1]])
            points[:,0]=np.clip(np.round(points[:,0]/rw*w),0,w-1);points[:,1]=np.clip(np.round(points[:,1]/rh*h),0,h-1)
            boxes.append([int(points[:,0].min()),int(points[:,1].min()),int(points[:,0].max()),int(points[:,1].max()),points])
        return boxes

class Regions(Db):
    def detect(self,image):
        h,w=image.shape[:2];x=cv2.resize(image[:,:,::-1],(640,640)).astype(np.float32).transpose(2,0,1)[None]/255
        logits,boxes=self.session.run(None,{'input':x});probs=1/(1+np.exp(-logits[0]));indices=np.argsort(probs.reshape(-1))[-300:][::-1];regions=[]
        for index in indices:
            score=float(probs.reshape(-1)[index])
            if score<.5:continue
            label=int(index%6);box=boxes[0,index//6];cx,cy,bw,bh=box
            regions.append(dict(label=label,score=score,box=[float((cx-bw/2)*w),float((cy-bh/2)*h),float((cx+bw/2)*w),float((cy+bh/2)*h)]))
        return regions

def preprocess(image,name,uv):
    if name.startswith('uvdoc'):
        image=uv.unwarp(image)
        if name=='uvdoc':return image
        name=name.removeprefix('uvdoc-')
    if name=='none':return image.copy()
    if name=='illumination':
        gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY).astype(np.float32);light=cv2.GaussianBlur(gray,(0,0),25)
        return np.clip(image.astype(np.float32)*(np.median(light)/np.maximum(light,16))[:,:,None],0,255).astype(np.uint8)
    if name=='clahe':
        lab=cv2.cvtColor(image,cv2.COLOR_BGR2LAB);lab[:,:,0]=cv2.createCLAHE(clipLimit=2,tileGridSize=(8,8)).apply(lab[:,:,0]);return cv2.cvtColor(lab,cv2.COLOR_LAB2BGR)
    if name=='unsharp':return cv2.addWeighted(image,1.6,cv2.GaussianBlur(image,(0,0),1),-.6,0)
    if name=='adaptive':
        gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY);mono=cv2.adaptiveThreshold(gray,255,cv2.ADAPTIVE_THRESH_GAUSSIAN_C,cv2.THRESH_BINARY,31,11);return cv2.cvtColor(mono,cv2.COLOR_GRAY2BGR)
    raise ValueError(name)

def overlap(line,box):
    cx,cy=(line[0]+line[2])/2,(line[1]+line[3])/2
    if not (box[0]<=cx<=box[2] and box[1]<=cy<=box[3]):return 0
    area=min((line[2]-line[0])*(line[3]-line[1]),(box[2]-box[0])*(box[3]-box[1]))
    return max(0,min(line[2],box[2])-max(line[0],box[0]))*max(0,min(line[3],box[3])-max(line[1],box[1]))/max(1,area)

def main():
    p=argparse.ArgumentParser();p.add_argument('evaluation',type=Path);p.add_argument('--models',type=Path,required=True);p.add_argument('--repeat',type=int,default=1);args=p.parse_args();cv2.setNumThreads(1)
    dest=args.evaluation/'japanese-v071/grid';dest.mkdir(parents=True,exist_ok=True);gold=json.loads((args.evaluation/'lm/gold.private.json').read_text())['references']
    uv=s.Assets();layout=Layout();paddles={name:s.Assets(detector=path) for name,path in [('pp-small',None),('pp-tiny',args.models/'pp-tiny-det.onnx'),('pp-medium',args.models/'pp-medium-det.onnx')]}
    for value in paddles.values():value.native_boxes=True
    db=Db(args.models/'yomi-db.onnx');regions=Regions(args.models/'yomi-layout.onnx');output=[]
    for image_name in gold:
        source=cv2.imread(str(args.evaluation/'input'/image_name))
        for treatment in ['none','illumination','clahe','unsharp','adaptive','uvdoc','uvdoc-illumination']:
            directory=dest/(image_name+'-'+treatment);directory.mkdir(exist_ok=True)
            times=[]
            for i in range(args.repeat+1):
                start=time.perf_counter();image=preprocess(source,treatment,uv);times.append(time.perf_counter()-start)
            image_path=directory/'page.png';cv2.imwrite(str(image_path),image)
            start=time.perf_counter();body=layout.ndl_detect(image);ndl_time=time.perf_counter()-start;masked=layout.masked(image)
            start=time.perf_counter();blocks=regions.detect(image);region_time=time.perf_counter()-start
            for detector in ['ndl','pp-tiny','pp-small','pp-medium','yomi-db']:
                if detector=='ndl':boxes=[x[:4] for x in body];det_time=0
                else:
                    start=time.perf_counter();boxes=db.detect(image) if detector=='yomi-db' else paddles[detector].pp_detect(image);det_time=time.perf_counter()-start
                    boxes=[box for box in boxes if all(overlap(box,block)<=.5 for block in masked)]
                entries=[];ordered=b.ordered([[*box[:4],str(i)] for i,box in enumerate(boxes)])
                for index,item in enumerate(ordered):
                    box=boxes[int(item[4])];crop=s.extract(image,box)
                    if crop.size==0:continue
                    path=directory/f'{detector}-{index:03}.png';cv2.imwrite(str(path),crop)
                    closest=max(body,key=lambda x:s.box_iou(box,x)) if body else None
                    entries.append(dict(file=str(path),box=[int(x) for x in box[:4]],tier=int(closest[5]) if closest is not None else 1))
                row=dict(image=image_name,preprocessing=treatment,detector=detector,page=str(image_path),crops=entries,regions=blocks,preprocessing_seconds=times[1:],layout_seconds=ndl_time,detection_seconds=det_time,region_seconds=region_time)
                output.append(row)
            print(json.dumps(dict(image=image_name,preprocessing=treatment,crops={x['detector']:len(x['crops']) for x in output[-5:]})),flush=True)
            (dest/'grid.private.json').write_text(json.dumps(output,indent=2))
if __name__=='__main__':main()
