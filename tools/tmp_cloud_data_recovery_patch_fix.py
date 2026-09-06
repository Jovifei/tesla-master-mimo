from pathlib import Path

p = Path('tools/tmp_cloud_data_recovery_patch.py')
text = p.read_text()
old = '''replace_once('deploy/jourvolt-dev-mock/fleet_provider.go', 'path := "/api/1/vehicles/" + url.PathEscape(vin) + "/vehicle_data"\\n\\tvar payload struct {', 'path := fleetVehicleDataPath(vin)\\n\\tvar payload struct {')'''
new = '''replace_once('deploy/jourvolt-dev-mock/fleet_provider.go', 'path := "/api/1/vehicles/" + url.PathEscape(vin) + "/vehicle_data"', 'path := fleetVehicleDataPath(vin)')\n\nreplace_once('deploy/jourvolt-dev-mock/fleet_provider.go', 'case strings.HasPrefix(path, "/api/1/vehicles/") && strings.HasSuffix(path, "/vehicle_data"):', 'case strings.HasPrefix(path, "/api/1/vehicles/") && strings.Contains(path, "/vehicle_data"):')'''
if old not in text:
    raise SystemExit('fleet provider patch-driver anchor missing')
p.write_text(text.replace(old, new, 1))
