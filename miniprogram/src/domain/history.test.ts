import { describe, expect, it } from 'vitest'
import { historyRowKey, historyScopeKey, mergeHistoryByKey, mergeHistoryEvidence, mergeHistoryPages, sortHistoryByStartDate } from './history'
import { driveEnergyReceipt, resolveChargeEnergy, resolveDriveEnergy } from './drive_energy'
import type { RawCharge, RawDrive } from '../services/types'

const proofStart = '2026-10-14T00:00:00Z', proofEnd = '2026-10-14T00:01:00Z'
function driveProof(value: number, qualityState: string) {
  const raw: RawDrive = { drive_id: 1, session_id: 's1', start_date: proofStart, end_date: proofEnd,
    source: 'telemetry_mqtt', quality_state: qualityState, odometer_details: { odometer_distance: 2 }, energy_contract: { version: 1,
      net_energy: { value_kwh: value, unit: 'kWh', method: 'api_reported_net', measurement_point: 'reported_net', source: 'telemetry_mqtt',
        source_field: 'energy_consumed_net', quality: 'reported', start_date: proofStart, end_date: proofEnd, time_basis: 'source_report', coverage_kind: 'provider_report' } } }
  const energyReceipt = driveEnergyReceipt(raw), energy = resolveDriveEnergy(energyReceipt)
  return { id: '1', sessionId: 's1', startDate: proofStart, endDate: proofEnd, source: 'telemetry_mqtt', qualityState,
    distanceKm: 2, energyConsumedKwh: energy.energyKwh, energy, energyReceipt }
}
function chargeProof(value: number, qualityState: string) {
  const metric = { value_kwh: value, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'battery_input',
    source: 'telemetry_mqtt', quality: 'reported', start_date: proofStart, end_date: proofEnd, coverage_kind: 'endpoints', coverage_ratio: 1 }
  const raw: RawCharge = { charge_id: 1, session_id: 's1', start_date: proofStart, end_date: proofEnd, source: 'telemetry_mqtt',
    quality_state: qualityState, energy_contract: { version: 1, battery_input: metric } }
  const energy = resolveChargeEnergy(raw)
  return { id: '1', sessionId: 's1', startDate: proofStart, endDate: proofEnd, source: 'telemetry_mqtt', qualityState,
    energyAddedKwh: energy.batteryInputKwh, energyUsedKwh: energy.inputEnergyKwh, energy, cost: value, currency: value === 99 ? 'USD' : 'EUR' }
}

describe('whole-record quality admission precedes energy proof merging', () => {
  it.each(['derived', 'incomplete'])('does not launder a lower-grade %s drive or charge proof beneath observed metadata', grade => {
    for (const value of [2, 0]) {
      const drive = mergeHistoryEvidence(driveProof(value, 'observed'), driveProof(99, grade))
      expect(drive.qualityState).toBe('observed')
      expect(drive.energy.energyKwh).toBe(value)
      expect(drive.energyReceipt.energy_contract?.net_energy?.value_kwh).toBe(value)
      const charge = mergeHistoryEvidence(chargeProof(value, 'observed'), chargeProof(99, grade))
      expect(charge.qualityState).toBe('observed')
      expect(charge.energy.batteryInputKwh).toBe(value)
      expect(charge.cost).toBe(value)
      expect(charge.currency).toBe('EUR')
    }
  })

  it('does not graft a lower-grade prior proof into a selected higher-grade whole incoming record', () => {
    const incoming = driveProof(0, 'observed')
    const raw = { ...incoming.energyReceipt, energy_contract: undefined }
    const weakIncoming = { ...incoming, energyReceipt: raw, energy: resolveDriveEnergy(raw), energyConsumedKwh: null }
    const upgraded = mergeHistoryEvidence(driveProof(99, 'derived'), weakIncoming)
    expect(upgraded.qualityState).toBe('observed')
    expect(upgraded.energy.energyKwh).toBeNull()
    expect(upgraded.energyReceipt.energy_contract).toBeUndefined()
  })

  it('preserves whole winner evidence while updating the current public ID for the same canonical session', () => {
    const retained = mergeHistoryEvidence(driveProof(2, 'observed'), { ...driveProof(99, 'derived'), id: 'new-public-id' })
    expect(retained.id).toBe('new-public-id')
    expect(retained.sessionId).toBe('s1')
    expect(retained.energy.energyKwh).toBe(2)
  })

  it('still permits same-grade real corrections and real zero contracts', () => {
    expect(mergeHistoryEvidence(driveProof(2, 'observed'), driveProof(0, 'observed')).energy.energyKwh).toBe(0)
    expect(mergeHistoryEvidence(chargeProof(2, 'observed'), chargeProof(0, 'observed')).energy.batteryInputKwh).toBe(0)
  })

  it('still honors a same-grade explicit unknown and cannot transplant proof to another source/window', () => {
    const original = driveProof(2, 'observed')
    const revokedRaw: RawDrive = { ...original.energyReceipt, energy_contract: { version: 1,
      net_energy: { ...original.energyReceipt.energy_contract!.net_energy!, quality: 'unknown', value_kwh: null, reason: 'no_verified_driving_net_energy' } } }
    const revoked = { ...original, energyReceipt: driveEnergyReceipt(revokedRaw), energy: resolveDriveEnergy(revokedRaw), energyConsumedKwh: null }
    expect(mergeHistoryEvidence(original, revoked).energy.energyKwh).toBeNull()
    for (const change of [{ source: 'fleet_api' }, { endDate: '2026-10-14T00:02:00Z' }]) {
      const incoming = { ...original, ...change }
      const raw = { ...incoming.energyReceipt, source: incoming.source, start_date: incoming.startDate,
        end_date: incoming.endDate, energy_contract: undefined }
      const corrected = { ...incoming, energyReceipt: raw, energy: resolveDriveEnergy(raw), energyConsumedKwh: null }
      expect(mergeHistoryEvidence(original, corrected).energy.energyKwh).toBeNull()
    }
  })
})

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

  it('differentiates records from different source instances even if they share the same session ID', () => {
    const itemA = { id: 'row-1', sessionId: 'same-session', source: 'collector_a', sourceInstanceId: 'inst-A', sourceRecordId: 'rec-1' }
    const itemB = { id: 'row-1', sessionId: 'same-session', source: 'collector_b', sourceInstanceId: 'inst-B', sourceRecordId: 'rec-2' }
    expect(historyRowKey(itemA)).not.toBe(historyRowKey(itemB))
    const merged = mergeHistoryByKey([itemA], [itemB], historyRowKey)
    expect(merged.items).toHaveLength(2)
  })

  it('does not join conflicting or one-sided source identities even with the same public id and session', () => {
    const base = { id: '7', sessionId: 'same', source: 'teslamate_archive', sourceInstanceId: 'A', sourceVehicleId: 'car', sourceRecordId: 'record' }
    for (const change of [{ source: 'fleet_api' }, { sourceInstanceId: 'B' }, { sourceVehicleId: 'other' }, { sourceRecordId: 'other' }, { sourceInstanceId: null }, { sourceRecordId: null }]) {
      const incoming = { ...base, ...change }
      expect(mergeHistoryByKey([base], [incoming], historyRowKey).items, JSON.stringify(change)).toHaveLength(2)
    }
    expect(mergeHistoryByKey([base], [{ ...base, id: 'new-public-id' }], historyRowKey).items).toHaveLength(1)
    const legacy = { id: '7', sessionId: 'same', source: 'teslamate_archive' }
    expect(mergeHistoryByKey([legacy], [{ ...legacy, id: 'new-public-id' }], historyRowKey).items).toHaveLength(1)
    expect(mergeHistoryByKey([legacy], [base], historyRowKey).items).toHaveLength(2)
  })

  it('keeps delimiter-containing source identities distinct', () => {
    const base = { id: '7', sessionId: 'same', source: 'teslamate_archive', sourceVehicleId: 'car' }
    const a = { ...base, sourceInstanceId: 'a:rec:b', sourceRecordId: 'c' }
    const b = { ...base, sourceInstanceId: 'a', sourceRecordId: 'b:rec:c' }
    expect(historyRowKey(a)).not.toBe(historyRowKey(b))
    expect(mergeHistoryByKey([a], [b], historyRowKey).items).toHaveLength(2)
  })
})
