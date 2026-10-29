import { describe, expect, it } from 'vitest'
import { parseTpmsDetails, parseTpmsReport, restoreTpmsSnapshot, TPMS_RETENTION_MS, tpmsInstant, tpmsPoints } from './tpms'

const now = Date.parse('2026-10-15T02:00:00Z')
const raw = { pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
  tpms_pressure_fl: 0, tpms_pressure_fl_observed_at: '2026-10-15T01:59:00Z',
  tpms_pressure_fr: 2.9, tpms_pressure_fr_observed_at: '2026-10-14T01:00:00Z',
  tpms_pressure_rl: 3.1, tpms_pressure_rr: 2.8, tpms_pressure_rr_observed_at: '2026-10-15T01:58:00Z' }
describe('per-wheel TPMS collector qualification', () => {
  it('keeps genuine zero, different wheel times and stale labels without substituting global report time', () => {
    const snapshot = parseTpmsDetails({ ...raw, observed_at: '2026-10-15T02:00:00Z' }, now)
    expect(snapshot.wheels.fl).toMatchObject({ pressureBar: 0, state: 'observed', observedAt: '2026-10-15T01:59:00.000Z' })
    expect(snapshot.wheels.fr).toMatchObject({ pressureBar: 2.9, state: 'stale', observedAt: '2026-10-14T01:00:00.000Z' })
    expect(snapshot.wheels.rl).toMatchObject({ pressureBar: null, state: 'unknown', reason: 'missing_or_invalid_wheel_time' })
    expect(tpmsPoints(snapshot, now)).toHaveLength(3)
  })
  it.each([
    { pressure_unit: 'psi' }, { source: 'fleet_api' }, { time_basis: 'poll_time' },
    { tpms_pressure_fl: -1 }, { tpms_pressure_fl: 15.1 }, { tpms_pressure_fl: Number.NaN },
    { tpms_pressure_fl_observed_at: '2026-10-15T02:00:01Z' },
    { tpms_pressure_fl_observed_at: new Date(now - TPMS_RETENTION_MS - 1).toISOString() },
    { tpms_pressure_fl_observed_at: '2026-10-15T01:59:00' },
  ])('rejects unqualified evidence %j', change => {
    expect(parseTpmsDetails({ ...raw, ...change }, now).wheels.fl.pressureBar).toBeNull()
  })
  it('reopens with original receipt time and rechecks age rather than refreshing qualification', () => {
    const snapshot = parseTpmsDetails(raw, now)
    const reopened = restoreTpmsSnapshot(snapshot, now + 60 * 60 * 1000)
    expect(reopened.wheels.fl.observedAt).toBe(snapshot.wheels.fl.observedAt)
    expect(reopened.wheels.fl.state).toBe('stale')
    expect(restoreTpmsSnapshot(snapshot, now + TPMS_RETENTION_MS).wheels.fl.state).toBe('unknown')
    expect(restoreTpmsSnapshot(parseTpmsDetails({ ...raw, pressure_unit: null }, now), now).wheels.fl.reason).toBe('missing_or_unverified_unit')
  })
  it('requires explicit valid calendar time and normalizes offsets', () => {
    expect(tpmsInstant('2026-02-30T00:00:00Z')).toBeNull()
    expect(tpmsInstant('2026-10-15T24:00:00Z')).toBeNull()
    expect(tpmsInstant('2026-10-15T09:59:00+08:00')).toBe(tpmsInstant('2026-10-15T01:59:00Z'))
    expect(tpmsInstant('2026-10-15T01:59:00.123456789Z')).toBe(now - 60_000 + 123)
  })
  it('separates a bar-valued report from mixed per-wheel alert evidence', () => {
    const mixed = { pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
      tpms_pressure_fl: 2.6, tpms_pressure_fl_observed_at: '2026-10-15T01:59:00Z',
      tpms_pressure_fr: 0, tpms_pressure_rl: 17 }
    expect(parseTpmsReport(mixed, 'bar')).toEqual({ fl: 2.6, fr: 0, rl: null, rr: null })
    expect(parseTpmsReport({ ...mixed, pressure_unit: null }, null).fl).toBeNull()
    expect(parseTpmsReport({ ...mixed, pressure_unit: 'psi' }, 'bar').fl).toBeNull()
    const qualified = parseTpmsDetails(mixed, now)
    expect(qualified.wheels.fl.pressureBar).toBe(2.6)
    expect(qualified.wheels.fr).toMatchObject({ pressureBar: null, reason: 'missing_or_invalid_wheel_time' })
    expect(tpmsPoints(qualified, now).map(point => point.wheel)).toEqual(['fl'])
  })
})
