#!/usr/bin/env python3
"""Convert an exported PARSeq graph. Always re-evaluate accuracy after conversion."""
import argparse, hashlib, json
from pathlib import Path
import onnx
from onnxruntime.quantization import quantize_dynamic,QuantType
from onnxruntime.transformers.float16 import convert_float_to_float16
p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);p.add_argument('--kind',choices=['int8','fp16'],required=True);a=p.parse_args()
if a.kind=='int8':
    quantize_dynamic(str(a.source),str(a.output),per_channel=True,reduce_range=False,weight_type=QuantType.QInt8,op_types_to_quantize=['MatMul','Gemm'],extra_options={'MatMulConstBOnly':True})
else:onnx.save(convert_float_to_float16(onnx.load(a.source),keep_io_types=True,disable_shape_infer=True),a.output)
print(json.dumps(dict(file=a.output.name,bytes=a.output.stat().st_size,sha256=hashlib.file_digest(a.output.open('rb'),'sha256').hexdigest())))
