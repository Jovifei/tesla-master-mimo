import { expect, it } from 'vitest'
import { mergeVehicleVisualConfig, resolveVehicleVisual } from './vehicle_visual'
import { APPEARANCE_VARIANTS } from './vehicle_appearance_catalogue'
import { vehicleVisualAssets } from './vehicle_visual_manifest'

it('offers every original body, colour and wheel combination through manual selection', () => {
  const selected = new Set<string>()
  for (const variant of APPEARANCE_VARIANTS) for (const paint of variant.paints) for (const wheel of variant.wheels) {
    const plan = resolveVehicleVisual({ model: variant.model, variant: variant.code, exteriorColor: paint, wheelType: wheel, modelYear: 2026 })
    expect(plan.quality).toBe('matched')
    expect(plan.assetKey).toBe(`${variant.code}_${paint}_${wheel}`)
    selected.add(plan.assetKey!)
  }
  expect([...selected].sort()).toEqual(Object.keys(vehicleVisualAssets).sort())
  expect(selected.size).toBe(97)
})

it('does not turn a legacy-wheel car into a refreshed car merely because of a shared paint colour', () => {
  expect(resolveVehicleVisual({ model: 'Y', exteriorColor: 'PN00', wheelType: 'Gemini19', modelYear: 2025 }).assetKey).toBeNull()
  expect(resolveVehicleVisual({ model: 'Y', exteriorColor: 'PN01', wheelType: 'WY19B', modelYear: 2026 }).assetKey).toBeNull()
})
it.each([
  [{ model: 'Y', exteriorColor: 'MidnightSilver', wheelType: 'Gemini19' }, 'my_PMNG_WY19B'],
  [{ model: 'Y', exteriorColor: 'PBSB', wheelType: 'WY19B' }, 'my_PBSB_WY19B'],
  [{ model: 'Model 3', exteriorColor: 'Deep Blue Metallic', wheelType: 'Pinwheel18CapKit' }, 'm3_PPSB_W38B'],
  [{ model: 'Y', exteriorColor: 'DiamondBlack', wheelType: 'Crossflow19', trim: '74D' }, 'myj_PX02_WY19P'],
  [{ model: 'Y', exteriorColor: 'PN01', wheelType: 'Photon18', trim: '50' }, 'myjs_PN01_WY18P'],
  [{ model: 'Y', exteriorColor: 'PB02', wheelType: 'WY21A', trim: 'P74D' }, 'myjp_PB02_WY21A'],
  [{ model: '3', exteriorColor: 'UltraRed', wheelType: 'Performance20', trim: 'Performance' }, 'm3hp_PR01_W30P'],
  [{ model: '3', exteriorColor: 'Stealth Grey', wheelType: 'W38A' }, 'm3h_PN01_W38A'],
  [{ model: 'S', exteriorColor: 'Red', wheelType: 'Tempest19' }, 'ms_PPMR_WT19'],
  [{ model: 'X', exteriorColor: 'PPSB', wheelType: 'Turbine22' }, 'mx_PPSB_WT22'],
])('maps actual model/paint/wheel contract %j to %s rather than fixed white', (config, key) => {
  const plan = resolveVehicleVisual(config)
  expect(plan.assetKey).toBe(key); expect(plan.assetPath).toMatch(new RegExp(`/images/${key}\\.png$`))
  expect(plan.packageName).toMatch(/^vehicle-assets-\d+$/); expect(plan.quality).not.toBe('unknown')
})
it('uses wheel variants instead of one image per model, while labelling an unavailable wheel fallback', () => {
  const first = resolveVehicleVisual({ model: 'Y', exteriorColor: 'PPSB', wheelType: 'Gemini19' })
  const second = resolveVehicleVisual({ model: 'Y', exteriorColor: 'PPSB', wheelType: 'Induction20' })
  expect(first.assetKey).toBe('my_PPSB_WY19B'); expect(second.assetKey).toBe('my_PPSB_WY20P')
  expect(second.quality).toBe('fallback_wheel')
})
it.each([{ model: 'Y' }, { model: 'Y', exteriorColor: 'unverified-pink' }, { model: 'Y', exteriorColor: 'MidnightCherryRed' },
  { model: 'Cybertruck', exteriorColor: 'PBSB' }, { model: 'Y', exteriorColor: 'PN00', variant: 'myjs' },
  { model: '3', exteriorColor: 'PBSB', variant: 'my' }])('does not replace missing/unknown/unavailable paint or wrong variant with white: %j', config => {
  const plan = resolveVehicleVisual(config)
  expect(plan.assetPath).toBeNull(); expect(plan.quality).toBe('unknown')
})
it('merges status-only offline configuration without erasing existing car fields or mutating either source', () => {
  const car = { model: 'Y', exteriorColor: 'PBSB', wheelType: 'Gemini19', trim: '74D' }
  const merged = mergeVehicleVisualConfig(car, { model: null, exteriorColor: 'MidnightSilver', wheelType: null })
  expect(merged).toEqual({ model: 'Y', exteriorColor: 'MidnightSilver', wheelType: 'Gemini19', trim: '74D' })
  expect(resolveVehicleVisual(merged).assetKey).toBe('my_PMNG_WY19B'); expect(car.exteriorColor).toBe('PBSB')
  expect(resolveVehicleVisual(mergeVehicleVisualConfig({ model: 'Y' }, { exteriorColor: 'PBSB', wheelType: 'Gemini19' })).assetKey).toBe('my_PBSB_WY19B')
})
it.each(Object.keys(vehicleVisualAssets))('resolves committed original asset combination %s exactly', assetKey => {
  const [variant, exteriorColor, wheelType] = assetKey.split('_')
  const model = variant.startsWith('m3') ? '3' : variant.startsWith('my') ? 'Y' : variant === 'ms' ? 'S' : 'X'
  expect(resolveVehicleVisual({ model, variant, exteriorColor, wheelType })).toMatchObject({ assetKey, ...vehicleVisualAssets[assetKey], quality: 'matched' })
})
it('keeps model year independent from generation and supports explicit Juniper default without a white substitution', () => {
  expect(resolveVehicleVisual({ model: 'Y', modelYear: 2025, exteriorColor: 'PPSB', wheelType: 'Gemini19' }).assetKey).toBe('my_PPSB_WY19B')
  expect(resolveVehicleVisual({ model: 'Y', modelYear: 2020, variant: 'juniper', exteriorColor: 'PX02', wheelType: 'WY19P' }).assetKey).toBe('myj_PX02_WY19P')
  expect(mergeVehicleVisualConfig({ modelYear: 2024 }, { modelYear: NaN }).modelYear).toBe(2024)
  expect(resolveVehicleVisual({ model: 'Y', variant: 'constructor', exteriorColor: 'PBSB' }).quality).toBe('unknown')
  expect(resolveVehicleVisual({ model: 'Y', exteriorColor: 'constructor' }).quality).toBe('unknown')
})
