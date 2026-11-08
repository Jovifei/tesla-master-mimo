import { describe, expect, it } from 'vitest'
import type { Drive } from '../services/types'
import { locationDisplayText, recentDriveCardModel } from './recent_drive'

function drive(partial: Partial<Drive>): Drive {
  return {
    id: 'd1',
    sessionId: null,
    startDate: '2026-11-07T08:00:00+08:00',
    endDate: '2026-11-07T08:42:00+08:00',
    startAddress: null,
    endAddress: null,
    durationMinutes: null,
    distanceKm: null,
    energyConsumedKwh: null,
    startBatteryLevel: null,
    endBatteryLevel: null,
    startLatitude: null,
    startLongitude: null,
    endLatitude: null,
    endLongitude: null,
    source: null,
    qualityState: null,
    qualityReason: null,
    ...partial,
  }
}

describe('recentDriveCardModel (App DashboardRecentDriveCard parity)', () => {
  it('builds route, distance, soc range and the four capsules', () => {
    const model = recentDriveCardModel(
      drive({
        startAddress: '杭州市余杭区文一西路',
        endAddress: '杭州市西湖区西溪路',
        distanceKm: 12.34,
        durationMinutes: 42,
        efficiencyWhKm: 152.4,
        speedMaxKmh: 87.6,
        startBatteryLevel: 78,
        endBatteryLevel: 71,
      })
    )
    expect(model.routeText).toContain('→')
    expect(model.distanceText).toBe('12.3 km')
    expect(model.socRangeText).toBe('78% → 71%')
    expect(model.endTimeText).toBe('11-07 08:42')
    expect(model.capsules.map(c => c.label)).toEqual(['能耗', '里程', '时长', '极速'])
    expect(model.capsules.map(c => c.value)).toEqual(['152 Wh/km', '12.3 km', '42 min', '88 km/h'])
  })

  it('falls back to energy when efficiency is missing and to -- for absent metrics', () => {
    const model = recentDriveCardModel(drive({ energyConsumedKwh: 2.34 }))
    expect(model.capsules[0].value).toBe('2.3 kWh')
    expect(model.capsules[1].value).toBe('--')
    expect(model.routeText).toBe('未知')
    expect(model.socRangeText).toBeNull()
  })

  it('collapses the route to the single known endpoint', () => {
    const model = recentDriveCardModel(drive({ endAddress: '杭州市余杭区文一西路 969 号' }))
    expect(model.routeText).not.toContain('→')
    expect(model.routeText).not.toContain('未知')
    expect(model.routeText.length).toBeGreaterThan(0)
  })
})

describe('locationDisplayText (App locationDisplay chain)', () => {
  it('prefers the geofence name', () => {
    expect(
      locationDisplayText({ geofence: '家', location: { latitude: 30.27, longitude: 120.15 }, recentDrive: null })
    ).toBe('家')
  })

  it('uses the last drive end address only while the car is still there', () => {
    const parked = drive({ endAddress: '杭州市余杭区文一西路', endLatitude: 30.2701, endLongitude: 120.1501 })
    expect(
      locationDisplayText({ geofence: null, location: { latitude: 30.27, longitude: 120.15 }, recentDrive: parked })
    ).toContain('文一西路')
    const moved = locationDisplayText({
      geofence: null,
      location: { latitude: 30.35, longitude: 120.25 },
      recentDrive: parked,
    })
    expect(moved).toBe('已获取位置 · 点击查看地图')
  })

  it('falls back to the map affordance, then to null without a location', () => {
    expect(locationDisplayText({ geofence: null, location: { latitude: 30.27, longitude: 120.15 } })).toBe(
      '已获取位置 · 点击查看地图'
    )
    expect(locationDisplayText({ geofence: null, location: null })).toBeNull()
  })
})
