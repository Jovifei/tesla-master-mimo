import { statusFieldSources, type CarStatus } from './types'
import type { WechatStorage } from './session'
import { restoreTpmsSnapshot } from '../domain/tpms'
import { canStoreCache } from './cache_budget'

const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
function key(user: string, origin: string, uid: string): string {
  return `matelink.status.v1.${[origin, user, uid].map(encode).join('.')}`
}
function project(value: unknown): CarStatus | null {
  if (!value || typeof value !== 'object') return null
  const row = value as Record<string, unknown>
  const n = (k: string, min: number, max: number): number | null =>
    typeof row[k] === 'number' && Number.isFinite(row[k]) && row[k] >= min && row[k] <= max ? row[k] as number : null
  const b = (k: string): boolean | null => typeof row[k] === 'boolean' ? row[k] as boolean : null
  const t = (k: string): string | null => typeof row[k] === 'string' && row[k].length <= 128 ? row[k] as string : null
  const shift = t('shiftState')?.trim().toUpperCase(), phases = n('chargerPhases', 0, 3), limitSoc = n('chargeLimitSoc', 0, 100)
  return {
    ...(row.tpms ? { tpms: restoreTpmsSnapshot(row.tpms) } : {}),
    state: t('state'), model: t('model'), trim: t('trim'), exteriorColor: t('exteriorColor'), wheelType: t('wheelType'),
    batteryLevel: n('batteryLevel', 0, 100), ratedRange: n('ratedRange', 0, 2000), odometer: n('odometer', 0, 10_000_000), location: null,
    idealRange: n('idealRange', 0, 2000), estimatedRange: n('estimatedRange', 0, 2000),
    locked: b('locked'), sentryMode: b('sentryMode'), windowsOpen: b('windowsOpen'), doorsOpen: b('doorsOpen'),
    trunkOpen: b('trunkOpen'), frunkOpen: b('frunkOpen'), isClimateOn: b('isClimateOn'), pluggedIn: b('pluggedIn'),
    isPreconditioning: b('isPreconditioning'),
    insideTemp: n('insideTemp', -100, 100), outsideTemp: n('outsideTemp', -100, 100), speed: n('speed', 0, 500),
    power: n('power', -2000, 2000), heading: n('heading', 0, 360), shiftState: shift && ['P', 'R', 'N', 'D'].includes(shift) ? shift : null, chargingState: t('chargingState'),
    chargeEnergyAdded: n('chargeEnergyAdded', 0, 2000), chargerPower: n('chargerPower', 0, 2000),
    chargerVoltage: n('chargerVoltage', 0, Infinity), chargerActualCurrent: n('chargerActualCurrent', -Infinity, Infinity),
    timeToFullChargeHours: n('timeToFullChargeHours', 0, Infinity), chargeLimitSoc: limitSoc !== null && Number.isInteger(limitSoc) ? limitSoc : null,
    chargerPhases: phases !== null && Number.isInteger(phases) ? phases : null,
    chargeCurrentRequest: n('chargeCurrentRequest', 0, Infinity), chargeCurrentRequestMax: n('chargeCurrentRequestMax', 0, Infinity), chargePortDoorOpen: b('chargePortDoorOpen'),
    observedAt: t('observedAt'), source: t('source'),
    fieldSources: statusFieldSources(row.fieldSources),
  }
}
export function readVehicleCache(storage: Pick<WechatStorage, 'getStorageSync'>, user: string, origin: string, uid: string): CarStatus | null {
  if (!user.trim() || !uid.trim()) return null
  const scope = key(user, origin, uid)
  try {
    const value = storage.getStorageSync(scope) as { schema?: number; scope?: string; status?: unknown } | null
    return value?.schema === 1 && value.scope === scope ? project(value.status) : null
  } catch { return null }
}
export function writeVehicleCache(storage: Pick<WechatStorage, 'setStorageSync'> & Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>, user: string, origin: string, uid: string, status: CarStatus): boolean {
  if (!user.trim() || !uid.trim()) return false
  const scope = key(user, origin, uid)
  try {
    const value = { schema: 1, scope, status: project(status), savedAt: new Date().toISOString() }
    if (storage.getStorageInfoSync && !canStoreCache(storage, scope, value)) return false
    storage.setStorageSync(scope, value); return true
  } catch { return false }
}
export function clearVehicleCachesForAccount(storage: Pick<WechatStorage, 'getStorageInfoSync' | 'removeStorageSync'> & Partial<Pick<WechatStorage, 'getStorageSync'>>, user: string, origin: string): void {
  if (!user.trim()) return
  const prefix = `matelink.status.v1.${encode(origin)}.${encode(user)}.`
  try {
    for (const item of storage.getStorageInfoSync?.().keys ?? []) {
      if (!item.startsWith(prefix)) continue
      const suffix = item.slice(prefix.length)
      // Current keys encode dots in the stable UID; old literal separators are ambiguous.
      let canonical = false
      try { canonical = Boolean(suffix) && !suffix.includes('.') && encode(decodeURIComponent(suffix)) === suffix } catch { /* Unknown encoding stays untouched. */ }
      if (!canonical) continue
      if (storage.getStorageSync) {
        const value = storage.getStorageSync(item) as { schema?: unknown; scope?: unknown } | undefined
        if (!value || value.schema !== 1 || value.scope !== item) continue
      }
      storage.removeStorageSync(item)
    }
  } catch { /* optional local cache */ }
}
