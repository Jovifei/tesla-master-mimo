import { describe, expect, it } from 'vitest'
import { normalizeReadinessStatus, toReadinessView } from './readiness'

describe('readiness presentation', () => {
  it('keeps unknown provider states fail-closed', () => {
    expect(normalizeReadinessStatus('future_provider_state')).toBe('unknown')
    expect(toReadinessView('future_provider_state').ready).toBe(false)
  })

  it('does not treat collection as ready before an available state', () => {
    expect(toReadinessView('collecting').ready).toBe(false)
    expect(toReadinessView('available').ready).toBe(true)
  })
})
