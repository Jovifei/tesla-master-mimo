"""Validate the design receipt, not the unaccepted Figma canvas or production App."""
from pathlib import Path
import json, hashlib, subprocess
R=Path(__file__).resolve().parent
c=json.loads((R/'design-contract.json').read_text())
assert c['repository']=='Jovifei/tesla-master-mimo'
assert len(c['figma']['android_frames'])==51 and len(c['figma']['mini_frames'])==10
assert c['figma']['frame_count']==61
assert c['figma']['android_click_nodes']+c['figma']['mini_click_nodes']==250
assert c['figma']['unresolved_navigation_targets_at_wiring']==0
assert c['figma']['final_visual_acceptance'] is False
assert c['figma']['repair_script_applied'] is False
assert len(c['figma']['pending_repairs'])>=3
assert c['evidence']['android_unique_screenshots']==38
assert c['evidence']['embedded_video_count']==0
assert c['motion_ms']['tab']==0 and c['motion_ms']['reduced']==0
assert all(c['truth_contract'][k] for k in ['unknown_is_not_zero','valid_zero_preserved','estimated_cost_separate','weather_must_not_replace_vehicle_temperature','legacy_unverified_tpms_excluded'])
text=(R/'preview.html').read_text()
assert "connect-src 'none'" in text and '<script src=' not in text
assert '__CAR__' not in text
assert hashlib.sha256((R/'preview.html').read_bytes()).hexdigest()==c['preview']['sha256']
subprocess.run(['node','--check',str(R/'figma-repair.mjs')],check=True)
print('Design manifest and offline build checks passed. Figma visual gate remains BLOCKED/FAILED.')
