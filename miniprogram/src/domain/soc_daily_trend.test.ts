import { describe, expect, it } from 'vitest'
import { socDailyTrend } from './soc_daily_trend'

const NOW = Date.parse('2026-11-16T12:00:00+08:00')

function drive(start: string, end: string | null, startSoc: number | null, endSoc: number | null, extra: Record<string, unknown> = {}) {
  return { startDate: start, endDate: end, startBatteryLevel: startSoc, endBatteryLevel: endSoc, ...extra }
}

describe('socDailyTrend', () => {
  it('keeps the last recorded SOC of each day, sorted ascending', () => {
    const result = socDailyTrend([
      drive('2026-11-15T19:00:00+08:00', '2026-11-15T19:30:00+08:00', 52, 48),
      drive('2026-11-15T22:00:00+08:00', '2026-11-15T22:30:00+08:00', 48, 45),
      drive('2026-11-16T08:00:00+08:00', '2026-11-16T09:00:00+08:00', 45, 41),
    ], 7, NOW)
    expect(result.points.map(point => point.day)).toEqual(['2026-11-15', '2026-11-16'])
    expect(result.points.map(point => point.soc)).toEqual([45, 41])
    expect(result.change).toBe(-4)
    expect(result.latestSoc).toBe(41)
    expect(result.unavailableReason).toBeNull()
  })

  it('falls back to the start SOC when the ending level is missing', () => {
    const result = socDailyTrend([
      drive('2026-11-16T08:00:00+08:00', '2026-11-16T09:00:00+08:00', 63, null),
    ], 7, NOW)
    expect(result.points).toHaveLength(1)
    expect(result.points[0]!.soc).toBe(63)
    expect(result.change).toBeNull()
  })

  it('skips quarantined, implausible and out-of-window rows', () => {
    const result = socDailyTrend([
      drive('2026-11-16T08:00:00+08:00', '2026-11-16T09:00:00+08:00', 60, 55), // valid
      drive('2026-11-16T10:00:00+08:00', '2026-11-16T10:30:00+08:00', 55, 50, { qualityState: 'quarantined' }),
      drive('2026-11-16T10:00:00+08:00', '2026-11-16T10:30:00+08:00', 55, 101),
      drive('2026-10-01T08:00:00+08:00', '2026-10-01T09:00:00+08:00', 80, 70),
    ], 7, NOW)
    expect(result.points.map(point => point.soc)).toEqual([55])
    expect(result.drivesInWindow).toBe(2)
  })

  it('separates no-history from no-qualified-samples', () => {
    expect(socDailyTrend([], 7, NOW).unavailableReason).toBe('no_history_in_window')
    const noSoc = socDailyTrend([
      drive('2026-11-16T08:00:00+08:00', null, null, null),
    ], 7, NOW)
    expect(noSoc.unavailableReason).toBe('no_qualified_soc_samples')
    expect(noSoc.drivesInWindow).toBe(1)
  })

  it('accepts a 30-day window', () => {
    const result = socDailyTrend([
      drive('2026-10-25T08:00:00+08:00', '2026-10-25T09:00:00+08:00', 70, 66),
    ], 30, NOW)
    expect(result.points).toHaveLength(1)
    expect(result.points[0]!.soc).toBe(66)
  })
})