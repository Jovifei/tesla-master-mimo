import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({
  pages: [{}] as object[],
  back: vi.fn().mockResolvedValue({}),
  switchTab: vi.fn().mockResolvedValue({}),
}))

vi.mock('@tarojs/components', () => ({ Button: 'button', Text: 'text', View: 'view' }))
vi.mock('@tarojs/taro', () => ({ default: {
  getCurrentPages: () => platform.pages,
  navigateBack: platform.back,
  switchTab: platform.switchTab,
} }))
vi.mock('../services/nav_geometry', () => ({
  getAppNavGeometry: () => ({ navBarTop: 48, capsuleRightPadding: 99, headerTotalHeight: 88 }),
}))

import SubpageHeader from './SubpageHeader'

it('renders the Android-style title and returns to More when no history exists', async () => {
  platform.pages = [{}]
  platform.back.mockClear()
  platform.switchTab.mockClear()
  let tree!: ReactTestRenderer
  await act(async () => {
    tree = TestRenderer.create(<SubpageHeader title="里程统计" subtitle="所选车辆" />)
  })
  expect(JSON.stringify(tree.toJSON())).toContain('里程统计')
  expect(JSON.stringify(tree.toJSON())).toContain('所选车辆')
  await act(async () => { await tree.root.findByType('button' as React.ElementType).props.onClick() })
  expect(platform.switchTab).toHaveBeenCalledWith({ url: '/pages/me/index' })
  expect(platform.back).not.toHaveBeenCalled()
  await act(async () => tree.unmount())
})

it('uses native back when the page has navigation history', async () => {
  platform.pages = [{}, {}]
  platform.back.mockClear()
  platform.switchTab.mockClear()
  let tree!: ReactTestRenderer
  await act(async () => {
    tree = TestRenderer.create(<SubpageHeader title="费用分析" />)
  })
  await act(async () => { await tree.root.findByType('button' as React.ElementType).props.onClick() })
  expect(platform.back).toHaveBeenCalledWith({ delta: 1 })
  expect(platform.switchTab).not.toHaveBeenCalled()
  await act(async () => tree.unmount())
})
