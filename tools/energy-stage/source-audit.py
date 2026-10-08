"""Read-only stage compatibility/call-site evidence. No application or provider is run."""
import os
from pathlib import Path
import re
import subprocess

API = 'bb09fac04d11796ce676555dad094776cd1ef0ce'
TOPICS = {'calls', 'cleanup', 'history_bounded_read.go', 'history_bounded_read_test.go', 'main.go',
          'readiness.go', 'telemetry_core.go', 'telemetry_http.go', 'telemetry_import.go', 'telemetry_service.go'}
topic = os.environ['AUDIT_TOPIC']
assert topic in TOPICS

def git(*args):
    return subprocess.check_output(['git', *args], text=True)

print('SOURCE', git('rev-parse', 'HEAD').strip(), 'TOPIC', topic)
if topic == 'calls':
    pattern = r'DriveEnergyResolver.resolve|resolveDriveEnergy\(|DriveEnergyCalculator.calculate|estimateStandbyEnergy\(|calculateWeightedEfficiency\('
    result = subprocess.run(['git', 'grep', '-n', '-E', pattern, '--', 'android/app/src/main'], text=True, capture_output=True)
    print(result.stdout)
    for name in ('DriveDetailScreen.kt', 'ChargeDetailScreen.kt', 'ParkedDetailScreen.kt', 'DrivesViewModel.kt', 'EfficiencyViewModel.kt'):
        for p in Path('android/app/src/main').rglob(name):
            lines = p.read_text().splitlines()
            print('UI_ENERGY', str(p), 'LINES', len(lines))
            for i, line in enumerate(lines):
                if re.search(r'energy|Energy|efficien|Efficien|coverage|Coverage|Power', line):
                    print(f'{i+1}: {line}')
elif topic == 'cleanup':
    files = git('ls-files').splitlines()
    candidates = [f for f in files if any(s in f.lower() for s in ('/build/', '/node_modules/', '/dist/', '/received')) or f.endswith(('.apk', '.aab', '.zip', '.log', '.hprof'))]
    for name in candidates:
        p = Path(name)
        print('CANDIDATE', p.stat().st_size if p.exists() else -1, name)
        refs = subprocess.run(['git', 'grep', '-l', '-F', name, '--', ':!'+name], text=True, capture_output=True)
        print('REFERENCES', refs.stdout.strip())
    print('CANDIDATE_COUNT', len(candidates))
else:
    merged = subprocess.run(['git', 'merge-tree', '--write-tree', 'HEAD', API], text=True, capture_output=True)
    assert merged.returncode in (0, 1), merged.stderr
    tree = merged.stdout.splitlines()[0]
    assert re.fullmatch('[a-f0-9]{40}', tree)
    name = 'deploy/jourvolt-dev-mock/' + topic
    print('MERGE_METADATA', merged.stdout)
    print('OURS', git('rev-parse', 'HEAD:'+name).strip())
    print('API', git('rev-parse', API+':'+name).strip())
    print('MERGED', git('rev-parse', tree+':'+name).strip())
    print(git('diff', '--no-ext-diff', '--unified=4', 'HEAD', tree, '--', name))
    print('END_REVIEW', topic)
