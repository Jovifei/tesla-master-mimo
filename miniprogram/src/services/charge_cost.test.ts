import { expect, it } from 'vitest'
import type { Charge } from './types'
import { CACHE_BUDGET_BYTES } from './cache_budget'
import { chargeCostKey, clearChargeCostOverride, overlayChargeCosts, readChargeCostOverride, writeChargeCostOverride } from './charge_cost'
const scope = { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' }
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
