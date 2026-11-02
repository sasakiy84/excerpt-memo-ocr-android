#!/usr/bin/env python3
"""Export the evaluated, rejected character LM for reproducible private benchmarks.

Requires torch, transformers, onnx. Download README.md, config.json, vocab.txt,
model.safetensors from bluolightning/bert-tiny-japanese-char at commit
c7fab3755215327405aea56a42ea6d4e5239e9a8 to the argument directory first.
The model is not shipped with the app.
"""

import argparse
from pathlib import Path
import torch
from transformers import BertLMHeadModel

p = argparse.ArgumentParser()
p.add_argument("directory", type=Path)
a = p.parse_args()
model = BertLMHeadModel.from_pretrained(a.directory, local_files_only=True).eval()


class Export(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.model = model

    def forward(self, ids, mask):
        return self.model(input_ids=ids, attention_mask=mask).logits


ids = torch.ones((1, 32), dtype=torch.long)
torch.onnx.export(
    Export(),
    (ids, ids),
    str(a.directory / "language.onnx"),
    input_names=["ids", "mask"],
    output_names=["logits"],
    dynamic_axes={
        "ids": {0: "batch", 1: "length"},
        "mask": {0: "batch", 1: "length"},
        "logits": {0: "batch", 1: "length"},
    },
    opset_version=17,
    dynamo=False,
)
