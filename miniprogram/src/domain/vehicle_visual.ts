import { vehicleVisualAssets } from './vehicle_visual_manifest'

export type VehicleVisualConfig = { model?: string | null; exteriorColor?: string | null; wheelType?: string | null; trim?: string | null; variant?: string | null; modelYear?: number | null }
export type VehicleVisualPlan = { assetKey: string | null; assetPath: string | null; packageName: string | null; alt: string; quality: 'matched' | 'fallback_wheel' | 'unknown'; reason: string | null }
const clean = (value: unknown): string | null => typeof value === 'string' && value.trim() && value.length <= 160 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() : null
const normalize = (value: unknown) => (clean(value) ?? '').toLowerCase().replace(/[\s_-]/g, '')
const paintNames: Record<string, string> = {
  black: 'PBSB', solidblack: 'PBSB', obsidianblack: 'PMBL', midnightsilver: 'PMNG', midnightsilvermetallic: 'PMNG', steelgrey: 'PMNG',
  silver: 'PMSS', silvermetallic: 'PMSS', white: 'PPSW', pearlwhite: 'PPSW', pearlwhitemulticoat: 'PPSW',
  deepblue: 'PPSB', deepbluemetallic: 'PPSB', blue: 'PPSB', red: 'PPMR', redmulticoat: 'PPMR',
  quicksilver: 'PN00', stealthgrey: 'PN01', stealthgray: 'PN01', midnightcherryred: 'PR00', ultrared: 'PR01',
  diamondblack: 'PX02', blackdiamond: 'PX02', glacierblue: 'PB01', marineblue: 'PB02',
}
const paintLabels: Record<string, string> = { PBSB: '黑色', PMBL: '曜石黑', PMNG: '午夜银', PMSS: '银色', PPSW: '珍珠白', PPSB: '深蓝', PPMR: '红色', PN00: '快银', PN01: '隐形灰', PR00: '午夜樱桃红', PR01: '烈焰红', PX02: '钻石黑', PB01: '冰川蓝', PB02: '海洋蓝' }
const defaults: Record<string, string> = { m3: 'W38B', m3h: 'W38A', m3hp: 'W30P', my: 'WY19B', myjs: 'WY18P', myj: 'WY19P', myjp: 'WY21A', ms: 'WT19', mx: 'WT20' }
type WheelRule = readonly [string, string, boolean?]
const wheelRules: Record<string, WheelRule[]> = {
  m3: [['pinwheel18', 'W38B'], ['aero18', 'W38B'], ['aeroturbine19', 'W39B'], ['stiletto19', 'W39B'], ['sport19', 'W39B'], ['uberturbine20', 'W32D'], ['performance20', 'W32P'], ['19', 'W39B', true], ['20', 'W32P', true], ['18', 'W38B', true]],
  m3h: [['nova19', 'W38A', true], ['helix19', 'W38A', true], ['photon18', 'W38A'], ['glider18', 'W38A'], ['nova18', 'W38A'], ['19', 'W38A', true], ['18', 'W38A', true]],
  m3hp: [['performance20', 'W30P'], ['20', 'W30P', true]],
  my: [['gemini19', 'WY19B'], ['pinwheel18', 'WY18B'], ['aero18', 'WY18B'], ['aeroturbine19', 'WY19B', true], ['stiletto19', 'WY19B', true], ['sport19', 'WY19B', true], ['apollo19', 'WY19B', true], ['induction20', 'WY20P', true], ['performance20', 'WY20P'], ['uberturbine21', 'WY20P', true], ['21', 'WY20P', true], ['20', 'WY20P', true], ['19', 'WY19B', true], ['18', 'WY18B', true]],
  myj: [['helix20', 'WY20A'], ['crossflow19', 'WY19P'], ['20', 'WY20A', true], ['19', 'WY19P', true]],
  myjs: [['photon18', 'WY18P'], ['crossflow19', 'WY19P'], ['19', 'WY19P', true], ['18', 'WY18P', true]],
  myjp: [['uberturbine21', 'WY21A'], ['arachnid21', 'WY21A', true], ['21', 'WY21A', true]],
  ms: [['tempest19', 'WT19'], ['19', 'WT19', true]],
  mx: [['turbine22', 'WT22'], ['cyberstream20', 'WT20'], ['slipstream20', 'WT20', true], ['22', 'WT22', true], ['20', 'WT20', true]],
}
const newPaint = new Set(['PN00', 'PN01', 'PR01', 'PX02', 'PB01', 'PB02'])
function inferredVariant(model: string, paint: string, wheel: string, trim: string): string {
  const performance = trim.startsWith('p') || trim.includes('performance')
  if (model === '3') {
    if (['w38b', 'w39b', 'w32d', 'w32p', 'aero18', 'pinwheel18', 'sport19', 'stiletto19', 'uberturbine20'].some(prefix => wheel.startsWith(prefix))) return 'm3'
    const highland = newPaint.has(paint) || ['photon18', 'glider18', 'nova18', 'nova19', 'helix19', 'w38a', 'w30p'].some(prefix => wheel.startsWith(prefix))
    return highland && (performance || wheel === 'w30p') ? 'm3hp' : highland ? 'm3h' : 'm3'
  }
  if (model === 'Y') {
    // Paints such as Quicksilver and Stealth Grey also exist on pre-refresh cars.
    // A known legacy wheel must not be overridden by a paint-only refresh guess.
    if (['wy19b', 'wy18b', 'wy20p', 'gemini19', 'induction20', 'pinwheel18', 'aero18', 'uberturbine21'].some(prefix => wheel.startsWith(prefix))) return 'my'
    const modernWheel = ['photon18', 'wy18p', 'crossflow19', 'wy19p', 'helix20', 'wy20a', 'wy21a'].some(prefix => wheel.startsWith(prefix))
    const perfWheel = ['uberturbine21', 'arachnid21', '21', 'wy21a'].some(prefix => wheel.startsWith(prefix))
    if ((newPaint.has(paint) || modernWheel || perfWheel) && (performance || perfWheel || paint === 'PB02')) return 'myjp'
    if ((newPaint.has(paint) || modernWheel) && (trim === '50' || wheel.startsWith('photon18') || wheel === 'wy18p')) return 'myjs'
    return newPaint.has(paint) || modernWheel ? 'myj' : 'my'
  }
  return model === 'S' ? 'ms' : 'mx'
}
/** Callers must bind car/status to the same authenticated origin+vehicle scope first. */
export function mergeVehicleVisualConfig(car?: VehicleVisualConfig | null, status?: VehicleVisualConfig | null): VehicleVisualConfig {
  const merged: VehicleVisualConfig = {}
  for (const field of ['model', 'exteriorColor', 'wheelType', 'trim', 'variant'] as const) {
    const value = clean(status?.[field]) ?? clean(car?.[field])
    if (value != null) merged[field] = value
  }
  const validYear = (year: unknown): year is number => typeof year === 'number' && Number.isInteger(year) && year >= 1980 && year <= 2100
  if (validYear(status?.modelYear)) merged.modelYear = status.modelYear
  else if (validYear(car?.modelYear)) merged.modelYear = car.modelYear
  return merged
}
export function resolveVehicleVisual(config: VehicleVisualConfig): VehicleVisualPlan {
  const model = (clean(config.model) ?? '').toUpperCase().replace(/^MODEL[\s_-]*/, '')
  const unknown = (reason: string): VehicleVisualPlan => ({ assetKey: null, assetPath: null, packageName: null, alt: '车辆配置尚未匹配 · 中性示意', quality: 'unknown', reason })
  if (!['3', 'Y', 'S', 'X'].includes(model)) return unknown('model_unknown')
  const color = clean(config.exteriorColor)
  if (!color) return unknown('paint_unknown')
  const rawCode = color.toUpperCase(), alias = normalize(color)
  const paint = Object.prototype.hasOwnProperty.call(paintLabels, rawCode) ? rawCode : Object.prototype.hasOwnProperty.call(paintNames, alias) ? paintNames[alias] : undefined
  if (!paint) return unknown('paint_unknown')
  const wheel = normalize(config.wheelType), trim = normalize(config.trim)
  const requestedVariant = clean(config.variant)?.toLowerCase()
  const aliases: Record<string, string> = { juniper: 'myj', 'juniper-standard': 'myjs', 'juniper-performance': 'myjp', highland: 'm3h', 'highland-performance': 'm3hp', legacy: model === '3' ? 'm3' : model === 'Y' ? 'my' : model === 'S' ? 'ms' : 'mx' }
  const explicit = requestedVariant ? Object.prototype.hasOwnProperty.call(aliases, requestedVariant) ? aliases[requestedVariant] : requestedVariant : undefined
  const variant = explicit ?? inferredVariant(model, paint, wheel, trim)
  const base = variant.startsWith('m3') ? '3' : variant.startsWith('my') ? 'Y' : variant === 'ms' ? 'S' : variant === 'mx' ? 'X' : null
  if (base !== model || !Object.prototype.hasOwnProperty.call(defaults, variant)) return unknown('variant_unknown')
  const directWheel = (clean(config.wheelType) ?? '').toUpperCase()
  let wheelCode = directWheel, fallback = false
  if (!Object.prototype.hasOwnProperty.call(vehicleVisualAssets, `${variant}_${paint}_${wheelCode}`)) {
    const rule = wheelRules[variant].find(([prefix]) => wheel.startsWith(prefix))
    wheelCode = rule?.[1] ?? defaults[variant]; fallback = !rule || rule[2] === true
  }
  const assetKey = `${variant}_${paint}_${wheelCode}`, asset = vehicleVisualAssets[assetKey]
  // A known paint that has no original resource stays unknown; never exchange it for PPSW.
  if (!asset) return unknown('paint_asset_unavailable')
  return { assetKey, ...asset, alt: `Model ${model} · ${paintLabels[paint]} · ${wheelCode}${fallback ? ' 轮毂示意' : ''}`, quality: fallback ? 'fallback_wheel' : 'matched', reason: fallback ? 'wheel_asset_fallback' : null }
}
