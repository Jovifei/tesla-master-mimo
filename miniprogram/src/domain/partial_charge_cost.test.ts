import { describe, expect, it, vi } from 'vitest'
import type { EnergyContract, RawCharge } from '../services/types'
import { partialChargeBatteryInput, resolveChargeEnergy } from './drive_energy'
import { chargeCosts } from './insights'
import { overlayChargeCosts, writeChargeCostOverride } from '../services/charge_cost'
vi.mock('@tarojs/taro', () => ({ default: {} }))
const scope = { userId: 'synthetic-user', stableId: 'synthetic-vehicle', origin: 'https://example.test' }
const price = { amount: 1.5, currency: 'CNY', source: 'saved_default' } as const
function raw(energy = 10): RawCharge {
  return { charge_id: 7, source: 'telemetry_mqtt', source_instance_id: 'collector-a',
    start_date: '2026-01-01T00:00:00Z', end_date: '2026-01-01T01:00:00Z', currency_code: 'CNY',
    quality_state: 'incomplete', energy_contract: { version: 1, battery_input: {
      method: 'session_counter_delta', measurement_point: 'battery_input', source: 'telemetry_mqtt',
      source_field: 'DCChargingEnergyIn', quality: 'unknown', reason: 'partial_counter_window',
      start_date: '2026-01-01T00:00:00Z', end_date: '2026-01-01T01:00:00Z',
      observed_start_at: '2026-01-01T00:05:00Z', observed_end_at: '2026-01-01T00:55:00Z',
      time_basis: 'collector_received_at', coverage_kind: 'endpoints', coverage_ratio: 5 / 6,
      covered_energy_kwh: energy } } }
}
function storage() {
 const values = new Map<string, unknown>()
 return { values, getStorageSync: (key: string) => values.get(key),
   getStorageInfoSync: () => ({ keys: [...values.keys()] }),
   setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
   removeStorageSync: (key: string) => { values.delete(key) } }
}
describe('same App partial-counter evidence through actual Mini normalizer and cost consumer', () => {
  it('keeps partial energy out of full energy and bill totals, including real zero', async () => {
    const { normalizeCharge } = await import('../services/api')
    for (const amount of [10, 0]) {
      const wire = raw(amount), charge = normalizeCharge(wire)!
      expect(partialChargeBatteryInput(wire)).toBe(amount)
      expect(resolveChargeEnergy(wire).batteryInputKwh).toBeNull()
      const display = overlayChargeCosts(storage(), scope, [charge], price)[0]
      expect(display.partialEstimatedCost).toBe(amount * 1.5)
      expect(display.cost).toBeNull()
      expect(display.costProvenance).toBe('estimate')
      expect(display.energy?.batteryInputKwh).toBeNull()
      expect(chargeCosts([display]).groups).toHaveLength(0)
    }
  })
  it('manual confirmed zero overrides the estimate and survives storage reopening', async () => {
    const { normalizeCharge } = await import('../services/api')
    const charge = normalizeCharge(raw())!, store = storage()
    expect(writeChargeCostOverride(store, scope, charge, 0, 'CNY', () => true)).toBe(true)
    const reopened = { getStorageSync: (key: string) => JSON.parse(JSON.stringify(store.values.get(key) ?? null)) }
    expect(overlayChargeCosts(reopened, scope, [charge], price)[0]).toMatchObject({
      cost: 0, costProvenance: 'manual', partialEstimatedCost: null })
    expect(overlayChargeCosts(reopened, { ...scope, userId: 'other' }, [charge], price)[0].costProvenance).toBe('estimate')
  })
  it('rejects unknown, reversed, source/unit/window conflict, nonfinite and unqualified evidence', async () => {
    const base = raw(), proof = base.energy_contract as EnergyContract
    const changes = [{ source: 'fleet_telemetry' }, { unit: 'Wh' }, { quality: 'reported' },
      { covered_energy_kwh: -1 }, { covered_energy_kwh: NaN }, { covered_energy_kwh: Infinity },
      { value_kwh: 99 }, { coverage_ratio: 1 }, { coverage_ratio: 0.1 },
      { observed_start_at: null }, { observed_end_at: '2025-12-31T23:00:00Z' },
      { end_date: '2026-01-02T01:00:00Z' }, { time_basis: 'unknown' }]
    for (const change of changes) expect(partialChargeBatteryInput({ ...base,
      energy_contract: { ...proof, battery_input: { ...proof.battery_input, ...change } } })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, source: 'fleet_telemetry' })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, energy_contract: { ...proof, version: 2 } })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, start_date: '2026-01-01T00:00:00' })).toBeNull()
    const { normalizeCharge } = await import('../services/api')
    const isolated = normalizeCharge({ ...base, quality_state: 'quarantined' })
    if (isolated) expect(overlayChargeCosts(storage(), scope, [isolated], price)[0].partialEstimatedCost).toBeNull()
  })
})
