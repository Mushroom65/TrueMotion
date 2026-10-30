#!/usr/bin/env python3
"""Check candidate source files; never print secret values or file contents."""
from pathlib import Path
import json, re, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
BLOCKED_NAMES = {'local.properties', 'signing.properties', 'google-services.json', '.env', 'credentials.json'}
BLOCKED_PARTS = {'.git', '.gradle', '.gradle-user', '.idea', '.cxx', '.kotlin', 'build', '__pycache__'}
BLOCKED_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.log', '.pyc'}
PATTERNS = {
    'private-key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'github-token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'aws-key': re.compile(rb'\bAKIA[0-9A-Z]{16}\b'),
}

def candidates():
    raw = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT)
    return sorted(set(p.decode('utf-8') for p in raw.split(b'\0') if p))

def audit():
    findings, total, size = [], 0, 0
    for name in candidates():
        p = ROOT / name
        if not p.is_file():
            continue  # submodules and tracked deletions
        total += 1
        length = p.stat().st_size
        size += length
        if p.name in BLOCKED_NAMES or p.name.startswith('.env.') or set(Path(name).parts) & BLOCKED_PARTS or p.suffix.lower() in BLOCKED_SUFFIXES:
            findings.append({'path': name, 'rule': 'private-or-generated-file'})
        if length >= 100 * 1024 * 1024:
            findings.append({'path': name, 'rule': 'github-file-size-limit'})
        if length < 4 * 1024 * 1024:
            data = p.read_bytes()
            if b'\0' not in data:
                for rule, pattern in PATTERNS.items():
                    if pattern.search(data):
                        findings.append({'path': name, 'rule': rule})
    return {'ok': not findings, 'files': total, 'bytes': size, 'findings': findings,
            'scope': 'Current candidate files; content-pattern and path checks, not a complete security guarantee'}

if __name__ == '__main__':
    report = audit()
    print(json.dumps(report, indent=2))
    sys.exit(0 if report['ok'] else 1)
