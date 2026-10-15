import { expect, it } from 'vitest'
import { evaluateTpmsAlerts, validTpmsProfile, type TpmsProfile } from './tpms_alerts'
import { parseTpmsDetails, type TpmsPoint } from './tpms'
const now = Date.parse('2026-10-16T00:00:00Z')
const profile: TpmsProfile = { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: true, deltaBar: .3, windowHours: 24 }
const snapshot = (value = 2.2, time = now - 60_000) => parseTpmsDetails({ pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at', tpms_pressure_fl: value, tpms_pressure_fl_observed_at: new Date(time).toISOString() }, now)
const previous = (value: number, time = now - 3_600_000): TpmsPoint => ({ wheel: 'fl', pressureBar: value, observedAt: new Date(time).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' })
it('uses actual App profile and change bounds without model defaults', () => {
  expect(validTpmsProfile(profile)).toBe(true)
  for (const bad of [{ lowBar: 0 }, { targetBar: 2.6 }, { deltaBar: 0 }, { deltaBar: 2.01 }, { windowHours: 0 }, { windowHours: 720.1 }, { enabled: 1 }]) expect(validTpmsProfile({ ...profile, ...bad })).toBe(false)
  expect(validTpmsProfile({ ...profile, deltaBar: .1, windowHours: 720 })).toBe(true)
  expect(evaluateTpmsAlerts(null, snapshot(), [], now).warnings).toEqual([])
})
it('preserves real zero as low pressure but never fabricates a zero-based historical change', () => {
  const result = evaluateTpmsAlerts(profile, snapshot(0), [previous(2.8)], now)
  expect(result.warnings).toMatchObject([{ wheel: 'fl', kind: 'low', pressureBar: 0 }])
  expect(result.wheels[0].changed).toBe(false)
})
it('rejects stale future unqualified current observations and does not replace their times', () => {
  for (const time of [now - 16 * 60_000, now + 1]) expect(evaluateTpmsAlerts(profile, snapshot(2.2, time), [], now).warnings).toEqual([])
  const raw = snapshot(); raw.wheels.fl.source = 'other' as never
  expect(evaluateTpmsAlerts(profile, raw, [], now).warnings).toEqual([])
})
it('compares latest prior wheel-local qualified receipt within the actual window only', () => {
  const result = evaluateTpmsAlerts(profile, snapshot(3), [previous(2.5), previous(2.8, now - 2_000_000), { ...previous(1, now - 1_000_000), source: 'other' as never }, { ...previous(1), wheel: 'fr' }], now)
  expect(result.wheels[0]).toMatchObject({ beforeBar: 2.8, changed: false })
  expect(evaluateTpmsAlerts(profile, snapshot(3), [previous(2.7)], now).warnings).toMatchObject([{ kind: 'change', deltaBar: .2999999999999998 }])
  expect(evaluateTpmsAlerts(profile, snapshot(3), [previous(2.4, now - 25 * 3_600_000)], now).warnings).toEqual([])
  expect(evaluateTpmsAlerts(profile, snapshot(3), [previous(2), previous(2.8)], now).wheels[0].reason).toBe('conflicting_previous_observation')
})
it('disabled rules do not produce warnings', () => {
  expect(evaluateTpmsAlerts({ ...profile, enabled: false, changeEnabled: false }, snapshot(2), [previous(3)], now).warnings).toEqual([])
})
