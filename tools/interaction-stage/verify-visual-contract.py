#!/usr/bin/env python3
from pathlib import Path
import json,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]
spec=json.loads((root/'docs/design/interaction-visuals-v1.json').read_text())
assert spec['scope']=='presentational-only' and spec['dataWrites'] is False
assert spec['wheelOrder']==['fl','fr','rl','rr']
ns={'android':'http://schemas.android.com/apk/res/android'}
a='{http://schemas.android.com/apk/res/android}'
vector=ET.parse(root/'android/app/src/main/res/drawable/interaction_vehicle_top.xml').getroot()
assert float(vector.attrib[a+'viewportWidth'])==240
assert float(vector.attrib[a+'viewportHeight'])==460
svg=ET.parse(root/'docs/design/vehicle-position-schematic.svg').getroot()
assert [(p.attrib[a+'fillColor'],p.attrib[a+'pathData']) for p in vector]==[(p.attrib['fill'],p.attrib['d']) for p in svg]
for path in ['android/app/src/main/res/values/interaction_visuals.xml','android/app/src/main/res/values-zh/interaction_visuals.xml']:
    values=ET.parse(root/path).getroot(); assert len(values)>=16 and len({v.attrib['name'] for v in values})==len(values)
print('PASS: shared shape, palette/state boundary, locale resources; no fake data')
