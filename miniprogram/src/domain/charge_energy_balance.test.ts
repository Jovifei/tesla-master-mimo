import { expect, it } from 'vitest'
import { qualifiedAcEnergyBalance } from './charge_energy_balance'
import { mergeChargeEnergyRecord, resolveChargeEnergy } from './drive_energy'
import type { Charge, EnergyContract } from '../services/types'

const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z'
function row(mode: string | null = 'ac'): Charge {
  const metric = (point: string, value: number) => ({ value_kwh: value, unit: 'kWh', method: 'session_counter_delta',
    measurement_point: point, source: 'telemetry_mqtt', quality: 'reported', start_date: start, end_date: end,
    observed_start_at: start, observed_end_at: end, time_basis: 'collector_received_at', coverage_kind: 'endpoints', coverage_ratio: 1 })
  const proof: EnergyContract = { version: 1, charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict',
    ac_input: metric('ac_charger_input', 10), battery_input: metric('battery_input', 9) }
  const energy = resolveChargeEnergy({ source: 'telemetry_mqtt', start_date: start, end_date: end, energy_contract: proof })
  return { id: '1', startDate: start, endDate: end, source: 'telemetry_mqtt', reportedChargeMode: mode, energy } as Charge
}
it('accepts equal offset instants and rejects local timestamps just like App Instant.parse', () => {
  const charge = row()
  expect(qualifiedAcEnergyBalance(charge)).toMatchObject({ efficiencyPercent: 90, lossKwh: 1 })
  charge.energy!.evidence!.battery_input!.observed_start_at = '2026-10-14T08:00:00+08:00'
  expect(qualifiedAcEnergyBalance(charge)?.efficiencyPercent).toBe(90)
  charge.energy!.evidence!.battery_input!.observed_start_at = '2026-10-14T00:00:00'
  expect(qualifiedAcEnergyBalance(charge)).toBeNull()
})
it('never grafts a cached raw AC mode under a newly admitted proof without the provider mode', () => {
  const prior = row(), incoming = row(null)
  const result = mergeChargeEnergyRecord(prior, incoming, { ...incoming, reportedChargeMode: 'ac' })
  expect(result.reportedChargeMode).toBeNull()
  expect(result.energy?.batteryInputKwh).toBe(9)
  expect(qualifiedAcEnergyBalance(result)).toBeNull()
})
it('retains a prior raw mode only with its retained proof and unchanged window', () => {
  const prior = row(), incoming = { ...row(null), energy: undefined }
  const retained = mergeChargeEnergyRecord(prior, incoming, incoming)
  expect(qualifiedAcEnergyBalance(retained)?.efficiencyPercent).toBe(90)
  const moved = { ...incoming, endDate: '2026-10-14T01:00:00Z' }
  expect(qualifiedAcEnergyBalance(mergeChargeEnergyRecord(prior, moved, moved))).toBeNull()
})
