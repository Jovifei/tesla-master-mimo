import { resolveVehicleVisual, type VehicleVisualConfig } from '../domain/vehicle_visual'
import { cacheBytes, canStoreCache } from './cache_budget'
import type { WechatStorage } from './session'

export type VehicleAppearanceScope = { origin: string; userId: string | null; stableId: string | null }
export const DEFAULT_VEHICLE_APPEARANCE: Readonly<VehicleVisualConfig> = Object.freeze({ model: 'Y', variant: 'myj', exteriorColor: 'PX02', wheelType: 'WY19P' })
export const VEHICLE_APPEARANCE_MAX_BYTES = 4096
type WriteStorage = Pick<WechatStorage, 'getStorageSync' | 'setStorageSync' | 'getStorageInfoSync'>
const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
const text = (value: unknown): string | null => typeof value === 'string' && value.trim() && value.length <= 160 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() : null
const identity = (value: unknown): value is string => typeof value === 'string' && Boolean(value.trim()) && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value)

export function vehicleAppearanceKey(scope: VehicleAppearanceScope): string | null {
  if (!scope || typeof scope.origin !== 'string' || !scope.origin.startsWith('https://')) return null
  const authority = scope.origin.slice(8), hostname = authority.replace(/:443$/, '')
  if (!authority || hostname.length > 253 || !hostname.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))) return null
  if (scope.userId === null && scope.stableId === null) return `matelink.vehicle-appearance.preview.v1.${encode(scope.origin)}`
  if (!identity(scope.userId) || !identity(scope.stableId)) return null
  return `matelink.vehicle-appearance.v1.${[scope.origin, scope.userId, scope.stableId].map(encode).join('.')}`
}

/** Manual selections require an explicit generation and a real asset; year never infers generation. */
function project(config: unknown): VehicleVisualConfig | null {
  if (!config || typeof config !== 'object' || Array.isArray(config)) return null
  const raw = config as VehicleVisualConfig
  const model = text(raw.model), variant = text(raw.variant), paint = text(raw.exteriorColor), wheel = text(raw.wheelType)
  if (!model || !variant || !paint || !wheel) return null
  if (raw.modelYear != null && (typeof raw.modelYear !== 'number' || !Number.isInteger(raw.modelYear) || raw.modelYear < 2008 || raw.modelYear > 2100)) return null
  const trim = raw.trim == null ? null : text(raw.trim)
  if (raw.trim != null && !trim) return null
  const plan = resolveVehicleVisual({ model, variant, exteriorColor: paint, wheelType: wheel, ...(trim ? { trim } : {}) })
  if (!plan.assetKey || plan.quality !== 'matched') return null
  const [canonicalVariant, exteriorColor, wheelType] = plan.assetKey.split('_')
  const canonicalModel = canonicalVariant.startsWith('m3') ? '3' : canonicalVariant.startsWith('my') ? 'Y' : canonicalVariant === 'ms' ? 'S' : canonicalVariant === 'mx' ? 'X' : null
  if (!canonicalModel) return null
  return { model: canonicalModel, variant: canonicalVariant, exteriorColor, wheelType,
    ...(trim ? { trim } : {}), ...(raw.modelYear != null ? { modelYear: raw.modelYear } : {}) }
}
export function validVehicleAppearance(config: unknown): config is VehicleVisualConfig {
  try { return project(config) !== null } catch { return false }
}
function decode(value: unknown, key: string): VehicleVisualConfig | null {
  if (!value || typeof value !== 'object' || Array.isArray(value) || cacheBytes(value) > VEHICLE_APPEARANCE_MAX_BYTES) return null
  const row = value as { schema?: unknown; scope?: unknown; source?: unknown; config?: unknown }
  return row.schema === 1 && row.scope === key && row.source === 'user_configured' ? project(row.config) : null
}

export function readVehicleAppearance(storage: Pick<WechatStorage, 'getStorageSync'>, scope: VehicleAppearanceScope): VehicleVisualConfig | null {
  const key = vehicleAppearanceKey(scope)
  if (!key) return null
  try { return decode(storage.getStorageSync(key), key) } catch { return null }
}
export function writeVehicleAppearance(storage: WriteStorage, scope: VehicleAppearanceScope, config: VehicleVisualConfig): boolean {
  const key = vehicleAppearanceKey(scope)
  if (!key) return false
  try {
    const visual = project(config)
    if (!visual) return false
    const previous = storage.getStorageSync(key)
    if (previous != null && previous !== '' && !decode(previous, key)) return false
    const value = { schema: 1, scope: key, source: 'user_configured', config: visual }
    if (cacheBytes(value) > VEHICLE_APPEARANCE_MAX_BYTES || !canStoreCache(storage, key, value)) return false
    storage.setStorageSync(key, value)
    return true
  } catch { return false }
}
export function clearVehicleAppearance(storage: Pick<WechatStorage, 'getStorageSync' | 'removeStorageSync'>, scope: VehicleAppearanceScope): boolean {
  const key = vehicleAppearanceKey(scope)
  if (!key) return false
  try {
    const previous = storage.getStorageSync(key)
    if (previous == null || previous === '') return true
    if (!decode(previous, key)) return false
    storage.removeStorageSync(key)
    return true
  } catch { return false }
}
