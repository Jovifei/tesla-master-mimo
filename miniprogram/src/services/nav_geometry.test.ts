import { describe, expect, it, vi } from 'vitest'
import Taro from '@tarojs/taro'
import { getAppNavGeometry } from './nav_geometry'

vi.mock('@tarojs/taro', () => ({
  default: {
    getMenuButtonBoundingClientRect: vi.fn(),
    getSystemInfoSync: vi.fn()
  }
}))

describe('getAppNavGeometry', () => {
  it('computes exact navigation bar geometry from WeChat capsule metrics', () => {
    vi.mocked(Taro.getMenuButtonBoundingClientRect).mockReturnValue({
      top: 48,
      bottom: 80,
      left: 288,
      right: 368,
      width: 80,
      height: 32
    } as any)
    vi.mocked(Taro.getSystemInfoSync).mockReturnValue({
      statusBarHeight: 44,
      windowWidth: 375
    } as any)

    const geo = getAppNavGeometry()
    expect(geo.statusBarHeight).toBe(44)
    expect(geo.navBarTop).toBe(48)
    expect(geo.navBarHeight).toBe(32)
    expect(geo.headerTotalHeight).toBe(84) // 80 + 4
    expect(geo.capsuleRightPadding).toBe(375 - 288 + 12) // 99
  })

  it('safely falls back when getMenuButtonBoundingClientRect is absent or invalid', () => {
    vi.mocked(Taro.getMenuButtonBoundingClientRect).mockReturnValue(undefined as any)
    vi.mocked(Taro.getSystemInfoSync).mockReturnValue({
      statusBarHeight: 30,
      windowWidth: 390
    } as any)

    const geo = getAppNavGeometry()
    expect(geo.statusBarHeight).toBe(30)
    expect(geo.navBarTop).toBe(36)
    expect(geo.headerTotalHeight).toBe(78)
    expect(geo.capsuleRightPadding).toBe(96)
  })

  it('safely handles complete exception / empty host gracefully', () => {
    vi.mocked(Taro.getMenuButtonBoundingClientRect).mockImplementation(() => { throw new Error('not supported') })
    vi.mocked(Taro.getSystemInfoSync).mockImplementation(() => { throw new Error('not supported') })

    const geo = getAppNavGeometry()
    expect(geo.statusBarHeight).toBe(24)
    expect(geo.navBarTop).toBe(30)
    expect(geo.headerTotalHeight).toBe(72)
    expect(geo.capsuleRightPadding).toBe(96)
  })
})
