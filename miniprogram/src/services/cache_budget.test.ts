import { describe, expect, it } from 'vitest'
import { canStoreCache, CACHE_BUDGET_BYTES } from './cache_budget'

function storage() {
  const values = new Map<string, unknown>()
  return { values, getStorageSync: (key: string) => values.get(key), getStorageInfoSync: () => ({ keys: [...values.keys()] }) }
}
describe('shared cache admission without data deletion', () => {
  it('counts UTF8 values, replacements and old caches without raising the existing budget', () => {
    const s = storage()
    s.values.set('matelink.history.v3.old', 'x'.repeat(CACHE_BUDGET_BYTES - 200))
    expect(canStoreCache(s, 'matelink.tpms.v1.new', { value: '中'.repeat(80) })).toBe(false)
    expect(canStoreCache(s, 'matelink.history.v3.old', { value: 1 })).toBe(true)
    expect(s.values.size).toBe(1)
  })
  it('fails closed when storage cannot be enumerated or read', () => {
    expect(canStoreCache({}, 'matelink.tpms.v1.x', {})).toBe(false)
    expect(canStoreCache({ getStorageInfoSync: () => { throw Error('quota') } }, 'matelink.events.v1.x', {})).toBe(false)
  })
})


it('reserves capacity for a real manual fee while refusing cache growth without deleting anything', async () => {
  const { USER_DATA_RESERVE_BYTES, localStorageUsage } = await import('./cache_budget')
  const { writeChargeCostOverride, readChargeCostOverride } = await import('./charge_cost')
  const values = new Map<string, unknown>()
  const s = { values, getStorageSync: (key: string) => values.get(key),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }),
    setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: (key: string) => { values.delete(key) } }
  const key = 'matelink.history.v3.synthetic', original = 'x'.repeat(CACHE_BUDGET_BYTES - USER_DATA_RESERVE_BYTES - 100)
  values.set(key, original)
  expect(canStoreCache(s, 'matelink.history.v3.next', { data: 'x'.repeat(500) })).toBe(false)
  const scope = { userId: 'owner', origin: 'https://example.test', stableId: 'v1' }
  const charge = { id: '1', source: 'teslamate_archive', qualityState: 'observed',
    startDate: '2026-01-01T00:00:00Z', endDate: '2026-01-01T01:00:00Z' } as import('./types').Charge
  expect(writeChargeCostOverride(s, scope, charge, 0, 'CNY', () => true)).toBe(true)
  expect(readChargeCostOverride(s, scope, charge)?.amount).toBe(0)
  expect(values.get(key)).toBe(original)
  const usage = localStorageUsage(s)!
  expect(usage.userBytes).toBeGreaterThan(0)
  expect(usage.totalBytes).toBeLessThan(CACHE_BUDGET_BYTES)
  expect(Object.keys(usage).sort()).toEqual(['budgetBytes', 'cacheBytes', 'reservedUserBytes', 'totalBytes', 'userBytes'])
})

it('retains a legacy over-budget archive and permits a same-size correction but no growth', () => {
  const s = storage(), key = 'matelink.history.v3.old', value = 'x'.repeat(CACHE_BUDGET_BYTES + 1)
  s.values.set(key, value)
  expect(canStoreCache(s, key, value.replace('x', 'y'))).toBe(true)
  expect(canStoreCache(s, key, value + 'x')).toBe(false)
  expect(s.values.get(key)).toBe(value)
})


it('allows real non-growing manual fee corrections over an inherited budget without deleting history', async () => {
  const { chargeCostKey, writeChargeCostOverride, readChargeCostOverride, clearChargeCostOverride } = await import('./charge_cost')
  const values = new Map<string, unknown>()
  const s = { getStorageSync: (key: string) => values.get(key),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }),
    setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: (key: string) => { values.delete(key) } }
  const scope = { userId: 'owner', origin: 'https://example.test', stableId: 'v1' }
  const charge = { id: 'retained', source: 'teslamate_archive', qualityState: 'observed',
    startDate: '2026-01-01T00:00:00Z', endDate: '2026-01-01T01:00:00Z' } as import('./types').Charge
  const now = Date.parse('2026-01-02T00:00:00Z')
  expect(writeChargeCostOverride(s, scope, charge, 9, 'CNY', () => true, now)).toBe(true)
  const archiveKey = 'matelink.history.v3.older-owner'
  const archive = 'x'.repeat(CACHE_BUDGET_BYTES + 200)
  values.set(archiveKey, archive)
  const keys = [...values.keys()]
  // A confirmed zero uses exactly the same bytes, is not missing, and survives reopening.
  expect(writeChargeCostOverride(s, scope, charge, 0, 'CNY', () => true, now + 1000)).toBe(true)
  expect(readChargeCostOverride(s, scope, charge)?.amount).toBe(0)
  expect(values.get(archiveKey)).toBe(archive)
  expect([...values.keys()]).toEqual(keys)
  const beforeGrowth = JSON.stringify([...values.entries()])
  expect(writeChargeCostOverride(s, scope, charge, 100, 'CNY', () => true, now + 2000)).toBe(false)
  expect(writeChargeCostOverride(s, scope, { ...charge, id: 'new' }, 0, 'CNY', () => true, now + 2000)).toBe(false)
  expect(writeChargeCostOverride(s, { ...scope, userId: 'other' }, charge, 0, 'CNY', () => true, now + 2000)).toBe(false)
  expect(writeChargeCostOverride(s, scope, charge, 5, 'CNY', () => false, now + 2000)).toBe(false)
  expect(JSON.stringify([...values.entries()])).toBe(beforeGrowth)
  expect(clearChargeCostOverride(s, scope, charge, () => true)).toBe(true)
  expect(readChargeCostOverride(s, scope, charge)).toBeNull()
  expect(values.has(chargeCostKey(scope)!)).toBe(true)
  expect(values.get(archiveKey)).toBe(archive)
  expect([...values.keys()]).toEqual(keys)
})
