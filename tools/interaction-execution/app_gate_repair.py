"""Exact reviewed corrections on the owned interaction branch; no data or provider I/O."""
from pathlib import Path
from hashlib import sha256

changes = {
 'android/app/src/main/java/com/matelink/domain/analytics/DriveEnergyBinding.kt': (
  '0ce8df1beed19c3495db0f8a88b1dd1aa1d2d5129f885f33c44d8762a800330a', [
   ('    val supplied = energyContract?.takeUnless {', '''    // Conflicting provider record identifiers cannot acquire evidence by presentation fallback.
    if (sourceRecordIdentity.conflicted) return ResolvedDriveEnergy(
        DriveEnergyEstimate(null, null, DriveEnergySource.UNAVAILABLE,
            qualityReason = "source_record_conflict"),
        EnergyMetric(source = source, startDate = startDate, endDate = endDate,
            quality = "unknown", reason = "source_record_conflict"))
    val supplied = energyContract?.takeUnless {'''),
   ('legacyScalarEnergyAllowed(source) || source == "telemetry_mqtt" || source == "fleet_api"', 'legacyScalarEnergyAllowed(source)')]),
 'android/app/src/main/java/com/matelink/ui/screens/drives/DrivesViewModel.kt': (
  'e7d857cc2175e32f66b86ccbc5a1d358cafc8e9fd30399a3fdfc1fbde22973cc', [
   ('    val matching = metrics?.takeIf {', '''    if (sourceRecordIdentity.conflicted)
        return presentDriveDetailEnergy(null, null, null, null, null, energyContract?.netEnergy)
    val matching = metrics?.takeIf {'''),
   ('legacyScalarEnergyAllowed(source) || source == "telemetry_mqtt" || source == "fleet_api"', 'legacyScalarEnergyAllowed(source)')]),
 'android/app/src/main/java/com/matelink/data/repository/DriveHistoryEnergyEnrichment.kt': (
  '128df75dcfacd6c6bc837c2058e3324ce1081c276fcb5f46fc72b092af39c53e', [
   ('detail.energyContract?.netEnergy?.reason in setOf("no_verified_driving_net_energy", "local_detail_scope_or_window_mismatch")',
    'detail.energyContract?.netEnergy?.reason == "no_verified_driving_net_energy"')]),
}
for name, (expected, replacements) in changes.items():
 p = Path(name); data = p.read_bytes()
 assert sha256(data).hexdigest() == expected, 'source moved: ' + name
 text = data.decode()
 for before, after in replacements:
  assert text.count(before) == 1, 'ambiguous edit: ' + name
  text = text.replace(before, after)
 p.write_text(text)
print('Three exact consumer corrections applied; raw receipts and data schemas untouched.')
