import { describe, expect, it } from 'vitest'
import { normalizeReadinessStatus, readinessStatusFor, readinessViewFor, toReadinessView } from './readiness'

describe('readiness presentation', () => {
  it('keeps unknown provider states fail-closed', () => {
    expect(normalizeReadinessStatus('future_provider_state')).toBe('unknown')
    expect(toReadinessView('future_provider_state').ready).toBe(false)
  })

  it('does not treat collection as ready before an available state', () => {
    expect(toReadinessView('collecting').ready).toBe(false)
    expect(toReadinessView('available').ready).toBe(true)
  })

  it('uses the telemetry item as the continuous collection readiness source', () => {
    const items = [
      { key: 'live_status', status: 'available', message: null, action: null },
      { key: 'telemetry', status: 'pairing_required', message: null, action: 'pair_tesla', source: 'telemetry_mqtt' },
    ]
    expect(readinessStatusFor(items)).toBe('pairing_required')
    expect(readinessViewFor(items).ready).toBe(false)
    expect(readinessViewFor(items).action).toBe('pair_tesla')
  })

  it.each(['waiting_vehicle', 'collecting', 'available', 'permission_required', 'telemetry_error', 'billing_blocked'])('retains telemetry state %s', status => {
    expect(normalizeReadinessStatus(status)).toBe(status)
  })
})
