import { expect, it } from 'vitest'
import { buildDriveHistoryItems, parkedAddressLabel } from './drive_history'
import type { Drive } from '../services/types'

const drive = (id: string, distanceKm: number | null, startDate: string, endDate: string, overrides: Partial<Drive> = {}): Drive => ({
  id, sessionId: null, startDate, endDate,
  startAddress: null, endAddress: null,
  durationMinutes: 10, distanceKm,
  energyConsumedKwh: null, startBatteryLevel: 50, endBatteryLevel: 40,
  startLatitude: null, startLongitude: null, endLatitude: null, endLongitude: null,
  source: 'telemetry_mqtt', qualityState: 'observed', qualityReason: null,
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
  expect(parked.key).toBe('parked-o-n')
  expect(parked.startDate).toBe('2026-10-23T20:59:00Z')
  expect(parked.endDate).toBe('2026-10-23T23:18:00Z')
  expect(parked.minutes).toBe(139)
})

it('short drives do not change parked inference between eligible boundaries', () => {
  const items = buildDriveHistoryItems([
    drive('n', 4, '2026-10-23T23:18:00Z', '2026-10-23T23:26:00Z'),
    drive('s', 0.2, '2026-10-23T21:00:00Z', '2026-10-23T21:05:00Z'),
    drive('o', 3, '2026-10-23T18:42:00Z', '2026-10-23T20:59:00Z'),
  ])
  // s is not an eligible boundary: the o→n parking window survives unchanged
  // (App: "short drives do not change parked inference") and s stays a plain row.
  expect(items.map(item => item.kind)).toEqual(['drive', 'parked', 'drive', 'drive'])
  const parked = items[1]
  if (parked.kind !== 'parked') return expect.unreachable()
  expect(parked.key).toBe('parked-o-n')
  expect(parked.minutes).toBe(139)
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
