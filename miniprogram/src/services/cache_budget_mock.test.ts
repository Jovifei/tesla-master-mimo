import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => vi.unstubAllEnvs())

it.each([true, false])('fixture=%s controls cache admission before storage enumeration', async fixture => {
  vi.resetModules()
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', fixture ? 'true' : 'false')
  const { canStoreCache } = await import('./cache_budget')
  const read = vi.fn(() => undefined)
  const enumerate = vi.fn(() => ({ keys: [] as string[] }))
  expect(canStoreCache({ getStorageSync: read, getStorageInfoSync: enumerate }, 'matelink.history.v3.synthetic', { rows: [] })).toBe(!fixture)
  if (fixture) {
    expect(read).not.toHaveBeenCalled()
    expect(enumerate).not.toHaveBeenCalled()
  } else {
    expect(read).toHaveBeenCalled()
    expect(enumerate).toHaveBeenCalled()
  }
})

it.each([true, false])('fixture=%s controls storage-usage inspection before storage enumeration', async fixture => {
  vi.resetModules()
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', fixture ? 'true' : 'false')
  const { localStorageUsage } = await import('./cache_budget')
  const read = vi.fn(() => ({ palette: 'cream' }))
  const enumerate = vi.fn(() => ({ keys: ['matelink.presentation.v1'] }))
  const usage = localStorageUsage({ getStorageSync: read, getStorageInfoSync: enumerate })
  if (fixture) {
    expect(usage).toBeNull()
    expect(read).not.toHaveBeenCalled()
    expect(enumerate).not.toHaveBeenCalled()
  } else {
    expect(usage?.totalBytes).toBeGreaterThan(0)
    expect(read).toHaveBeenCalledWith('matelink.presentation.v1')
    expect(enumerate).toHaveBeenCalled()
  }
})
