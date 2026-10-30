#!/usr/bin/env python3
"""Verify included native core and source content independent of checkout line endings."""
from pathlib import Path
import hashlib, json

root = Path(__file__).resolve().parents[1]
provenance = json.loads((root / 'docs/TRUE_MOTION_PROVENANCE.json').read_text(encoding='utf-8'))
active = provenance['activeNativeOverride']
digest = lambda data: hashlib.sha256(data).hexdigest()
assert digest((root / active['path']).read_bytes()) == active['sha256'], 'Native core differs'
for path, expected in active['sourceNormalizedSHA256'].items():
    assert digest((root / path).read_bytes().replace(b'\r\n', b'\n')) == expected, path
for item in provenance['bootstrapNative']['files']:
    if item['path'] != active['path']:
        assert digest((root / item['path']).read_bytes()) == item['sha256'], item['path']
print('Native core, source content and bootstrap library hashes verified')
