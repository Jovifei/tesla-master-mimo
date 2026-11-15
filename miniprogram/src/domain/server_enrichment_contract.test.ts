import { describe, expect, it } from 'vitest'
import { driveEnergyReceipt, resolveChargeEnergy, resolveDriveEnergy } from './drive_energy'

describe('pilot server enrichment contracts', () => {
  it('admits the derived SOC capacity estimate', () => {
    const start = '2026-11-02T10:54:00Z', end = '2026-11-02T11:20:00Z', capacity = 58.545
    const value = (75 - 74) / 100 * capacity
    const raw = {
      drive_id: 496, start_date: start, end_date: end, source: 'teslamate_archive',
      odometer_details: { odometer_distance: 25 },
      battery_details: { start_battery_level: 75, end_battery_level: 74 },
      energy_contract: { version: 1, net_energy: {
        value_kwh: value, unit: 'kWh', method: 'soc_capacity_estimate', measurement_point: 'battery_soc',
        source: 'teslamate_archive', quality: 'estimated', time_basis: 'source_report', coverage_kind: 'endpoints',
        coverage_ratio: 1, capacity_kwh: capacity, capacity_basis: 'api_reported_current_capacity',
        capacity_source_field: 'battery_health.current_capacity', calculation_version: 1,
        start_soc: 75, end_soc: 74, start_date: start, end_date: end, reason: 'soc_capacity_estimate',
      } },
    }
    const result = resolveDriveEnergy(driveEnergyReceipt(raw as never))
    expect(result.energyKwh).toBeCloseTo(value, 12)
    expect(result.source).toBe('soc_capacity_estimate')
    expect(result.quality).toBe('estimated')
  })

  it('admits the borrowed charge session counter', () => {
    const start = '2026-11-14T12:41:37Z', end = '2026-11-14T12:57:37Z'
    const raw = {
      charge_id: 1, start_date: start, end_date: end, source: 'local_import', charge_energy_added: null,
      energy_contract: { version: 1, battery_input: {
        value_kwh: 12.3, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'battery_input',
        source: 'local_import', quality: 'reported', time_basis: 'source_report', coverage_kind: 'endpoints',
        coverage_ratio: 1, start_date: start, end_date: end,
      } },
    }
    const result = resolveChargeEnergy(raw as never)
    expect(result.batteryInputKwh).toBe(12.3)
    expect(result.quality).toBe('reported')
  })
})