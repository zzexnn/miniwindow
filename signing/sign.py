#!/usr/bin/env python3
"""Sign locally with an existing private key; no key generation or ephemeral fallback."""
import argparse
import os
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('unsigned', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--apksigner-jar', type=Path, required=True)
p.add_argument('--java', default='java')
p.add_argument('--keystore', type=Path, required=True)
p.add_argument('--password-file', type=Path, required=True)
args = p.parse_args()
expected = (Path(__file__).parent/'certificate-sha256.txt').read_text().strip()
if not args.keystore.is_file() or not args.password_file.is_file():
    raise SystemExit('Existing private signing files are required; do not generate a replacement key.')
base = [args.java, '-jar', str(args.apksigner_jar)]
subprocess.run(base + ['sign', '--ks', str(args.keystore), '--ks-key-alias', 'miniwindow',
    '--ks-pass', 'file:'+str(args.password_file), '--key-pass', 'file:'+str(args.password_file),
    '--v4-signing-enabled', 'false', '--out', str(args.output), str(args.unsigned)], check=True)
try:
    r = subprocess.run(base + ['verify','--verbose','--print-certs',str(args.output)],
                       capture_output=True,text=True,check=True)
    actual = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', r.stdout)
    if actual is None or actual.group(1) != expected:
        raise RuntimeError('Signing certificate differs from pinned public certificate')
    print(r.stdout)
except Exception:
    args.output.unlink(missing_ok=True)
    raise
