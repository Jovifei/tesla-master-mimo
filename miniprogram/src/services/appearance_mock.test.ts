import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ values: new Map<string, unknown>(), read: vi.fn(), write: vi.fn() }))
vi.mock('@tarojs/taro', () => ({ default: {
  getStorageSync: (key: string) => { platform.read(key); return platform.values.get(key) },
  setStorageSync: (key: string, value: unknown) => { platform.write(key, value); platform.values.set(key, value) },
} }))

beforeEach(() => { vi.resetModules(); platform.values.clear(); platform.read.mockReset(); platform.write.mockReset() })
afterEach(() => vi.unstubAllEnvs())

it('fixture appearance persists independently without reading or writing the real preference', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'true')
  const realKey = 'matelink.presentation.v1'
  const realValue = { palette: 'cream', reducedMotion: false }
  platform.values.set(realKey, realValue)
  const appearance = await import('./appearance')
  expect(appearance.readAppearance()).toEqual({ palette: 'neutral', reducedMotion: false, dayAxis: 'vertical' })
  expect(appearance.writeAppearance({ palette: 'cream', reducedMotion: true })).toBe(true)
  expect(appearance.readAppearance()).toEqual({ palette: 'cream', reducedMotion: true, dayAxis: 'vertical' })
  expect(platform.values.get(realKey)).toEqual(realValue)
  expect(platform.read).not.toHaveBeenCalledWith(realKey)
  expect(platform.write.mock.calls.some(([key]) => key === realKey)).toBe(false)
  vi.resetModules()
  expect((await import('./appearance')).readAppearance()).toEqual({ palette: 'cream', reducedMotion: true, dayAxis: 'vertical' })
  expect(platform.values.get(realKey)).toEqual(realValue)
})

it('real appearance continues reading and writing the original preference', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false')
  const appearance = await import('./appearance')
  platform.values.set(appearance.APPEARANCE_KEY, { palette: 'neutral', reducedMotion: false })
  expect(appearance.readAppearance()).toEqual({ palette: 'neutral', reducedMotion: false, dayAxis: 'vertical' })
  expect(appearance.writeAppearance({ palette: 'cream' })).toBe(true)
  expect(platform.values.get(appearance.APPEARANCE_KEY)).toEqual({ palette: 'cream', reducedMotion: false, dayAxis: 'vertical' })
  expect(platform.read).toHaveBeenCalledWith(appearance.APPEARANCE_KEY)
  expect(platform.write).toHaveBeenCalledWith(appearance.APPEARANCE_KEY, { palette: 'cream', reducedMotion: false, dayAxis: 'vertical' })
})
