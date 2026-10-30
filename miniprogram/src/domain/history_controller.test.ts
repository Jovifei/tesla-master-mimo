import { describe, expect, it } from 'vitest'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, emptyHistoryControllerState, enrichHistoryInterval, reconcileHistoryCache, type HistoryControllerState } from './history_controller'
import type { Charge, Drive, HistoryPage } from '../services/types'
import { driveEnergyReceipt, resolveDriveEnergy } from './drive_energy'

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
  it('reconciles cache aliases only within existing membership and keeps page metadata/cursor', () => {
    const original = row(1)
    const state = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original], true), 1).state
    const next = reconcileHistoryCache(state, [{ ...original, id: 'new-public-id', startAddress: 'new cache evidence' }, row(2), { ...original, sessionId: 'conflicting-session', startAddress: 'must not graft' }])
    expect(next.items).toHaveLength(1)
    expect(next.items[0]).toMatchObject({ id: 'new-public-id', startAddress: 'new cache evidence' })
    expect(next.pages[0].items[0]).toBe(next.items[0])
    expect(next.pages[0].meta).toBe(state.pages[0].meta)
    expect(next.page).toBe(state.page)
    expect(reconcileHistoryCache(next, [])).toBe(next)
  })
  it('collects no more than 500 actual interval rows and leaves a stopped snapshot explicitly partial', async () => {
    const fetchPage = async (number: number) => page(number, Array.from({ length: 20 }, (_, index) => row((number - 1) * 20 + index)), true, 30)
    const interval = await collectHistoryInterval(await fetchPage(1), fetchPage, () => true)
    expect(interval).toMatchObject({ complete: false, receivedPages: 25, reason: 'limit' })
    expect(interval?.items).toHaveLength(500)
  })

  it('abandons a statistics snapshot after its owner changes and retains only verified prefix on failures', async () => {
    let current = true
    const cancelled = await collectHistoryInterval(page(1, [row(1)], true, 2), async () => {
      current = false
      return page(2, [row(2)], false, 2)
    }, () => current)
    expect(cancelled).toBeNull()
    const failed = await collectHistoryInterval(page(1, [row(1)], true, 2), async () => { throw new Error('offline') }, () => true)
    expect(failed).toMatchObject({ complete: false, receivedPages: 1, reason: 'request_failed' })
    expect(failed?.items.map(row => row.id)).toEqual(['drive-1'])
    const repeated = await collectHistoryInterval(page(1, [row(1)], true, 2), async () => page(2, [row(1)], false, 2), () => true)
    expect(repeated).toMatchObject({ complete: false, receivedPages: 1, reason: 'repeated_page' })
  })

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
  it('does not graft old charge values into a corrected time window', () => {
    const previous: Charge = { ...chargeRow(1), startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z',
      address: 'old location', cost: 12, currency: 'CNY', startBatteryLevel: 20, endBatteryLevel: 80, energyAddedKwh: 10 }
    const corrected: Charge = { ...previous, startDate: '2026-10-02T00:00:00Z', endDate: '2026-10-02T01:00:00Z',
      address: null, cost: null, currency: null, startBatteryLevel: null, endBatteryLevel: null, energyAddedKwh: null }
    const initial = acceptHistoryPage(emptyHistoryControllerState<Charge>(), page(1, [previous], false, 1), 1).state
    const updated = acceptHistoryPage(initial, page(1, [corrected], false, 1), 1).state
    expect(updated.items).toHaveLength(1)
    expect(updated.items[0]).toMatchObject({ startDate: corrected.startDate, endDate: corrected.endDate,
      address: null, cost: null, currency: null, startBatteryLevel: null, endBatteryLevel: null, energyAddedKwh: null })
    expect(initial.items[0]).toMatchObject({ address: 'old location', cost: 12, energyAddedKwh: 10 })
  })

  it('does not graft old drive data into a corrected start boundary', () => {
    const previous: Drive = { ...row(1), startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z',
      startAddress: 'old start', endAddress: 'old end', distanceKm: 10, energyConsumedKwh: 2, startBatteryLevel: 80, endBatteryLevel: 70 }
    const corrected: Drive = { ...previous, startDate: '2026-10-01T00:10:00Z', startAddress: null,
      endAddress: null, distanceKm: null, energyConsumedKwh: null, startBatteryLevel: null, endBatteryLevel: null }
    const state = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [previous], false, 1), 1).state
    const updated = acceptHistoryPage(state, page(1, [corrected], false, 1), 1).state
    expect(updated.items[0]).toMatchObject({ startDate: corrected.startDate, startAddress: null, endAddress: null,
      distanceKm: null, energyConsumedKwh: null, startBatteryLevel: null, endBatteryLevel: null })
  })

  it('retains qualified sparse details when time windows represent the same instant', () => {
    const previous: Charge = { ...chargeRow(1), startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', address: 'known location', cost: 12 }
    const equalWindow: Charge = { ...previous, startDate: '2026-10-01T08:00:00+08:00', endDate: '2026-10-01T09:00:00+08:00', address: null, cost: null }
    const initial = acceptHistoryPage(emptyHistoryControllerState<Charge>(), page(1, [previous], false, 1), 1).state
    const updated = acceptHistoryPage(initial, page(1, [equalWindow], false, 1), 1).state
    expect(updated.items[0]).toMatchObject({ startDate: equalWindow.startDate, address: 'known location', cost: 12 })
  })

  it('does not let missing timestamps or weaker quality alone replace a known cached window', () => {
    const previous: Charge = { ...chargeRow(1), startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', address: 'known location', cost: 12 }
    const state = acceptHistoryPage(emptyHistoryControllerState<Charge>(), page(1, [previous], false, 1), 1).state
    const sparse: Charge = { ...previous, startDate: null, endDate: null, address: null, cost: null }
    const weaker: Charge = { ...previous, startDate: '2026-10-02T00:00:00Z', endDate: '2026-10-02T01:00:00Z', qualityState: 'derived', address: null, cost: null }
    for (const update of [sparse, weaker]) {
      expect(acceptHistoryPage(state, page(1, [update], false, 1), 1).state.items[0]).toMatchObject({ startDate: previous.startDate, endDate: previous.endDate, address: 'known location', cost: 12 })
    }
  })

  it('preserves legacy unknown-quality cached windows when a refresh omits timestamps', () => {
    const previous: Charge = { ...chargeRow(1), source: null, qualityState: null,
      startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', address: 'legacy location', cost: 12 }
    const sparse: Charge = { ...previous, startDate: null, endDate: null, address: null, cost: null }
    const state = acceptHistoryPage(emptyHistoryControllerState<Charge>(), page(1, [previous], false, 1), 1).state
    expect(acceptHistoryPage(state, page(1, [sparse], false, 1), 1).state.items[0]).toMatchObject({
      startDate: previous.startDate, endDate: previous.endDate, address: 'legacy location', cost: 12,
    })
  })

  it('does not replace a record or accept detail from a different or missing source instance', () => {
    const original: Drive = { ...row(1), source: 'teslamate_archive', sourceInstanceId: 'A', sourceVehicleId: 'car', sourceRecordId: 'rec' }
    const foreign = { ...original, sourceInstanceId: 'B', startAddress: 'foreign' }
    const unknown = { ...original, sourceInstanceId: null, startAddress: 'unverified' }
    const state = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original], false, 1), 1).state
    for (const detail of [foreign, unknown]) {
      expect(acceptHistoryDetail(state, original, detail)).toBe(state)
      expect(reconcileHistoryCache(state, [detail])).toBe(state)
      expect(enrichHistoryInterval([original], [detail])[0]).toEqual(original)
    }
    const withBoth = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original, foreign], false, 1), 1).state
    expect(withBoth.items).toHaveLength(2)
    expect(withBoth.pages[0].items.map(item => item.sourceInstanceId)).toEqual(['A', 'B'])
  })

  it('continues pagination but reports incomplete coverage when a malformed record was discarded', async () => {
    const first = page(1, [row(1)], true, 2)
    const damaged = { ...first, meta: { ...first.meta, discardedRows: 1 } }
    const requested: number[] = []
    const result = await collectHistoryInterval(damaged, async n => { requested.push(n); return page(2, [row(2)], false, 2) }, () => true)
    expect(requested).toEqual([2])
    expect(result).toMatchObject({ complete: false, receivedPages: 2, reason: 'invalid_rows_discarded' })
    expect(result?.items).toHaveLength(2)
  })

  it('continues past an entirely malformed full page without claiming complete coverage', async () => {
    const first = page(1, [], true, 2)
    const damaged = { ...first, meta: { ...first.meta, discardedRows: 20 } }
    const requested: number[] = []
    const result = await collectHistoryInterval(damaged, async n => { requested.push(n); return page(2, [row(2)], false, 2) }, () => true)
    expect(requested).toEqual([2])
    expect(result).toMatchObject({ complete: false, receivedPages: 2, reason: 'invalid_rows_discarded' })
    expect(result?.items.map(item => item.id)).toEqual(['drive-2'])
  })

  it('rejects a delayed charge detail after a newer same-window cost/energy correction', () => {
    const original = { ...chargeRow(1), energyAddedKwh: 2, energyUsedKwh: 3, cost: 4, currency: 'EUR' }
    const current = acceptHistoryPage(emptyHistoryControllerState<Charge>(), page(1, [original], true), 1).state
    const corrected = { ...original, energyAddedKwh: 0, energyUsedKwh: 0, cost: 0 }
    const state = { ...current, items: [corrected] }
    expect(acceptHistoryDetail(state, original, { ...original, address: 'stale detail' })).toBe(state)
  })

  it.each(['derived', 'incomplete'])('retains observed proof in canonical items and pages after a lower-grade %s detail', grade => {
    const startDate = '2026-10-14T00:00:00Z', endDate = '2026-10-14T00:01:00Z'
    const receipt = (value: number) => driveEnergyReceipt({ drive_id: 1, session_id: 'session-1', start_date: startDate, end_date: endDate,
      source: 'telemetry_mqtt', odometer_details: { odometer_distance: 2 }, energy_contract: { version: 1, net_energy: {
        value_kwh: value, unit: 'kWh', method: 'api_reported_net', measurement_point: 'reported_net', source: 'telemetry_mqtt',
        quality: 'reported', start_date: startDate, end_date: endDate, time_basis: 'source_report', coverage_kind: 'provider_report', source_field: 'energy_consumed_net',
      } } })
    const oldReceipt = receipt(2), nextReceipt = receipt(99)
    const original: Drive = { ...row(1), id: '1', startDate, endDate, distanceKm: 2, energyReceipt: oldReceipt,
      energy: resolveDriveEnergy(oldReceipt), energyConsumedKwh: 2 }
    const incoming: Drive = { ...original, qualityState: grade, energyReceipt: nextReceipt, energy: resolveDriveEnergy(nextReceipt), energyConsumedKwh: 99 }
    const current = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [original], false, 1), 1).state
    const detail = acceptHistoryDetail(current, original, incoming)
    expect(detail.items[0].energy?.energyKwh).toBe(2)
    expect(detail.items[0].qualityState).toBe('observed')
    expect(detail.pages[0].items[0]).toBe(detail.items[0])
    expect(detail.pages[0].items[0].energyReceipt?.energy_contract?.net_energy?.value_kwh).toBe(2)
  })

  it('never admits details into an already quarantined canonical row', () => {
    const isolated = { ...row(1), qualityState: 'quarantined', qualityReason: 'ambiguous_source' }
    const current = acceptHistoryPage(emptyHistoryControllerState<Drive>(), page(1, [isolated], false, 1), 1).state
    const incoming = { ...isolated, qualityState: 'observed', qualityReason: null, startAddress: 'Later unqualified detail' }
    expect(acceptHistoryDetail(current, isolated, incoming)).toBe(current)
    expect(current.items[0].qualityState).toBe('quarantined')
  })

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
