"""Build the reviewed candidate from pinned source, without production access.
Only the isolated review ref is pushed by the workflow. Promotion is separate.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path.cwd()
BASE = 'e83e86eb711013bc280c1534c2cc76bffd1cef8a'
CANDIDATE = ROOT / 'candidate'

def git(*args, cwd=CANDIDATE, env=None):
    return subprocess.check_output(['git', *args], cwd=cwd, env=env, text=True).strip()

git('worktree', 'add', '--detach', str(CANDIDATE), BASE, cwd=ROOT)
git('config', 'user.name', 'github-actions[bot]')
git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
messages = [
    'fix(fleet): merge independent core and location snapshots',
    'fix(telemetry): recover transient setup failures and expose onboarding state',
    'fix(ui): use generic placeholder for unidentified vehicles',
    'fix(data): preserve confirmed metadata and expose discovery failures',
    'fix(history): restore cloud archives with scoped quality-preserving merges',
    'fix(sync): preserve cloud evidence across phone recovery and account changes',
]
for index, message in enumerate(messages, 1):
    for path in sorted((ROOT / '.verification/patches').glob(f'{index:02}*.patch')):
        patch = path.read_text()
        if path.name == '05c.patch':
            wrong = '     val ended = normalizeImportTimestamp(endDate) ?: null\n'
            assert patch.count(wrong) == 1
            patch = patch.replace(wrong, '     val ended = normalizeImportTimestamp(endDate) ?: return null\n')
        subprocess.run(['git', 'apply', '--recount', '--index', '--whitespace=error'],
                       cwd=CANDIDATE, input=patch, text=True, check=True)
    if index == 5:
        target = CANDIDATE / 'android/app/src/test/java/com/matelink/data/repository/HistoryRecoveryTest.kt'
        target.write_bytes((ROOT / '.verification/HistoryRecoveryTest.kt').read_bytes())
        git('add', str(target))
    git('diff', '--cached', '--check')
    clock = f'2026-09-10T00:00:{index:02}+00:00'
    env = dict(os.environ, GIT_AUTHOR_DATE=clock, GIT_COMMITTER_DATE=clock)
    git('-c', 'commit.gpgsign=false', 'commit', '-m', message, env=env)
    if index == 5:
        assert git('rev-parse', 'HEAD') == 'b2d2f4fba1a928f8d7778c3107cf673d1fd928d7'

expected = json.loads((ROOT / '.verification/expected.json').read_text())
expected.update(json.loads((ROOT / '.verification/expected-followup.json').read_text()))
changed = set(git('diff', '--name-only', BASE).splitlines())
assert changed == set(expected), ('Unexpected changed file set', changed.symmetric_difference(expected))
for name, digest in expected.items():
    observed = hashlib.sha256((CANDIDATE / name).read_bytes()).hexdigest()
    assert observed == digest, (name, observed, digest)
assert not git('status', '--porcelain'), 'Candidate contains uncommitted changes'
reports = ROOT / 'reports'
reports.mkdir(exist_ok=True)
(reports / 'SOURCE_HEAD.txt').write_text(git('rev-parse', 'HEAD') + '\n')
(reports / 'SOURCE_COMMITS.txt').write_text(git('log', '--reverse', '--format=%H %s', BASE + '..HEAD') + '\n')
print((reports / 'SOURCE_COMMITS.txt').read_text())
