#!/usr/bin/env python3
"""Check bundled model and charset bytes against the pinned provenance manifest."""
import hashlib
import json
from pathlib import Path

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
manifest = json.loads((assets / "models-manifest.json").read_text())
for name, expected in manifest["files"].items():
    actual = hashlib.sha256((assets / name).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Checksum mismatch: {name}")
print(f"Verified {len(manifest['files'])} model/charset files from NDLOCR-Lite {manifest['version']}")
