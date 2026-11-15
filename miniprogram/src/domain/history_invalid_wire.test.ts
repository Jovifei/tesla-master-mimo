import { expect, it, vi } from 'vitest'
import { normalizeCharge, normalizeDrive } from '../services/api'
import type { RawCharge, RawDrive } from '../services/types'
import { sortHistoryByStartDate } from './history'
import { emptyHistoryQuery, filterHistory } from './history_query'
import { historyDayGroups } from './ui_presentation'

vi.mock('@tarojs/taro', () => ({ default: {} }))

it.each([
  { kind: 'drive', normalize: (id: number, startDate: unknown) => normalizeDrive({ drive_id: id, start_date: startDate } as unknown as RawDrive) },
  { kind: 'charge', normalize: (id: number, startDate: unknown) => normalizeCharge({ charge_id: id, start_date: startDate } as unknown as RawCharge) },
])('retains $kind records with non-string wire dates as stable unknown dates', ({ normalize }) => {
  const numberDate = 1700000000, objectDate = { epoch: numberDate }
  const numberRow = normalize(1, numberDate)!, validRow = normalize(2, '2026-11-09T12:00:00Z')!, objectRow = normalize(3, objectDate)!
  const sorted = sortHistoryByStartDate([numberRow, validRow, objectRow])

  expect(sorted.map(row => row.id)).toEqual(['2', '1', '3'])
  expect(sorted[1].startDate).toBe(numberDate)
  expect(sorted[2].startDate).toEqual(objectDate)
  expect(filterHistory(sorted, emptyHistoryQuery()).map(row => row.id)).toEqual(['2', '1', '3'])
  expect(historyDayGroups(sorted, row => row.startDate).find(group => group.key === 'unknown')?.items.map(row => row.id)).toEqual(['1', '3'])
})
