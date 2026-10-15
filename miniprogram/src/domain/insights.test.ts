import { describe, expect, it } from 'vitest'
import type { Charge, Drive } from '../services/types'
import { driveEnergyReceipt, resolveDriveEnergy, resolveChargeEnergy } from './drive_energy'
import { aggregateDriveHistory } from './history'
import { chargeCosts, insightDrive, makeManualTrip, manualTripKey, manualTripSummary, personalizedRange, ratedRangeComparisons, rangeInfluences, readManualTrips, removeManualTrip, writeManualTrip } from './insights'
import { CACHE_BUDGET_BYTES } from '../services/cache_budget'

const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z', now = Date.parse('2026-10-15T00:00:00Z')
function drive(id: string, distanceKm: number | null, value: number | null): Drive {
  const raw = { drive_id: id, start_date: start, end_date: end, source: 'telemetry_mqtt', odometer_details: { odometer_distance: distanceKm }, energy_contract: { version: 1, net_energy: {
    value_kwh: value, unit: 'kWh', source: 'telemetry_mqtt', quality: value == null ? 'unknown' : 'reported', method: 'api_reported_net', measurement_point: 'reported_net', start_date: start, end_date: end } } }
  const receipt = driveEnergyReceipt(raw)
  return { id, startDate: start, endDate: end, distanceKm, durationMinutes: 30, source: 'telemetry_mqtt', qualityState: 'observed', energyReceipt: receipt, energy: resolveDriveEnergy(receipt), speedAvgKmh: 60, outsideTempC: 20 } as Drive
}
function charge(id: string, cost: number | null, currencyCode: string | null, energyKwh: number | null = null): Charge {
  const energy = resolveChargeEnergy({ source: 'telemetry_mqtt', start_date: start, end_date: end, energy_contract: { version: 1,
    charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict',
    ac_input: { value_kwh: energyKwh, unit: 'kWh', source: 'telemetry_mqtt', quality: energyKwh == null ? 'unknown' : 'reported', method: 'session_counter_delta', measurement_point: 'ac_charger_input', start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1 },
    battery_input: { value_kwh: energyKwh, unit: 'kWh', source: 'telemetry_mqtt', quality: energyKwh == null ? 'unknown' : 'reported', method: 'session_counter_delta', measurement_point: 'battery_input', start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1 } } })
  return { id, sessionId: null, startDate: start, endDate: end, cost, currencyCode, currency: null, source: 'telemetry_mqtt', qualityState: 'observed', chargeMode: 'ac', address: 'synthetic place', energy } as Charge
}
const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid' }
function store() {
  const data = new Map<string, unknown>()
  return { data, getStorageSync: (key: string) => data.get(key), setStorageSync: (key: string, value: unknown) => { data.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: (key: string) => { data.delete(key) }, getStorageInfoSync: () => ({ keys: [...data.keys()] }) }
}
describe('actual evidence-backed insight calculations', () => {
  it('uses proven paired drive energy; preserves negative/zero/unknown on summary reconstruction', () => {
    const a = drive('1', 10, 2), unknown = drive('2', 10, null)
    a.energy!.energyKwh = 999
    expect(aggregateDriveHistory([insightDrive(a), insightDrive(unknown)]).efficiency).toMatchObject({ value: 20, known: 1, total: 2 })
    expect(aggregateDriveHistory([insightDrive(drive('3', 10, -0.5))]).efficiency.value).toBe(-5)
    expect(aggregateDriveHistory([insightDrive(drive('4', 10, 0))]).energy.value).toBe(0)
    expect(aggregateDriveHistory([insightDrive(unknown)]).energy.value).toBeNull()
  })
  it('separates explicit currencies and pairs a charge price only with that charge qualified input', () => {
    const result = chargeCosts([charge('1', 0, 'EUR', 2), charge('2', 10, 'USD', 4), charge('3', 100, null, 100), charge('4', null, 'USD'), charge('5', 90, 'USD', null)])
    expect(result).toMatchObject({ unknownCurrency: 1, missingCost: 1, total: 5 })
    expect(result.groups.map(row => [row.currency, row.cost, row.energyKwh, row.perKwh])).toEqual([['EUR', 0, 2, 0], ['USD', 100, 4, 2.5]])
    expect(chargeCosts([charge('1', null, null)]).groups).toEqual([])
    expect(JSON.stringify(result)).not.toContain('CNY')
  })
  it('matches App condition/global sample gates and refuses SOC or unproven capacity as usable energy', () => {
    const rows = Array.from({ length: 5 }, (_, i) => drive(String(i), 20, 4))
    const capacity = { currentCapacityKwh: 60, source: 'teslamate', observedAt: start, qualityState: 'observed' }
    const status = { outsideTemp: 20, speed: 60, batteryLevel: 99 } as never
    expect(personalizedRange(rows, capacity, status, now)).toMatchObject({ source: 'grouped', rangeKm: 300, sampleCount: 5, efficiencyKwh100Km: 20 })
    expect(personalizedRange(rows.slice(0, 4), capacity, status, now).rangeKm).toBeNull()
    expect(personalizedRange(rows, null, status, now).rangeKm).toBeNull()
    expect(personalizedRange(rows, { ...capacity, source: null }, status, now).rangeKm).toBeNull()
    expect(personalizedRange(Array.from({ length: 10 }, (_, i) => drive(String(i), 30, 6)), capacity, null, now)).toMatchObject({ source: 'global', rangeKm: 300 })
    expect(personalizedRange([drive('negative', 300, -1)], capacity, status, now).rangeKm).toBeNull()
  })
  it('requires two rated endpoints and metric distance rather than synthesizing a rated drop from SOC', () => {
    const valid = { ...drive('1', 10, 2), startRatedRangeKm: 100, endRatedRangeKm: 80 }
    expect(ratedRangeComparisons([valid, drive('2', 10, 2), { ...valid, id: '3', startRatedRangeKm: 0 }])).toHaveLength(1)
    expect(ratedRangeComparisons([valid])[0]).toMatchObject({ ratedDropKm: 20, deviationPercent: 50, distanceKm: 10 })
  })
  it('matches App summer/winter calendar and <=40/>40 speed grouping without assigning missing speed', () => {
    const base = { ...drive('1', 10, 2), startRatedRangeKm: 100, endRatedRangeKm: 80 }
    const rows = [{ ...base, startDate: '2026-07-01T12:00:00Z', speedAvgKmh: 40 }, { ...base, id: '2', startDate: '2026-01-01T12:00:00Z', distanceKm: 20, speedAvgKmh: 41 }, { ...base, id: '3', speedAvgKmh: null }]
    expect(rangeInfluences(rows)).toEqual({ summer: 50, winter: 0, lowSpeed: 50, highSpeed: 0 })
  })
  it('does not expose an overflowed total, per-kWh cost or range as Infinity or fabricated zero', () => {
    const result = chargeCosts([charge('1', 1e308, 'EUR'), charge('2', 1e308, 'EUR')])
    expect(result.groups[0].cost).toBeNull()
    expect(result.groups[0].months[0].cost).toBeNull()
    const range = personalizedRange(Array.from({ length: 10 }, (_, i) => drive(String(i), 30, 1e308)), { currentCapacityKwh: 60, source: 'teslamate', qualityState: 'observed', observedAt: start }, null, now)
    expect(range.rangeKm).toBeNull()
  })
})
describe('manual selected long trip persistence', () => {
  it('persists/rebuilds selected legs only, strips locations/credentials and recalculates proof without laundering unknown energy', () => {
    const storage = store(), raw = { ...drive('1', 10, 2), latitude: 42, startAddress: 'PRIVATE_ADDRESS', token: 'PRIVATE_TOKEN', vin: 'PRIVATE_VIN', positions: [{ latitude: 42 }] }
    const trip = makeManualTrip('trip1', '人工选择', [{ kind: 'drive', drive: raw }, { kind: 'drive', drive: drive('2', 10, null) }, { kind: 'charge', charge: charge('c', 0, 'EUR', 2) }], start)!
    expect(writeManualTrip(storage, scope, trip)).toBe(true)
    const recreated = { getStorageSync: (key: string) => JSON.parse(JSON.stringify(storage.data.get(key) ?? null)) }
    const rebuilt = readManualTrips(recreated, scope)[0]
    expect(manualTripSummary(rebuilt)).toMatchObject({ driveCount: 2, chargeCount: 1, energy: { value: 2, known: 1, total: 2 }, efficiency: { value: 20, known: 1, total: 2 } })
    const saved = JSON.stringify([...storage.data.values()])
    for (const secret of ['PRIVATE_ADDRESS', 'PRIVATE_TOKEN', 'PRIVATE_VIN', 'positions', 'latitude']) expect(saved).not.toContain(secret)
  })
  it('isolates account/origin/stable UID and retains unrelated scoped archives on exact trip removal', () => {
    const storage = store(), trip = makeManualTrip('one', '行程', [{ kind: 'drive', drive: drive('1', 10, 2) }], start)!
    writeManualTrip(storage, scope, trip)
    writeManualTrip(storage, { ...scope, userId: 'owner.child' }, trip)
    for (const changed of [{ ...scope, userId: 'other' }, { ...scope, origin: 'https://other.test' }, { ...scope, stableId: 'other' }]) expect(readManualTrips(storage, changed)).toEqual([])
    expect(removeManualTrip(storage, scope, 'one')).toBe(true)
    expect(readManualTrips(storage, { ...scope, userId: 'owner.child' })).toHaveLength(1)
    expect(manualTripKey({ ...scope, origin: 'http://api.test' })).toBeNull()
  })
  it('enforces selected-leg/count/shared byte budget without evicting existing trips', () => {
    const storage = store()
    expect(makeManualTrip('one', '行程', [{ kind: 'charge', charge: charge('c', 1, 'EUR') }])).toBeNull()
    expect(makeManualTrip('one', '行程', Array.from({ length: 51 }, (_, i) => ({ kind: 'drive', drive: drive(String(i), 1, 1) })))).toBeNull()
    const trip = makeManualTrip('one', '行程', [{ kind: 'drive', drive: drive('1', 10, 2) }], start)!
    expect(writeManualTrip(storage, scope, trip)).toBe(true)
    storage.data.set('matelink.existing.archive', 'x'.repeat(CACHE_BUDGET_BYTES))
    expect(writeManualTrip(storage, scope, { ...trip, id: 'two' })).toBe(false)
    expect(readManualTrips(storage, scope).map(row => row.id)).toEqual(['one'])
  })
  it('deduplicates same selected canonical legs and rejects explicit withdrawn proof on reconstructed summary', () => {
    const a = drive('1', 10, 2), trip = makeManualTrip('one', '行程', [{ kind: 'drive', drive: a }, { kind: 'drive', drive: a }], start)!
    expect(trip.legs).toHaveLength(1)
    const row = (trip.legs[0] as { kind: 'drive'; drive: Drive }).drive
    row.energyReceipt!.energy_contract!.net_energy!.quality = 'unknown'
    expect(manualTripSummary(trip).energy.value).toBeNull()
  })
  it('refuses a 21st saved trip without truncating existing user records', () => {
    const storage = store(), trip = makeManualTrip('0', '行程', [{ kind: 'drive', drive: drive('1', 10, 2) }], start)!
    for (let i = 0; i < 20; i++) expect(writeManualTrip(storage, scope, { ...trip, id: String(i) })).toBe(true)
    expect(writeManualTrip(storage, scope, { ...trip, id: 'twenty-one' })).toBe(false)
    expect(readManualTrips(storage, scope)).toHaveLength(20)
  })
  it('refuses an oversized fifty-leg payload while retaining the previous saved trip', () => {
    const storage = store(), previous = makeManualTrip('one', '保留行程', [{ kind: 'drive', drive: drive('1', 10, 2) }], start)!
    expect(writeManualTrip(storage, scope, previous)).toBe(true)
    const legs = Array.from({ length: 50 }, (_, i) => {
      const row = drive(`synthetic-${i}-${'x'.repeat(180)}`, 10, 2), proof = row.energyReceipt!.energy_contract!.net_energy!
      proof.source_field = 'f'.repeat(160); proof.capacity_model = 'm'.repeat(160); proof.capacity_trim = 't'.repeat(160)
      return { kind: 'drive' as const, drive: row }
    })
    expect(writeManualTrip(storage, scope, makeManualTrip('large', '过大组合', legs, start)!)).toBe(false)
    expect(readManualTrips(storage, scope).map(trip => trip.id)).toEqual(['one'])
  })
  it('keeps an ambiguous existing same-key archive unchanged rather than replacing it on save or delete', () => {
    const storage = store(), key = manualTripKey(scope)!, archived = { schema: 0, scope: key, trips: [{ unknown: 'preserve-owner-archive' }] }
    storage.data.set(key, archived)
    const trip = makeManualTrip('one', '新行程', [{ kind: 'drive', drive: drive('1', 10, 2) }], start)!
    expect(writeManualTrip(storage, scope, trip)).toBe(false)
    expect(removeManualTrip(storage, scope, 'one')).toBe(false)
    expect(storage.data.get(key)).toEqual(archived)
  })
})
