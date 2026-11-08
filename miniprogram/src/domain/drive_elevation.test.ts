import { describe, expect, it } from 'vitest'
import { elevationStats } from './drive_elevation'

const points = (...elevations: (number | null)[]) => elevations.map(elevationM => ({ elevationM }))

describe('elevationStats (App DriveDetailMetrics parity)', () => {
  it('computes max/min and cumulative gain/loss over consecutive samples', () => {
    const stats = elevationStats(points(100, 130, 120, 180))
    expect(stats).toEqual({ max: 180, min: 100, gain: 90, loss: 10 })
  })

  it('skips pairs with a missing endpoint instead of bridging the gap', () => {
    const stats = elevationStats(points(100, null, 150, 140))
    expect(stats?.max).toBe(150)
    expect(stats?.min).toBe(100)
    // Only the 150 -> 140 pair is consecutive-valid: loss 10, gain 0.
    expect(stats?.gain).toBe(0)
    expect(stats?.loss).toBe(10)
  })

  it('returns null gain/loss for a single sample and null overall without elevation', () => {
    expect(elevationStats(points(88))).toEqual({ max: 88, min: 88, gain: null, loss: null })
    expect(elevationStats(points(null, null))).toBeNull()
    expect(elevationStats([])).toBeNull()
  })
})
