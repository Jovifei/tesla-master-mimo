import { expect, it } from 'vitest'
import type { Charge } from './types'
import { CACHE_BUDGET_BYTES } from './cache_budget'
import { APP_DEFAULT_CHARGE_PRICE_CNY, chargeCostKey, clearChargeCostOverride, estimateAppDefaultChargeCost, overlayChargeCosts, readChargeCostOverride, writeChargeCostOverride } from './charge_cost'
const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' }
const appDefault = { amount: APP_DEFAULT_CHARGE_PRICE_CNY, currency: 'CNY', source: 'app_default' } as const
const charge = { id: '1', sessionId: 'canonical', source: 'telemetry_mqtt', qualityState: 'observed', cost: 10, currency: 'EUR', currencyCode: 'EUR', energy: { quality: 'unknown' } } as Charge
function store() {
  const data = new Map<string, unknown>()
  return { data, getStorageSync: (key: string) => data.get(key), setStorageSync: (key: string, value: unknown) => { data.set(key, JSON.parse(JSON.stringify(value))) }, getStorageInfoSync: () => ({ keys: [...data.keys()] }), removeStorageSync: (key: string) => { data.delete(key) } }
}
it('persists genuine manual zero with explicit currency/provenance without mutating server bill or energy', () => {
  const storage = store(); expect(writeChargeCostOverride(storage, scope, charge, 0, 'USD', () => true)).toBe(true)
  const reopened = { getStorageSync: (key: string) => JSON.parse(JSON.stringify(storage.data.get(key) ?? null)) }
  const result = overlayChargeCosts(reopened, scope, [charge])[0]
  expect(result).toMatchObject({ cost: 0, currencyCode: 'USD', costProvenance: 'manual', reportedCost: 10, reportedCurrency: 'EUR' })
  expect(result.energy).toBe(charge.energy); expect(charge.cost).toBe(10); expect(result.source).toBe(charge.source)
  expect(clearChargeCostOverride(storage, scope, charge, () => true)).toBe(true)
  expect(overlayChargeCosts(storage, scope, [charge])[0]).toMatchObject({ cost: 10, costProvenance: 'actual' })
})
it('isolates origin/account/vehicle/source/session while following a corrected public ID for same canonical session', () => {
  const storage = store(); writeChargeCostOverride(storage, scope, charge, 5, 'EUR', () => true)
  expect(readChargeCostOverride(storage, scope, { ...charge, id: 'new-public-id' })?.amount).toBe(5)
  for (const different of [{ ...scope, userId: 'other' }, { ...scope, origin: 'https://other.test' }, { ...scope, stableId: 'other' }]) expect(readChargeCostOverride(storage, different, charge)).toBeNull()
  for (const different of [{ ...charge, sessionId: 'other' }, { ...charge, source: 'teslamate_archive' }, { ...charge, source: null }, { ...charge, qualityState: 'quarantined' }]) expect(readChargeCostOverride(storage, scope, different)).toBeNull()
})
it('rejects stale page/session saves, invalid amount/currency and missing source', () => {
  const storage = store()
  for (const amount of [-1, NaN, Infinity]) expect(writeChargeCostOverride(storage, scope, charge, amount, 'EUR', () => true)).toBe(false)
  expect(writeChargeCostOverride(storage, scope, charge, 1, '', () => true)).toBe(false)
  expect(writeChargeCostOverride(storage, scope, charge, 1, 'EUR', () => false)).toBe(false)
  expect(writeChargeCostOverride(storage, scope, { ...charge, source: null }, 1, 'EUR', () => true)).toBe(false)
  let calls = 0
  expect(writeChargeCostOverride(storage, scope, charge, 1, 'EUR', () => ++calls < 3)).toBe(false)
  expect(storage.data.size).toBe(0)
})
it('keeps unknown or actual zero when no manual override is present', () => {
  const result = overlayChargeCosts(store(), scope, [{ ...charge, cost: null, currency: null, currencyCode: null }, { ...charge, cost: 0 }])
  expect(result[0]).toMatchObject({ cost: null, costProvenance: 'unknown' }); expect(result[1]).toMatchObject({ cost: 0, costProvenance: 'actual' })
})
it('matches the App default estimate from qualified battery energy while preserving the original missing bill', () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency: null, currencyCode: null,
    energy: { batteryInputKwh: 24.0175, quality: 'reported' } } as Charge
  const storage = store()
  expect(APP_DEFAULT_CHARGE_PRICE_CNY).toBe(1.14)
  expect(estimateAppDefaultChargeCost(legacy)?.toFixed(2)).toBe('27.38')
  const result = overlayChargeCosts(storage, scope, [legacy], appDefault)[0]
  expect(result).toMatchObject({ costProvenance: 'estimate', cost: 24.0175 * 1.14, currencyCode: 'CNY', reportedCost: null })
  expect(legacy.cost).toBeNull(); expect(storage.data.size).toBe(0)
  expect(overlayChargeCosts(storage, scope, [{ ...legacy, cost: 0 }], appDefault)[0]).toMatchObject({ cost: 24.0175 * 1.14, costProvenance: 'estimate', reportedCost: 0 })
  expect(writeChargeCostOverride(storage, scope, legacy, 0, 'USD', () => true)).toBe(true)
  expect(overlayChargeCosts(storage, scope, [legacy], appDefault)[0]).toMatchObject({ cost: 0, costProvenance: 'manual', currencyCode: 'USD', reportedCost: null })
})
it('treats TeslaMate raw zero as unfilled while preserving manual zero, positive bills and genuine zero from another source', () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: 0, currency: 'CNY', currencyCode: 'CNY',
    energy: { batteryInputKwh: 10, quality: 'reported' } } as Charge
  const storage = store()
  const estimated = overlayChargeCosts(storage, scope, [legacy], appDefault)[0]
  expect(estimated).toMatchObject({ costProvenance: 'estimate', reportedCost: 0 })
  expect(estimated.cost).toBeCloseTo(11.4, 10)
  expect(overlayChargeCosts(storage, scope, [{ ...legacy, energy: undefined }], appDefault)[0]).toMatchObject({ cost: null, costProvenance: 'unknown', reportedCost: 0 })
  expect(overlayChargeCosts(storage, scope, [{ ...legacy, cost: 7 }], appDefault)[0]).toMatchObject({ cost: 7, costProvenance: 'actual', reportedCost: 7 })
  expect(overlayChargeCosts(storage, scope, [{ ...legacy, source: 'telemetry_mqtt' }], appDefault)[0]).toMatchObject({ cost: 0, costProvenance: 'actual', reportedCost: 0 })
  expect(overlayChargeCosts(storage, scope, [legacy])[0]).toMatchObject({ cost: 0, costProvenance: 'actual', reportedCost: 0 })
  expect(writeChargeCostOverride(storage, scope, legacy, 0, 'CNY', () => true)).toBe(true)
  expect(overlayChargeCosts(storage, scope, [legacy], appDefault)[0]).toMatchObject({ cost: 0, costProvenance: 'manual', reportedCost: 0 })
})
it('withholds a default CNY estimate for foreign currency, unqualified telemetry and quarantined rows', () => {
  const candidate = { ...charge, source: 'teslamate_archive', cost: null, currency: null, currencyCode: null,
    energy: { batteryInputKwh: 10, quality: 'reported' } } as Charge
  expect(estimateAppDefaultChargeCost({ ...candidate, currencyCode: 'EUR' })).toBeNull()
  expect(estimateAppDefaultChargeCost({ ...candidate, source: 'telemetry_mqtt' })).toBeNull()
  expect(estimateAppDefaultChargeCost({ ...candidate, qualityState: 'quarantined' })).toBeNull()
  expect(estimateAppDefaultChargeCost({ ...candidate, energy: undefined })).toBeNull()
})
it('respects an explicit zero or foreign-currency saved default without using peak/flat/valley rates', () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency: null, currencyCode: null,
    energy: { batteryInputKwh: 10, quality: 'reported' } } as Charge
  const storage = store()
  const freeEstimate = overlayChargeCosts(storage, scope, [legacy], { amount: 0, currency: 'CNY', source: 'saved_default' })[0]
  expect(freeEstimate).toMatchObject({ cost: 0, currencyCode: 'CNY', costProvenance: 'estimate', estimatedUnitPrice: 0, estimatePriceSource: 'saved_default', reportedCost: null })
  const foreign = overlayChargeCosts(storage, scope, [legacy], { amount: 2, currency: 'EUR', source: 'saved_default' })[0]
  expect(foreign).toMatchObject({ cost: 20, currencyCode: 'EUR', costProvenance: 'estimate', estimatedUnitPrice: 2, reportedCost: null })
  expect(overlayChargeCosts(storage, scope, [{ ...legacy, currencyCode: 'USD' }], { amount: 2, currency: 'EUR', source: 'saved_default' })[0].cost).toBeNull()
  expect(storage.data.size).toBe(0)
})
it('refuses overflow of row/shared budgets and preserves existing overrides', () => {
  const storage = store()
  for (let i = 0; i < 200; i++) expect(writeChargeCostOverride(storage, scope, { ...charge, sessionId: `session-${i}` }, i, 'EUR', () => true)).toBe(true)
  expect(writeChargeCostOverride(storage, scope, { ...charge, sessionId: 'extra' }, 1, 'EUR', () => true)).toBe(false)
  expect(readChargeCostOverride(storage, scope, { ...charge, sessionId: 'session-0' })?.amount).toBe(0)
  storage.data.set('matelink.history.protected', 'x'.repeat(CACHE_BUDGET_BYTES))
  expect(writeChargeCostOverride(storage, scope, charge, 10, 'EUR', () => true)).toBe(false)
})
it('retains ambiguous/invalid same-key archives on attempted save or clear without importing legacy history', () => {
  const storage = store(), key = chargeCostKey(scope)!, archive = { schema: 0, rows: [{ old: 'owner-data' }] }
  storage.data.set(key, archive)
  expect(writeChargeCostOverride(storage, scope, charge, 1, 'EUR', () => true)).toBe(false)
  expect(clearChargeCostOverride(storage, scope, charge, () => true)).toBe(false)
  expect(storage.data.get(key)).toEqual(archive)
})
it('enforces serialized64KiB before the200row ceiling and retains the earliest saved manual zero', () => {
  const storage = store(), original = { ...charge, sessionId: 'original' }
  expect(writeChargeCostOverride(storage, scope, original, 0, 'EUR', () => true)).toBe(true)
  let stored = 1, refused = false
  for (let index = 0; index < 199; index++) {
    const accepted = writeChargeCostOverride(storage, scope, { ...charge, source: 's'.repeat(128), sessionId: `${index}-${'x'.repeat(240)}` }, 1, 'EUR', () => true)
    if (!accepted) { refused = true; break }
    stored++
  }
  expect(refused).toBe(true); expect(stored).toBeLessThan(200)
  expect(readChargeCostOverride(storage, scope, original)?.amount).toBe(0)
})
