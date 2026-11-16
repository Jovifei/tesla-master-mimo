import { describe, expect, it } from 'vitest'
import { frequentPlaces } from './frequent_places'

function drive(partial: Record<string, unknown>) {
  return {
    id: '1', sessionId: null, startDate: null, endDate: null, startAddress: null, endAddress: null,
    durationMinutes: null, distanceKm: null, energyConsumedKwh: null, startBatteryLevel: null,
    endBatteryLevel: null, startLatitude: null, startLongitude: null, endLatitude: null,
    endLongitude: null, source: null, qualityState: null, qualityReason: null, ...partial,
  } as never
}

describe('frequentPlaces', () => {
  it('counts start and end addresses and keeps coordinates from the newest visit', () => {
    const result = frequentPlaces([
      drive({ startAddress: '家', endAddress: '公司', startLatitude: 30.1, startLongitude: 120.1, startDate: '2026-11-15T08:00:00+08:00', endLatitude: 30.2, endLongitude: 120.2, endDate: '2026-11-15T09:00:00+08:00' }),
      drive({ startAddress: '公司', endAddress: '家', startLatitude: 30.3, startLongitude: 120.3, startDate: '2026-11-16T18:00:00+08:00', endLatitude: 30.15, endLongitude: 120.15, endDate: '2026-11-16T19:00:00+08:00' }),
    ])
    expect(result.map(place => place.name)).toEqual(['家', '公司'])
    expect(result[0]).toMatchObject({ count: 2, latitude: 30.15, longitude: 120.15 })
    expect(result[1]).toMatchObject({ count: 2, latitude: 30.3, longitude: 120.3 })
  })

  it('drops unknown placeholders and quarantined drives', () => {
    const result = frequentPlaces([
      drive({ startAddress: '未知地点', endAddress: '某商场' }),
      drive({ startAddress: '其他地方', qualityState: 'quarantined' }),
    ])
    expect(result.map(place => place.name)).toEqual(['某商场'])
  })

  it('keeps a place without coordinates but never invents them', () => {
    const result = frequentPlaces([drive({ startAddress: '老小区' })])
    expect(result[0]).toMatchObject({ name: '老小区', latitude: null, longitude: null, count: 1 })
  })
})