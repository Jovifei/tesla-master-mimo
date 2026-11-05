import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {} }))
import { DashboardVehicleArt } from './DashboardParts'
import { APP_DISPLAY_CONTRACT } from '../../domain/app_display_contract'
it.each([320, 375, 430])('uses App logical geometry at %ipx without width-dependent rpx scaling', width => {
  let tree!: TestRenderer.ReactTestRenderer
  act(() => { tree = TestRenderer.create(<div style={{ width }}><DashboardVehicleArt car={{ model: 'Y' }}
    imageSrc="/synthetic-vehicle.png" imageLoading={false} imageError={false} onRetryImage={() => {}} /></div>) })
  expect(tree.root.findByProps({ className: 'dashboard-vehicle-art' }).props.style.height).toBe('170px')
  expect(tree.root.findByProps({ className: 'dashboard-vehicle-image' }).props.style).toBe('height:160px')
  expect(APP_DISPLAY_CONTRACT.vehicleHeroHeight).toBe(170)
  act(() => tree.unmount())
})
