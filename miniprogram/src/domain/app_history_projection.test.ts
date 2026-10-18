import { describe, expect, it } from 'vitest'
import type { Charge, Drive } from '../services/types'
import { appChargeListRows, appChargeStatisticsRows, isAppDriveAnalysisEligible } from './app_history_projection'

const charge = (id: string, energyAddedKwh: number | null, qualityState: Charge['qualityState'] = 'observed', qualityReason: string | null = null): Charge => ({
  id, sessionId: id, startDate: '2026-10-14T00:00:00Z', endDate: '2026-10-14T00:01:00Z',
  source: 'teslamate_archive', qualityState, qualityReason, energyAddedKwh, cost: 0, currency: 'CNY', currencyCode: 'CNY',
} as Charge)

describe('App charge list and statistics projections', () => {
  it('hides qualified short sessions only from the default list and preserves exact zero in statistics', () => {
    const rows = [charge('long', 1), charge('zero', 0), charge('boundary', 0.1), charge('unknown', null),
      charge('incomplete', 0, 'incomplete', 'missing_charge_measurement'),
      charge('legacy-quality', 0, 'incomplete', 'remote_quality_unavailable'),
      charge('quarantined', 5, 'quarantined')]
    const before = JSON.stringify(rows)

    expect(appChargeListRows(rows).map(row => row.id)).toEqual(['long', 'unknown', 'incomplete'])
    expect(appChargeListRows(rows, true).map(row => row.id)).toEqual(['long', 'zero', 'boundary', 'unknown', 'incomplete', 'legacy-quality'])
    expect(appChargeStatisticsRows(rows).map(row => row.id)).toEqual(['long', 'zero', 'boundary', 'unknown', 'legacy-quality'])
    expect(appChargeStatisticsRows(rows).find(row => row.id === 'zero')?.energyAddedKwh).toBe(0)
    expect(appChargeStatisticsRows(rows).find(row => row.id === 'zero')?.cost).toBe(0)
    expect(JSON.stringify(rows)).toBe(before)
  })
})

it('admits analysis-quality short drives without applying the route-page distance threshold', () => {
  const drive = (qualityState: Drive['qualityState'], qualityReason: string | null = null) => ({ qualityState, qualityReason, distanceKm: 0.05, durationMinutes: 0.5 } as Drive)
  expect(isAppDriveAnalysisEligible(drive('observed'))).toBe(true)
  expect(isAppDriveAnalysisEligible(drive('derived'))).toBe(true)
  expect(isAppDriveAnalysisEligible(drive('incomplete', 'remote_quality_unavailable'))).toBe(true)
  expect(isAppDriveAnalysisEligible(drive('incomplete', 'missing_distance'))).toBe(false)
  expect(isAppDriveAnalysisEligible(drive('quarantined'))).toBe(false)
})
