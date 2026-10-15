import type { Car } from './types'
import type { WechatStorage } from './session'
import { canStoreCache } from './cache_budget'

const prefix = 'matelink.cars.v1.'
const MAX_CARS = 100
const MAX_BYTES = 128_000
const encodeScope = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
function key(userId: string, origin: string): string | null {
  return userId.trim() && /^https:\/\/[a-z0-9.-]+(?::443)?\/?$/i.test(origin)
    ? `${prefix}${encodeScope(origin)}.${encodeScope(userId)}` : null
}
function project(value: unknown): Car | null {
  if (!value || typeof value !== 'object') return null
  const car = value as Car
  if (!Number.isSafeInteger(car.id) || car.id <= 0 || typeof car.stableId !== 'string' || !car.stableId.trim() || car.stableId.length > 256) return null
  const text = (v: unknown) => typeof v === 'string' ? v.slice(0, 256) : null
  return { id: car.id, stableId: car.stableId, name: text(car.name), displayName: text(car.displayName),
    model: text(car.model), trim: text(car.trim), exteriorColor: text(car.exteriorColor), wheelType: text(car.wheelType), remoteVerified: false,
    capacityKwh: typeof car.capacityKwh === 'number' && Number.isFinite(car.capacityKwh) && car.capacityKwh > 0 ? car.capacityKwh : null,
    capacityBasis: ['api_reported_current_capacity', 'provided_capacity', 'model_trim_assumption', 'model_assumption', 'default_assumption'].includes(car.capacityBasis ?? '') ? car.capacityBasis : undefined,
    capacitySourceField: ['battery_health.current_capacity', 'current_capacity'].includes(car.capacitySourceField ?? '') ? car.capacitySourceField : null,
    capacityCalculationVersion: car.capacityCalculationVersion === 1 ? 1 : undefined }
}
export function readCarsCache(storage: Pick<WechatStorage, 'getStorageSync'>, userId: string, origin: string): Car[] {
  const scope = key(userId, origin)
  if (!scope) return []
  try {
    const value = storage.getStorageSync(scope) as { scope?: string; cars?: unknown[]; schema?: number } | undefined
    if (value?.scope !== scope || value.schema !== 1 || !Array.isArray(value.cars) || value.cars.length > MAX_CARS ||
        JSON.stringify(value).length * 3 > MAX_BYTES) return []
    const cars = value.cars.map(project).filter((car): car is Car => car != null)
    return new Set(cars.map(car => car.stableId)).size === cars.length ? cars : []
  } catch { return [] }
}
export function writeCarsCache(storage: Pick<WechatStorage, 'setStorageSync'> & Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>, userId: string, origin: string, cars: readonly Car[]): boolean {
  const scope = key(userId, origin)
  if (!scope || cars.length > MAX_CARS) return false
  const projected = cars.map(project)
  if (projected.some(car => !car) || new Set(projected.map(car => car!.stableId)).size !== cars.length) return false
  const value = { schema: 1, scope, cars: projected, savedAt: new Date().toISOString() }
  if (JSON.stringify(value).length * 3 > MAX_BYTES) return false
  if (storage.getStorageInfoSync && !canStoreCache(storage, scope, value)) return false
  try { storage.setStorageSync(scope, value); return true } catch { return false }
}
export function clearCarsCacheForAccount(storage: Pick<WechatStorage, 'removeStorageSync'>, userId: string, origin: string): void {
  const scope = key(userId, origin)
  if (scope) { try { storage.removeStorageSync(scope) } catch { /* optional cache */ } }
}
