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
})
