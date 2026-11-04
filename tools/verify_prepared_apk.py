#!/usr/bin/env python3
"""Compare signed APK payloads; signing metadata is the only permitted difference."""
import hashlib
import re
import sys
import zipfile
from pathlib import Path


def payload(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert len(names) == len(set(names)), 'Duplicate ZIP members'
        return {name: hashlib.sha256(apk.read(name)).digest() for name in names
                if not name.endswith('/') and not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I)}


if __name__ == '__main__':
    built, prepared, expected = sys.argv[1:]
    assert hashlib.file_digest(Path(prepared).open('rb'), 'sha256').hexdigest() == expected, 'Prepared APK checksum differs'
    left, right = payload(built), payload(prepared)
    differences = sorted(name for name in left.keys() | right.keys() if left.get(name) != right.get(name))
    assert not differences, f'APK payload differs from the source build: {differences}'
    assert 'AndroidManifest.xml' in left and 'classes.dex' in left and 'resources.arsc' in left
    print(f'All {len(left)} APK payload entries match the clean source build')
