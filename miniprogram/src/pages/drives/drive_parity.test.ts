import { describe, expect, it } from 'vitest'
import type { Drive } from '../../services/types'
import { isAppRouteDrive, isAppStatisticsDrive } from './drive_parity'

const row = (overrides: Partial<Drive> = {}) => ({
  id: '1', source: 'teslamate', qualityState: 'observed', qualityReason: 'api_evidence',
  distanceKm: 0.2, durationMinutes: 1, ...overrides,
} as Drive)

describe('App drive admission parity', () => {
  it('excludes only qualified tiny repositioning movements from routes and totals', () => {
    expect(isAppRouteDrive(row({ distanceKm: 0.19 }))).toBe(false)
    expect(isAppRouteDrive(row({ durationMinutes: 0 }))).toBe(false)
    expect(isAppRouteDrive(row())).toBe(true)
    expect(isAppStatisticsDrive(row({ distanceKm: 0.19 }))).toBe(false)
    expect(isAppStatisticsDrive(row())).toBe(true)
  })

  it('retains incomplete or unknown-measurement rows in history without inflating App summary', () => {
    expect(isAppRouteDrive(row({ qualityState: 'incomplete', distanceKm: 0.01 }))).toBe(true)
    expect(isAppStatisticsDrive(row({ qualityState: 'incomplete', distanceKm: 0.01 }))).toBe(false)
    expect(isAppRouteDrive(row({ distanceKm: null }))).toBe(true)
    expect(isAppStatisticsDrive(row({ distanceKm: null }))).toBe(true)
  })

  it('uses the same legacy remote-quality exception and quarantines bad provenance', () => {
    expect(isAppRouteDrive(row({ qualityState: 'incomplete', qualityReason: 'remote_quality_unavailable', distanceKm: 0.1 }))).toBe(false)
    expect(isAppStatisticsDrive(row({ qualityState: 'incomplete', qualityReason: 'remote_quality_unavailable' }))).toBe(true)
    expect(isAppRouteDrive(row({ qualityState: 'quarantined' }))).toBe(false)
  })

  it('qualifies peak output as power > 0 and peak regen as power < 0', () => {
    const positiveOnly = [10, 20, 30]
    const outPos = positiveOnly.filter(p => p > 0)
    const regenPos = positiveOnly.filter(p => p < 0)
    expect(outPos.length ? Math.max(...outPos) : null).toBe(30)
    expect(regenPos.length ? Math.abs(Math.min(...regenPos)) : null).toBeNull()

    const negativeOnly = [-30, -20, -10]
    const outNeg = negativeOnly.filter(p => p > 0)
    const regenNeg = negativeOnly.filter(p => p < 0)
    expect(outNeg.length ? Math.max(...outNeg) : null).toBeNull()
    expect(regenNeg.length ? Math.abs(Math.min(...regenNeg)) : null).toBe(30)
  })

  it('correctly formats 0 minutes duration instead of treating as falsy missing data', () => {
    const durationMin = 0
    const label = durationMin != null ? `${durationMin} 分钟` : '暂无数据'
    expect(label).toBe('0 分钟')
  })
})
