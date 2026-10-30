import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Car } from './types'
import type { ParkedDetail } from './analysis_types'
import { createParkedProofVerifier, parkedProofKey, type ParkedCandidate } from './parked_proof'

const getParkedDetail = vi.hoisted(() => vi.fn())
vi.mock('./analysis_api', () => ({ analysisApi: { getParkedDetail } }))
const car = { id: 7, stableId: 'vehicle-7', remoteVerified: true } as Car
const candidate: ParkedCandidate = { olderId: '1', newerId: '2', startDate: '2026-10-01T00:00:00Z', endDate: '2026-10-01T01:00:00Z', source: 'teslamate_archive' }
const detail = (patch: Partial<ParkedDetail> = {}): ParkedDetail => ({ olderDriveId: '1', newerDriveId: '2',
  startDate: '2026-10-01T08:00:00+08:00', endDate: '2026-10-01T09:00:00+08:00', source: 'teslamate_archive', ...patch } as ParkedDetail)
const reply = (value: ParkedDetail | null) => ({ detail: value, meta: {} })

beforeEach(() => { getParkedDetail.mockReset() })
describe('exact server-backed parked window proof', () => {
  it('accepts matching identities, source and equivalent instants, then reuses only the confirmed result', async () => {
    getParkedDetail.mockResolvedValue(reply(detail()))
    const verifier = createParkedProofVerifier(car, () => true)
    expect(await verifier.verify([candidate])).toEqual(new Set([parkedProofKey(candidate)]))
    expect(await verifier.verify([candidate])).toEqual(new Set([parkedProofKey(candidate)]))
    expect(verifier.get(candidate)).toEqual(detail())
    expect(getParkedDetail).toHaveBeenCalledTimes(1)
  })
  it.each([
    ['missing', null], ['older ID', detail({ olderDriveId: '3' })], ['newer ID', detail({ newerDriveId: '4' })],
    ['start', detail({ startDate: '2026-10-01T00:00:01Z' })], ['end', detail({ endDate: '2026-10-01T01:00:01Z' })],
    ['source', detail({ source: 'fleet_api' })],
  ])('keeps %s proof unknown', async (_label, value) => {
    getParkedDetail.mockResolvedValue(reply(value))
    expect(await createParkedProofVerifier(car, () => true).verify([candidate])).toEqual(new Set())
  })
  it('retries transient errors instead of caching a failure as a permanent result', async () => {
    getParkedDetail.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(reply(detail()))
    const verifier = createParkedProofVerifier(car, () => true)
    expect(await verifier.verify([candidate])).toEqual(new Set())
    expect(await verifier.verify([candidate])).toEqual(new Set([parkedProofKey(candidate)]))
    expect(getParkedDetail).toHaveBeenCalledTimes(2)
  })
  it('rejects a response that arrives after the owning vehicle scope is invalidated', async () => {
    let release!: (value: unknown) => void
    let current = true
    getParkedDetail.mockImplementation(() => new Promise(resolve => { release = resolve }))
    const verifier = createParkedProofVerifier(car, () => current)
    const pending = verifier.verify([candidate])
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    current = false; release(reply(detail()))
    expect(await pending).toEqual(new Set())
    expect(verifier.get(candidate)).toBeNull()
  })
  it('publishes a verified pair without waiting for an unrelated slow request', async () => {
    let release!: (value: unknown) => void
    const second = { ...candidate, olderId: '3', newerId: '4' }
    getParkedDetail.mockResolvedValueOnce(reply(detail())).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const progress = vi.fn(), verifier = createParkedProofVerifier(car, () => true)
    const pending = verifier.verify([candidate, second], progress)
    await vi.waitFor(() => expect(progress).toHaveBeenCalledWith(parkedProofKey(candidate)), { timeout: 300 })
    release(reply(detail({ olderDriveId: '3', newerDriveId: '4' })))
    expect((await pending).size).toBe(2)
  })
  it('shares in-flight requests and never exceeds four concurrent endpoints', async () => {
    let active = 0, peak = 0
    const pending: (() => void)[] = []
    getParkedDetail.mockImplementation((_car, olderId: string, newerId: string) => new Promise(resolve => {
      active++; peak = Math.max(peak, active)
      pending.push(() => { active--; resolve(reply(detail({ olderDriveId: olderId, newerDriveId: newerId }))) })
    }))
    const candidates = Array.from({ length: 7 }, (_, index) => ({ ...candidate, olderId: String(index + 1), newerId: String(index + 2) }))
    const verifier = createParkedProofVerifier(car, () => true)
    const first = verifier.verify(candidates), second = verifier.verify(candidates)
    await vi.waitFor(() => expect(pending.length).toBe(4))
    while (pending.length) { pending.shift()!(); await Promise.resolve(); await Promise.resolve() }
    await vi.waitFor(() => expect(getParkedDetail).toHaveBeenCalledTimes(7))
    while (pending.length) { pending.shift()!(); await Promise.resolve(); await Promise.resolve() }
    expect((await first).size).toBe(7); expect((await second).size).toBe(7)
    expect(peak).toBeLessThanOrEqual(4)
  })
})
