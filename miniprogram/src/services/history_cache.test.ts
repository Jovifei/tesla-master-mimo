import { describe, expect, it } from 'vitest'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from './history_cache'

function storage() {
  const values = new Map<string, unknown>()
  return {
    values,
    getStorageSync: (key: string) => values.get(key),
    setStorageSync: (key: string, value: unknown) => values.set(key, value),
    removeStorageSync: (key: string) => values.delete(key),
  }
}

describe('history cache', () => {
  it('scopes cached rows by account and stable vehicle', () => {
    expect(historyCacheKey('drives', 'user-a', 'vehicle-a')).not.toBe(historyCacheKey('drives', 'user-b', 'vehicle-a'))
    expect(historyCacheKey('drives', 'user-a', 'vehicle-a')).not.toBe(historyCacheKey('charges', 'user-a', 'vehicle-a'))
  })

  it('round trips rows and treats malformed storage as empty', () => {
    const store = storage()
    const key = historyCacheKey('charges', 'user-a', 'vehicle-a')
    writeHistoryCache(store, key, [{ id: 'charge-1' }])
    expect(readHistoryCache<{ id: string }>(store, key)).toEqual([{ id: 'charge-1' }])
    store.setStorageSync(key, { items: 'not-an-array' })
    expect(readHistoryCache(store, key)).toEqual([])
  })
})
