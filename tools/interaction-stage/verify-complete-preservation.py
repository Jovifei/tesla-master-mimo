#!/usr/bin/env python3
"""Verify this stage is additive UI work on its own exact base, not a replacement backend."""
import json
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
branch = os.environ.get('GITHUB_REF_NAME') or git('branch', '--show-current')
bases = {
    'codex/app-interaction-complete-20261106': 'fc812ff8505dc72f6002023bc93c041d5de92b75',
    'codex/miniprogram-interaction-complete-20261106': '51d6514a7d897ec2890e60bc37d00c43c4d87a71',
}
assert branch in bases, 'Unauthorized stage branch'
base = bases[branch]
subprocess.run(['git', 'merge-base', '--is-ancestor', base, 'HEAD'], cwd=root, check=True)
app = branch == 'codex/app-interaction-complete-20261106'
allowed_app = {
    'android/app/src/main/java/com/matelink/ui/components/InteractionVisuals.kt',
    'android/app/src/main/java/com/matelink/ui/components/InteractionEvidencePresentation.kt',
    'android/app/src/main/res/values/interaction_evidence.xml',
    'android/app/src/main/res/values-zh/interaction_evidence.xml',
}
allowed_mini = {
    'miniprogram/src/components/interaction/InteractionVisuals.tsx',
    'miniprogram/src/components/interaction/interaction.scss',
    'miniprogram/src/components/interaction/InteractionReceiptRegression.test.tsx',
}
changed = git('diff', '--name-only', base, 'HEAD').splitlines()
for path in changed:
    allowed = path.startswith(('.github/workflows/', 'docs/', 'tools/interaction-stage/'))
    if app:
        allowed |= path in allowed_app or path.startswith(('android/app/src/test/', 'android/app/src/androidTest/'))
    else:
        allowed |= path in allowed_mini
    assert allowed, f'Unexpected non-visual change: {path}'
    assert not path.endswith(('.jks', '.keystore', '.p12', '.pem', '.env', '.db')), path
subprocess.run(['git', 'diff', '--check', base, 'HEAD'], cwd=root, check=True)
print(json.dumps({'branch':branch,'base':base,'head':git('rev-parse','HEAD'),
                  'tree':git('rev-parse','HEAD^{tree}'),'parent':git('rev-parse','HEAD^'),
                  'changed':changed,'protected_data_and_screens_preserved':True}, indent=2))
