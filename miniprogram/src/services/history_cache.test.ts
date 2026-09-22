import { describe, expect, it } from 'vitest'
import { clearHistoryCachesForAccount, HISTORY_CACHE_SCHEMA, historyCacheKey, readHistoryCache, writeHistoryCache } from './history_cache'

function storage() {
  const values = new Map<string, unknown>()
  return {
    values,
    getStorageSync: (key: string) => values.get(key),
    setStorageSync: (key: string, value: unknown) => values.set(key, value),
    removeStorageSync: (key: string) => values.delete(key),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }),
  }
}

describe('history cache', () => {
  it('scopes cached rows by account and stable vehicle', () => {
    expect(historyCacheKey('drives', 'user-a', 'vehicle-a')).not.toBe(historyCacheKey('drives', 'user-b', 'vehicle-a'))
    expect(historyCacheKey('drives', 'user-a', 'vehicle-a')).not.toBe(historyCacheKey('charges', 'user-a', 'vehicle-a'))
  })

  it('round trips rows and treats malformed storage as empty', () => {
    const store = storage()
    const key = historyCacheKey('charges', 'user-a', 'vehicle-a', 'https://api.example.test')
    expect(writeHistoryCache(store, key, [{ id: 'charge-1' }])).toBe(true)
    expect(readHistoryCache<{ id: string }>(store, key)).toEqual([{ id: 'charge-1' }])
    const saved = store.values.get(key) as { schema: number; scope: string }
    expect(saved.schema).toBe(HISTORY_CACHE_SCHEMA)
    expect(saved.scope).toBe(key)
    store.setStorageSync(key, { items: 'not-an-array' })
    expect(readHistoryCache(store, key)).toEqual([])
  })

  it('isolates API origins while keeping a durable key across session generations', () => {
    const a = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api-a.example.test', 1)
    const b = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api-b.example.test', 1)
    const c = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api-a.example.test', 2)
    expect(a).toBe(c)
    expect(a).not.toBe(b)
  })

  it('reports a storage quota failure without throwing', () => {
    const failing = { setStorageSync: () => { throw new Error('quota') } }
    const key = historyCacheKey('drives', 'user-a', 'vehicle-a')
    expect(writeHistoryCache(failing, key, [{ id: 'drive-1' }])).toBe(false)
  })

  it('migrates strictly scoped v2 generation keys without deleting them', () => {
    const store = storage() as ReturnType<typeof storage> & { getStorageInfoSync: () => { keys: string[] } }
    store.getStorageInfoSync = () => ({ keys: [...store.values.keys()] })
    const key = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api.example.test')
    const legacyKey = key.replace('.v3.', '.v2.') + '.7'
    store.setStorageSync(legacyKey, { schema: 2, scope: legacyKey, items: [{ id: 'drive-1', sessionId: 's1' }], savedAt: 'now' })
    expect(readHistoryCache(store, key)).toEqual([{ id: 'drive-1', sessionId: 's1' }])
    expect(store.values.has(legacyKey)).toBe(true)
  })

  it('does not read or write anonymous history archives', () => {
    const store = storage()
    const key = historyCacheKey('drives', null, 'vehicle-a', 'https://api.example.test')
    expect(writeHistoryCache(store, key, [{ id: 'anonymous-drive' }])).toBe(false)
    expect(readHistoryCache<{ id: string }>(store, key)).toEqual([])
  })

  it('clears only the deleted account history on the current API origin', () => {
    const store = storage()
    const accountA = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api-a.example.test')
    const accountALegacy = historyCacheKey('charges', 'user-a', 'vehicle-a', 'https://api-b.example.test').replace('.v3.', '.v2.') + '.4'
    const accountB = historyCacheKey('drives', 'user-b', 'vehicle-a', 'https://api-a.example.test')
    store.setStorageSync(accountA, {})
    store.setStorageSync(accountALegacy, {})
    store.setStorageSync(accountB, {})

    clearHistoryCachesForAccount(store, 'user-a', 'https://api-a.example.test')

    expect(store.values.has(accountA)).toBe(false)
    expect(store.values.has(accountALegacy)).toBe(true)
    expect(store.values.has(accountB)).toBe(true)
  })

  it('preserves other accounts whose vehicle ID matches the deleted account and removes scoped v2 history', () => {
    const store = storage()
    const origin = 'https://api.example.test'
    const other = historyCacheKey('drives', 'user-b', 'user-a', origin)
    const longerAccount = historyCacheKey('drives', 'user-a.extra', 'vehicle', origin)
    const legacy = historyCacheKey('charges', 'user-a', 'vehicle', origin).replace('.v3.', '.v2.') + '.7'
    for (const key of [other, longerAccount, legacy]) store.setStorageSync(key, {})
    clearHistoryCachesForAccount(store, 'user-a', origin)
    expect(store.values.has(other)).toBe(true)
    expect(store.values.has(longerAccount)).toBe(true)
    expect(store.values.has(legacy)).toBe(false)
  })
})
