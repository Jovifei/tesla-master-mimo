import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view' }))
import type { CarStatus } from '../../services/types'
import { dashboardRange, dashboardSnapshot, dashboardVehicleSubtitle, dashboardPower, dashboardShift, dashboardMetric, dashboardTemperature, dashboardTpmsPlaceholder, hudRemaining, rawChargeHours } from './presentation'
it('groups the App odometer while preserving an observed zero', () => {
 expect(dashboardMetric(19992, 'km', 0)).toBe('19,992 km')
 expect(dashboardMetric(0, 'km', 0)).toBe('0 km')
})
it('uses the App one-decimal temperature presentation only for observed values', () => {
 expect(dashboardTemperature(28)).toBe('28.0°C')
 expect(dashboardTemperature(27.9)).toBe('27.9°C')
 expect(dashboardTemperature(0)).toBe('0.0°C')
 expect(dashboardTemperature(null)).toBe('等待车辆')
})
it('mirrors the App charging HUD `Xh Ym` / `Xm` remaining format', () => {
 expect(hudRemaining(2.5)).toBe('2h 30m')
 expect(hudRemaining(0.4)).toBe('24m')
 expect(hudRemaining(null)).toBe('暂无数据')
 expect(hudRemaining(-1)).toBe('暂无数据')
})
it('keeps the bottom charging card on the App raw hours rendering', () => {
 expect(rawChargeHours(3)).toBe('3h')
 expect(rawChargeHours(3.5)).toBe('3.5h')
 expect(rawChargeHours(null)).toBe('暂无数据')
})
it('shows accurate readiness wording for missing TPMS wheels and plain data for available', () => {
 expect(dashboardTpmsPlaceholder({ status: 'collecting', lastObservedAt: null })).toBe('收集中')
 expect(dashboardTpmsPlaceholder({ status: 'waiting_vehicle', lastObservedAt: null })).toBe('等待车辆')
 expect(dashboardTpmsPlaceholder({ status: 'permission_required', lastObservedAt: null })).toBe('需要 Tesla 授权')
 expect(dashboardTpmsPlaceholder({ status: 'unsupported', lastObservedAt: null })).toBe('不支持')
 expect(dashboardTpmsPlaceholder({ status: 'telemetry_error', lastObservedAt: null })).toBe('暂不可用')
 expect(dashboardTpmsPlaceholder({ status: 'collecting', lastObservedAt: '2026-10-25T01:00:00Z' })).toBe('收集中 · 最近观测：2026-10-25T01:00:00Z')
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
