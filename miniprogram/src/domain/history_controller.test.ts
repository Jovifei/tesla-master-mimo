import { describe, expect, it } from 'vitest'
import { acceptHistoryDetail, acceptHistoryPage, emptyHistoryControllerState, type HistoryControllerState } from './history_controller'
import type { Charge, Drive, HistoryPage } from '../services/types'

const row = (id: number): Drive => ({
  id: `drive-${id}`, sessionId: `session-${id}`, startDate: `2026-09-14T${String(Math.floor(id / 60)).padStart(2, '0')}:${String(id % 60).padStart(2, '0')}:00Z`,
  endDate: null, startAddress: null, endAddress: null, durationMinutes: null, distanceKm: null,
  energyConsumedKwh: null, startBatteryLevel: null, endBatteryLevel: null, startLatitude: null,
  startLongitude: null, endLatitude: null, endLongitude: null, source: 'telemetry_mqtt', qualityState: 'observed', qualityReason: null,
})

const chargeRow = (id: number): Charge => ({
  id: `charge-${id}`, sessionId: `charge-session-${id}`, startDate: `2026-09-14T${String(Math.floor(id / 60)).padStart(2, '0')}:${String(id % 60).padStart(2, '0')}:00Z`,
  endDate: null, address: null, durationMinutes: null, energyAddedKwh: null, energyUsedKwh: null,
  cost: null, startBatteryLevel: null, endBatteryLevel: null, latitude: null, longitude: null,
  chargerPowerKw: null, source: 'telemetry_mqtt', qualityState: 'observed', qualityReason: null,
})

function page<T extends Drive | Charge>(pageNumber: number, items: T[], hasMore: boolean, totalPages = 4): HistoryPage<T> {
  return {
    items,
    meta: { page: pageNumber, show: 20, total: 66, totalPages, availability: 'available', source: 'telemetry_mqtt', qualityState: 'observed', qualityReason: null, hasMore },
  }
}

describe('history page controller', () => {
  it('accepts four pages of 66 rows and keeps the intermediate prefix loadable', () => {
    let state: HistoryControllerState<Drive> = emptyHistoryControllerState()
    const batches = [Array.from({ length: 20 }, (_, i) => row(i)), Array.from({ length: 20 }, (_, i) => row(i + 20)), Array.from({ length: 20 }, (_, i) => row(i + 40)), Array.from({ length: 6 }, (_, i) => row(i + 60))]
    batches.forEach((items, index) => {
      const decision = acceptHistoryPage(state, page(index + 1, items, index < 3), index + 1)
      expect(decision.accepted).toBe(true)
      state = decision.state
      if (index < 3) expect(decision.hasMore).toBe(true)
    })
    expect(state.items).toHaveLength(66)
    expect(state.pages).toHaveLength(4)
    expect(state.page).toBe(4)
    expect(acceptHistoryPage(state, page(4, [], false), 5).accepted).toBe(false)
  })

  it('applies the same four-page contract to charge history', () => {
    let state: HistoryControllerState<Charge> = emptyHistoryControllerState()
    const batches = [Array.from({ length: 20 }, (_, i) => chargeRow(i)), Array.from({ length: 20 }, (_, i) => chargeRow(i + 20)), Array.from({ length: 20 }, (_, i) => chargeRow(i + 40)), Array.from({ length: 6 }, (_, i) => chargeRow(i + 60))]
    batches.forEach((items, index) => {
      const decision = acceptHistoryPage(state, page(index + 1, items, index < 3), index + 1)
      expect(decision.accepted).toBe(true)
      state = decision.state
    })
    expect(state.items).toHaveLength(66)
    expect(state.page).toBe(4)
  })

  it('does not advance after a failed third-page response and accepts a retry', () => {
    let state: HistoryControllerState<Drive> = emptyHistoryControllerState()
    for (let index = 0; index < 2; index += 1) state = acceptHistoryPage(state, page(index + 1, [row(index)], true), index + 1).state
    const failed = acceptHistoryPage(state, page(4, [row(4)], true), 3)
    expect(failed.accepted).toBe(false)
    expect(failed.errorReason).toBe('page_number_mismatch')
    expect(failed.state).toBe(state)
    const retried = acceptHistoryPage(state, page(3, [row(2)], true), 3)
    expect(retried.accepted).toBe(true)
    expect(retried.state.page).toBe(3)
  })

  it('rejects repeated pages and duplicate clicks without duplicating evidence', () => {
    let state: HistoryControllerState<Drive> = emptyHistoryControllerState()
    state = acceptHistoryPage(state, page(1, [row(1)], true), 1).state
    state = acceptHistoryPage(state, page(2, [row(2)], true), 2).state
    const repeated = acceptHistoryPage(state, page(3, [row(2)], true), 3)
    expect(repeated.accepted).toBe(false)
    expect(repeated.errorReason).toBe('repeated_page')
    const duplicateClick = acceptHistoryPage(state, page(3, [row(3)], true), 3)
    expect(duplicateClick.accepted).toBe(true)
    expect(duplicateClick.state.items.map(item => item.id)).toEqual(['drive-3', 'drive-2', 'drive-1'])
  })

  it('accepts an empty terminal page while rejecting contradictory metadata', () => {
    let state: HistoryControllerState<Drive> = emptyHistoryControllerState()
    state = acceptHistoryPage(state, page(1, [row(1)], true, 2), 1).state
    const terminal = acceptHistoryPage(state, page(2, [], false, 2), 2)
    expect(terminal.accepted).toBe(true)
    expect(terminal.complete).toBe(true)
    const contradiction = acceptHistoryPage(state, page(2, [row(2)], false, 3), 2)
    expect(contradiction.accepted).toBe(false)
    expect(contradiction.errorReason).toBe('total_pages_contradiction')
  })
})

describe('canonical history detail consumption', () => {
  it('writes accepted detail into the list and every containing page', () => {
    const original = row(1)
    const state = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original], false, 1), 1).state
    const detail = { ...original, startAddress: 'Genuine detail address' }
    const updated = acceptHistoryDetail(state, original, detail)
    expect(updated.items[0].startAddress).toBe('Genuine detail address')
    expect(updated.pages[0].items[0]).toBe(updated.items[0])
    const refreshed = acceptHistoryPage(updated, page(1, [{ ...original, startAddress: null }], false, 1), 1).state
    expect(refreshed.items[0].startAddress).toBe('Genuine detail address')
    expect(refreshed.pages[0].items[0]).toBe(refreshed.items[0])
  })
  it('rejects stale public IDs and another canonical session after an in-flight detail', () => {
    const original = row(1)
    const state = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original], false, 1), 1).state
    const changed = acceptHistoryPage(state, page(1, [{ ...original, id: 'drive-new-id' }], false, 1), 1).state
    expect(acceptHistoryDetail(changed, original, { ...original, startAddress: 'stale' })).toBe(changed)
    expect(acceptHistoryDetail(state, original, { ...original, sessionId: 'another-session' })).toBe(state)
  })
})
