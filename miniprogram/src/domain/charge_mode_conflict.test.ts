import { expect, it, vi } from 'vitest'
import type { Charge } from '../services/types'
import { qualifiedAcEnergyBalance } from './charge_energy_balance'
import { chargeCosts, makeManualTrip, readManualTrips, writeManualTrip } from './insights'
import { estimateTariffCost } from './tariff'

vi.mock('@tarojs/taro', () => ({ default: {} }))

const start = '2026-10-16T09:30:00+08:00', end = '2026-10-16T10:30:00+08:00'
const metric = (value: number, point: string) => ({ value_kwh: value, unit: 'kWh', method: 'session_counter_delta', measurement_point: point,
  source: 'teslamate_archive', quality: 'reported', start_date: start, end_date: end, observed_start_at: start, observed_end_at: end,
  time_basis: 'source_sample_time', coverage_kind: 'endpoints', coverage_ratio: 1 })
const raw = { charge_id: 7, session_id: 'charge-7', source: 'teslamate_archive', source_instance_id: 'collector', source_vehicle_id: 'vehicle', source_record_id: 'record',
  start_date: start, end_date: end, quality_state: 'observed', cost: 12, currency: 'CNY', charge_type: 'ac', fast_charger_present: true,
  energy_contract: { version: 1, charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict', battery_input: metric(8, 'battery_input'), ac_input: metric(10, 'ac_charger_input') } }
const tariff = { currency: 'CNY', peak: 1, flat: .7, valley: .3, timeZone: 'Asia/Shanghai' }
const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'vehicle' }
function storage() {
  const values = new Map<string, unknown>()
  return { getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: (key: string) => { values.delete(key) }, getStorageInfoSync: () => ({ keys: [...values.keys()] }) }
}
async function tripFor(charge: Charge) {
  const { normalizeDrive } = await import('../services/api')
  const drive = normalizeDrive({ drive_id: 1, source: 'teslamate_archive', start_date: start, end_date: end, odometer_details: { odometer_distance: 10 } })!
  return makeManualTrip('trip', '充电行程', [{ kind: 'drive', drive }, { kind: 'charge', charge }], start)!
}

it('refuses AC tariff estimation for a normalized mode conflict while retaining bill and energy', async () => {
  const { normalizeCharge } = await import('../services/api')
  const charge = normalizeCharge(raw)!
  expect(charge).toMatchObject({ chargeModeConflict: true, chargeMode: null, cost: 12, energyAddedKwh: 8, energyUsedKwh: 10 })
  expect(qualifiedAcEnergyBalance(charge)).toBeNull()
  expect(estimateTariffCost(charge, tariff)).toBeNull()
  expect(chargeCosts([charge]).groups[0]).toMatchObject({ cost: 12, energyKwh: 8, months: [{ ac: null, dc: null, unknownMode: 12 }] })
})

it('preserves the mode conflict across manual-trip serialization and monthly accounting', async () => {
  const { normalizeCharge } = await import('../services/api')
  const charge = normalizeCharge(raw)!, cache = storage()
  expect(writeManualTrip(cache, scope, await tripFor(charge))).toBe(true)
  const leg = readManualTrips(cache, scope)[0].legs.find(item => item.kind === 'charge')!
  if (leg.kind !== 'charge') throw new Error('expected charge')
  expect(leg.charge).toMatchObject({ chargeModeConflict: true, chargeMode: null, cost: 12, energyAddedKwh: 8, energyUsedKwh: 10, energy: { chargeMode: null } })
  expect(estimateTariffCost(leg.charge, tariff)).toBeNull()
  expect(chargeCosts([{ ...leg.charge, chargeMode: 'ac' }]).groups[0]).toMatchObject({ cost: 12, energyKwh: 8, months: [{ ac: null, dc: null, unknownMode: 12 }] })
})

it('fails closed for malformed stored conflict markers', async () => {
  const { normalizeCharge } = await import('../services/api')
  const malformed = { ...normalizeCharge({ ...raw, fast_charger_present: false })!, chargeModeConflict: 'true' } as unknown as Charge
  expect(estimateTariffCost(malformed, tariff)).toBeNull()
  expect(chargeCosts([malformed]).groups[0].months[0]).toMatchObject({ ac: null, dc: null, unknownMode: 12 })
  const cache = storage()
  expect(writeManualTrip(cache, scope, await tripFor(malformed))).toBe(true)
  const leg = readManualTrips(cache, scope)[0].legs.find(item => item.kind === 'charge')!
  if (leg.kind !== 'charge') throw new Error('expected charge')
  expect(leg.charge).toMatchObject({ chargeModeConflict: true, chargeMode: null, cost: 12, energyAddedKwh: 8, energy: { chargeMode: null } })
  expect(estimateTariffCost(leg.charge, tariff)).toBeNull()
})

it.each([undefined, null, false])('keeps older non-conflicting charge records usable when marker is %s', async marker => {
  const { normalizeCharge } = await import('../services/api')
  const { fast_charger_present: _, ...oldRaw } = raw
  const charge = { ...normalizeCharge(oldRaw)!, chargeModeConflict: marker } as Charge
  expect(estimateTariffCost(charge, tariff)).toBeCloseTo(8.5, 10)
  const cache = storage()
  expect(writeManualTrip(cache, scope, await tripFor(charge))).toBe(true)
  const leg = readManualTrips(cache, scope)[0].legs.find(item => item.kind === 'charge')!
  if (leg.kind !== 'charge') throw new Error('expected charge')
  expect(leg.charge).toMatchObject({ chargeMode: 'ac', cost: 12, energyAddedKwh: 8, energy: { chargeMode: 'ac' } })
  expect(estimateTariffCost(leg.charge, tariff)).toBeCloseTo(8.5, 10)
})
