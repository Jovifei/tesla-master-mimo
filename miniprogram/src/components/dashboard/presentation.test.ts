import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view' }))
import type { CarStatus } from '../../services/types'
import { dashboardRange, dashboardSnapshot, dashboardVehicleSubtitle, dashboardPower, dashboardShift, dashboardMetric } from './presentation'
it('groups the App odometer while preserving an observed zero', () => {
 expect(dashboardMetric(19992, 'km', 0)).toBe('19,992 km')
 expect(dashboardMetric(0, 'km', 0)).toBe('0 km')
})
it.each([['74D','长续航版'],['50','标准续航版'],['P100D','性能版'],['Long Range','长续航版'],['unknown',null]])('matches actual App trim rule %s without exposing raw trim', (raw, expected) => { expect(dashboardVehicleSubtitle(null,raw)).toBe(expected); expect(dashboardVehicleSubtitle('Model Y',raw)).toBe(expected ? `Model Y · ${expected}` : 'Model Y') })
it('retains zero range, signed direction and missing shift without guessing', () => {
 expect(dashboardRange({ ratedRange:0,idealRange:50,estimatedRange:99 } as CarStatus)).toBe(0)
 expect(dashboardRange({ ratedRange:null,idealRange:0,estimatedRange:99 } as CarStatus)).toBe(0)
 expect(dashboardPower(-11)).toEqual({ label:'回收',value:11 })
 expect(dashboardPower(0)).toEqual({ label:'功率',value:0 })
 expect(dashboardShift(null)).toBeNull(); expect(dashboardShift('unknown')).toBeNull()
})
it('requires known source and valid recent time for LIVE; cached evidence stays cached', () => {
 const now=Date.parse('2026-10-16T00:00:00Z'),make=(source:string|null,observedAt:string|null)=>({source,observedAt} as CarStatus)
 expect(dashboardSnapshot(make('live_mqtt',null),false,now).kind).toBe('unknown')
 expect(dashboardSnapshot(make('fleet_api','bad'),false,now).kind).toBe('unknown')
 expect(dashboardSnapshot(make(null,'2026-10-16T00:00:00Z'),false,now).kind).toBe('unknown')
 expect(dashboardSnapshot(make('live_mqtt','2026-10-16T00:00:00Z'),false,now).kind).toBe('live')
 expect(dashboardSnapshot(make('live_mqtt','2026-10-16T00:00:00Z'),true,now).kind).toBe('cached')
 expect(dashboardSnapshot(make('live_mqtt','2026-10-16T00:00:06Z'),false,now).kind).toBe('history')
 expect(dashboardSnapshot(make('live_mqtt','2026-02-30T00:00:00Z'),false,now).kind).toBe('unknown')
})
