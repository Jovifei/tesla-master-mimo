#!/usr/bin/env python3
"""Static asset/consumer checks supplement, never replace, real component and native tests."""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[2]
spec = json.loads((root/'docs/design/interaction-visuals-v1.json').read_text())
assert spec['scope'] == 'presentational-only'
assert spec['dataWrites'] is False and spec['providerRequests'] is False and spec['sessionWrites'] is False
assert spec['wheelOrder'] == ['fl','fr','rl','rr'] and spec['vehicleViewport'] == [240,460]
svg = ET.parse(root/'docs/design/vehicle-position-schematic.svg').getroot()
assert svg.attrib['viewBox'] == '0 0 240 460'
asset = (root/'miniprogram/src/assets/interaction/vehicle-position.png').read_bytes()
assert asset[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',asset[16:24]) == (240,460)
assert hashlib.sha256(asset).hexdigest() == '435a84f40faa5459b9c0a4084d84f8f21207e4e3ea9e8fde2209fa529a4a067a'
# Pin only the additional presentational asset to App's existing Juniper image.
# Never replace the original catalogue or claim that this illustration identifies a car.
connection = (root/'miniprogram/src/assets/interaction/vehicle-connection.png').read_bytes()
catalogue = (root/'miniprogram/vehicle-assets/vehicle-assets-5/images/myj_PPSW_WY19P.png').read_bytes()
assert connection == catalogue
assert hashlib.sha256(connection).hexdigest() == '450d729623ffdb4aa7f2936afd6b0522f918f0b1c8de1f81cdb90baf71a56d54'
assert struct.unpack('>II', connection[16:24]) == (720,405)
assert "import vehicle from '../../assets/interaction/vehicle-connection.png'" in (root/'miniprogram/src/components/interaction/InteractionVisuals.tsx').read_text()
for path, required in {
 'miniprogram/src/pages/vehicle/index.tsx': ['ConnectionVisual'],
 'miniprogram/src/pages/auth/index.tsx': ['ConnectionVisual'],
 'miniprogram/src/pages/me/index.tsx': ['ConnectionVisual','connectionVisualPhase'],
 'miniprogram/src/components/TpmsPanel.tsx': ['TpmsVehicleVisual','selectedWheel'],
 'miniprogram/src/pages/drives/index.tsx': ['RouteInteractionVisual','EmptyHistoryVisual','ParkedChargeTimeline'],
 'miniprogram/src/pages/charges/index.tsx': ['EmptyHistoryVisual'],
 'miniprogram/src/components/dashboard/DashboardParts.tsx': ['VehicleOpeningsVisual'],
 'miniprogram/src/components/LoadingIndicator.tsx': ['matelink_loading_logo','useVisualMotion'],
}.items():
 text=(root/path).read_text()
 assert all(term in text for term in required),path
print('PASS shared artwork/state contract and wired consumers; no data/provider/session writes')
