import { describe, expect, it } from 'vitest'
import { clearHistoryCachesForAccount, HISTORY_CACHE_MAX_BYTES, HISTORY_CACHE_MAX_ROWS, historyUtf8Bytes, HISTORY_CACHE_SCHEMA, historyCacheKey, readHistoryCache, writeHistoryCache, inspectHistoryCache } from './history_cache'

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
  it('round-trips exact reported charge mode separately from derived mode and keeps old missing mode unknown', () => {
    const store = storage(), key = historyCacheKey('charges', 'a', 'uid', 'https://api.example.test')
    writeHistoryCache(store, key, [{ id: '1', chargeMode: 'ac', reportedChargeMode: 'AC' }, { id: '2', chargeMode: 'ac' }])
    const rows = readHistoryCache<{ id: string; reportedChargeMode?: string }>(store, key)
    expect(rows.find(row => row.id === '1')?.reportedChargeMode).toBe('AC')
    expect(rows.find(row => row.id === '2')?.reportedChargeMode).toBeUndefined()
  })
  it('diagnostics inspect only matching current cache and never migrate or write a legacy archive', () => {
    const store = storage(), origin = 'https://api.example.test'
    const current = historyCacheKey('drives', 'a', 'uid', origin)
    const previous = current.replace('.v3.', '.v2.') + '.1'
    store.setStorageSync(previous, { schema: 2, scope: previous, items: [{ id: 'legacy', qualityState: 'observed' }] })
    expect(inspectHistoryCache(store, 'a', 'uid', origin).rows).toBe(0)
    expect(store.values.has(current)).toBe(false)
    writeHistoryCache(store, current, [{ id: 'real', qualityState: 'observed' }, { id: 'isolated', qualityState: 'quarantined' }, { qualityState: 'observed' }])
    const before = JSON.stringify([...store.values])
    expect(inspectHistoryCache(store, 'a', 'uid', origin)).toMatchObject({ rows: 2, qualified: 1, quarantined: 1 })
    expect(inspectHistoryCache(store, 'b', 'uid', origin).rows).toBe(0)
    expect(inspectHistoryCache(store, 'a', 'other', origin).rows).toBe(0)
    expect(inspectHistoryCache(store, 'a', 'uid', 'https://other.example.test').rows).toBe(0)
    expect(JSON.stringify([...store.values])).toBe(before)
    store.setStorageSync(current, { schema: 3, scope: previous, items: [{ id: 'wrong-scope' }] })
    expect(inspectHistoryCache(store, 'a', 'uid', origin).rows).toBe(0)
  })
  it('cannot read another account when dotted account and vehicle components collide', () => {
    const store = storage(), origin = 'https://api.example.test'
    const owner = historyCacheKey('drives', 'a.b', 'c', origin)
    const other = historyCacheKey('drives', 'a', 'b.c', origin)
    writeHistoryCache(store, owner, [{ id: 'owner-only' }])
    expect(readHistoryCache(store, other)).toEqual([])
    expect(owner).not.toBe(other)
  })
  it('preserves but does not read or delete old ambiguous separator keys', () => {
    const store = storage(), origin = 'https://api.example.test'
    const old = `matelink.history.v3.${encodeURIComponent(origin)}.a.b.c.drives`
    const value = { schema: 3, scope: old, items: [{ id: 'ambiguous-owner' }], savedAt: 'now' }
    store.setStorageSync(old, value)
    expect(readHistoryCache(store, historyCacheKey('drives', 'a.b', 'c', origin))).toEqual([])
    clearHistoryCachesForAccount(store, 'a.b', origin)
    expect(store.values.get(old)).toEqual(value)
  })
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

  it('does not guess a generation from a malformed legacy suffix', () => {
    const store = storage(), key = historyCacheKey('drives', 'a', 'uid', 'https://api.example.test')
    const legacyKey = key.replace('.v3.', '.v2.') + '.7.other'
    const value = { schema: 2, scope: legacyKey, items: [{ id: 'ambiguous' }], savedAt: 'now' }
    store.setStorageSync(legacyKey, value)
    expect(readHistoryCache(store, key)).toEqual([])
    expect(store.values.get(legacyKey)).toBe(value)
    expect(store.values.has(key)).toBe(false)
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

describe('bounded history cache payload', () => {
  it('removes upstream credentials, VIN and raw trajectory arrays on writes and old reads', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'uid', 'https://api.example.test')
    const item = { id: '1', source: 'telemetry_mqtt', vin: 'private-vin', access_token: 'private-token',
      energyReceipt: { refreshToken: 'private-refresh', session_key: 'private-session',
        drive_details: [{ date: '2026-10-14T00:00:00Z', power: 1, speed: 2 }],
        power_integral: { energyKwh: 0, complete: true } } }
    expect(writeHistoryCache(store, key, [item])).toBe(true)
    const serialized = JSON.stringify(store.values.get(key))
    expect(serialized).not.toMatch(/private-|drive_details/)
    expect(serialized).toContain('power_integral')
    const legacy = { schema: HISTORY_CACHE_SCHEMA, scope: key, savedAt: '2026-10-14T00:00:00Z', items: [item] }
    store.setStorageSync(key, legacy)
    expect(JSON.stringify(readHistoryCache(store, key))).not.toMatch(/private-|drive_details/)
    expect(store.values.get(key)).toBe(legacy)
  })
  it('omits precise normalized and legacy snake-case coordinates while retaining addresses and energy', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'uid', 'https://api.example.test')
    const record = { id: '1', startAddress: 'Genuine start address', endAddress: 'Genuine end address', energyConsumedKwh: 1.25,
      startLatitude: 31.123456, startLongitude: 121.123456, endLatitude: 31.654321, endLongitude: 121.654321,
      latitude: 30.123456, longitude: 120.123456, start_latitude: 31.123456, start_longitude: 121.123456,
      end_latitude: 31.654321, end_longitude: 121.654321,
      energyReceipt: { start_latitude: 31.123456, end_longitude: 121.654321, source: 'telemetry_mqtt',
        energy_contract: { net_energy: { value_kwh: 1.25, quality: 'reported' } } } }
    expect(writeHistoryCache(store, key, [record])).toBe(true)
    const saved = JSON.stringify(store.values.get(key))
    for (const coordinate of ['Latitude', 'Longitude', 'latitude', 'longitude', '31.123456', '121.654321']) expect(saved).not.toContain(coordinate)
    expect(saved).toContain('Genuine start address')
    expect(saved).toContain('energyConsumedKwh')
    expect(saved).toContain('value_kwh')
    const chargeKey = historyCacheKey('charges', 'owner', 'uid', 'https://api.example.test')
    writeHistoryCache(store, chargeKey, [{ id: 'c', latitude: 31.123456, longitude: 121.123456, address: 'Genuine charge site', energyAddedKwh: 5 }])
    expect(JSON.stringify(store.values.get(chargeKey))).not.toContain('latitude')
    expect(JSON.stringify(store.values.get(chargeKey))).toContain('Genuine charge site')
  })

  it('projects precise coordinates out of old v3 reads without deleting or rewriting the original cache', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'uid', 'https://api.example.test')
    const legacy = { schema: HISTORY_CACHE_SCHEMA, scope: key, savedAt: '2026-10-14T00:00:00Z', items: [
      { id: '1', startLatitude: 31.123456, startLongitude: 121.123456, end_latitude: 31.654321, end_longitude: 121.654321,
        startAddress: 'Legacy genuine address', energyConsumedKwh: 0 },
    ] }
    store.setStorageSync(key, legacy)
    const projected = JSON.stringify(readHistoryCache(store, key))
    expect(projected).not.toContain('Latitude')
    expect(projected).not.toContain('Longitude')
    expect(projected).not.toContain('latitude')
    expect(projected).not.toContain('longitude')
    expect(projected).toContain('Legacy genuine address')
    expect(projected).toContain('energyConsumedKwh')
    expect(store.values.get(key)).toBe(legacy)
  })

  it('retains 358 small rows but caps large archives at 500 most recent rows', () => {
    const store = storage(), key = historyCacheKey('drives', 'a', 'car', 'https://a.test')
    const rows = Array.from({ length: 600 }, (_, id) => ({ id: String(id), startDate: new Date(1700000000000 + id * 1000).toISOString() }))
    writeHistoryCache(store, key, rows.slice(0, 358))
    expect(readHistoryCache(store, key)).toHaveLength(358)
    writeHistoryCache(store, key, rows)
    const read = readHistoryCache<{ id: string }>(store, key)
    expect(read).toHaveLength(HISTORY_CACHE_MAX_ROWS)
    expect(read[0].id).toBe('599')
    expect(read[499].id).toBe('100')
  })
  it('counts actual UTF-8 bytes and strips whole trajectories from nested receipts', () => {
    const store = storage(), key = historyCacheKey('drives', 'a', 'car', 'https://a.test')
    const items = Array.from({ length: 500 }, (_, id) => ({ id: String(id), address: '汉😀'.repeat(600),
      positions: Array(1000).fill({ latitude: 1 }), energyReceipt: { drive_details: { positions: [1, 2], duration: 10 }, source: 'telemetry_mqtt' } }))
    expect(historyUtf8Bytes('汉😀')).toBe(7)
    expect(writeHistoryCache(store, key, items)).toBe(true)
    expect(historyUtf8Bytes(JSON.stringify(store.values.get(key)))).toBeLessThanOrEqual(HISTORY_CACHE_MAX_BYTES)
    const read = readHistoryCache<Record<string, unknown>>(store, key)
    expect(read.length).toBeGreaterThan(0)
    expect(read.length).toBeLessThan(500)
    expect(read[0]).not.toHaveProperty('positions')
    expect(read[0]).not.toHaveProperty('energyReceipt.drive_details.positions')
    expect(read[0]).toHaveProperty('energyReceipt.source', 'telemetry_mqtt')
  })
})

it('removes only this account scoped filter preferences together with deleted history', () => {
  const store = storage(), origin = 'https://a.test'
  const own = historyCacheKey('drives', 'a', 'car', origin) + '.query'
  const other = historyCacheKey('drives', 'b', 'car', origin) + '.query'
  store.setStorageSync(own, { startDate: '2026-10-14' }); store.setStorageSync(other, {})
  clearHistoryCachesForAccount(store, 'a', origin)
  expect(store.values.has(own)).toBe(false)
  expect(store.values.has(other)).toBe(true)
})
