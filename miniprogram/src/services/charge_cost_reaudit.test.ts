import { describe, expect, it } from 'vitest'
import type { Charge } from './types'
import { chargeEstimatePrice, chargeCostKey, overlayChargeCosts, readChargeCostOverride, writeChargeCostOverride } from './charge_cost'
import { tariffKey } from '../domain/tariff'

const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'vehicle' }
const price = { amount: 1.14, currency: 'CNY', source: 'app_default' } as const
const charge = (patch: Partial<Charge> = {}): Charge => ({ id: '7', sessionId: 'session-7', source: 'teslamate_archive',
  sourceInstanceId: 'instance-a', sourceVehicleId: 'car-a', sourceRecordId: 'record-7', qualityState: 'observed', qualityReason: null,
  startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', address: null, durationMinutes: 60,
  energyAddedKwh: 10, energyUsedKwh: null, cost: null, startBatteryLevel: 40, endBatteryLevel: 60,
  latitude: null, longitude: null, chargerPowerKw: null, chargeMode: 'dc',
  energy: { batteryInputKwh: 10, acInputKwh: null, inputEnergyKwh: null, quality: 'reported', reason: null, evidence: null, chargeMode: 'dc' },
  ...patch })
function storage() {
  const rows = new Map<string, unknown>()
  return { rows, getStorageSync: (key: string) => rows.get(key), setStorageSync: (key: string, value: unknown) => rows.set(key, structuredClone(value)),
    removeStorageSync: (key: string) => rows.delete(key), getStorageInfoSync: () => ({ keys: [...rows.keys()] }) }
}

describe('charge cost source identity and projection', () => {
  it('does not infer free from present vehicle-level DC eligibility', () => {
    const projected = overlayChargeCosts(storage(), scope, [charge()], price, true)[0]
    expect(projected).toMatchObject({ costProvenance: 'estimate', currencyCode: 'CNY', reportedCost: null })
    expect(projected.cost).toBeCloseTo(11.4)
  })
  it('never treats TeslaMate missing-price zero as an actual bill even when estimates are disabled', () => {
    const projected = overlayChargeCosts(storage(), scope, [charge({ cost: 0 })])[0]
    expect(projected).toMatchObject({ cost: null, costProvenance: 'unknown', reportedCost: 0 })
  })
  it('does not apply a saved cost to another source instance, record, or vehicle', () => {
    const store = storage(), original = charge()
    expect(writeChargeCostOverride(store, scope, original, 7, 'CNY', () => true)).toBe(true)
    expect(readChargeCostOverride(store, scope, { ...original, id: 'new-public-id' })?.amount).toBe(7)
    for (const changed of [{ sourceInstanceId: 'instance-b' }, { sourceRecordId: 'record-b' }, { sourceVehicleId: 'car-b' }]) {
      expect(readChargeCostOverride(store, scope, { ...original, ...changed })).toBeNull()
    }
  })
  it('preserves old unbound override while refusing to attach it to an identified source instance', () => {
    const store = storage(), key = chargeCostKey(scope)!
    const legacy = { schema: 1, scope: key, rows: [{ identity: 'session:session-7', source: 'teslamate_archive', amount: 4, currency: 'CNY', updatedAt: '2026-10-01T00:00:00Z' }] }
    store.rows.set(key, structuredClone(legacy))
    expect(readChargeCostOverride(store, scope, charge())).toBeNull()
    expect(store.rows.get(key)).toEqual(legacy)
    expect(writeChargeCostOverride(store, scope, charge(), 7, 'CNY', () => true)).toBe(true)
    expect((store.rows.get(key) as typeof legacy).rows).toHaveLength(2)
  })
  it('uses the same stored default tariff and fallback for all consumers', () => {
    const store = storage()
    expect(typeof chargeEstimatePrice).toBe('function')
    expect(chargeEstimatePrice(store, scope)).toEqual(price)
    store.rows.set(tariffKey(scope.userId, scope.origin), { currency: 'EUR', peak: 1, flat: 1, valley: 1, defaultPrice: 0 })
    expect(chargeEstimatePrice(store, scope)).toEqual({ amount: 0, currency: 'EUR', source: 'saved_default' })
  })
  it('keeps explicit unknown currency distinct from a missing currency', () => {
    const missing = overlayChargeCosts(storage(), scope, [charge()], price)[0]
    expect(missing.cost).toBeCloseTo(11.4)
    expect(overlayChargeCosts(storage(), scope, [charge({ currency: null, currencyCode: null })], price)[0].cost).toBeNull()
    expect(overlayChargeCosts(storage(), scope, [charge({ currency: 'TRY', currencyCode: 'TRY' })], price)[0].cost).toBeNull()
    expect(overlayChargeCosts(storage(), scope, [charge({ currency: 'BGN', currencyCode: 'BGN' })], price)[0].cost).toBeNull()
    expect(overlayChargeCosts(storage(), scope, [charge({ currency: 'ZZZ', currencyCode: 'ZZZ' })], price)[0].cost).toBeNull()
  })
})
