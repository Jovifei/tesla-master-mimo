import { describe, expect, it } from 'vitest'
import type { Drive } from '../../services/types'
import { appDriveMonthCounts } from './drive_parity'

describe('App monthly drive chart', () => {
  it('counts qualified rows by local-calendar month and fills empty months', () => {
    const rows = [
      { startDate: new Date(2026, 6, 2, 12).toISOString() },
      { startDate: new Date(2026, 6, 4, 12).toISOString() },
      { startDate: new Date(2026, 8, 1, 12).toISOString() },
    ] as Drive[]
    const groups = appDriveMonthCounts(rows)
    expect(groups.slice(0, 3)).toEqual([
      { month: '2026-07', count: 2 }, { month: '2026-08', count: 0 }, { month: '2026-09', count: 1 },
    ])
  })
})
