import { expect, it } from 'vitest'
import { buildDriveHistoryItems, parkedAddressLabel, selectVisibleDriveHistoryItems, verifiedParkedPairs } from './drive_history'
import type { Drive } from '../services/types'

const drive = (id: string, distanceKm: number | null, startDate: string, endDate: string, overrides: Partial<Drive> = {}): Drive => ({
  id, sessionId: null, startDate, endDate,
  startAddress: null, endAddress: null,
  durationMinutes: 10, distanceKm,
  energyConsumedKwh: null, startBatteryLevel: 50, endBatteryLevel: 40,
  startLatitude: null, startLongitude: null, endLatitude: null, endLongitude: null,
  source: 'telemetry_mqtt', sourceInstanceId: 'collector-A', sourceVehicleId: 'car-A', qualityState: 'observed', qualityReason: null,
  ...overrides,
})

it('inserts a parked window after the newer drive between eligible neighbours', () => {
  const items = buildDriveHistoryItems([
    drive('n', 4, '2026-10-23T23:18:00Z', '2026-10-23T23:26:00Z'),
    drive('o', 3, '2026-10-23T18:42:00Z', '2026-10-23T20:59:00Z'),
  ])
  expect(items.map(item => item.kind)).toEqual(['drive', 'parked', 'drive'])
  const parked = items[1]
  if (parked.kind !== 'parked') return expect.unreachable()
  expect([parked.older.id, parked.newer.id]).toEqual(['o', 'n'])
  expect(parked.startDate).toBe('2026-10-23T20:59:00Z')
  expect(parked.endDate).toBe('2026-10-23T23:18:00Z')
  expect(parked.minutes).toBe(139)
})

it('an intervening short drive breaks adjacency and does not fabricate a bridge parked window', () => {
  const fullSequence = [
    drive('n', 4, '2026-10-23T23:18:00Z', '2026-10-23T23:26:00Z'),
    drive('s', 0.2, '2026-10-23T21:00:00Z', '2026-10-23T21:05:00Z'),
    drive('o', 3, '2026-10-23T18:42:00Z', '2026-10-23T20:59:00Z'),
  ]
  const pairs = verifiedParkedPairs(fullSequence)
  expect(pairs.size).toBe(0)
  const items = selectVisibleDriveHistoryItems(fullSequence, fullSequence, pairs)
  expect(items.map(item => item.kind)).toEqual(['drive', 'drive', 'drive'])
})

it('preserves parking adjacency when filters hide irrelevant drives', () => {
  const fullSequence = [
    drive('n', 4, '2026-10-23T23:18:00Z', '2026-10-23T23:26:00Z'),
    drive('o', 3, '2026-10-23T18:42:00Z', '2026-10-23T20:59:00Z'),
    drive('older', 10, '2026-10-22T10:00:00Z', '2026-10-22T11:00:00Z'),
  ]
  const pairs = verifiedParkedPairs(fullSequence)
  expect(pairs.size).toBe(2)

  // Visible only includes n and o (e.g. today's drives)
  const visible = [fullSequence[0], fullSequence[1]]
  const items = selectVisibleDriveHistoryItems(fullSequence, visible, pairs)
  expect(items.map(item => item.kind)).toEqual(['drive', 'parked', 'drive'])
})

it('drops zero and negative windows instead of fabricating a stop', () => {
  const items = buildDriveHistoryItems([
    drive('n', 4, '2026-10-23T20:59:00Z', '2026-10-23T21:26:00Z'),
    drive('o', 3, '2026-10-23T20:00:00Z', '2026-10-23T20:59:00Z'),
  ])
  expect(items.every(item => item.kind === 'drive')).toBe(true)
})

it('prefers the Chinese address part and never fabricates a translation', () => {
  expect(parkedAddressLabel('杭州市 余杭塘路, Hangzhou', '访云街 西蔡庙')).toBe('杭州市 余杭塘路')
  expect(parkedAddressLabel('123 Main Street', 'Springfield')).toBe('123 Main Street')
  expect(parkedAddressLabel(null, undefined)).toBeNull()
})

it('projects only the selected source instance when public drive IDs collide', () => {
  const full = [
    drive('n', 4, '2026-10-23T23:18:00Z', '2026-10-23T23:26:00Z', { sourceInstanceId: 'A', sourceRecordId: 'new' }),
    drive('o', 3, '2026-10-23T21:00:00Z', '2026-10-23T21:20:00Z', { sourceInstanceId: 'A', sourceRecordId: 'old' }),
    drive('n', 4, '2026-10-23T19:00:00Z', '2026-10-23T19:10:00Z', { sourceInstanceId: 'B', sourceRecordId: 'new' }),
    drive('o', 3, '2026-10-23T17:00:00Z', '2026-10-23T17:10:00Z', { sourceInstanceId: 'B', sourceRecordId: 'old' }),
  ]
  const shown = selectVisibleDriveHistoryItems(full, full.slice(0, 2))
  expect(shown.map(item => item.kind)).toEqual(['drive', 'parked', 'drive'])
  expect(shown.filter(item => item.kind === 'drive').map(item => item.kind === 'drive' && item.drive.sourceInstanceId)).toEqual(['A', 'A'])
})
