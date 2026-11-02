#!/usr/bin/env python3
"""Private photo benchmark: confidence versus character masked-LM selective rereading.
Inputs/outputs stay outside Git. Gold references must be manually transcribed.
"""

import argparse, importlib.util, json, time, math, unicodedata
from pathlib import Path
import numpy as np
import cv2
import onnxruntime as ort

spec = importlib.util.spec_from_file_location(
    "baseline", Path(__file__).with_name("benchmark-ocr.py")
)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
_original_normalize = b.normalize
b.normalize = lambda s: _original_normalize(s).replace("，", "、").replace(",", "、")


class Language:
    def __init__(self, root):
        vocab = (root / "vocab.txt").read_text().splitlines()
        self.vocab = {s: i for i, s in enumerate(vocab)}
        opt = ort.SessionOptions()
        opt.intra_op_num_threads = 2
        opt.inter_op_num_threads = 1
        self.session = ort.InferenceSession(
            str(root / "language.onnx"), opt, providers=["CPUExecutionProvider"]
        )
        self.cache = {}

    def score(self, text):
        text = _original_normalize(text)[:120]
        if text in self.cache:
            return self.cache[text]
        v = self.vocab
        ids = [v.get(c, v["[UNK]"]) for c in text]
        valid = [i for i, x in enumerate(ids) if x != v["[UNK]"]]
        if not valid or len(valid) < len(ids) * 0.85:
            return None
        losses = []
        for start in range(0, len(valid), 8):
            pos = valid[start : start + 8]
            x = np.array([[v["[CLS]"], *ids, v["[SEP]"]]] * len(pos), dtype=np.int64)
            for row, i in enumerate(pos):
                x[row, i + 1] = v["[MASK]"]
            logits = self.session.run(None, {"ids": x, "mask": np.ones_like(x)})[0]
            for row, i in enumerate(pos):
                z = logits[row, i + 1]
                peak = float(z.max())
                losses.append(
                    peak + math.log(float(np.exp(z - peak).sum())) - float(z[ids[i]])
                )
        result = float(np.mean(losses))
        self.cache[text] = result
        return result


def distance(a, c):
    prev = list(range(len(c) + 1))
    for i, x in enumerate(a, 1):
        row = [i]
        for j, y in enumerate(c, 1):
            row.append(min(row[-1] + 1, prev[j] + 1, prev[j - 1] + (x != y)))
        prev = row
    return prev[-1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("directory", type=Path)
    p.add_argument("--refresh", action="store_true")
    args = p.parse_args()
    root = args.directory
    work = root / "lm"
    gold = json.loads((work / "gold.private.json").read_text())
    cv2.setNumThreads(1)
    m = b.Models(b.ASSETS / "models")
    m.native_boxes = True
    lm = Language(work)
    results = []
    # Same PP recognizer preprocessing, separate stronger weights and matching charset.
    medium_chars = [""] + json.loads((b.ASSETS / "ppocrv6-charset.json").read_text()) + [" "]
    medium = ort.InferenceSession(
        str(work / "medium-inference.onnx"),
        providers=["CPUExecutionProvider"],
        sess_options=lm.session.get_session_options(),
    )
    for name, refs in gold["references"].items():
        cache = work / (name + ".rows.json")
        if cache.exists() and not args.refresh:
            rows = json.loads(cache.read_text())
        else:
            records = []
            originals = {k: getattr(m, k) for k in ["ndl_recognize", "pp_recognize"]}
            for k, fn in originals.items():

                def wrapped(image, *a, fn=fn, k=k):
                    t = time.perf_counter()
                    text = fn(image, *a)
                    idx = len(records)
                    cv2.imwrite(str(work / f"{name}-{idx}.png"), image)
                    records.append(
                        {
                            "text": text,
                            "confidence": m.last_confidence,
                            "engine": k,
                            "crop": f"{name}-{idx}.png",
                            "base_seconds": time.perf_counter() - t,
                        }
                    )
                    return text

                setattr(m, k, wrapped)
            started = time.perf_counter()
            lines, _ = m.run(cv2.imread(str(root / "input" / name)), "auto")
            base_time = time.perf_counter() - started
            for k, fn in originals.items():
                setattr(m, k, fn)
            rows = []
            for line in lines:
                r = next(r for r in records if r["text"] == line[4] and "box" not in r)
                r["box"] = list(map(int, line[:4]))
                rows.append(r)
            # Generate candidates offline for comparison; production only runs selected rows.
            for r in rows:
                image = cv2.imread(str(work / r["crop"]))
                t = time.perf_counter()
                original_session = m.session
                original_chars = m.pp_chars
                m.session = lambda n: (
                    medium if n == "ppocr6-rec/inference.onnx" else original_session(n)
                )
                m.pp_chars = medium_chars
                try:
                    r["candidate"] = m.pp_recognize(image)
                    r["candidate_confidence"] = m.last_confidence
                finally:
                    m.session = original_session
                    m.pp_chars = original_chars
                r["retry_seconds"] = time.perf_counter() - t
            cache.write_text(json.dumps(rows, ensure_ascii=False, indent=2))
            (work / (name + ".time.json")).write_text(
                json.dumps({"base_seconds": base_time})
            )
        for r in rows:
            if "nll" in r:
                continue
            t = time.perf_counter()
            r["nll"] = lm.score(r["text"])
            r["candidate_nll"] = lm.score(r["candidate"])
            r["lm_seconds"] = time.perf_counter() - t
        baseline = "\n".join(r["text"] for r in rows)
        base_errors = sum(b.excerpt_errors(x, baseline)[0] for x in refs)
        for mode in [
            "none",
            "confidence",
            "language",
            "combined",
            "all",
            "medium_all",
            "cascade95",
            "cascade98",
            "cascade99",
        ]:
            limit = min(4, max(1, len(rows) // 10))
            eligible = [r for r in rows if len(r["text"]) >= 8]
            if mode == "confidence":
                eligible = sorted(eligible, key=lambda r: r["confidence"])[:limit]
            if mode == "language":
                eligible = sorted(
                    [r for r in eligible if r["nll"] is not None],
                    key=lambda r: -r["nll"],
                )[:limit]
            if mode == "combined":
                eligible = sorted(
                    [
                        r
                        for r in eligible
                        if r["confidence"] < 0.95 and r["nll"] is not None
                    ],
                    key=lambda r: -r["nll"],
                )[:limit]
            if mode == "none":
                eligible = []
            if mode.startswith("cascade"):
                eligible = [
                    r
                    for r in rows
                    if r["engine"] == "pp_recognize"
                    and r["confidence"] < int(mode[-2:]) / 100
                ]
            if mode == "medium_all":
                eligible = [r for r in rows if r["engine"] == "pp_recognize"]
            final = []
            changes = []
            for i, r in enumerate(rows):
                a, c = r["text"], r["candidate"]
                choose = (
                    r in eligible
                    and c != a
                    and r["nll"] is not None
                    and r["candidate_nll"] is not None
                    and r["candidate_nll"] < r["nll"] - 0.2
                    and distance(a, c) <= max(1, len(a) * 0.25)
                )
                if mode.startswith("cascade") or mode == "medium_all":
                    choose = r in eligible
                final.append(c if choose else a)
                if choose:
                    changes.append(i)
            text = "\n".join(final)
            before = [b.excerpt_errors(x, baseline)[0] for x in refs]
            after = [b.excerpt_errors(x, text)[0] for x in refs]
            result = {
                "image": name,
                "split": "development" if name in gold["development"] else "evaluation",
                "mode": mode,
                "characters": sum(b.excerpt_errors(x, "")[1] for x in refs),
                "baseline_errors": base_errors,
                "errors": sum(after),
                "harmed_excerpts": sum(y > x for x, y in zip(before, after)),
                "corrupted_correct_excerpts": sum(
                    x == 0 and y > 0 for x, y in zip(before, after)
                ),
                "retry_rows": len(eligible),
                "changed_rows": changes,
                "retry_seconds": sum(r["retry_seconds"] for r in eligible),
                "lm_seconds": (
                    sum(r["lm_seconds"] for r in rows)
                    if mode in ["language", "combined", "all"]
                    else (
                        sum(r["lm_seconds"] for r in eligible)
                        if mode == "confidence"
                        else 0
                    )
                ),
                "baseline_seconds": json.loads(
                    (work / (name + ".time.json")).read_text()
                )["base_seconds"],
            }
            results.append(result)
            print(json.dumps(result), flush=True)
        cache.write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    (work / "comparison.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2)
    )


if __name__ == "__main__":
    main()
