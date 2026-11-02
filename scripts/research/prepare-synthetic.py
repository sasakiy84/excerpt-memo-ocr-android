#!/usr/bin/env python3
"""Generate reproducible fresh known-text line images outside the repository."""
import argparse,json,hashlib,math
from pathlib import Path
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont
p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--fonts',type=Path,required=True);args=p.parse_args()
dest=args.output;dest.mkdir(parents=True,exist_ok=True);fontpath=args.fonts/'NotoSerifJP.ttf'
font_hashes={}
for item in json.loads(Path(__file__).with_name('models-lock.json').read_text())['artifacts']:
 if item.get('role')=='synthetic-font':
  actual=hashlib.file_digest((args.fonts/item['local']).open('rb'),'sha256').hexdigest()
  if actual!=item['sha256']:raise ValueError('Font checksum mismatch: '+item['local'])
  font_hashes[item['local']]=actual
texts=[
'必要な文章を選び、読み取り結果を端末内で保存する。',
'図書館の開館時刻は午前九時で、休館日は月曜日です。',
'住宅政策と人口移動の変化を自治体ごとの資料から調べた。',
'国際社会では民族自決と国家の関係が繰り返し議論された。',
'調査の結果、三十代と四十代の利用者が増えている。',
'資料の比較には、発行年、対象地域、調査方法を用いる。',
'誤った文字だけを確認し、原文の句読点と段落を残す。',
'新しい機械でも、曖昧な条件を正しく解釈するとは限らない。',
'連続した文字列の開始位置と終了位置を指で移動する。',
'収入は１２，３４５円で、支出との差額は６７８円だった。',
'契約書の第３条には、更新期限を２０２６年と記載した。',
'Android 16で撮影した資料を、通信なしで読み取る。']
records=[];cv2.setNumThreads(1)
for font_name,font_file in [('sans',args.fonts/'NotoSansJP.ttf'),('serif',fontpath)]:
 for size in [16,24,32]:
  font=ImageFont.truetype(str(font_file),size)
  try:font.set_variation_by_axes([400])
  except (OSError,AttributeError):pass
  for n,text in enumerate(texts):
   width=math.ceil(font.getlength(text))+24;height=size+28;image=Image.new('RGB',(width,height),'white');ImageDraw.Draw(image).text((12,6),text,font=font,fill='black')
   original=np.array(image)[:,:,::-1].copy()
   yy,xx=np.where(original.min(2)<245)
   original=original[max(0,yy.min()-2):min(height,yy.max()+3),max(0,xx.min()-2):min(width,xx.max()+3)]
   height,width=original.shape[:2]
   for effect in ['clean','shadow','blur']:
    value=original
    if effect=='shadow':
     x=np.linspace(-1,1,width,dtype=np.float32);light=.45+.55*np.minimum(1,np.abs(x)*1.4);value=np.clip(value.astype(np.float32)*light[None,:,None],0,255).astype(np.uint8)
    elif effect=='blur':value=cv2.GaussianBlur(value,(0,0),.6)
    path=dest/f'{font_name}-{size}-{n:02}-{effect}.png';cv2.imwrite(str(path),value);records.append(dict(file=str(path),text=text,font=font_name,size=size,effect=effect))
(dest/'gold.private.json').write_text(json.dumps(dict(records=records,fonts=font_hashes),ensure_ascii=False,indent=2))
print('Generated',len(records),'line images',sum(len(x['text']) for x in records),'characters',flush=True)
