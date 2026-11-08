import { describe, expect, it } from 'vitest'
import { temperatureTrendPoints, type TemperatureTrendDrive } from './temperature_trend'

const NOW = Date.parse('2026-11-08T12:00:00Z')
const drive = (patch: Partial<TemperatureTrendDrive>): TemperatureTrendDrive => ({
  startDate: '2026-11-05T08:00:00Z',
  outsideTempC: 12,
  source: 'telemetry_mqtt',
  qualityState: 'observed',
  ...patch,
})

describe('temperatureTrendPoints (App TemperatureTrendViewModel parity)', () => {
  it('takes one point per qualified drive at its start instant, sorted by time', () => {
    const result = temperatureTrendPoints(
      [drive({ startDate: '2026-11-06T08:00:00Z', outsideTempC: 9 }), drive({})],
      7,
      NOW
    )
    expect(result.points).toEqual([
      { date: '2026-11-05T08:00:00Z', outsideTempC: 12 },
      { date: '2026-11-06T08:00:00Z', outsideTempC: 9 },
    ])
    expect(result.unavailableReason).toBeNull()
  })

  it('excludes drives outside the window, quarantined rows and unknown sources', () => {
    const result = temperatureTrendPoints(
      [
        drive({ startDate: '2026-10-20T08:00:00Z' }),
        drive({ qualityState: 'quarantined' }),
        drive({ source: 'manual_guess' }),
        drive({ outsideTempC: null }),
      ],
      7,
      NOW
    )
    expect(result.points).toEqual([])
    expect(result.drivesInWindow).toBe(2)
    expect(result.unavailableReason).toBe('no_qualified_trip_temperatures')
  })

  it('reports no_history_in_window when the window has no drives at all', () => {
    const result = temperatureTrendPoints([drive({ startDate: '2026-09-01T08:00:00Z' })], 30, NOW)
    expect(result.drivesInWindow).toBe(0)
    expect(result.unavailableReason).toBe('no_history_in_window')
  })
})
