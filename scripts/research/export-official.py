"""Export pinned official Yomitoku weights without installing its PDF/server dependencies."""
import argparse, dataclasses, importlib, json, os, sys, types
from pathlib import Path
import torch
from safetensors.torch import load_file

ROOT = Path(os.environ['OCR_RESEARCH_MODELS']).resolve()
SOURCE = ROOT / 'official-source/src/yomitoku'
for name, path in [('yomitoku', SOURCE), ('yomitoku.models', SOURCE/'models'),
    ('yomitoku.models.layers', SOURCE/'models/layers'), ('yomitoku.configs', SOURCE/'configs'),
    ('yomitoku.postprocessor', SOURCE/'postprocessor')]:
    package = types.ModuleType(name); package.__path__ = [str(path)]; sys.modules[name] = package

class Config(dict):
    def __getattr__(self, key):
        try: return self[key]
        except KeyError: raise AttributeError(key) from None

def config(value):
    if dataclasses.is_dataclass(value): return config(dataclasses.asdict(value))
    if isinstance(value, dict): return Config({k: config(v) for k,v in value.items()})
    if isinstance(value, list): return [config(v) for v in value]
    return value

def load(name):
    module, cls = {
        'tiny': ('tiny_dynw_v5','TextRecognizerPARSeqTinyDynwV5Config'),
        'middle': ('middle_dynw_v5','TextRecognizerPARSeqMiddleDynwV5Config'),
        'large': ('large_v4_1','TextRecognizerPARSeqLargeV41Config')
    }[name]
    cfg = config(getattr(importlib.import_module('yomitoku.configs.cfg_text_recognizer_parseq_'+module), cls)())
    PARSeq = importlib.import_module('yomitoku.models.parseq').PARSeq
    Tokenizer = importlib.import_module('yomitoku.postprocessor.parseq_tokenizer').ParseqTokenizer
    model = PARSeq(cfg)
    model.tokenizer = Tokenizer(Path(cfg.charset).read_text())
    model.load_state_dict(load_file(str(ROOT/(name+'.safetensors'))), strict=True)
    model.eval()
    return model, cfg

def main():
    p=argparse.ArgumentParser();p.add_argument('model',choices=['tiny','middle','large']);p.add_argument('--mode',choices=['ar','nar','nar0'],default='ar');p.add_argument('--export',action='store_true');p.add_argument('--cloze-mask',action='store_true');a=p.parse_args()
    torch.set_num_threads(2);torch.set_num_interop_threads(1);torch.manual_seed(42)
    model,cfg=load(a.model)
    if a.cloze_mask:
        source=SOURCE/'models/parseq.py';text=source.read_text()
        assert text.count('dtype=torch.int64')==1
        text=text.replace('dtype=torch.int64','dtype=torch.bool',1)
        derived=types.ModuleType('yomitoku.models.parseq_cloze');derived.__package__='yomitoku.models'
        exec(compile(text,str(source)+' (boolean cloze mask)','exec'),derived.__dict__)
        model.forward=types.MethodType(derived.PARSeq.forward,model)
    if a.mode != 'ar':model.decode_ar=False
    if a.mode == 'nar0':model.refine_iters=0
    model.export_onnx=True
    torch.backends.mha.set_fastpath_enabled(False)
    print(a.model, a.mode, 'parameters',sum(x.numel() for x in model.parameters()),'tokens',len(model.tokenizer),flush=True)
    if not a.export:return
    import importlib.util, numpy as np, onnxruntime as ort
    spec=importlib.util.spec_from_file_location('export_attention',Path(__file__).with_name('export-attention.py'))
    attention=importlib.util.module_from_spec(spec);spec.loader.exec_module(attention)
    torch.manual_seed(17)
    samples=[torch.rand(1,3,32,w)*2-1 for w in [96,320,800]]
    with torch.inference_mode(): references=[model(x).numpy() for x in samples]
    for module in model.modules():
        if isinstance(module,torch.nn.MultiheadAttention):module.__class__=attention.ExportAttention
    with torch.inference_mode():
        for x,ref in zip(samples,references):
            actual=model(x).numpy();difference=float(np.max(np.abs(ref-actual)))
            assert np.allclose(ref,actual,atol=.003,rtol=.003),difference
            print('attention parity',x.shape[-1],difference,flush=True)
    path=ROOT/(a.model+'-'+a.mode+('-cloze' if a.cloze_mask else '')+'.onnx')
    image=torch.zeros(1,3,32,96)
    with torch.inference_mode():
        torch.onnx.export(model,image,str(path),opset_version=17,dynamo=False,
            input_names=['input'],output_names=['output'],dynamic_axes={'input':{3:'width'}},external_data=False)
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),options,providers=['CPUExecutionProvider'])
    for x,reference in zip(samples,references):
        actual=session.run(None,{'input':x.numpy()})[0]
        difference=float(np.max(np.abs(reference-actual)))
        assert np.allclose(reference,actual,atol=.006,rtol=.003),difference
        print('onnx parity',x.shape[-1],difference,flush=True)
    print('Exported',path.name,path.stat().st_size,flush=True)

if __name__=='__main__':main()
