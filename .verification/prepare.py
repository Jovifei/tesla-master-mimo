"""Append reviewed changes to the already published candidate; never rewrite it."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path.cwd()
BASE = 'e83e86eb711013bc280c1534c2cc76bffd1cef8a'
PINNED = '2e72a5ce6f0b9caa54450cc41787d2193c8076cf'
CANDIDATE = ROOT / 'candidate'

def git(*args, cwd=CANDIDATE, env=None):
    return subprocess.check_output(['git', *args], cwd=cwd, env=env, text=True).strip()

git('worktree', 'add', '--detach', str(CANDIDATE), PINNED, cwd=ROOT)
assert git('merge-base', BASE, 'HEAD') == BASE
git('config', 'user.name', 'github-actions[bot]')
git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
expected = json.loads((ROOT / '.verification/expected.json').read_text())
for name, digest in expected.items():
    assert hashlib.sha256((CANDIDATE / name).read_bytes()).hexdigest() == digest, name
for path in sorted((ROOT / '.verification/patches').glob('06*.patch')):
    subprocess.run(['git','apply','--recount','--index','--whitespace=error'], cwd=CANDIDATE,
                   input=path.read_text(), text=True, check=True)
git('diff', '--cached', '--check')
clock = '2026-09-10T00:00:06+00:00'
env = dict(os.environ, GIT_AUTHOR_DATE=clock, GIT_COMMITTER_DATE=clock)
git('-c','commit.gpgsign=false','commit','-m','fix(sync): preserve cloud evidence across phone recovery and account changes',env=env)
expected.update(json.loads((ROOT / '.verification/expected-followup.json').read_text()))
changed = set(git('diff','--name-only',BASE).splitlines())
assert changed == set(expected), ('Unexpected changed file set',changed.symmetric_difference(expected))
for name,digest in expected.items():
    observed = hashlib.sha256((CANDIDATE / name).read_bytes()).hexdigest()
    assert observed == digest, (name,observed,digest)
assert not git('status','--porcelain')
reports=ROOT/'reports'; reports.mkdir(exist_ok=True)
(reports/'SOURCE_HEAD.txt').write_text(git('rev-parse','HEAD')+'\n')
(reports/'SOURCE_COMMITS.txt').write_text(git('log','--reverse','--format=%H %s',BASE+'..HEAD')+'\n')
print((reports/'SOURCE_COMMITS.txt').read_text())
