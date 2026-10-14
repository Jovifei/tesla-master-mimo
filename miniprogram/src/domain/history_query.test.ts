import { describe, expect, it } from 'vitest'
import { emptyHistoryQuery, filterHistory, normalizeHistoryQuery, readHistoryQuery, writeHistoryQuery } from './history_query'

describe('persisted local calendar history query', () => {
  it('uses local calendar dates including a UTC boundary, and preserves zero distance', () => {
    const afterMidnight = new Date(2026, 9, 14, 0, 30).toISOString()
    const beforeMidnight = new Date(2026, 9, 13, 23, 30).toISOString()
    const rows = [{ id: 'today', startDate: afterMidnight, distanceKm: 0 }, { id: 'yesterday', startDate: beforeMidnight, distanceKm: 5 }]
    expect(filterHistory(rows, { startDate: '2026-10-14', endDate: '2026-10-14', minDistanceKm: 0 }).map(row => row.id)).toEqual(['today'])
    expect(filterHistory(rows, { ...emptyHistoryQuery(), minDistanceKm: 1 }).map(row => row.id)).toEqual(['yesterday'])
  })
  it('normalizes invalid dates/distances and swaps reversed civil boundaries', () => {
    expect(normalizeHistoryQuery({ startDate: '2026-02-30', endDate: 'invalid', minDistanceKm: Infinity })).toEqual(emptyHistoryQuery())
    expect(normalizeHistoryQuery({ startDate: '2026-10-20', endDate: '2026-10-14', minDistanceKm: 5 })).toEqual({ startDate: '2026-10-14', endDate: '2026-10-20', minDistanceKm: 5 })
  })
  it('restores only the selected account/origin/vehicle query and ignores quota errors', () => {
    const map = new Map<string, unknown>()
    const storage = { getStorageSync: (key: string) => map.get(key), setStorageSync: (key: string, value: unknown) => { map.set(key, value) } }
    const query = { startDate: '2026-10-14', endDate: '', minDistanceKm: 5 }
    expect(writeHistoryQuery(storage, 'origin:account:vehicle-a:drives', query)).toBe(true)
    expect(readHistoryQuery(storage, 'origin:account:vehicle-a:drives')).toEqual(query)
    expect(readHistoryQuery(storage, 'origin:account:vehicle-b:drives')).toEqual(emptyHistoryQuery())
    expect(writeHistoryQuery({ setStorageSync() { throw new Error('quota') } }, 'scope', query)).toBe(false)
  })
})
