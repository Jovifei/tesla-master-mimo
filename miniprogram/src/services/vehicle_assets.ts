import type { VehicleVisualPlan } from '../domain/vehicle_visual'
import { vehicleVisualAssets } from '../domain/vehicle_visual_manifest'
import Taro from '@tarojs/taro'

export const VEHICLE_ASSET_LOAD_TIMEOUT_MS = 20_000
export const VEHICLE_COMPONENT_WAIT_MS = 3000
type NativeAssetComponent = { ensureAsset(assetKey: string): Promise<string>; clearAsset(assetKey?: string): void }
export class VehicleAssetError extends Error {
  constructor(readonly code: 'invalid_plan' | 'loader_unavailable' | 'load_failed' | 'stale_request') { super('车辆图片暂时无法读取，请重试'); this.name = 'VehicleAssetError' }
}
const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))
function nativeComponent(): NativeAssetComponent | null {
  const pages = Taro.getCurrentPages()
  const page = pages[pages.length - 1] as unknown as { selectComponent?: (selector: string) => unknown; $scope?: { selectComponent?: (selector: string) => unknown } } | undefined
  const host = page?.selectComponent ? page : page?.$scope
  const component = host?.selectComponent?.('#matelink-vehicle-asset') as NativeAssetComponent | null | undefined
  return component && typeof component.ensureAsset === 'function' && typeof component.clearAsset === 'function' ? component : null
}
/** Public local resources only. Native component preserves its own require.async scope. */
export async function ensureVehicleAsset(plan: VehicleVisualPlan, options: { isCurrent?: () => boolean } = {}): Promise<string> {
  const current = () => { try { return options.isCurrent?.() !== false } catch { return false } }
  if (!current()) throw new VehicleAssetError('stale_request')
  const entry = plan.assetKey && Object.prototype.hasOwnProperty.call(vehicleVisualAssets, plan.assetKey) ? vehicleVisualAssets[plan.assetKey] : null
  if (!entry || entry.assetPath !== plan.assetPath || entry.packageName !== plan.packageName || plan.quality === 'unknown') throw new VehicleAssetError('invalid_plan')
  const key = plan.assetKey!, started = Date.now()
  let component: NativeAssetComponent | null = null
  // Taro renders asynchronously; wait briefly for the always-mounted native host.
  if (typeof Taro.nextTick === 'function') await new Promise<void>(resolve => {
    let finished = false
    const complete = () => { if (!finished) { finished = true; clearTimeout(timer); resolve() } }
    const timer = setTimeout(complete, 30)
    try { Taro.nextTick(complete) } catch { complete() }
  })
  while (!component && Date.now() - started <= VEHICLE_COMPONENT_WAIT_MS) {
    if (!current()) throw new VehicleAssetError('stale_request')
    try { component = nativeComponent() } catch { /* host may still be applying native setData */ }
    if (!component) await delay(30)
  }
  if (!current()) throw new VehicleAssetError('stale_request')
  if (!component) throw new VehicleAssetError('loader_unavailable')
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const source = await Promise.race([
      component.ensureAsset(key),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new VehicleAssetError('load_failed')), VEHICLE_ASSET_LOAD_TIMEOUT_MS) }),
    ])
    if (!current()) throw new VehicleAssetError('stale_request')
    if (source !== entry.assetPath) throw new VehicleAssetError('load_failed')
    return source
  } catch (error) {
    if (!current() || error && typeof error === 'object' && 'code' in error && error.code === 'stale_request') throw new VehicleAssetError('stale_request')
    if (error instanceof VehicleAssetError) throw error
    throw new VehicleAssetError('load_failed')
  } finally { if (timer !== undefined) clearTimeout(timer) }
}
