"""Read-only stage compatibility/call-site evidence. No application or provider is run."""
import os
from pathlib import Path
import re
import subprocess

REPOSITORY = 'Jovifei/tesla-master-mimo'
API = 'bb09fac04d11796ce676555dad094776cd1ef0ce'
TOPICS = {'calls', 'cleanup', 'history_bounded_read.go', 'history_bounded_read_test.go', 'main.go',
          'readiness.go', 'telemetry_core.go', 'telemetry_http.go', 'telemetry_import.go', 'telemetry_service.go'}
topic = os.environ['AUDIT_TOPIC']
assert topic in TOPICS

def git(*args):
    return subprocess.check_output(['git', *args], text=True)

print('SOURCE', git('rev-parse', 'HEAD').strip(), 'TOPIC', topic)
if topic == 'calls':
    pattern = r'DriveEnergyResolver|DriveEnergyCalculator|estimateStandbyEnergy|calculateWeightedEfficiency|energyConsumedNet|energyConsumed|energy_consumed_net|EnergyRemaining|ACChargingEnergyIn|DCChargingEnergyIn'
    result = subprocess.run(['git', 'grep', '-l', '-E', pattern, '--', 'android/app/src/main', 'deploy/jourvolt-dev-mock'], text=True, capture_output=True)
    print(result.stdout)
    print('DIRECT_CALCULATION_CALLS')
    result = subprocess.run(['git', 'grep', '-n', '-E', r'DriveEnergyResolver.resolve|DriveEnergyCalculator.calculate|estimateStandbyEnergy\(|calculateWeightedEfficiency\(', '--', 'android/app/src/main'], text=True, capture_output=True)
    print(result.stdout)
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
    text = git('show', tree + ':' + name)
    lines = text.splitlines()
    ranges = []
    begin = None
    for i, line in enumerate(lines):
        if line.startswith('<<<<<<<'):
            begin = max(0, i-4)
        if line.startswith('>>>>>>>') and begin is not None:
            ranges.append((begin, min(len(lines), i+5)))
            begin = None
    print('MERGE_EXIT', merged.returncode, 'CONFLICTS', len(ranges), 'MERGED_LINES', len(lines))
    for a, b in ranges:
        print('\n'.join(f'{i+1}: {lines[i]}' for i in range(a, b)))
    print('END_REVIEW', topic)
