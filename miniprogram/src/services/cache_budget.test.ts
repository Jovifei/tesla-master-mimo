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
