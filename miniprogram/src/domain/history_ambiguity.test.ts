import { describe, expect, it } from 'vitest'
import { aggregateDriveHistory, historyRowKey, isHistoryIdentityAmbiguous, isHistoryQuarantined, mergeHistoryByKey } from './history'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, type HistoryControllerState } from './history_controller'
import { historyCacheKey, inspectHistoryCache, readHistoryCache, writeHistoryCache } from '../services/history_cache'
import type { Drive, HistoryPage } from '../services/types'

const origin = 'https://api.example.test'
const drive = (overrides: Partial<Drive> = {}): Drive => ({ id: '1', sessionId: 'session-1', source: 'teslamate_archive',
  qualityState: 'observed', qualityReason: null, startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z',
  distanceKm: 10, durationMinutes: 60, energyConsumedKwh: null, startBatteryLevel: 80, endBatteryLevel: 78,
  startAddress: null, endAddress: null, startLatitude: null, startLongitude: null, endLatitude: null, endLongitude: null, ...overrides })
const identified = (overrides: Partial<Drive> = {}) => drive({ sourceInstanceId: 'instance-a', sourceVehicleId: 'car-a', sourceRecordId: 'record-1', ...overrides })
const page = (item: Drive): HistoryPage<Drive> => ({ items: [item], meta: { page: 1, show: 20, total: 1, totalPages: 1,
  availability: 'available', source: 'teslamate_archive', qualityState: 'observed', qualityReason: null, hasMore: false } })
function storage() {
  const values = new Map<string, unknown>()
  return { values, getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => values.set(key, structuredClone(value)),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }) }
}

describe('history identity ambiguity', () => {
  it('retains an older unbound cache row without double-counting after a qualified live row and offline reopen', () => {
    const old = drive(), fresh = identified(), store = storage(), key = historyCacheKey('drives', 'owner', 'vehicle', origin)
    const state: HistoryControllerState<Drive> = { items: [old], pages: [], page: 0 }
    const result = acceptHistoryPage(state, page(fresh), 1)
    expect(result.state.items).toHaveLength(2)
    const ambiguous = result.state.items.find(row => !row.sourceInstanceId)!
    expect(ambiguous).toMatchObject({ id: old.id, distanceKm: 10, identityAmbiguous: true })
    expect(typeof isHistoryIdentityAmbiguous).toBe('function')
    expect(isHistoryIdentityAmbiguous(ambiguous)).toBe(true)
    expect(isHistoryQuarantined(ambiguous)).toBe(true)
    expect(result).toMatchObject({ complete: false, hasMore: false, errorReason: 'ambiguous_history_identity' })
    expect(isHistoryQuarantined(result.state.items.find(row => row.sourceInstanceId === 'instance-a'))).toBe(false)
    expect(writeHistoryCache(store, key, result.state.items)).toBe(true)
    const reopened = readHistoryCache<Drive>(store, key)
    expect(reopened).toHaveLength(2)
    expect(aggregateDriveHistory(reopened).distance.value).toBe(10)
    expect(inspectHistoryCache(store, 'owner', 'vehicle', origin)).toMatchObject({ rows: 2, qualified: 1, quarantined: 1 })
  })
  it('retains and counts separately two proven different instances, while merging one proven session', () => {
    const a = identified(), b = identified({ sourceInstanceId: 'instance-b' })
    const distinct = mergeHistoryByKey([a], [b], historyRowKey).items
    expect(distinct).toHaveLength(2)
    expect(distinct.every(row => !isHistoryQuarantined(row))).toBe(true)
    expect(aggregateDriveHistory(distinct).distance.value).toBe(20)
    expect(mergeHistoryByKey([a], [{ ...a, id: 'new-public-id' }], historyRowKey).items).toHaveLength(1)
  })
  it('preserves the old scoped archive even if an online consumer writes only its new rows', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'vehicle', origin), old = drive()
    expect(writeHistoryCache(store, key, [old])).toBe(true)
    expect(writeHistoryCache(store, key, [identified()])).toBe(true)
    const reopened = readHistoryCache<Drive>(store, key)
    expect(reopened).toHaveLength(2)
    expect(isHistoryIdentityAmbiguous(reopened.find(row => !row.sourceInstanceId))).toBe(true)
    expect(aggregateDriveHistory(reopened).distance.value).toBe(10)
  })
  it('does not replace an unreadable or future-version archive with new rows', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'vehicle', origin)
    const archived = { schema: 999, scope: key, items: [drive()] }
    store.values.set(key, archived)
    expect(writeHistoryCache(store, key, [identified()])).toBe(false)
    expect(store.values.get(key)).toBe(archived)
  })
  it('terminates a fully paged ambiguous interval as partial without requesting an imaginary next page', async () => {
    let requests = 0
    const first = { ...page(identified()), items: [drive(), identified()] }
    const result = await collectHistoryInterval(first, async () => { requests++; throw new Error('unexpected page') }, () => true)
    expect(result).toMatchObject({ complete: false, receivedPages: 1, reason: 'ambiguous_history_identity' })
    expect(requests).toBe(0)
  })
  it('continues remaining real pages after ambiguity while reporting incomplete coverage', async () => {
    const first = { ...page(identified()), items: [drive(), identified()], meta: { ...page(identified()).meta, hasMore: true, totalPages: 2 } }
    const later = drive({ id: '2', sessionId: 'other' })
    const result = await collectHistoryInterval(first, async number => {
      expect(number).toBe(2)
      return { ...page(later), meta: { ...page(later).meta, page: 2, totalPages: 2 } }
    }, () => true)
    expect(result).toMatchObject({ complete: false, receivedPages: 2, reason: 'ambiguous_history_identity' })
    expect(result?.items).toHaveLength(3)
  })
  it('keeps an unresolved source ambiguity after a higher-quality update to the unbound row', () => {
    const legacy = drive({ qualityState: 'incomplete' }), fresh = identified()
    const first = mergeHistoryByKey([legacy], [fresh], historyRowKey).items
    expect(first.some(isHistoryIdentityAmbiguous)).toBe(true)
    const refreshed = mergeHistoryByKey(first, [{ ...legacy, qualityState: 'observed' }], historyRowKey).items
    expect(refreshed.filter(isHistoryIdentityAmbiguous)).toHaveLength(1)
    expect(aggregateDriveHistory(refreshed).distance.value).toBe(10)
  })
  it('does not discard the old ambiguous archive when adding the new row exceeds the bounded cache', () => {
    const store = storage(), key = historyCacheKey('drives', 'owner', 'vehicle', origin), old = drive()
    const others = Array.from({ length: 499 }, (_, i) => drive({ id: `other-${i}`, sessionId: `other-${i}`,
      startDate: '2026-10-10T00:00:00Z', endDate: '2026-10-10T01:00:00Z' }))
    expect(writeHistoryCache(store, key, [old, ...others])).toBe(true)
    const prior = structuredClone(store.values.get(key))
    const rows = mergeHistoryByKey([old, ...others], [identified({ startDate: '2026-10-20T00:00:00Z', endDate: '2026-10-20T01:00:00Z' })], historyRowKey).items
    expect(writeHistoryCache(store, key, rows)).toBe(false)
    expect(store.values.get(key)).toEqual(prior)
  })
  it('never attaches a new public id to a retained higher-quality but conflicting old window', () => {
    const old = identified(), incoming = identified({ id: '2', qualityState: 'incomplete', startDate: '2026-10-02T00:00:00Z', endDate: '2026-10-02T01:00:00Z', distanceKm: 99 })
    const merged = mergeHistoryByKey([old], [incoming], historyRowKey).items
    expect(merged).toHaveLength(1)
    expect(merged[0]).toMatchObject({ id: '1', startDate: old.startDate, distanceKm: 10, qualityState: 'observed' })
    expect(isHistoryIdentityAmbiguous(merged[0])).toBe(true)
    const state = { items: merged, pages: [], page: 0 }
    expect(acceptHistoryDetail(state, merged[0], { ...incoming, qualityState: 'observed' })).toBe(state)
  })
  it('releases a window-conflict hold once the same canonical session supplies a verified corrected window', () => {
    const old = identified(), weak = identified({ id: '2', qualityState: 'incomplete', startDate: '2026-10-02T00:00:00Z', endDate: '2026-10-02T01:00:00Z', distanceKm: 99 })
    const held = mergeHistoryByKey([old], [weak], historyRowKey).items
    expect(isHistoryIdentityAmbiguous(held[0])).toBe(true)
    const verified = mergeHistoryByKey(held, [{ ...weak, qualityState: 'observed' }], historyRowKey).items
    expect(verified).toHaveLength(1)
    expect(verified[0]).toMatchObject({ id: '2', startDate: weak.startDate, distanceKm: 99 })
    expect(isHistoryIdentityAmbiguous(verified[0])).toBe(false)
  })
})
