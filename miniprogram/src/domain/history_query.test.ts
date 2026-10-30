import { describe, expect, it } from 'vitest'
import {
  defaultChargeHistoryQuery,
  defaultDriveHistoryQuery,
  emptyHistoryQuery,
  filterHistory,
  historyDateTime,
  historyDetailDateTime,
  historyEditorialDate,
  normalizeHistoryQuery,
  readHistoryQuery,
  writeHistoryQuery,
} from './history_query'

describe('persisted local calendar history query', () => {
  it('formats charge and drive card dates like the App without changing stored instants', () => {
    const instant = new Date(2026, 9, 18, 19, 3).toISOString()
    expect(historyEditorialDate(instant, false)).toBe('周日 · 10月18日 · 07:03 下午')
    expect(historyEditorialDate(instant, true)).toBe('周日 · 10月18日 · 19:03')
    expect(historyEditorialDate(null)).toBe('暂无日期')
  })
  it('formats detail timestamps compactly like the App', () => {
    expect(historyDetailDateTime('2026-10-27T18:54:00+08:00')).toBe('10-27 18:54')
    expect(historyDetailDateTime(null)).toBe('暂无日期')
  })
  it('excludes quarantined drive and charge records without hiding legitimate zero or missing-energy summaries', () => {
    const rows = [
      { id: 'observed-zero', qualityState: 'observed', startDate: '2026-10-14T00:00:00Z', distanceKm: 0 },
      { id: 'missing-energy', qualityState: 'incomplete', startDate: '2026-10-14T00:00:00Z', distanceKm: 1 },
      { id: 'isolated', qualityState: 'quarantined', startDate: '2026-10-14T00:00:00Z', distanceKm: 2 },
      { id: 'case-isolated', qualityState: ' Quarantined ', startDate: '2026-10-14T00:00:00Z', distanceKm: 2 },
    ]
    expect(filterHistory(rows, emptyHistoryQuery()).map(row => row.id)).toEqual(['observed-zero', 'missing-energy'])
  })

  it('uses local calendar dates including a UTC boundary, and preserves zero distance', () => {
    const afterMidnight = new Date(2026, 9, 14, 0, 30).toISOString()
    const beforeMidnight = new Date(2026, 9, 13, 23, 30).toISOString()
    const rows = [{ id: 'today', startDate: afterMidnight, distanceKm: 0 }, { id: 'yesterday', startDate: beforeMidnight, distanceKm: 5 }]
    expect(filterHistory(rows, { startDate: '2026-10-14', endDate: '2026-10-14', minDistanceKm: 0 }).map(row => row.id)).toEqual(['today'])
    expect(filterHistory(rows, { ...emptyHistoryQuery(), minDistanceKm: 1 }).map(row => row.id)).toEqual(['yesterday'])
  })
  it('keeps equivalent explicit-offset observations together across month/year midnight boundaries', () => {
    for (const date of [new Date(2027, 0, 1, 0, 30), new Date(2026, 10, 1, 0, 15)]) {
      const iso = date.toISOString()
      const offset = new Date(date.getTime() + 8 * 3600000).toISOString().replace('Z', '+08:00')
      const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
      expect(historyDateTime(iso)).toBe(historyDateTime(offset))
      expect(filterHistory([{ startDate: iso }, { startDate: offset }], { ...emptyHistoryQuery(), startDate: day, endDate: day })).toHaveLength(2)
      const yesterday = new Date(date.getFullYear(), date.getMonth(), date.getDate() - 1, 23, 59).toISOString()
      expect(filterHistory([{ startDate: yesterday }], { ...emptyHistoryQuery(), startDate: day, endDate: day })).toHaveLength(0)
    }
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
  it('matches the App actual distance category boundaries without assigning missing distance', () => {
    const rows = [null, 0, 9.99, 10, 99.99, 100].map((distanceKm, id) => ({ id, distanceKm }))
    expect(filterHistory(rows, { ...emptyHistoryQuery(), maxDistanceKm: 10 }).map(row => row.id)).toEqual([1, 2])
    expect(filterHistory(rows, { ...emptyHistoryQuery(), minDistanceKm: 10, maxDistanceKm: 100 }).map(row => row.id)).toEqual([3, 4])
    expect(filterHistory(rows, { ...emptyHistoryQuery(), minDistanceKm: 100 }).map(row => row.id)).toEqual([5])
    expect(normalizeHistoryQuery({ ...emptyHistoryQuery(), minDistanceKm: 10, maxDistanceKm: 100 })).toEqual({ ...emptyHistoryQuery(), minDistanceKm: 10, maxDistanceKm: 100 })
  })

  it('generates Android-parity default query windows (last 7 days for drives, last 30 days for charges)', () => {
    const fixedNow = new Date(2026, 9, 30, 15, 0, 0) // 2026-10-30
    const driveQuery = defaultDriveHistoryQuery(fixedNow)
    expect(driveQuery).toEqual({
      startDate: '2026-10-24', // 7 days inclusive: 24, 25, 26, 27, 28, 29, 30
      endDate: '2026-10-30',
      minDistanceKm: 0,
    })

    const chargeQuery = defaultChargeHistoryQuery(fixedNow)
    expect(chargeQuery).toEqual({
      startDate: '2026-10-01', // 30 days inclusive: 30 - 29 = 1
      endDate: '2026-10-30',
      minDistanceKm: 0,
    })
  })

  it('restores custom fallback query when storage is empty', () => {
    const map = new Map<string, unknown>()
    const storage = { getStorageSync: (key: string) => map.get(key) }
    const fallback = { startDate: '2026-10-24', endDate: '2026-10-30', minDistanceKm: 0 }
    expect(readHistoryQuery(storage, 'empty-scope', fallback)).toEqual(fallback)
  })
})
