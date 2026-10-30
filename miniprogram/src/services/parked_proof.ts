import type { Car } from './types'
import { sameEnergyInstant } from '../domain/drive_energy'
import { analysisApi } from './analysis_api'
import type { ParkedDetail } from './analysis_types'

export type ParkedCandidate = { olderId: string; newerId: string; startDate: string; endDate: string; source: string }
export function parkedProofKey(candidate: ParkedCandidate): string {
  return JSON.stringify([candidate.olderId, candidate.newerId, candidate.startDate, candidate.endDate, candidate.source])
}
/** One verifier belongs to one account, vehicle, session generation and page lifetime. */
export function createParkedProofVerifier(car: Car, isCurrent: () => boolean, maxConcurrency = 4) {
  const limit = Number.isFinite(maxConcurrency) ? Math.max(1, Math.min(4, Math.floor(maxConcurrency))) : 4
  const confirmed = new Map<string, ParkedDetail>(), inflight = new Map<string, Promise<boolean>>()
  let active = 0
  const queue: (() => void)[] = []
  const current = () => { try { return isCurrent() } catch { return false } }
  const acquire = () => new Promise<void>(resolve => {
    if (active < limit) { active++; resolve() } else queue.push(resolve)
  })
  const release = () => { const next = queue.shift(); if (next) next(); else active-- }
  const check = (candidate: ParkedCandidate): Promise<boolean> => {
    const key = parkedProofKey(candidate)
    if (!current()) return Promise.resolve(false)
    if (confirmed.has(key)) return Promise.resolve(true)
    const existing = inflight.get(key)
    if (existing) return existing
    const pending = (async () => {
      await acquire()
      try {
        if (!current()) return false
        const { detail } = await analysisApi.getParkedDetail(car, candidate.olderId, candidate.newerId, { isCurrent: current })
        const valid = Boolean(current() && detail && detail.olderDriveId === candidate.olderId && detail.newerDriveId === candidate.newerId &&
          detail.source === candidate.source && sameEnergyInstant(detail.startDate, candidate.startDate) && sameEnergyInstant(detail.endDate, candidate.endDate))
        if (valid && detail) confirmed.set(key, detail)
        return valid
      } catch { return false } finally { release() }
    })()
    inflight.set(key, pending)
    void pending.finally(() => { if (inflight.get(key) === pending) inflight.delete(key) })
    return pending
  }
  return {
    get(candidate: ParkedCandidate): ParkedDetail | null { return current() ? confirmed.get(parkedProofKey(candidate)) ?? null : null },
    async verify(candidates: readonly ParkedCandidate[], onVerified?: (key: string) => void): Promise<Set<string>> {
      const results = await Promise.all(candidates.map(async candidate => {
        if (!await check(candidate) || !current()) return null
        const key = parkedProofKey(candidate)
        onVerified?.(key)
        return key
      }))
      return current() ? new Set(results.filter((key): key is string => key !== null)) : new Set()
    },
  }
}
