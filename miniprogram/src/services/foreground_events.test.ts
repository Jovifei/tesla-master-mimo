import { describe, expect, it } from 'vitest'
import { inspectForegroundEvents, observeForegroundEvents } from './foreground_events'
import type { Drive } from './types'

const scope = { origin: 'https://api.example.test', userId: 'owner.a', stableId: 'vehicle.a' }
const now = Date.parse('2026-10-15T12:00:00Z')
function storage() {
  const values = new Map<string, unknown>()
  return { values, getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => { values.set(key, value) }, getStorageInfoSync: () => ({ keys: [...values.keys()] }) }
}
function row(id: string, endDate: string): Drive {
  return { id, sessionId: `session-${id}`, startDate: '2026-10-15T10:00:00Z', endDate, source: 'telemetry_mqtt', qualityState: 'observed' } as Drive
}
describe('durable foreground completion observations', () => {
  it('notifies the first natural completion after an accepted empty initial history', () => {
    const s = storage()
    expect(observeForegroundEvents(s, scope, 'drives', [], now)).toEqual([])
    expect(observeForegroundEvents(s, scope, 'drives', [row('first', '2026-10-15T12:01:00Z')], now + 120000).map(n => n.id)).toEqual(['first'])
  })
  it('same-ID legacy time corrections do not issue a second completion notice', () => {
    const s = storage()
    observeForegroundEvents(s, scope, 'drives', [], now)
    const fresh = { ...row('legacy', '2026-10-15T12:01:00Z'), sessionId: null }
    expect(observeForegroundEvents(s, scope, 'drives', [fresh], now + 120000)).toHaveLength(1)
    expect(observeForegroundEvents(s, scope, 'drives', [{ ...fresh, startDate: '2026-10-15T10:01:00Z', endDate: '2026-10-15T12:02:00Z' }], now + 180000)).toEqual([])
  })
  it.each(['local_import', 'snapshot_estimate', 'physical_model', 'inferred_soc_jump', 'arbitrary'])('does not promote unverified %s despite observed label', source => {
    const s = storage()
    observeForegroundEvents(s, scope, 'drives', [], now)
    expect(observeForegroundEvents(s, scope, 'drives', [{ ...row('bad', '2026-10-15T12:01:00Z'), source }], now + 120000)).toEqual([])
  })
  it('suppresses first import, old backfill and corrections, emits new completion once after reconstruction', () => {
    const s = storage(), old = row('old', '2026-10-15T11:00:00Z'), fresh = row('new', '2026-10-15T12:01:00Z')
    expect(observeForegroundEvents(s, scope, 'drives', [old], now)).toEqual([])
    const notices = observeForegroundEvents(s, scope, 'drives', [fresh, old], now + 120000)
    expect(notices.map(n => n.id)).toEqual(['new'])
    expect(observeForegroundEvents(s, scope, 'drives', [{ ...fresh, id: 'corrected', endDate: '2026-10-15T12:02:00Z' }], now + 180000)).toEqual([])
    expect(observeForegroundEvents(s, scope, 'drives', [fresh, row('backfill', '2026-10-15T10:59:00Z')], now + 180000)).toEqual([])
    expect([...s.values.keys()].join('')).not.toContain('owner.a')
  })
  it('isolates origin/account/vehicle/source and ignores unqualified, future and unfinished rows', () => {
    const s = storage(), old = row('old', '2026-10-15T11:00:00Z'), fresh = row('new', '2026-10-15T12:01:00Z')
    observeForegroundEvents(s, scope, 'drives', [old], now)
    expect(observeForegroundEvents(s, { ...scope, userId: 'owner.b' }, 'drives', [fresh], now + 120000)).toEqual([])
    expect(observeForegroundEvents(s, scope, 'drives', [{ ...fresh, source: 'archive' }], now + 120000)).toEqual([])
    expect(observeForegroundEvents(s, scope, 'drives', [{ ...fresh, qualityState: 'quarantined' }, { ...fresh, endDate: null }, { ...fresh, endDate: '2028-01-01T00:00:00Z' }], now)).toEqual([])
  })
  it('does not publish when durable dedup fails and never stores coordinates or VIN', () => {
    const s = storage(), old = row('old', '2026-10-15T11:00:00Z'), fresh = row('new', '2026-10-15T12:01:00Z')
    observeForegroundEvents(s, scope, 'drives', [old], now)
    const failing = { ...s, setStorageSync: () => { throw Error('full') } }
    expect(observeForegroundEvents(failing, scope, 'drives', [fresh], now + 120000)).toEqual([])
    expect(inspectForegroundEvents(failing, scope, 'drives')).toMatchObject({ limited: true, reason: 'storage_unavailable' })
    observeForegroundEvents(s, scope, 'drives', [{ ...fresh, vin: 'PRIVATE', latitude: 32 } as Drive], now + 120000)
    expect(JSON.stringify([...s.values.values()])).not.toMatch(/PRIVATE|latitude|vin/)
  })
})
