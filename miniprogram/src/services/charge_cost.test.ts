import { expect, it } from 'vitest'
import type { Charge } from './types'
import { CACHE_BUDGET_BYTES } from './cache_budget'
import { APP_DEFAULT_CHARGE_PRICE_CNY, chargeCostKey, clearChargeCostOverride, estimateAppDefaultChargeCost, overlayChargeCosts, readChargeCostOverride, writeChargeCostOverride } from './charge_cost'
const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' }
const appDefault = { amount: APP_DEFAULT_CHARGE_PRICE_CNY, currency: 'CNY', source: 'app_default' } as const
const charge = { id: '1', sessionId: 'canonical', source: 'telemetry_mqtt', qualityState: 'observed', startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', cost: 10, currency: 'EUR', currencyCode: 'EUR', energy: { quality: 'unknown' } } as Charge
function store() {
  const data = new Map<string, unknown>()
  return { data, getStorageSync: (key: string) => data.get(key), setStorageSync: (key: string, value: unknown) => { data.set(key, JSON.parse(JSON.stringify(value))) }, getStorageInfoSync: () => ({ keys: [...data.keys()] }), removeStorageSync: (key: string) => { data.delete(key) } }
}
it('never applies or replaces a manual charge amount across a corrected session window', () => {
  const storage = store(), old = { ...charge, sourceInstanceId: 'A', sourceVehicleId: 'car', sourceRecordId: 'rec', startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z' }
  const corrected = { ...old, startDate: '2026-10-02T00:00:00Z', endDate: '2026-10-02T01:00:00Z', cost: 20 }
  expect(writeChargeCostOverride(storage, scope, old, 5, 'CNY', () => true)).toBe(true)
  expect(readChargeCostOverride(storage, scope, corrected)).toBeNull()
  expect(overlayChargeCosts(storage, scope, [corrected])[0]).toMatchObject({ cost: 20, costProvenance: 'actual', reportedCost: 20 })
  expect(writeChargeCostOverride(storage, scope, corrected, 7, 'CNY', () => true)).toBe(true)
  expect(readChargeCostOverride(storage, scope, old)?.amount).toBe(5)
  expect(readChargeCostOverride(storage, scope, corrected)?.amount).toBe(7)
  expect((storage.data.get(chargeCostKey(scope)!) as { rows: unknown[] }).rows).toHaveLength(2)
  expect(clearChargeCostOverride(storage, scope, corrected, () => true)).toBe(true)
  expect(readChargeCostOverride(storage, scope, old)?.amount).toBe(5)
  expect(readChargeCostOverride(storage, scope, corrected)).toBeNull()
})
it('compares both endpoints as exact instants including fractional nanoseconds and time-zone offsets', () => {
  const storage = store(), old = { ...charge, startDate: '2026-10-01T00:00:00.123456789Z', endDate: '2026-10-01T01:00:00.987654321Z' }
  expect(writeChargeCostOverride(storage, scope, old, 0, 'CNY', () => true)).toBe(true)
  const same = { ...old, startDate: '2026-10-01T08:00:00.123456789+08:00', endDate: '2026-10-01T09:00:00.987654321+08:00' }
  expect(overlayChargeCosts(storage, scope, [same])[0]).toMatchObject({ cost: 0, costProvenance: 'manual' })
  expect(readChargeCostOverride(storage, scope, { ...same, startDate: '2026-10-01T08:00:00.123456780+08:00' })).toBeNull()
  expect(readChargeCostOverride(storage, scope, { ...same, endDate: '2026-10-01T09:00:00.987654320+08:00' })).toBeNull()
  expect(readChargeCostOverride(storage, scope, { ...same, endDate: null })).toBeNull()
})
it('retains legacy unbound amounts without attaching them to an unverified time window', () => {
  const storage = store(), key = chargeCostKey(scope)!, old = { schema: 1, scope: key,
    rows: [{ identity: 'session:canonical', source: charge.source, amount: 6, currency: 'EUR', updatedAt: '2026-10-01T00:00:00Z' }] }
  storage.data.set(key, structuredClone(old))
  const target = { ...charge, startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z' }
  expect(readChargeCostOverride(storage, scope, target)).toBeNull()
  expect(writeChargeCostOverride(storage, scope, target, 0, 'EUR', () => true)).toBe(true)
  const archive = storage.data.get(key) as typeof old
  expect(archive.rows).toHaveLength(2)
  expect(archive.rows).toContainEqual(old.rows[0])
  expect(readChargeCostOverride(storage, scope, target)?.amount).toBe(0)
  expect(clearChargeCostOverride(storage, scope, target, () => true)).toBe(true)
  expect(storage.data.get(key)).toEqual(old)
})
it('rejects new manual amounts when either end of the session window is absent or invalid', () => {
  const storage = store(), valid = { ...charge, startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z' }
  for (const patch of [{ startDate: null }, { endDate: null }, { startDate: 'bad' }, { endDate: '2026-09-30T23:00:00Z' }])
    expect(writeChargeCostOverride(storage, scope, { ...valid, ...patch }, 1, 'EUR', () => true)).toBe(false)
  expect(storage.data.size).toBe(0)
})
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
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency: undefined, currencyCode: undefined,
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
  expect(overlayChargeCosts(storage, scope, [legacy])[0]).toMatchObject({ cost: null, costProvenance: 'unknown', reportedCost: 0 })
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
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency: undefined, currencyCode: undefined,
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

it('honors the upstream bill and never guesses free from current vehicle-level supercharging settings', () => {
  const dcWithBill = { ...charge, chargeMode: 'dc', cost: 10 } as Charge, ac = { ...charge, chargeMode: 'ac', cost: 10 } as Charge
  const billed = overlayChargeCosts(store(), scope, [dcWithBill], appDefault, true)
  expect(billed[0]).toMatchObject({ cost: 10, costProvenance: 'actual' })
  expect(billed[0].reportedCost).toBe(10)

  const dcWithoutBill = { ...charge, chargeMode: 'dc', cost: null } as Charge
  const unbilled = overlayChargeCosts(store(), scope, [dcWithoutBill], appDefault, true)
  expect(unbilled[0]).toMatchObject({ cost: null, costProvenance: 'unknown' })

  const manualWins = store()
  expect(writeChargeCostOverride(manualWins, scope, dcWithBill, 5, 'CNY', () => true)).toBe(true)
  expect(overlayChargeCosts(manualWins, scope, [dcWithBill], appDefault, true)[0]).toMatchObject({ cost: 5, costProvenance: 'manual' })
  expect(overlayChargeCosts(store(), scope, [ac], appDefault, true)[0]).toMatchObject({ cost: 10, costProvenance: 'actual' })
  const noFlag = overlayChargeCosts(store(), scope, [dcWithBill], appDefault, null)
  expect(noFlag[0]).toMatchObject({ cost: 10, costProvenance: 'actual' })
  const estimated = overlayChargeCosts(store(), scope, [{ ...charge, chargeMode: 'dc', cost: 0 }], appDefault, true)
  expect(estimated[0]).toMatchObject({ cost: 0, costProvenance: 'actual' })
})

it('refuses to estimate an unlisted ISO currency or currency conflict into CNY', () => {
  const tryCharge = { ...charge, currency: 'TRY', currencyCode: 'TRY', cost: null } as Charge
  expect(estimateAppDefaultChargeCost(tryCharge, 1.14, 'CNY')).toBeNull()

  const conflictCharge = { ...charge, currency: null, currencyCode: null, cost: null } as Charge
  expect(estimateAppDefaultChargeCost(conflictCharge, 1.14, 'CNY')).toBeNull()
})
