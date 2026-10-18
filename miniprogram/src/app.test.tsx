import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({
  theme: 'light',
  onThemeChange: vi.fn(),
  offThemeChange: vi.fn(),
  setTabBarStyle: vi.fn(() => Promise.resolve()),
  setNavigationBarColor: vi.fn(() => Promise.resolve()),
}))
vi.mock('@tarojs/taro', () => ({ default: {
  getSystemInfoSync: () => ({ theme: platform.theme }),
  onThemeChange: platform.onThemeChange,
  offThemeChange: platform.offThemeChange,
  setTabBarStyle: platform.setTabBarStyle,
  setNavigationBarColor: platform.setNavigationBarColor,
} }))

import App from './app'

it('keeps the App teal selected tab color after runtime theme application', async () => {
  let tree: renderer.ReactTestRenderer
  await act(async () => { tree = renderer.create(<App><div /></App>) })
  expect(platform.setTabBarStyle).toHaveBeenCalledWith(expect.objectContaining({ selectedColor: '#157a86' }))
  const onTheme = platform.onThemeChange.mock.calls[0]?.[0] as ((value: { theme: string }) => void) | undefined
  expect(onTheme).toBeDefined()
  await act(async () => { onTheme?.({ theme: 'dark' }) })
  expect(platform.setTabBarStyle).toHaveBeenLastCalledWith(expect.objectContaining({ selectedColor: '#8cccd2' }))
  await act(async () => { tree!.unmount() })
})
