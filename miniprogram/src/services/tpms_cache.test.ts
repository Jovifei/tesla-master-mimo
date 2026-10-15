import { describe, expect, it } from 'vitest'
import { parseTpmsDetails, TPMS_RETENTION_MS } from '../domain/tpms'
import { CACHE_BUDGET_BYTES, cacheBytes } from './cache_budget'
import { clearTpmsCachesForAccount, observeTpmsCache, readTpmsCache, TPMS_CACHE_MAX_BYTES, tpmsCacheKey } from './tpms_cache'

const now = Date.parse('2026-10-15T02:00:00Z'), origin = 'https://api.example.test'
function store() {
  const data = new Map<string, unknown>()
  return { data, getStorageSync: (key: string) => data.get(key), setStorageSync: (key: string, value: unknown) => { data.set(key, JSON.parse(JSON.stringify(value))) },
    getStorageInfoSync: () => ({ keys: [...data.keys()] }), removeStorageSync: (key: string) => { data.delete(key) } }
}
function snapshot(at: number, pressure = 2.9) {
  return parseTpmsDetails({ pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
    ...Object.fromEntries(['fl', 'fr', 'rl', 'rr'].flatMap(wheel => [[`tpms_pressure_${wheel}`, pressure], [`tpms_pressure_${wheel}_observed_at`, new Date(at).toISOString()]])) }, now)
}
describe('bounded local foreground TPMS history', () => {
  it('rebuilds across storage reopen; deduplicates repeated receipts and sorts late observations', () => {
    const storage = store()
    observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now - 1000), now)
    observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now - 3000), now)
    const result = observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now - 1000, 0), now)
    expect(result.history.points).toHaveLength(8)
    expect(result.history.limited).toBe(false)
    const recreatedStorage = { getStorageSync: (key: string) => JSON.parse(JSON.stringify(storage.data.get(key) ?? null)) }
    const reopened = readTpmsCache(recreatedStorage, 'owner', origin, 'uid', now + 1000)
    expect(reopened.points).toEqual(result.history.points)
    expect(reopened.points.slice(-4).map(row => row.pressureBar)).toEqual([0, 0, 0, 0])
    expect(reopened.oldestAt).toBe(new Date(now - 3000).toISOString())
  })
  it('isolates account, origin, stable vehicle and source; exact account cleanup preserves dotted IDs', () => {
    const storage = store()
    observeTpmsCache(storage, 'a', origin, 'uid', snapshot(now), now)
    observeTpmsCache(storage, 'a.b', origin, 'uid', snapshot(now), now)
    expect(readTpmsCache(storage, 'b', origin, 'uid', now).points).toEqual([])
    expect(readTpmsCache(storage, 'a', 'https://other.test', 'uid', now).points).toEqual([])
    expect(readTpmsCache(storage, 'a', origin, 'other', now).points).toEqual([])
    const key = tpmsCacheKey('a', origin, 'uid')!
    expect(key).toContain('telemetry_mqtt')
    clearTpmsCachesForAccount(storage, 'a', origin)
    expect(readTpmsCache(storage, 'a', origin, 'uid', now).points).toEqual([])
    expect(readTpmsCache(storage, 'a.b', origin, 'uid', now).points).toHaveLength(4)
    expect(tpmsCacheKey('a', 'http://api.example.test', 'uid')).toBeNull()
  })
  it('enforces 30-day, 500-per-wheel and UTF-8 per-scope ceilings without new quota', () => {
    const storage = store()
    const key = tpmsCacheKey('owner', origin, 'uid')!
    const points = Array.from({ length: 650 }, (_, index) => ['fl', 'fr', 'rl', 'rr'].map(wheel => ({
      wheel, pressureBar: 2.9, observedAt: new Date(now - index * 1000).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' }))).flat()
    storage.data.set(key, { schema: 1, scope: key, points, limited: false })
    const result = observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now), now)
    expect(result.written).toBe(true)
    expect(result.history.limited).toBe(true)
    for (const wheel of ['fl', 'fr', 'rl', 'rr']) expect(result.history.points.filter(row => row.wheel === wheel).length).toBeLessThanOrEqual(500)
    expect(cacheBytes(storage.data.get(key))).toBeLessThanOrEqual(TPMS_CACHE_MAX_BYTES)
    expect(readTpmsCache(storage, 'owner', origin, 'uid', now + TPMS_RETENTION_MS + 1).points).toEqual([])
  })
  it('retains exactly the latest 500 for one busy wheel before the byte limit is reached', () => {
    const storage = store(), key = tpmsCacheKey('owner', origin, 'uid')!
    const points = Array.from({ length: 650 }, (_, index) => ({ wheel: 'fl', pressureBar: 2.9,
      observedAt: new Date(now - index * 1000).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' }))
    storage.data.set(key, { schema: 1, scope: key, points, limited: false })
    const result = observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now), now)
    const fl = result.history.points.filter(row => row.wheel === 'fl')
    expect(fl).toHaveLength(500)
    expect(fl[0].observedAt).toBe(new Date(now - 499_000).toISOString())
    expect(fl[499].observedAt).toBe(new Date(now).toISOString())
    expect(result.history.limited).toBe(true)
  })
  it('refuses writes when existing caches fill original shared budget; retains stored archive', () => {
    const storage = store()
    observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now - 1000), now)
    const before = readTpmsCache(storage, 'owner', origin, 'uid', now)
    storage.data.set('matelink.history.owner', 'x'.repeat(CACHE_BUDGET_BYTES))
    const result = observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now), now)
    expect(result).toMatchObject({ written: false, reason: 'cache_budget', history: before })
    expect(storage.data.get('matelink.history.owner')).toBe('x'.repeat(CACHE_BUDGET_BYTES))
  })
  it('rejects invalid reconstructed provenance, old/future receipts and absent enumeration without importing legacy histories', () => {
    const storage = store(), key = tpmsCacheKey('owner', origin, 'uid')!
    storage.data.set(key, { schema: 1, scope: key, points: [
      { wheel: 'fl', pressureBar: 2.9, observedAt: new Date(now).toISOString(), source: 'phone_poll', timeBasis: 'collector_received_at' },
      { wheel: 'fr', pressureBar: 2.9, observedAt: new Date(now + 1).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' },
    ] })
    storage.data.set('legacy-tpms', { pressureBar: 2.9 })
    expect(readTpmsCache(storage, 'owner', origin, 'uid', now).points).toEqual([])
    expect(observeTpmsCache({ ...storage, getStorageInfoSync: undefined }, 'owner', origin, 'uid', snapshot(now), now).written).toBe(false)
    expect(storage.data.has('legacy-tpms')).toBe(true)
  })
  it('preserves an ambiguous same-key old archive instead of overwriting it with a new receipt', () => {
    const storage = store(), key = tpmsCacheKey('owner', origin, 'uid')!, archived = { schema: 0, points: [{ pressure: 2.9, legacy: true }] }
    storage.data.set(key, archived)
    expect(observeTpmsCache(storage, 'owner', origin, 'uid', snapshot(now), now)).toMatchObject({ written: false, reason: 'ambiguous_archive' })
    expect(storage.data.get(key)).toEqual(archived)
  })
})
