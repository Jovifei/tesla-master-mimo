import { describe, expect, it } from 'vitest'
import { createRequestScopeGate, requestScopeKey } from './request_scope'

const scope = (overrides: Partial<Parameters<typeof requestScopeKey>[0]> = {}) => ({
  accountId: 'user-a',
  stableVehicleId: 'vehicle-a',
  apiOrigin: 'https://api.example.test',
  sessionGeneration: 1,
  ...overrides,
})

describe('request scope gate', () => {
  it('invalidates an old lane when the selected vehicle changes', () => {
    const gate = createRequestScopeGate()
    gate.bind(scope())
    const old = gate.begin('history')
    gate.bind(scope({ stableVehicleId: 'vehicle-b' }))
    expect(old.isCurrent()).toBe(false)
  })

  it('keeps list and detail lanes independent while making each lane latest wins', () => {
    const gate = createRequestScopeGate()
    gate.bind(scope())
    const list = gate.begin('history')
    const firstDetail = gate.begin('detail')
    const secondDetail = gate.begin('detail')
    expect(list.isCurrent()).toBe(true)
    expect(firstDetail.isCurrent()).toBe(false)
    expect(secondDetail.isCurrent()).toBe(true)
  })

  it('invalidates all lanes on hide or unload and rejects unsafe scope inputs', () => {
    const gate = createRequestScopeGate()
    gate.bind(scope())
    const pending = gate.begin('history')
    gate.invalidate()
    expect(pending.isCurrent()).toBe(false)
    expect(() => requestScopeKey(scope({ accountId: '' }))).toThrow('history_identity_unavailable')
  })
})
