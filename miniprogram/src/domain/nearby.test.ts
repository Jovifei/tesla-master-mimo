import { describe, expect, it } from 'vitest'
import type { ChargingSite } from '../services/types'
import { isSuperchargerSite, siteDisplayParking, siteDisplayPrice, siteStallsText } from './nearby'

function site(partial: Partial<ChargingSite>): ChargingSite {
  return {
    name: null,
    type: null,
    latitude: null,
    longitude: null,
    distance_km: null,
    available_stalls: null,
    total_stalls: null,
    site_closed: null,
    price_text: null,
    parking_policy: null,
    ...partial,
  }
}

describe('nearby charging display derivations (App parity)', () => {
  it('detects superchargers by type or name like the App', () => {
    expect(isSuperchargerSite(site({ type: 'supercharger' }))).toBe(true)
    expect(isSuperchargerSite(site({ name: '杭州超充站' }))).toBe(true)
    expect(isSuperchargerSite(site({ name: 'Tesla 目的地' }))).toBe(true)
    expect(isSuperchargerSite(site({ type: 'destination_charging', name: '酒店停车场' }))).toBe(false)
  })

  it('formats stall occupancy only when both counts exist', () => {
    expect(siteStallsText(site({ available_stalls: 3, total_stalls: 8 }))).toBe('3/8')
    expect(siteStallsText(site({ available_stalls: 3 }))).toBeNull()
    expect(siteStallsText(site({}))).toBeNull()
  })

  it('prefers backend price_text and parking_policy over defaults', () => {
    const s = site({ type: 'supercharger', price_text: '¥0.95~1.65/度', parking_policy: '超充免费停车2小时' })
    expect(siteDisplayPrice(s)).toBe('¥0.95~1.65/度')
    expect(siteDisplayParking(s)).toBe('超充免费停车2小时')
  })

  it('falls back to the App defaults per site type', () => {
    expect(siteDisplayPrice(site({ type: 'supercharger' }))).toBe('¥1.25/度 (分时)')
    expect(siteDisplayParking(site({ type: 'supercharger' }))).toBe('充电免费停车2小时')
    expect(siteDisplayPrice(site({ type: 'destination_charging' }))).toBe('¥1.15/度')
    expect(siteDisplayParking(site({ type: 'destination_charging' }))).toBe('前2小时免费停车')
  })
})
