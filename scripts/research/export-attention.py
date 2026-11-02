"""Equivalent batch-first attention expressed in standard ONNX-friendly operations."""
import math
import torch
from torch.nn import functional as F

class ExportAttention(torch.nn.MultiheadAttention):
    def forward(self, query, key, value, key_padding_mask=None, need_weights=True,
                attn_mask=None, average_attn_weights=True, is_causal=False):
        assert self.batch_first and self._qkv_same_embed_dim and not self.add_zero_attn
        assert self.bias_k is None and self.bias_v is None and not is_causal
        d=self.embed_dim;heads=self.num_heads;head=d//heads;batch=query.shape[0]
        bias=self.in_proj_bias
        q=F.linear(query,self.in_proj_weight[:d],None if bias is None else bias[:d])
        k=F.linear(key,self.in_proj_weight[d:2*d],None if bias is None else bias[d:2*d])
        v=F.linear(value,self.in_proj_weight[2*d:],None if bias is None else bias[2*d:])
        q=q.reshape(batch,-1,heads,head).transpose(1,2)
        k=k.reshape(batch,-1,heads,head).transpose(1,2)
        v=v.reshape(batch,-1,heads,head).transpose(1,2)
        scores=(q*(1/math.sqrt(head)))@k.transpose(-1,-2)
        if attn_mask is not None:
            scores=scores.masked_fill(attn_mask,float('-inf')) if attn_mask.dtype==torch.bool else scores+attn_mask
        if key_padding_mask is not None:
            mask=key_padding_mask[:,None,None,:]
            scores=scores.masked_fill(mask,float('-inf')) if mask.dtype==torch.bool else scores+mask
        weights=torch.softmax(scores,-1)
        output=(weights@v).transpose(1,2).reshape(batch,-1,d)
        output=F.linear(output,self.out_proj.weight,self.out_proj.bias)
        return output,(weights.mean(1) if average_attn_weights else weights) if need_weights else None
