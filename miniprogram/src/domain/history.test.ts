import { describe, expect, it } from 'vitest'
import { historyRowKey, historyScopeKey, mergeHistoryByKey, mergeHistoryPages, sortHistoryByStartDate } from './history'

describe('history merge', () => {
  it('retains local rows when the cloud page is empty', () => {
    const result = mergeHistoryByKey(
      [{ id: 'local-1', distance: null }],
      [],
      row => row.id,
    )
    expect(result.items).toEqual([{ id: 'local-1', distance: null }])
    expect(result.retainedLocalCount).toBe(1)
    expect(result.addedRemoteCount).toBe(0)
  })

  it('does not duplicate a stable row key', () => {
    const result = mergeHistoryByKey(
      [{ id: 'drive-1', distance: 10 }],
      [{ id: 'drive-1', distance: 99 }, { id: 'drive-2', distance: 20 }],
      row => row.id,
    )
    expect(result.items).toEqual([
      { id: 'drive-1', distance: 10 },
      { id: 'drive-2', distance: 20 },
    ])
  })

  it('merges the same telemetry session even when public ids differ and fills missing fields', () => {
    const result = mergeHistoryByKey(
      [{ id: 'drive-1', sessionId: 'session-1', startDate: '2026-09-13T01:00:00Z', startAddress: null, distanceKm: null }],
      [{ id: 'drive-99', sessionId: 'session-1', startDate: '2026-09-13T01:00:00Z', startAddress: 'Home', distanceKm: 12 }],
      historyRowKey,
    )
    expect(result.items).toEqual([{ id: 'drive-99', sessionId: 'session-1', startDate: '2026-09-13T01:00:00Z', startAddress: 'Home', distanceKm: 12 }])
    expect(result.addedRemoteCount).toBe(0)
  })

  it('does not downgrade observed evidence to a derived record', () => {
    const result = mergeHistoryByKey(
      [{ id: 'drive-1', sessionId: 'session-1', qualityState: 'observed', source: 'telemetry_mqtt', distanceKm: 20 }],
      [{ id: 'drive-1', sessionId: 'session-1', qualityState: 'derived', source: 'fleet_api', distanceKm: 99 }],
      historyRowKey,
    )
    expect(result.items[0]).toMatchObject({ qualityState: 'observed', source: 'telemetry_mqtt', distanceKm: 20 })
  })

  it('deduplicates rows across pages while retaining local history after an empty cloud page', () => {
    const result = mergeHistoryPages(
      [{ id: 'local-1', distance: null }],
      [
        { items: [{ id: 'remote-1', distance: 4 }], meta: { page: 1, show: 1, total: 2, totalPages: 2, availability: 'available', source: 'telemetry_mqtt', qualityState: null, qualityReason: null, hasMore: true } },
        { items: [{ id: 'remote-1', distance: 4 }, { id: 'remote-2', distance: 8 }], meta: { page: 2, show: 1, total: 2, totalPages: 2, availability: 'available', source: 'telemetry_mqtt', qualityState: null, qualityReason: null, hasMore: false } },
      ],
      row => row.id,
    )
    expect(result.items.map(row => row.id)).toEqual(['local-1', 'remote-1', 'remote-2'])
    expect(result.retainedLocalCount).toBe(1)
    expect(result.receivedPages).toBe(2)
    expect(result.complete).toBe(true)
    expect(mergeHistoryPages(result.items, [{ items: [], meta: { page: 1, show: 20, total: 0, totalPages: 0, availability: 'collecting', source: null, qualityState: null, qualityReason: null, hasMore: false } }], row => row.id).items).toHaveLength(3)
  })

  it('keeps account and vehicle scopes separate', () => {
    expect(historyScopeKey('user-a', 1)).not.toBe(historyScopeKey('user-b', 1))
    expect(historyScopeKey('user-a', 1)).not.toBe(historyScopeKey('user-a', 2))
  })

  it('keeps a valid intermediate prefix loadable before the final page arrives', () => {
    const pages = [1, 2].map(page => ({
      items: [{ id: `drive-${page}`, sessionId: `session-${page}`, startDate: `2026-09-${page.toString().padStart(2, '0')}T00:00:00Z` }],
      meta: { page, show: 20, total: 66, totalPages: 4, availability: 'available', source: 'telemetry_mqtt', qualityState: null, qualityReason: null, hasMore: true },
    }))
    const result = mergeHistoryPages([], pages, historyRowKey)
    expect(result.validPrefix).toBe(true)
    expect(result.complete).toBe(false)
    expect(result.hasMore).toBe(true)
    expect(result.errorReason).toBeNull()
  })

  it('blocks a page whose metadata contradicts the remaining total', () => {
    const result = mergeHistoryPages([], [{
      items: [{ id: 'drive-1', sessionId: 'session-1', startDate: '2026-09-01T00:00:00Z' }],
      meta: { page: 1, show: 20, total: 66, totalPages: 4, availability: 'available', source: 'telemetry_mqtt', qualityState: null, qualityReason: null, hasMore: false },
    }], historyRowKey)
    expect(result.validPrefix).toBe(false)
    expect(result.errorReason).toBe('total_pages_contradiction')
  })

  it('keeps the merged list newest first while retaining unknown dates', () => {
    const sorted = sortHistoryByStartDate([
      { id: 'old', startDate: '2026-09-01T00:00:00Z' },
      { id: 'unknown', startDate: null },
      { id: 'new', startDate: '2026-09-13T00:00:00Z' },
    ])
    expect(sorted.map(item => item.id)).toEqual(['new', 'old', 'unknown'])
  })
})
