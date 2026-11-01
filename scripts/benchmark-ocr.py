#!/usr/bin/env python3
"""Compare real ONNX OCR pipelines on private images; keep outputs outside Git.

Dependencies: scripts/requirements-ocr-benchmark.txt.
The NDLOCR path mirrors the Android preprocessing, cascade and reading order.
Times are host CPU measurements, not predictions of physical Android latency.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import resource
import statistics
import time
import unicodedata

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
NDL_NAMES = [
    "parseq-ndl-24x256-30-tiny-189epoch-tegaki3-r8data-202604.onnx",
    "parseq-ndl-24x384-50-tiny-300epoch-tegaki3-r8data-202604.onnx",
    "parseq-ndl-24x768-100-tiny-153epoch-tegaki3-r8data-202604.onnx",
]


def ordered(lines):
    """Same whitespace XY cuts as ReadingOrder.java, including vertical order."""
    vertical = sum(b[3] - b[1] > (b[2] - b[0]) * 1.2 for b in lines) > len(lines) / 2

    def gap(items, axis):
        items = sorted(items, key=lambda b: b[axis])
        end, best, position = items[0][axis + 2], 0, 0
        for b in items:
            if b[axis] - end > best:
                best, position = b[axis] - end, (b[axis] + end) / 2
            end = max(end, b[axis + 2])
        return best, position

    def cut(items, depth=0):
        if len(items) < 2:
            return items
        x, y = gap(items, 0), gap(items, 1)
        axis, (score, split) = (0, x) if x[0] > y[0] else (1, y)
        if depth >= 32 or score <= 0:
            return sorted(items, key=lambda b: (-(b[0] + b[2]) / 2, b[1]) if vertical else (b[1], b[0]))
        a = [b for b in items if (b[axis] + b[axis + 2]) / 2 < split]
        b = [b for b in items if (b[axis] + b[axis + 2]) / 2 >= split]
        if not a or not b:
            return items
        a, b = cut(a, depth + 1), cut(b, depth + 1)
        return b + a if axis == 0 and vertical else a + b

    return cut(lines)


def normalize(text):
    return "".join(unicodedata.normalize("NFKC", text).split())


def excerpt_errors(reference, prediction):
    """Minimum edit distance to a contiguous predicted excerpt, not whole-page CER.

    Only outside prefix/suffix is free; missing reference characters and inserted
    text inside the excerpt still count. Intended for manually transcribed spans.
    """
    reference, prediction = normalize(reference), normalize(prediction)
    previous = [0] * (len(prediction) + 1)
    for i, a in enumerate(reference, 1):
        current = [i]
        for j, b in enumerate(prediction, 1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (a != b)))
        previous = current
    return min(previous), len(reference)


class Models:
    def __init__(self, directory):
        self.directory = directory
        self.sessions = {}
        self.load_seconds = 0
        self.charset = json.loads((ASSETS / "charset.json").read_text())
        self.pp_chars = [""] + json.loads((ASSETS / "ppocrv6-charset.json").read_text()) + [" "]

    def model_path(self, name):
        filename = {"ppocr6-det/inference.onnx": "ppocrv6-small-det.onnx",
                    "ppocr6-rec/inference.onnx": "ppocrv6-small-rec.onnx",
                    "uvdoc/inference.onnx": "uvdoc.onnx"}.get(name, name)
        return self.directory / filename

    def session(self, name):
        if name not in self.sessions:
            start = time.perf_counter()
            options = ort.SessionOptions()
            options.intra_op_num_threads = 4 if "deim" in name or "ppocr6-det" in name else 2
            options.inter_op_num_threads = 1
            options.log_severity_level = 3
            path = self.model_path(name)
            self.sessions[name] = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
            self.load_seconds += time.perf_counter() - start
        return self.sessions[name]

    def unwarp(self, image):
        session = self.session("uvdoc/inference.onnx")
        # Paddle's exported UVDoc contains resize, dense grid and GridSample.
        tensor = image.transpose(2, 0, 1)[None].astype(np.float32) / 255
        output = session.run(None, {session.get_inputs()[0].name: tensor})[0][0]
        return np.clip(output.transpose(1, 2, 0) * 255, 0, 255).astype(np.uint8)

    def ndl_detect(self, image):
        session = self.session("deim-s-1024x1024.onnx")
        h, w = image.shape[:2]
        side = max(h, w)
        shape = session.get_inputs()[0].shape
        ih, iw = shape[2:]
        canvas = np.zeros((ih, iw, 3), np.uint8)
        rh, rw = max(1, round(h * iw / side)), max(1, round(w * iw / side))
        canvas[:rh, :rw] = cv2.resize(image, (rw, rh))
        x = canvas[:, :, ::-1].astype(np.float32) / 255
        x = ((x - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)).transpose(2, 0, 1)[None]
        labels, boxes, scores, *rest = session.run(None, {session.get_inputs()[0].name: x, session.get_inputs()[1].name: np.array([[ih, iw]], np.int64)})
        labels, boxes, scores = labels.reshape(-1), boxes.reshape(-1, 4), scores.reshape(-1)
        tiers = rest[0].reshape(-1) if rest else np.ones(len(labels), np.int64)
        candidates, tables = [], []
        for label, box, score, tier in zip(labels, boxes, scores, tiers):
            if score <= .2 or label not in [2, 3, 4, 5, 6, 10, 11, 16, 17]:
                continue
            b = np.array(box * side / iw, np.int32)
            b[[0, 2]] = np.clip(b[[0, 2]], 0, w)
            b[[1, 3]] = np.clip(b[[1, 3]], 0, h)
            if min(b[2] - b[0], b[3] - b[1]) < 2:
                continue
            if label == 16:
                tables.append(b)
            else:
                candidates.append([*map(int, b), float(score), int(tier), int(label)])
        kept = []
        for b in sorted(candidates, key=lambda b: -b[4]):
            duplicate = False
            area = (b[2] - b[0]) * (b[3] - b[1])
            for a in kept:
                if (a[6] == 10) != (b[6] == 10) or (a[6] == 11) != (b[6] == 11):
                    continue
                overlap = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
                union = area + (a[2] - a[0]) * (a[3] - a[1]) - overlap
                if overlap / union > .65:
                    duplicate = True
                    break
            if duplicate:
                continue
            if b[6] in (10, 11):
                continue
            in_table = any(max(0, min(t[2], b[2]) - max(t[0], b[0])) * max(0, min(t[3], b[3]) - max(t[1], b[1])) >= area * .5 for t in tables)
            if not in_table:
                kept.append(b)
        return kept

    def ndl_recognize(self, image, tier):
        if image.shape[0] > image.shape[1] * .8:
            image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
        first = 0 if tier == 3 else 1 if tier == 2 else 2
        for index in range(first, 3):
            session = self.session(NDL_NAMES[index])
            shape = session.get_inputs()[0].shape
            # Upstream receives RGB from PIL and reverses to BGR. Same as Android.
            x = cv2.resize(image, (shape[3], shape[2])).astype(np.float32) / 127.5 - 1
            output = session.run(None, {session.get_inputs()[0].name: x.transpose(2, 0, 1)[None]})[0][0]
            ids = output.argmax(axis=-1)
            probs = output if np.allclose(output.sum(axis=1), 1, atol=.001) else np.exp(output - output.max(axis=1, keepdims=True))
            probs = probs / probs.sum(axis=1, keepdims=True)
            chosen_probs = []
            text = ""
            for value in ids:
                if value == 0:
                    break
                chosen_probs.append(float(probs[len(chosen_probs), value]))
                text += self.charset[value - 1]
            self.last_confidence = float(np.exp(np.mean(np.log(np.maximum(chosen_probs, 1e-8))))) if chosen_probs else 0
            if index == 2 or len(text) < [25, 45][index]:
                return text

    def pp_recognize(self, image):
        if image.shape[0] >= image.shape[1] * 1.5:
            image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
        h, w = image.shape[:2]
        width = min(3200, max(320, math.ceil(48 * w / h)))
        content_width = min(width, math.ceil(48 * w / h))
        content = cv2.resize(image, (content_width, 48)).astype(np.float32) / 127.5 - 1
        x = np.zeros((1, 3, 48, width), np.float32)
        x[0, :, :, :content_width] = content.transpose(2, 0, 1)
        session = self.session("ppocr6-rec/inference.onnx")
        output = session.run(None, {session.get_inputs()[0].name: x})[0][0]
        ids = output.argmax(axis=-1)
        previous, text, chosen_probs = -1, "", []
        for step, value in enumerate(ids):
            if value and value != previous:
                text += self.pp_chars[value]
                chosen_probs.append(float(output[step, value]))
            previous = value
        self.last_confidence = float(np.mean(chosen_probs)) if chosen_probs else 0
        return text

    def pp_detect(self, image):
        h, w = image.shape[:2]
        ratio = min(1, 1024 / max(h, w))
        rh, rw = max(32, round(h * ratio / 32) * 32), max(32, round(w * ratio / 32) * 32)
        x = cv2.resize(image, (rw, rh)).astype(np.float32) / 255
        x = ((x - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)).transpose(2, 0, 1)[None]
        session = self.session("ppocr6-det/inference.onnx")
        pred = session.run(None, {session.get_inputs()[0].name: x})[0][0, 0]
        contours, _ = cv2.findContours((pred > .2).astype(np.uint8) * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        result = []
        for contour in contours[:3000]:
            rect = cv2.minAreaRect(contour)
            if min(rect[1]) < 3:
                continue
            points = cv2.boxPoints(rect)
            mask = np.zeros(pred.shape, np.uint8)
            cv2.fillPoly(mask, [np.int32(points)], 1)
            if cv2.mean(pred, mask)[0] < .45:
                continue
            distance = cv2.contourArea(points) * 1.4 / cv2.arcLength(points, True)
            if getattr(self, 'native_boxes', False):
                rect = (rect[0], (rect[1][0] + 2 * distance, rect[1][1] + 2 * distance), rect[2])
            else:
                offset = pyclipper.PyclipperOffset()
                offset.AddPath(np.int32(points).tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
                expanded = offset.Execute(distance)
                if len(expanded) != 1:
                    continue
                rect = cv2.minAreaRect(np.float32(expanded[0]))
            if min(rect[1]) < 5:
                continue
            points = sorted(cv2.boxPoints(rect), key=lambda p: p[0])
            left, right = sorted(points[:2], key=lambda p: p[1]), sorted(points[2:], key=lambda p: p[1])
            points = np.float32([left[0], right[0], right[1], left[1]])
            points[:, 0] = np.clip(points[:, 0] * w / rw, 0, w - 1)
            points[:, 1] = np.clip(points[:, 1] * h / rh, 0, h - 1)
            result.append([int(points[:, 0].min()), int(points[:, 1].min()), int(points[:, 0].max()), int(points[:, 1].max()), points])
        return result

    def automatic(self, image):
        boxes = self.ndl_detect(image)
        vertical = [b[3] - b[1] for b in boxes if b[3] - b[1] > (b[2] - b[0]) * 1.2]
        horizontal = [b for b in boxes if b[3] - b[1] <= (b[2] - b[0]) * 1.2]
        long_rows = [b for b in horizontal if b[2] - b[0] >= image.shape[1] * .3]
        ratios = []
        for b in long_rows:
            pitches = []
            for a in long_rows:
                if a is b: continue
                overlap = min(a[2], b[2]) - max(a[0], b[0])
                dy = abs((a[1] + a[3] - b[1] - b[3]) / 2)
                if overlap >= min(a[2] - a[0], b[2] - b[0]) * .8 and dy > min(a[3] - a[1], b[3] - b[1]) * .15: pitches.append(dy)
            if pitches: ratios.append((b[3] - b[1]) / min(pitches))
        if len(vertical) >= 3 and len(vertical) >= len(boxes) * .7 and statistics.median(vertical) > image.shape[0] * .35:
            self.native_boxes = True
            self.selected_route = 'paddle-long-vertical'
            return self.run(image, 'pp')
        flatten = len(long_rows) >= 8 and len(horizontal) >= len(boxes) * .6 and ratios and statistics.median(ratios) > 1.2
        if flatten:
            image = self.unwarp(image)
            boxes = self.ndl_detect(image)
        self.selected_route = 'uvdoc-ndl-layout-paddle' if flatten else 'ndl'
        lines = []
        for b in boxes:
            crop = image[b[1]:b[3], b[0]:b[2]]
            text = self.pp_recognize(crop) if flatten else self.ndl_recognize(crop, b[5])
            if text.strip(): lines.append([*b[:4], text])
        return ordered(lines), image

    def run(self, image, workflow):
        if workflow == "auto":
            return self.automatic(image)
        if workflow.startswith("uv-"):
            image = self.unwarp(image)
            workflow = workflow[3:]
        lines = []
        if workflow == "pp":
            for b in self.pp_detect(image):
                points = b[4]
                width = max(2, int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3]))))
                height = max(2, int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2]))))
                matrix = cv2.getPerspectiveTransform(points, np.float32([[0, 0], [width, 0], [width, height], [0, height]]))
                crop = cv2.warpPerspective(image, matrix, (width, height), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
                text = self.pp_recognize(crop)
                if text.strip():
                    lines.append([*b[:4], text])
        else:
            for b in self.ndl_detect(image):
                crop = image[b[1]:b[3], b[0]:b[2]]
                text = self.pp_recognize(crop) if workflow == "hybrid" else self.ndl_recognize(crop, b[5])
                if text.strip():
                    lines.append([*b[:4], text])
        return ordered(lines), image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=ASSETS / "models")
    parser.add_argument("--workflow", choices=["ndl", "pp", "hybrid", "uv-ndl", "uv-pp", "uv-hybrid", "auto"], required=True)
    parser.add_argument("--truth", type=Path, help="JSON: {image filename: [manually transcribed excerpt, ...]}")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("images", type=Path, nargs="+")
    args = parser.parse_args()
    cv2.setNumThreads(1)
    truth = json.loads(args.truth.read_text()) if args.truth else {}
    models = Models(args.models)
    rows = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for path in args.images:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f"Cannot read {path}")
        h, w = image.shape[:2]
        scale = min(1, 2800 / max(h, w))
        image = cv2.resize(image, (round(w * scale), round(h * scale)))
        times = []
        for iteration in range(args.repeat + 1):
            start = time.perf_counter()
            lines, processed = models.run(image, args.workflow)
            times.append(time.perf_counter() - start)
        text = "\n".join(b[4] for b in lines)
        spans = [excerpt_errors(t, text) for t in truth.get(path.name, [])]
        row = {"image": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": [w, h],
               "cold_seconds": times[0], "warm_seconds": times[1:], "median_seconds": statistics.median(times[1:]),
               "route": getattr(models, "selected_route", args.workflow), "lines": len(lines), "text": text, "boxes": lines, "excerpt_errors": spans,
               "excerpt_cer": sum(x[0] for x in spans) / sum(x[1] for x in spans) if spans else None}
        # Numpy points stay out of rows: output coordinates are original line boxes.
        rows.append(row)
        if args.workflow.startswith("uv-"):
            cv2.imwrite(str(args.output.parent / f"{path.stem}-{args.workflow}.jpg"), processed)
        print(json.dumps({k: row[k] for k in ["image", "median_seconds", "lines", "excerpt_cer"]}), flush=True)
    models_used = list(models.sessions)
    result = {"workflow": args.workflow, "onnxruntime": ort.__version__, "model_load_seconds": models.load_seconds,
              "peak_rss_mib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
              "model_bytes": sum(models.model_path(name).stat().st_size for name in models_used),
              "models": models_used, "images": rows}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
