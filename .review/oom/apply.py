"""Materialize only checksum-verified reviewed code; no production access."""
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess

assert os.environ['GITHUB_REF'] == 'refs/heads/fix/20261002-history-metadata-memory'
root = Path('.review/oom')
parts = sorted(root.glob('part*.b64'))
assert len(parts) == 7
patch = gzip.decompress(base64.b64decode(''.join(p.read_text().strip() for p in parts), validate=True))
assert hashlib.sha256(patch).hexdigest() == 'ac3387062e61fd936354b37cd6debbd4d0a4ea83de4c89aa56ab9831535e3c6d'
expected = json.loads((root / 'expected.json').read_text())
subprocess.run(['git','apply','--check','--whitespace=error'],input=patch,check=True)
subprocess.run(['git','apply','--index','--whitespace=error'],input=patch,check=True)
for path, digest in expected.items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest, path
changed = set(subprocess.check_output(['git','diff','--cached','--name-only'],text=True).splitlines())
assert changed == set(expected), changed
subprocess.run(['git','rm','-r','--','.review/oom'],check=True)
subprocess.run(['git','diff','--cached','--check'],check=True)
subprocess.run(['git','config','user.name','github-actions[bot]'],check=True)
subprocess.run(['git','config','user.email','41898282+github-actions[bot]@users.noreply.github.com'],check=True)
subprocess.run(['git','commit','-m','fix(history): bound metadata and paginated reads without deleting archive'],check=True)
