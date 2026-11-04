from pathlib import Path
import base64, hashlib, lzma, subprocess, shutil
root=Path(__file__).parent
payload=''.join((root/f'mini-patch-{i}.b64').read_text().strip() for i in range(2))
patch=lzma.decompress(base64.b64decode(payload, validate=True))
assert hashlib.sha256(patch).hexdigest()=='d58b3b83ae996d56c3d4163a08c51f3b2d743d5dc87a1e2943cf3a8556696c59', 'source transport mismatch'
subprocess.run(['git','apply','--check','--index','-'], input=patch, check=True)
subprocess.run(['git','apply','--index','-'], input=patch, check=True)
initial=subprocess.check_output(['git','diff','--cached','--name-only'],text=True).splitlines()
assert len(initial)==27 and all(p.startswith(('miniprogram/src/','docs/')) for p in initial), initial
changed=[]
def replace(path, old, new, count=1):
    p=Path(path); s=p.read_text(); assert s.count(old)==count, (path, old, s.count(old)); p.write_text(s.replace(old,new)); changed.append(path)
replace('miniprogram/src/components/interaction/InteractionVisuals.tsx',' ariaRole="button"','')
for f in ('miniprogram/src/components/TpmsPanel.test.tsx','miniprogram/src/services/tpms_pipeline.test.tsx'):
    replace(f,"Text: 'text', View: 'view', Button: 'button'","Text: 'text', View: 'view', Button: 'button', Image: 'image'")
    replace(f,"tree.root.findByType('button').props.onClick()","tree.root.findByProps({ className: 'tpms-evidence-toggle' }).props.onClick()",3 if '/components/' in f else 1)
replace('miniprogram/src/components/dashboard/AppGeometryContract.test.tsx',"import { DashboardVehicleArt }", "vi.mock('@tarojs/taro', () => ({ default: {} }))\nimport { DashboardVehicleArt }")
f='miniprogram/src/components/interaction/interaction.scss'
replace(f,'.tpms-position-board { min-height:216px; position:relative; display:flex; justify-content:center; align-items:center; }','.tpms-position-board { min-height:216px; display:grid; grid-template-columns:minmax(0,1fr) 108px minmax(0,1fr); column-gap:8px; row-gap:32px; align-items:center; }')
replace(f,'.interaction-visual .tpms-position-wheel { position:absolute; width:31%; max-width:112px; min-height:62px; margin:0; padding:6px 3px; text-align:center; border:1px solid var(--iv-line); border-radius:12px; line-height:1.35; background:var(--iv-surface); color:var(--iv-text); }','.tpms-position-board > .interaction-vehicle-top { grid-column:2; grid-row:1 / 3; align-self:center; }\n.interaction-visual .tpms-position-wheel { width:100%; min-width:0; min-height:62px; margin:0; padding:6px 3px; text-align:center; border:1px solid var(--iv-line); border-radius:12px; line-height:1.35; background:var(--iv-surface); color:var(--iv-text); overflow-wrap:anywhere; }')
replace(f,'.tpms-position-fl { left:0; top:23px; } .tpms-position-fr { right:0; top:23px; }\n.tpms-position-rl { left:0; bottom:21px; } .tpms-position-rr { right:0; bottom:21px; }','.tpms-position-fl { grid-column:1; grid-row:1; } .tpms-position-fr { grid-column:3; grid-row:1; }\n.tpms-position-rl { grid-column:1; grid-row:2; } .tpms-position-rr { grid-column:3; grid-row:2; }')
android='android/app/src/test/java/com/matelink/ui/screens/charges/ChargesRoomCostConsumerTest.kt'
replace(android,'        f.db.chargeSummaryDao().upsert(requireNotNull(ChargeData(65,\n            startDate = start, endDate = end, chargeEnergyAdded = 8.0,','        // Distinct real sessions need distinct windows: same-window receipts are\n        // intentionally deduplicated by UnifiedHistoryRepository. Do not race its\n        // final projection against an earlier unmerged Room emission.\n        f.db.chargeSummaryDao().upsert(requireNotNull(ChargeData(65,\n            startDate = "2026-10-20T12:00:00Z", endDate = "2026-10-20T13:00:00Z", chargeEnergyAdded = 8.0,')
dest=Path('tools/interaction-stage/browser'); dest.mkdir(parents=True,exist_ok=True)
for name in ('platform.tsx','fixture.tsx','fixture.css','build.mjs','capture.mjs'):
    p=dest/name; assert not p.exists(); shutil.copyfile(root/'browser'/name,p); changed.append(str(p))
doc=Path('docs/REVIEW-INTERACTION-VISUALS-CORRECTIONS-20261104.md'); assert not doc.exists(); shutil.copyfile(root/'corrections.md',doc); changed.append(str(doc))
subprocess.run(['git','add',*sorted(set(changed))],check=True)
paths=subprocess.check_output(['git','diff','--cached','--name-only'],text=True).splitlines()
assert len(paths)==37, paths
assert all(p.startswith(('miniprogram/src/','docs/','tools/interaction-stage/browser/')) or p==android for p in paths), paths
assert not any(p.endswith(('package.json','package-lock.json')) for p in paths)
print('Applied real visuals and focused corrective tests; owner 0.6.5 locks and original data contracts preserved.')
