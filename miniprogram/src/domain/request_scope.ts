/** A non-secret request scope; tokens must never be stored in logs or cache keys. */
export type RequestScope = Readonly<{
  accountId: string
  stableVehicleId: string
  apiOrigin: string
  sessionGeneration: number
}>

export function requestScopeKey(scope: RequestScope): string {
  if (!scope.accountId.trim() || !scope.stableVehicleId.trim() || !scope.apiOrigin.startsWith('https://') || (!Number.isSafeInteger(scope.sessionGeneration) || scope.sessionGeneration < 0)) {
    throw new Error('history_identity_unavailable')
  }
  return JSON.stringify([scope.apiOrigin, scope.accountId, scope.stableVehicleId, scope.sessionGeneration])
}

/**
 * Page lifecycle gate. Bind before reading cache, invalidate on hide/unload and
 * before changing vehicle/account, and check after every await (also in catch
 * and finally). Different lanes let detail requests supersede other details
 * without cancelling a list request. Same-lane requests are latest-wins.
 */
export function createRequestScopeGate() {
  let scopeKey: string | null = null
  let generation = 0
  const lanes = new Map<string, number>()
  return {
    bind(scope: RequestScope): boolean {
      const next = requestScopeKey(scope)
      if (next === scopeKey) return false
      scopeKey = next
      generation += 1
      lanes.clear()
      return true
    },
    invalidate(): void { scopeKey = null; generation += 1; lanes.clear() },
    begin(lane: string): { isCurrent(): boolean } {
      if (!scopeKey) throw new Error('history_identity_unavailable')
      const owner = scopeKey, epoch = generation
      const sequence = (lanes.get(lane) ?? 0) + 1
      lanes.set(lane, sequence)
      return { isCurrent: () => owner === scopeKey && epoch === generation && sequence === lanes.get(lane) }
    },
  }
}
